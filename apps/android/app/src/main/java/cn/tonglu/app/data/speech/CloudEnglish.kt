package cn.tonglu.app.data.speech

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal const val CLOUD_ENGLISH_BASE = "https://dl.useall.top/tts/english/cet-20261024-v1/"

internal fun englishAudioText(text: String): String = text.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

internal object CloudEnglishVoices {
    val voices = listOf(
        SpeechVoice("kokoro-v1-af-heart", "Heart · 美式", SoundSource.CloudVoice, Accent.American,
            VoiceGender.Female, available = true, engineVersion = "Kokoro 1.0 · 预生成音频",
            license = "词表 BSD-3-Clause · 模型 Apache-2.0", licenseUrl = CLOUD_ENGLISH_BASE + "NOTICE.txt",
            note = "随包四、六级精简词书；首次发音联网下载，已缓存可离线。例句和任意文章尚未覆盖，不会自动换声音。"),
        SpeechVoice("kokoro-v1-bf-emma", "Emma · 英式", SoundSource.CloudVoice, Accent.British,
            VoiceGender.Female, available = true, engineVersion = "Kokoro 1.0 · 预生成音频",
            license = "词表 BSD-3-Clause · 模型 Apache-2.0", licenseUrl = CLOUD_ENGLISH_BASE + "NOTICE.txt",
            note = "随包四、六级精简词书；首次发音联网下载，已缓存可离线。例句和任意文章尚未覆盖，不会自动换声音。"),
    )
    fun find(id: String?) = voices.firstOrNull { it.id == id }
}

