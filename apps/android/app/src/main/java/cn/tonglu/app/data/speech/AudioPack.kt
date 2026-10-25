package cn.tonglu.app.data.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class PackedAudio(
    val id: String, val purpose: String, val text: String, val voiceId: String,
    val locale: String, val rate: Double, val key: String, val sha256: String,
    val bytes: Long, val seconds: Double,
) {
    val path get() = "audio/$key.m4a"
    val cacheName get() = "$key-$sha256.m4a"
}

internal data class EnglishAudioPack(val revision: String, val items: List<PackedAudio>) {
    companion object {
        fun parse(json: String): EnglishAudioPack {
            val root = JSONObject(json)
            require(root.getInt("schemaVersion") == 1 && root.getBoolean("complete")) { "音频清单未完成" }
            require(root.getJSONArray("errors").length() == 0) { "音频清单含失败项" }
            val dataset = root.getJSONObject("dataset")
            require(dataset.getString("language") == "en") { "试听包只支持英语" }
            for (field in listOf("source", "sourceUrl", "license", "revision")) {
                require(dataset.getString(field).isNotBlank()) { "缺少资源来源或许可" }
            }
            val fingerprint = root.getJSONObject("model").getString("sha256")
            require(fingerprint.matches(Regex("[a-f0-9]{64}"))) { "模型指纹无效" }
            val source = root.getJSONObject("provenance")
            for (field in listOf("source", "sourceUrl", "license")) require(source.getString(field).isNotBlank())
            val raw = root.getJSONArray("items")
            require(raw.length() in 1..20000) { "清单条数无效" }
            val seen = mutableSetOf<Pair<String, String>>()
            val items = (0 until raw.length()).map { n ->
                val row = raw.getJSONObject(n)
                val spec = row.getJSONObject("identity")
                val voice = spec.getJSONObject("voice")
                val voiceId = voice.getString("id")
                val expected = when (voiceId) {
                    "kokoro-v1-af-heart" -> Triple("en-US", 3, "en-us")
                    "kokoro-v1-bf-emma" -> Triple("en-GB", 21, "en")
                    else -> error("未支持的英语声线")
                }
                require(voice.getString("locale") == expected.first && voice.getInt("speakerId") == expected.second
                    && voice.getString("frontendLanguage") == expected.third) { "声线与语种不匹配" }
                require(spec.getString("modelFingerprint") == fingerprint) { "音频模型版本不一致" }
                val key = row.getString("key")
                val sha = row.getString("sha256")
                require(key.matches(Regex("[a-f0-9]{64}")) && sha.matches(Regex("[a-f0-9]{64}"))) { "音频指纹无效" }
                require(row.getString("path") == "audio/$key.m4a") { "音频路径无效" }
                val id = row.getString("id")
                val text = spec.getString("text")
                val rate = spec.getDouble("rate")
                val bytes = row.getLong("bytes")
                val seconds = row.getDouble("audioSeconds")
                val purpose = row.getString("purpose")
                require(id.isNotBlank() && seen.add(id to voiceId)) { "条目重复或无名称" }
                require(text.isNotBlank() && text.length <= 500 && text.none { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' }) { "英语试听文本无效" }
                require(rate.isFinite() && rate in 0.5..2.0 && bytes in 1..5_242_880L
                    && seconds.isFinite() && seconds in 0.01..120.0) { "音频大小或时长无效" }
                require(purpose in listOf("word", "sentence", "reading")) { "音频用途无效" }
                PackedAudio(id, purpose, text, voiceId, expected.first, rate, key, sha, bytes, seconds)
            }
            val requests = root.getJSONArray("requests")
            require(requests.length() == items.size) { "清单缺少音频" }
            val expected = items.associateBy { it.id to it.voiceId }
            val requested = mutableSetOf<Pair<String, String>>()
            for (n in 0 until requests.length()) {
                val r = requests.getJSONObject(n)
                val pair = r.getString("id") to r.getString("voiceId")
                val item = expected[pair]
                require(requested.add(pair) && item != null && item.key == r.getString("key")
                    && item.purpose == r.getString("purpose")) { "清单音频不完整或重复" }
            }
            return EnglishAudioPack(dataset.getString("revision"), items)
        }
    }
}

internal data class AudioPackLoad(val pack: EnglishAudioPack, val cached: Boolean, val warning: String? = null)

/** Fixed public texts only: requests contain audio paths, never a user's article. */
internal class AudioPackStore(
    private val directory: File,
    baseUrl: String,
    allowLocalHttp: Boolean = false,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build(),
) {
    private val base = baseUrl.toHttpUrl().also {
        require(it.scheme == "https" || (allowLocalHttp && it.scheme == "http" && it.host == "127.0.0.1"))
        require(it.encodedPath.endsWith("/") && it.username.isEmpty() && it.password.isEmpty())
    }
    private val manifest = File(directory, "manifest-${digest(base.toString().toByteArray())}.json")
    private val audio = File(directory, "audio")
    private val mutex = Mutex()

    /** Render a verified local manifest before starting any network refresh. */
    suspend fun cached(): EnglishAudioPack? = withContext(Dispatchers.IO) {
        if (manifest.isFile) EnglishAudioPack.parse(manifest.readText()) else null
    }

    suspend fun load(): AudioPackLoad = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val temp = File.createTempFile("manifest-", ".part", directory)
        try {
            fetch("manifest.json", temp, 8L * 1024 * 1024)
            val pack = EnglishAudioPack.parse(temp.readText())
            check(temp.renameTo(manifest)) { "保存音频清单失败" }
            AudioPackLoad(pack, false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!manifest.isFile) throw e
            AudioPackLoad(EnglishAudioPack.parse(manifest.readText()), true, e.message ?: "网络不可用")
        } finally { temp.delete() }
    }

    /** Cache verification and file I/O always run off the main thread. */
    suspend fun file(item: PackedAudio): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            audio.mkdirs()
            val target = File(audio, item.cacheName)
            if (valid(target, item)) {
                target.setLastModified(System.currentTimeMillis())
                return@withLock target
            }
            if (target.exists()) check(target.delete()) { "无法清除损坏音频" }
            val temp = File.createTempFile("download-", ".part", audio)
            try {
                fetch(item.path, temp, item.bytes)
                check(valid(temp, item)) { "音频校验失败，请重试" }
                check(temp.renameTo(target)) { "保存音频失败" }
                trim(target)
                target
            } finally { temp.delete() }
        }
    }

    private suspend fun fetch(relative: String, target: File, maxBytes: Long): Unit = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(base.resolve(relative)!!).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r ->
                        check(r.isSuccessful) { "音频下载失败（HTTP ${r.code}）" }
                        val body = r.body ?: error("音频响应为空")
                        require(body.contentLength() <= maxBytes) { "音频响应超过大小限制" }
                        target.outputStream().use { out -> body.byteStream().use { input ->
                            val buffer = ByteArray(16 * 1024); var done = 0L
                            while (true) {
                                if (!continuation.isActive) throw CancellationException("下载已取消")
                                val n = input.read(buffer)
                                if (n < 0) break
                                done += n; require(done <= maxBytes) { "音频响应超过大小限制" }
                                out.write(buffer, 0, n)
                            }
                        } }
                    }
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                } finally {
                    // Cancellation may resume the caller before this callback closes
                    // its file handle; clean up again after closing on Windows.
                    if (continuation.isCancelled) target.delete()
                }
            }
        })
    }

    private fun trim(keep: File) {
        val files = audio.listFiles()?.filter { it.name.matches(Regex("[a-f0-9]{64}-[a-f0-9]{64}\\.m4a")) }.orEmpty()
        var bytes = files.sumOf { it.length() }
        for (file in files.sortedBy { it.lastModified() }) {
            if (bytes <= 64L * 1024 * 1024) break
            if (file != keep) { val size = file.length(); if (file.delete()) bytes -= size }
        }
    }

    private fun valid(file: File, item: PackedAudio) = file.isFile && file.length() == item.bytes &&
        file.inputStream().use { input ->
            val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(16384)
            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
            md.digest().joinToString("") { "%02x".format(it) } == item.sha256
        }

    companion object {
        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
