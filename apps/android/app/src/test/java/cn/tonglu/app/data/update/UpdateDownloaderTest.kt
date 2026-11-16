package cn.tonglu.app.data.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UpdateDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var dest: File
    private val bytes = ByteArray(512 * 1024) { (it % 251).toByte() }
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .readTimeout(2, TimeUnit.SECONDS).build()

    @Before fun setUp() {
        dest = File(temporary.root, "update.apk")
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @After fun tearDown() {
        server.stop(0)
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"
    private fun HttpExchange.full() {
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }
    private fun HttpExchange.remaining(offset: Int, total: Int = bytes.size) {
        responseHeaders.add("Content-Range", "bytes $offset-${bytes.lastIndex}/$total")
        sendResponseHeaders(206, (bytes.size - offset).toLong())
        responseBody.use { it.write(bytes, offset, bytes.size - offset) }
        close()
    }
    private suspend fun download(vararg paths: String) = downloadResumable(client, paths.map(::url),
        dest, bytes.size.toLong(), "update-test", backoff = { 0L }, onProgress = {})

    @Test fun 一次完整下载并携带用户代理() = runBlocking {
        val agents = Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/full") { exchange ->
            agents += exchange.requestHeaders.getFirst("User-Agent")
            exchange.full()
        }
        val progress = mutableListOf<Long>()
        downloadResumable(client, listOf(url("/full")), dest, bytes.size.toLong(), "update-test",
            backoff = { 0L }, onProgress = { progress += it })
        assertArrayEquals(bytes, dest.readBytes())
        assertEquals(listOf("update-test"), agents)
        assertEquals(bytes.size.toLong(), progress.last())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun 连接中断后只请求剩余范围() = runBlocking {
        val ranges = Collections.synchronizedList(mutableListOf<String?>())
        val count = AtomicInteger()
        server.createContext("/resume") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            ranges += range
            if (count.getAndIncrement() == 0) {
                // 故意少写，模拟线路在声明的响应体结束前断开。
                exchange.responseHeaders.add("Connection", "close")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.write(bytes, 0, bytes.size / 2)
                exchange.responseBody.flush()
                runCatching { exchange.responseBody.close() }
                exchange.close()
            } else {
                val offset = range.removePrefix("bytes=").removeSuffix("-").toInt()
                exchange.remaining(offset)
            }
        }
        download("/resume")
        assertArrayEquals(bytes, dest.readBytes())
        assertEquals(listOf(null, "bytes=${bytes.size / 2}-"), ranges)
    }

    @Test fun 忽略Range时从头覆盖部分文件() = runBlocking {
        File(dest.path + ".part").writeBytes(ByteArray(123) { 99 })
        val ranges = Collections.synchronizedList(mutableListOf<String>())
        server.createContext("/full") { exchange ->
            ranges += exchange.requestHeaders.getFirst("Range")
            exchange.full()
        }
        download("/full")
        assertEquals(listOf("bytes=123-"), ranges)
        assertArrayEquals(bytes, dest.readBytes())
    }

    @Test fun 首源404直接换源而不重试() = runBlocking {
        val missing = AtomicInteger()
        server.createContext("/missing") { exchange ->
            missing.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        server.createContext("/full") { it.full() }
        download("/missing", "/full")
        assertEquals(1, missing.get())
        assertArrayEquals(bytes, dest.readBytes())
    }

    @Test fun 全部503按源重试并给出中文原因() = runBlocking {
        val calls = AtomicInteger()
        listOf("/one", "/two").forEach { path ->
            server.createContext(path) { exchange ->
                calls.incrementAndGet()
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
        }
        try {
            download("/one", "/two")
            fail("应当失败")
        } catch (e: UpdateDownloadException) {
            assertTrue(e.userMessage.contains("暂时不可用"))
            assertTrue(e.userMessage.contains("503"))
        }
        assertEquals(8, calls.get())
        assertFalse(dest.exists())
    }

    @Test fun 错误ContentRange总长不产出安装包且可换源() = runBlocking {
        val part = File(dest.path + ".part")
        part.writeBytes(bytes.copyOfRange(0, 123))
        val badCalls = AtomicInteger()
        server.createContext("/bad") { exchange ->
            badCalls.incrementAndGet()
            exchange.remaining(123, bytes.size + 1)
        }
        server.createContext("/good") { it.remaining(123) }
        try {
            download("/bad")
            fail("应当拒绝错误的范围")
        } catch (_: UpdateDownloadException) {
            assertFalse(dest.exists())
            assertEquals(123L, part.length())
        }
        assertEquals(1, badCalls.get())
        download("/bad", "/good")
        assertArrayEquals(bytes, dest.readBytes())
        assertEquals(2, badCalls.get())
    }

    @Test fun 超长部分文件删除后重新下载() = runBlocking {
        File(dest.path + ".part").writeBytes(ByteArray(bytes.size + 1))
        val ranges = Collections.synchronizedList(mutableListOf<String?>())
        server.createContext("/full") { exchange ->
            ranges += exchange.requestHeaders.getFirst("Range")
            exchange.full()
        }
        download("/full")
        assertEquals(listOf<String?>(null), ranges)
        assertArrayEquals(bytes, dest.readBytes())
    }

    @Test fun 取消保留部分文件且不产出安装包() = runBlocking {
        server.createContext("/full") { it.full() }
        val task = launch {
            val downloading = coroutineContext[Job]!!
            downloadResumable(client, listOf(url("/full")), dest, bytes.size.toLong(), "update-test",
                backoff = { 0L }, onProgress = { if (it > 0) downloading.cancel() })
            fail("应当取消")
        }
        task.join()
        assertTrue(task.isCancelled)
        assertFalse(dest.exists())
        assertTrue(File(dest.path + ".part").length() in 1 until bytes.size.toLong())
    }

    @Test fun 完整断点直接覆盖旧目标且无需联网() = runBlocking {
        dest.writeText("旧文件")
        File(dest.path + ".part").writeBytes(bytes)
        downloadResumable(client, emptyList(), dest, bytes.size.toLong(), "update-test",
            backoff = { 0L }, onProgress = {})
        assertArrayEquals(bytes, dest.readBytes())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test fun 错误ContentRange起点拒绝写入() = runBlocking {
        val part = File(dest.path + ".part")
        part.writeBytes(bytes.copyOfRange(0, 123))
        server.createContext("/bad") { it.remaining(124) }
        try {
            download("/bad")
            fail("应当拒绝错误的起点")
        } catch (_: UpdateDownloadException) {
            assertFalse(dest.exists())
            assertEquals(123L, part.length())
        }
    }
}