/** Only fixed public texts are indexed. Requests never contain the user's text. */
internal class CloudEnglishIndex private constructor(private val entries: Map<Pair<String, String>, PackedAudio>) {
    fun find(text: String, voiceId: String): PackedAudio? = entries[englishAudioText(text) to voiceId]
    companion object {
        fun parse(json: String): CloudEnglishIndex {
            val root = JSONObject(json)
            require(root.getInt("schemaVersion") == 1 && root.getBoolean("complete") &&
                root.getString("revision") == "cet-20261024-v1" && root.getDouble("rate") == 0.9) { "英语音频版本无效" }
            for (key in listOf("source", "sourceUrl", "license", "modelSource", "modelLicense"))
                require(root.getString(key).isNotBlank()) { "音频缺少来源或许可" }
            require(root.getString("model").matches(Regex("[a-f0-9]{64}"))) { "模型指纹无效" }
            val rows = root.getJSONArray("items")
            require(rows.length() in 1..20000) { "英语音频数量无效" }
            val entries = mutableMapOf<Pair<String, String>, PackedAudio>()
            for (n in 0 until rows.length()) {
                val row = rows.getJSONArray(n)
                require(row.length() == 6) { "英语音频条目无效" }
                val text = row.getString(0)
                require(text.isNotBlank() && text == englishAudioText(text) && text.length <= 500 &&
                    text.none { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' }) { "英语文本无效" }
                val voice = CloudEnglishVoices.find(row.getString(1)) ?: error("不支持的英语声线")
                val key = row.getString(2); val sha = row.getString(3)
                require(key.matches(Regex("[a-f0-9]{64}")) && sha.matches(Regex("[a-f0-9]{64}"))) { "音频指纹无效" }
                val bytes = row.getLong(4); val seconds = row.getDouble(5)
                require(bytes in 1..5_242_880 && seconds.isFinite() && seconds in 0.01..120.0) { "音频大小或时长无效" }
                val pair = text to voice.id
                require(pair !in entries) { "英语音频条目重复" }
                entries[pair] = PackedAudio(key, "word", text, voice.id,
                    if (voice.accent == Accent.British) "en-GB" else "en-US", 0.9, key, sha, bytes, seconds)
            }
            // A completed pack must offer both accepted voices for every text.
            require(entries.keys.groupBy { it.first }.values.all { it.size == 2 }) { "英语音频缺少声线" }
            return CloudEnglishIndex(entries)
        }
    }
}

internal class CloudEnglishStore(
    private val directory: File,
    baseUrl: String = CLOUD_ENGLISH_BASE,
    allowLocalHttp: Boolean = false,
    private val bundledIndex: (() -> String)? = null,
    private val onTiming: (String) -> Unit = {},
) {
    private val base = baseUrl.toHttpUrl().also {
        require(it.scheme == "https" || (allowLocalHttp && it.scheme == "http" && it.host == "127.0.0.1"))
        require(it.encodedPath.endsWith("/") && it.username.isEmpty() && it.password.isEmpty() && it.query == null)
    }
    private var loaded: CloudEnglishIndex? = null
    private val mutex = Mutex()
    private val cached = File(directory, "cet-20261024-v1.json")
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private fun parsed(text: String, source: String): CloudEnglishIndex {
        val started = System.nanoTime()
        return CloudEnglishIndex.parse(text).also {
            onTiming("index parsed source=$source elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
        }
    }
    suspend fun index(): CloudEnglishIndex = withContext(Dispatchers.IO) {
        mutex.withLock {
        loaded?.let { return@withLock it }
        if (cached.isFile) {
            try { return@withLock parsed(cached.readText(), "cache").also { loaded = it } }
            catch (e: Exception) {
                onTiming("Invalid cached index; loading verified replacement: ${e.javaClass.simpleName}")
            }
        }
        val fetchedAt = System.nanoTime()
        val supplied = bundledIndex?.invoke()
        val text = supplied ?: fetch()
        if (supplied == null) onTiming("index fetched elapsedMs=${(System.nanoTime() - fetchedAt) / 1_000_000}")
        val parsed = parsed(text, if (supplied == null) "network" else "bundled")
        directory.mkdirs()
        val temp = File.createTempFile("index-", ".part", directory)
        try { temp.writeText(text); check(temp.renameTo(cached)) { "保存英语音频索引失败" } }
        finally { temp.delete() }
        parsed.also { loaded = it }
        }
    }

    private suspend fun fetch(): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(base.resolve("index.json")!!).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use { r ->
                        check(r.isSuccessful) { "英语音频索引下载失败（HTTP ${r.code}）" }
                        val body = r.body ?: error("英语音频索引为空")
                        require(body.contentLength() <= 4 * 1024 * 1024) { "英语音频索引过大" }
                        body.byteStream().use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                if (!continuation.isActive) throw CancellationException("下载已取消")
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= 4 * 1024 * 1024) { "英语音频索引过大" }
                                output.write(buffer, 0, count)
                            }
                            output.toString("UTF-8")
                        }
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
}

/** One request owns index fetch and playback; stopping invalidates both. */
internal class CloudEnglishPlayback(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val index = CloudEnglishStore(File(app.filesDir, "english_cloud_index"), bundledIndex = {
        // AAPT expands source .gz assets and strips their suffix in the APK.
        app.assets.open("speech/english-cet-20261024-v1.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }, onTiming = { android.util.Log.i("TongluCloudEnglish", it) })
    private val audio = AudioPackStore(File(app.cacheDir, "english_cloud_audio"), CLOUD_ENGLISH_BASE)
    private var job: Job? = null
    private var waiting: Job? = null
    private var generation = 0L
    private var disposed = false
    private var requestedAt = 0L
    private var requestedVoice = ""
    private val player = AudioPackPlayer(app, audio, scope) { message, _ ->
        if (message == "正在播放") android.util.Log.i("TongluCloudEnglish",
            "prepared voice=$requestedVoice elapsedMs=${android.os.SystemClock.elapsedRealtime() - requestedAt}")
        if (message == "正在播放" || message == "播放完成" || message == "已停止") waiting?.cancel()
        else if (message == "正在准备…") {
            waiting?.cancel()
            waiting = scope.launch { delay(700); inform("正在准备英语发音，首次使用需要下载音频…") }
        } else { waiting?.cancel(); inform(message) }
    }
    private val owner = generateSequence(context) { (it as? ContextWrapper)?.baseContext?.takeIf { next -> next !== it } }
        .filterIsInstance<LifecycleOwner>().firstOrNull()
    private val lifecycleObserver = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
    init { owner?.lifecycle?.addObserver(lifecycleObserver) }

    fun stop() {
        generation++; job?.cancel(); job = null; waiting?.cancel(); waiting = null; player.stop()
        if (active === this) active = null
    }
    fun close() { stop(); disposed = true; owner?.lifecycle?.removeObserver(lifecycleObserver); scope.cancel() }
    fun play(request: SpeechRequest): SpeechResult {
        if (disposed) return SpeechResult.Unavailable("语音页面已关闭，请重新打开")
        if (!request.locale.startsWith("en", true)) return SpeechResult.Unavailable("Heart / Emma 仅支持英语")
        if (request.text.isBlank()) return SpeechResult.Unavailable("没有可朗读的文本")
        if (!request.rate.isFinite() || request.rate !in 0.5f..2f) return SpeechResult.Unavailable("语速必须在 0.5～2 倍之间")
        stopActive(); stop(); active = this
        requestedAt = android.os.SystemClock.elapsedRealtime()
        requestedVoice = request.voiceId.orEmpty()
        val token = generation
        waiting = scope.launch { delay(700); if (generation == token) inform("正在准备英语发音，首次使用需要下载音频…") }
        job = scope.launch {
            try {
                val pack = index.index()
                val item = pack.find(request.text, request.voiceId.orEmpty())
                    ?: error("这段文本尚无所选音色的预生成音频。当前覆盖随包四、六级精简词书；可在声音设置中选择系统或离线声音朗读其他内容。")
                if (token == generation) player.play(listOf(item), request.rate / item.rate.toFloat())
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (token == generation) {
                    waiting?.cancel()
                    android.util.Log.w("TongluCloudEnglish", "Cloud audio request failed", e)
                    inform(e.message ?: "英语音频准备失败，请检查网络后重试")
                }
            }
        }
        return SpeechResult.Spoken
    }
    private fun inform(message: String) { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
    companion object {
        private var active: CloudEnglishPlayback? = null
        fun stopActive() { active?.stop() }
    }
}
