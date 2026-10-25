package cn.tonglu.app.data.speech

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioPackTest {
    private val key = "a".repeat(64)
    private val fingerprint = "b".repeat(64)
    private val bytes = "candidate-audio".toByteArray()
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun manifest(): JSONObject = JSONObject("""
        {"schemaVersion":1,"complete":true,"errors":[],
         "dataset":{"language":"en","revision":"test1","source":"authored","sourceUrl":"fixture","license":"CC0"},
         "provenance":{"source":"Kokoro","sourceUrl":"official","license":"Apache-2.0"},
         "model":{"sha256":"$fingerprint"},
         "requests":[{"id":"word","voiceId":"kokoro-v1-af-heart","key":"$key","purpose":"word"}],
         "items":[{"id":"word","purpose":"word","key":"$key","sha256":"${sha(bytes)}","bytes":${bytes.size},"audioSeconds":1.0,
          "path":"audio/$key.m4a","identity":{"modelFingerprint":"$fingerprint","text":"University.","rate":0.9,
           "voice":{"id":"kokoro-v1-af-heart","locale":"en-US","speakerId":3,"frontendLanguage":"en-us"}}}]}
    """)

    @Test fun parseValidPackAndEnglishIdentity() {
        val p = EnglishAudioPack.parse(manifest().toString())
        assertEquals("test1", p.revision); assertEquals("en-US", p.items.single().locale)
        assertEquals("University.", p.items.single().text)
    }

    @Test fun parserRejectsIncompleteWrongLanguageDuplicateAndTraversal() {
        val variants = listOf<(JSONObject) -> Unit>(
            { it.put("complete", false) },
            { it.getJSONObject("dataset").put("language", "ja") },
            { it.getJSONArray("items").getJSONObject(0).put("path", "../secret") },
            { it.getJSONArray("items").put(it.getJSONArray("items").getJSONObject(0)) },
            { it.getJSONArray("requests").getJSONObject(0).put("key", "c".repeat(64)) },
            { it.getJSONArray("items").getJSONObject(0).getJSONObject("identity").getJSONObject("voice").put("locale", "ja-JP") },
            { it.getJSONArray("items").getJSONObject(0).getJSONObject("identity").put("text", "こんにちは") },
            { it.getJSONArray("items").getJSONObject(0).put("bytes", 6_000_000) },
            { it.getJSONObject("dataset").put("license", "") },
        )
        variants.forEach { change ->
            val input = manifest(); change(input)
            assertThrows(Exception::class.java) { EnglishAudioPack.parse(input.toString()) }
        }
    }

    private fun server(block: (HttpServer, String, File) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool(); server.executor = executor
        val directory = Files.createTempDirectory("audio-pack-test").toFile()
        server.start()
        try { block(server, "http://127.0.0.1:${server.address.port}/", directory) }
        finally { server.stop(0); executor.shutdownNow(); directory.deleteRecursively() }
    }

    @Test fun downloadCacheOfflineManifestAndSameLengthCorruptionRepair() = server { server, base, directory ->
        val count = AtomicInteger()
        server.createContext("/manifest.json") { exchange ->
            val body = manifest().toString().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/audio/") { exchange ->
            count.incrementAndGet(); exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        runBlocking {
            val store = AudioPackStore(directory, base, true)
            val item = store.load().pack.items.single()
            val first = store.file(item); assertArrayEquals(bytes, first.readBytes())
            store.file(item); assertEquals(1, count.get())
            val damaged = bytes.clone(); damaged[2] = (damaged[2].toInt() xor 1).toByte(); first.writeBytes(damaged)
            store.file(item); assertEquals(2, count.get()); assertArrayEquals(bytes, first.readBytes())
            server.removeContext("/manifest.json")
            val offline = store.load(); assertTrue(offline.cached); assertTrue(offline.warning!!.contains("404"))
        }
    }

    @Test fun wrongHashAndOversizedAudioNeverBecomeCacheHits() = server { server, base, directory ->
        server.createContext("/audio/") { exchange ->
            val body = ByteArray(bytes.size + 1)
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        runBlocking {
            val store = AudioPackStore(directory, base, true)
            val item = EnglishAudioPack.parse(manifest().toString()).items.single()
            var failed = false
            try { store.file(item) } catch (_: Exception) { failed = true }
            assertTrue(failed); assertFalse(File(directory, "audio/${item.cacheName}").exists())
            assertEquals(0, File(directory, "audio").listFiles()!!.size)
        }
    }

    @Test fun cancelBlockedDownloadReturnsPromptlyAndNextRequestWorks() = server { server, base, directory ->
        val started = CountDownLatch(1); val release = CountDownLatch(1); val count = AtomicInteger()
        server.createContext("/audio/") { exchange ->
            if (count.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { out ->
                    out.write(bytes, 0, 1); out.flush(); started.countDown(); release.await(5, TimeUnit.SECONDS)
                }
            } else {
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
        }
        runBlocking {
            val store = AudioPackStore(directory, base, true)
            val item = EnglishAudioPack.parse(manifest().toString()).items.single()
            val job = launch(Dispatchers.Default) { store.file(item) }
            assertTrue(withContext(Dispatchers.IO) { started.await(3, TimeUnit.SECONDS) })
            withTimeout(1500) { job.cancelAndJoin() }
            release.countDown()
            assertArrayEquals(bytes, withTimeout(3000) { store.file(item) }.readBytes())
            withTimeout(1500) {
                while (File(directory, "audio").listFiles().orEmpty().any { it.extension == "part" }) delay(20)
            }
        }
    }

    @Test fun localHttpRequiresExplicitOptInAndCannotTargetOtherHost() {
        assertThrows(IllegalArgumentException::class.java) { AudioPackStore(File("unused"), "http://127.0.0.1/") }
        assertThrows(IllegalArgumentException::class.java) { AudioPackStore(File("unused"), "http://example.com/", true) }
    }

    @Test fun cachedManifestIsAvailableWithoutStartingNetworkRefresh() = server { server, base, directory ->
        val requests = AtomicInteger()
        server.createContext("/manifest.json") { exchange ->
            requests.incrementAndGet()
            val body = manifest().toString().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }
        }
        runBlocking {
            val store = AudioPackStore(directory, base, true)
            assertNull(store.cached())
            assertEquals(0, requests.get())
            store.load()
            assertEquals("test1", withTimeout(1000) { store.cached() }!!.revision)
            assertEquals(1, requests.get())
        }
    }
}
