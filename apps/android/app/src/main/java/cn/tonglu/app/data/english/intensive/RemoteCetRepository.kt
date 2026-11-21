package cn.tonglu.app.data.english.intensive

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.ProtocolException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resumeWithException

private const val SOURCE_TIMEOUT_MS = 20_000L
private const val MAX_SOURCE_BYTES = 32L * 1024 * 1024

internal class RemoteCetSourceException(cause: IOException) : IOException(cause)

internal fun remoteCetErrorMessage(error: Exception): String = when (error) {
    is RemoteCetSourceException -> "原卷来源在境外，当前网络连不上。可以稍后重试，或先用『我的文章与书籍』导入本地文件阅读。"
    else -> "原卷读取失败，请重试"
}

/** Download only from the fixed upstream commit on explicit article selection; private cache only. */
class RemoteCetRepository(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "cet-source-cache")
    companion object {
        private val lock = Mutex()
        private val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    suspend fun article(entry: IntensiveEntry): IntensiveArticle =
        withTimeoutOrNull(SOURCE_TIMEOUT_MS) { readArticle(entry) }
            ?: throw RemoteCetSourceException(SocketTimeoutException("原卷读取超过 20 秒"))

    private suspend fun readArticle(entry: IntensiveEntry): IntensiveArticle = withContext(Dispatchers.IO) {
        lock.withLock {
            val spec = requireNotNull(entry.remote)
            check(directory.isDirectory || directory.mkdirs()) { "无法创建本机原卷缓存" }
            val file = File(directory, spec.sourceSha256 + if (spec.format == "pdf") ".pdf" else ".md")
            if (file.exists() && IntensiveCodec.hash(file.readBytes()) != spec.sourceSha256) check(file.delete())
            if (!file.isFile) download(spec, file)
            if (spec.format == "markdown") {
                val text = file.readText().replace(Regex("<u>&emsp;(\\d+)&emsp;</u>"), "[$1]")
                    .replace(Regex("</?[^>]+>"), "").replace(Regex("(?m)^#+\\s*"), "")
                    .replace("**", "").replace("_", "")
                return@withLock RemoteCetCodec.article(entry, text)
            }
            PDFBoxResourceLoader.init(app)
            var mismatch: IllegalArgumentException? = null
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { pdf ->
                require(pdf.numberOfPages in 1..150) { "原卷页数超出可读取范围" }
                for (sorted in listOf(false, true)) {
                    coroutineContext.ensureActive()
                    val cache = File(directory, spec.sourceSha256 + if (sorted) "-sorted.txt" else "-logical.txt")
                    val text = if (cache.isFile) cache.readText() else PDFTextStripper().apply {
                        sortByPosition = sorted
                    }.getText(pdf).also {
                        require(it.length <= 2_000_000) { "原卷文字层超过本机解析上限" }
                        val pending = File(cache.path + ".part")
                        pending.writeText(it)
                        check(pending.renameTo(cache)) { "原卷文字缓存保存失败" }
                    }
                    try { return@withLock RemoteCetCodec.article(entry, text) }
                    catch (e: IllegalArgumentException) { mismatch = e }
                }
            }
            throw requireNotNull(mismatch)
        }
    }

    private suspend fun download(spec: RemoteCetSpec, target: File) {
        val partial = File(directory, spec.sourceSha256 + ".part")
        try {
            var url = spec.sourceUrl
            for (attempt in 0..3) {
                require(RemoteCetCodec.allowedSource(url)) { "原卷链接未通过来源检查" }
                val bytes = when (val response = fetch(url)) {
                    is SourceResponse.Redirect -> {
                        url = URL(URL(url), response.location).toString()
                        continue
                    }
                    is SourceResponse.Document -> response.bytes
                }
                if (bytes.take(7).toByteArray().toString(Charsets.UTF_8).startsWith("version") && url.contains("raw.githubusercontent.com")) {
                    url = url.replace("https://raw.githubusercontent.com/", "https://media.githubusercontent.com/media/")
                    continue
                }
                if (spec.format == "pdf") require(bytes.size >= 5 && bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-") { "来源未返回有效PDF原卷" }
                require(IntensiveCodec.hash(bytes) == spec.sourceSha256) { "原卷下载指纹不一致，未使用变更的文件" }
                partial.writeBytes(bytes)
                coroutineContext.ensureActive()
                check(partial.renameTo(target)) { "原卷缓存保存失败" }
                return
            }
            throw RemoteCetSourceException(ProtocolException("原卷来源重定向次数过多"))
        } finally { if (partial.exists()) check(partial.delete()) { "临时原卷缓存清理失败" } }
    }

    private sealed interface SourceResponse {
        data class Redirect(val location: String) : SourceResponse
        data class Document(val bytes: ByteArray) : SourceResponse
    }

    private suspend fun fetch(url: String): SourceResponse = try {
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).header("User-Agent", "Taimi-CET-Reader/1.0").build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            if (it.code in setOf(301, 302, 303, 307, 308)) {
                                SourceResponse.Redirect(it.header("Location") ?: throw ProtocolException("原卷重定向缺少地址"))
                            } else {
                                if (it.code != 200) throw ProtocolException("原卷来源返回 HTTP ${it.code}")
                                val source = checkNotNull(it.body) { "原卷响应为空" }.source()
                                require(!source.request(MAX_SOURCE_BYTES + 1)) { "原卷文件超过32 MB限制" }
                                SourceResponse.Document(source.readByteArray())
                            }
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        }
    } catch (e: IOException) {
        throw RemoteCetSourceException(e)
    }
}
