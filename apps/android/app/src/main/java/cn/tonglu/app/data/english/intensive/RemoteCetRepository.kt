package cn.tonglu.app.data.english.intensive

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** Download only from the fixed upstream commit on explicit article selection; private cache only. */
class RemoteCetRepository(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "cet-source-cache")
    companion object { private val lock = Mutex() }

    suspend fun article(entry: IntensiveEntry): IntensiveArticle = withContext(Dispatchers.IO) {
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
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000; connection.readTimeout = 45000
                connection.setRequestProperty("User-Agent", "Taimi-CET-Reader/1.0")
                try {
                    val code = connection.responseCode
                    if (code in setOf(301, 302, 303, 307, 308)) {
                        url = URL(URL(url), connection.getHeaderField("Location")).toString()
                        continue
                    }
                    require(code == 200) { "原卷来源暂时无法访问（HTTP $code），请重试" }
                    var total = 0L
                    connection.inputStream.use { input -> partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 32L * 1024 * 1024) { "原卷文件超过32 MB限制" }
                            output.write(buffer, 0, count)
                        }
                    } }
                    val bytes = partial.readBytes()
                    if (bytes.take(7).toByteArray().toString(Charsets.UTF_8).startsWith("version") && url.contains("raw.githubusercontent.com")) {
                        url = url.replace("https://raw.githubusercontent.com/", "https://media.githubusercontent.com/media/")
                        continue
                    }
                    if (spec.format == "pdf") require(bytes.size >= 5 && bytes.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-") { "来源未返回有效PDF原卷" }
                    require(IntensiveCodec.hash(bytes) == spec.sourceSha256) { "原卷下载指纹不一致，未使用变更的文件" }
                    check(partial.renameTo(target)) { "原卷缓存保存失败" }
                    return
                } finally { connection.disconnect() }
            }
            throw IllegalArgumentException("原卷来源重定向次数过多，请稍后重试")
        } finally { if (partial.exists()) check(partial.delete()) { "临时原卷缓存清理失败" } }
    }
}
