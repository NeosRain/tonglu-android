package cn.tonglu.app.data.speech

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

private const val TAG = "TongluTtsModels"

/** 后台补拉清单的最小间隔（见 [TtsModelStore.refreshInBackground]）。 */
private const val REFRESH_MIN_INTERVAL_MS = 10 * 60 * 1000L

/**
 * 下载取消登记簿。**抽成纯 JVM 类是为了能测**：`Context`/真实 HTTP 都进不了单测，
 * 而「取消谁、清谁」的规则一旦写错就是要么取消不了、要么误伤别的任务。
 *
 * 两条规则：
 * - [requestCancel] 先记标记再掐连接。连接可能还没建好（register 之前就点取消），
 *   只掐现有连接会漏 —— 标记让读取循环在下一块自查退出。
 * - [clearCancel] 在**新一轮下载开始时**调，不是结束时：失败原因要如时报给用户，
 *   「上次被取消」不能污染这次的结果判断。
 */
internal class TtsDownloadRegistry {
    private val cancelActions = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private val cancelled: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 下载开始时登记：给一个能立即掐断该模型当前 HTTP 连接的动作。 */
    fun register(modelId: String, cancelAction: () -> Unit) {
        cancelActions[modelId] = cancelAction
        // 用户可能在IO线程建连前取消；登记后立即兑现，不能再等网络读取。
        if (modelId in cancelled) cancelAction()
    }

    /** 下载结束（成功/失败/取消）后注销，防止取消动作挂在已完成的连接上。 */
    fun unregister(modelId: String) {
        cancelActions.remove(modelId)
    }

    /** 请求取消：记标记 + 立即掐断当前连接（阻塞中的 read 会立刻抛错，不用等 readTimeout）。 */
    fun requestCancel(modelId: String) {
        cancelled.add(modelId)
        cancelActions[modelId]?.invoke()
    }

    fun isCancelRequested(modelId: String): Boolean = modelId in cancelled

    /** 新一轮下载开始：清掉上一轮的取消标记。 */
    fun clearCancel(modelId: String) {
        cancelled.remove(modelId)
    }
}

/** 以单调时钟统计下载；最多每 250ms 发布一次，避免每个网络块都触发界面重组。 */
internal class TtsDownloadMetrics(private val startedAtMs: Long) {
    private var lastPublishedMs = Long.MIN_VALUE

    fun sample(done: Long, total: Long, nowMs: Long): TtsDownloadSnapshot? {
        val complete = total > 0L && done >= total
        if (lastPublishedMs != Long.MIN_VALUE && nowMs - lastPublishedMs < 250L && !complete) return null
        lastPublishedMs = nowMs
        val elapsed = (nowMs - startedAtMs).coerceAtLeast(1L)
        val speed = (done.coerceAtLeast(0L).toDouble() * 1000 / elapsed).toLong()
        val remaining = if (total > done && speed > 0L) ((total - done) / speed) else null
        return TtsDownloadSnapshot(done.coerceAtLeast(0L), total, speed, remaining)
    }
}

internal data class TtsDownloadSnapshot(
    val done: Long,
    val total: Long,
    val bytesPerSecond: Long,
    val remainingSeconds: Long?,
) {
    val progress: Float get() = if (total > 0L) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
    val label: String get() = buildString {
        append("%.1f".format(java.util.Locale.ROOT, done / 1048576.0))
        append(" / ")
        append(if (total > 0L) "%.1f MB".format(java.util.Locale.ROOT, total / 1048576.0) else "未知大小")
        append(" · ")
        append("%.0f KB/s".format(java.util.Locale.ROOT, bytesPerSecond / 1024.0))
        remainingSeconds?.let { append(" · 约${it / 60}分${it % 60}秒") }
    }
}

/**
 * 离线语音模型清单的公开地址。
 *
 * **不挂 GitHub release**（会被限速、被墙），模型放在自己的对象存储上，
 * 由 `scripts/tts_publish_models.py` 发布到 R2 的 `tts/` 前缀（公开直链 + CDN）。
 * 网盘的清理任务只扫 `netdisk/` 前缀，不会动这里。
 */
const val TTS_MANIFEST_URL = "https://dl.useall.top/tts/manifest.json"

/**
 * 模型内置的一个**可选声音**（sherpa-onnx 里的「说话人 / speaker」）。
 *
 * ## 为什么要把说话人提到清单里
 * 2026-10-08 用户报「中文只有一个女声，没有男声」。真因不是模型缺男声 ——
 * 权重里明明烘着 187 个说话人 —— 而是**客户端从来没把说话人列表暴露出来**：
 * [SpeechVoice] 里没有说话人字段，`BuiltinTts.speak` 永远传 `sid = 0`，
 * 于是不管模型有多少种嗓音，界面上永远只有一条。
 *
 * [sid] 是 `OfflineTts.generate(text, sid, speed)` 的第二个参数，**必须是权重里真实存在的序号**。
 * 越界的后果实测（`work/tmp/verify_published_zip.py`，fanchen-C 187 个说话人、传 9999）：
 * ```
 * This model contains only 187 speakers. sid should be in the range [0, 186]. Given: 9999. Use sid=0
 * ```
 * 引擎会在**它自己的日志**里说一句，然后**照常出声**（用 sid=0）—— 不抛异常、不返回空音频。
 * 而那句话是 C++ 侧的 stderr，App 打不到自己的 tag 上（这台设备上 shell 的 logcat 本就封着），
 * 所以从用户角度看就是**静默换嗓**：选了男声出来女声，界面上没有任何异常。
 * 结论：这里的每一条都得是**量出来/验过**的，不是凑数。
 */
data class TtsVoice(
    /** 说话人序号，直接传给引擎。 */
    val sid: Int,
    /** "male" / "female"，其它值按「未标」处理。清单里写什么就是什么，客户端不猜。 */
    val gender: String,
    /** 界面上显示的名字，如「中文·男声（离线）」。留空则按模型名自动拼。 */
    val name: String,
)

/**
 * 一个可下载的离线语音模型。字段与服务器 `tts/manifest.json` 一一对应。
 *
 * 之所以把「模型文件名、lexicon、几个 fst」也放进清单而不是写死在代码里，
 * 是因为**换模型不该改代码**：AISHELL-3 的权重叫 `model.onnx`、带 `new_heteronym.fst`、
 * 有 174 个说话人；换成 fanchen-C 之后权重叫 `vits-zh-hf-fanchen-C.onnx`、
 * 带 `new_heteronym.fst`、187 个说话人。清单驱动，加模型只改服务器。
 */
data class TtsModel(
    val id: String,
    val name: String,
    /** "zh" / "en"。用来判断这条声线能不能读某种语言。 */
    val lang: String,
    val accent: Accent,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val license: String,
    val licenseUrl: String,
    val attribution: String,
    /** 给用户的补充说明（比如「没打包 rule.far，多音字准确率略降」）。 */
    val note: String,
    val modelFile: String,
    val lexicon: String,
    val tokens: String,
    /** espeak-ng 数据目录。**中文与 icefall 英文模型都不需要**，留空即可。 */
    val dataDir: String,
    val dictDir: String,
    val ruleFsts: List<String>,
    val ruleFars: List<String>,
    val speakers: Int,
    /**
     * 这个模型对外提供哪几条声线。**空 = 只提供一条默认声线（sid 0）**，
     * 老清单没有这个字段时行为完全不变。
     */
    val voices: List<TtsVoice> = emptyList(),
) {
    /** 下载体积的人话表达。 */
    val sizeLabel: String get() = if (bytes <= 0L) "未知" else "%d.%d MB".format(bytes / 1024 / 1024, (bytes / 1024 / 102) % 10)
}

/**
 * 离线语音模型的**取回与安装**（方案 C：模型不进 APK，用到才下）。
 *
 * ## 为什么要「清单 + 下载」而不打进包
 * 打进包时 release APK 约 155MB（模型 65MB + onnxruntime 60MB）。
 * 模型挪出去之后 APK 回到 **约 93MB**，代价是第一次用离线声线要下 28MB。
 * 内置声线不是人人都要用 —— 让用的人付这 28MB，比所有人一起扛 65MB 合理。
 *
 * ## 下载的三道闸（跟英语词库那套一致，别自己发明）
 * 1. 先落**临时文件**，绝不直接写进目标目录；
 * 2. 对**字节数**；
 * 3. 对 **sha256** —— 传输出错、运营商插东西、缓存串味都过不去这一关。
 * 三道都过了才解压、才改名就位。
 */
object TtsModelStore {

    @Volatile private var cached: List<TtsModel>? = null
    @Volatile private var lastError: String? = null
    /** 磁盘上的清单缓存**每个进程只读一次**（读失败也不重试，免得每个页面都去读一遍坏文件）。 */
    @Volatile private var diskLoaded = false
    /** 后台补拉的单飞开关与节流时间（[refreshInBackground]）。 */
    private val refreshGuard = Any()
    @Volatile private var refreshing = false
    @Volatile private var lastRefreshAtMs = 0L
    private val manifestWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "tonglu-tts-manifest").apply { isDaemon = true }
    }

    /** 上一次拉清单的失败原因（给人看的）。 */
    val manifestError: String? get() = lastError

    /** 上一次拉到清单时清掉了哪些孤儿目录（给日志和走查用；正常情况是空表）。 */
    @Volatile var lastPruned: List<String> = emptyList()
        private set

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 下载取消登记簿（单任务由界面层保证，这里按模型 id 兜底）。 */
    private val registry = TtsDownloadRegistry()

    /**
     * 请求取消一个模型的下载。界面在用户点「取消」或下载所在的协程被撤销时调用：
     * 立即掐断 HTTP 连接，阻塞中的读取立刻抛错——**不用等下一块数据或 60s readTimeout**
     * （S103 遗留：此前取消只能等连接自然结束）。
     */
    fun cancelDownload(modelId: String) {
        registry.requestCancel(modelId)
        Log.i(TAG, "已请求取消下载：$modelId")
    }

    /** 在界面发布新下载任务之前清理旧标记，IO启动后不再清掉本轮用户的取消。 */
    fun prepareDownload(modelId: String) = registry.clearCancel(modelId)

    /** 模型装到哪：`filesDir/tts/<model-id>/`。 */
    fun dirOf(context: Context, model: TtsModel): File =
        File(File(context.filesDir, "tts"), model.id)

    /**
     * 是否已安装就位。判据是**清单里声明的每个文件都在**，
     * 不能只看目录存在 —— 上次下载到一半被打断会留下半个目录。
     */
    fun isInstalled(context: Context, model: TtsModel): Boolean {
        val dir = dirOf(context, model)
        if (!dir.isDirectory) return false
        val need = requiredFiles(model)
        if (!need.all { File(dir, it).length() > 0L }) return false
        // espeak-ng 数据目录（英文模型要）是一个**目录**，按文件判会漏。
        if (model.dataDir.isNotBlank() && !File(dir, model.dataDir).isDirectory) return false
        return true
    }

    /** 这个模型一定要有哪些文件。空白字段（如英文模型没有 lexicon）不算缺。 */
    private fun requiredFiles(model: TtsModel): List<String> = buildList {
        add(model.modelFile)
        add(model.lexicon)
        add(model.tokens)
        addAll(model.ruleFsts)
        addAll(model.ruleFars)
    }.filter { it.isNotBlank() }

    /**
     * 取模型清单。**这里绝不走网络**（[list] 会被 Compose 在主线程调到，
     * 联网会直接撞 `NetworkOnMainThreadException`），只返回缓存或兜底。
     * 真正的联网在 [refresh]，由调用方自己放到后台线程。
     */
    fun list(): List<TtsModel> = cached ?: fallback

    /**
     * 同上，但会**先把上次成功拉到的清单从磁盘读回内存**再返回。
     *
     * 为什么必须有这个重载（英式「发不出声音」的真因之一）：清单以前只有两个来源 ——
     * 「本进程里联网拉到过」和「APK 里编死的兜底」。用户在声音设置页下载并选中英式声线之后，
     * **只有声音设置页会拉清单**；单词练习 / 每日例句 / 语境词卡这些页面从不拉，
     * 它们看到的是兜底那份（= **上一版 App 编进去的模型配置**，里面根本没有英式条目）→
     * 偏好里存的 `builtin-en-vctk-british#101` 解析不出来 → 悄悄退回系统朗读 →
     * 手机系统没有 en-GB 语音 → 弹「发不出声音：系统没有可用的英式声音」。
     * 进程被杀/重启之后，连声音设置页也要先等一次联网才认得出这条声线。
     *
     * 落盘的是**上一次真的拉到过**的那份清单（见 [refresh]），所以断网也能解析出
     * 用户实际下载过的模型；文件只有几 KB，在主线程读盘是安全的。
     */
    fun list(context: Context): List<TtsModel> {
        loadDiskCacheOnce(context)
        return list()
    }

    /** 清单磁盘缓存的位置。放在 `tts/` 下但**不带前导点**也没关系：[orphanDirs] 只删目录。 */
    private fun cacheFile(context: Context) = File(File(context.filesDir, "tts"), "manifest-cache.json")

    private fun loadDiskCacheOnce(context: Context) {
        if (diskLoaded) return
        diskLoaded = true
        if (cached != null) return
        val file = cacheFile(context)
        if (!file.isFile || file.length() <= 0L) return
        runCatching { parseManifest(file.readText()) }
            .onSuccess { if (it.isNotEmpty()) cached = it }
            .onFailure { Log.w(TAG, "磁盘上的清单缓存读不出来，用内置兜底：${it::class.java.simpleName}: ${it.message}") }
    }

    /**
     * 后台补拉清单：**不阻塞调用方、不抛异常、单飞 + 节流**。
     *
     * 给「不是声音设置页」的入口用（[SpeechService.start] 会调一次）：
     * 以前清单只在声音设置页拉，别的页面永远活在上一版编死的清单里。
     * 10 分钟内不重复拉；失败也算一次（不重试风暴），下次进页面再说。
     */
    fun refreshInBackground(context: Context) {
        val app = context.applicationContext
        val now = System.nanoTime() / 1_000_000
        synchronized(refreshGuard) {
            if (refreshing || now - lastRefreshAtMs < REFRESH_MIN_INTERVAL_MS) return
            refreshing = true
            lastRefreshAtMs = now
        }
        manifestWorker.execute {
            try {
                refresh(app)
            } catch (bad: Throwable) {
                Log.w(TAG, "后台刷新清单失败（本次忽略）：${bad::class.java.simpleName}: ${bad.message}")
            } finally {
                synchronized(refreshGuard) { refreshing = false }
            }
        }
    }

    /**
     * 联网拉一次清单、刷新缓存，并顺手清掉**清单里已经没有的**旧模型目录。**必须在后台线程调。**
     *
     * 拉不到就保留 [fallback] —— CDN 抽风不该让整个离线语音消失，
     * 尤其是已经下载好模型的用户。兜底那份改模型时要跟着
     * `scripts/tts_publish_models.py` 的 MODELS 一起改。
     *
     * ⚠️ 清孤儿只在**真的拉到清单**时才做：那时清单里的 id 就是"活着的"全集；
     * 拉不到时用的是兜底清单，拿它去删会把只有新清单里才有的模型误判成孤儿。
     * （这条链路曾经只写在注释里、根本没接线，老用户的 28MB 旧权重一直躺着 ——
     * 由 `scripts/audit_wiring.py` 的 W1 钉住。）
     */
    fun refresh(context: Context): List<TtsModel> {
        val fetched = runCatching { fetch() }.onFailure {
            lastError = "清单拉取失败（${it::class.java.simpleName}: ${it.message}），用内置兜底列表"
            Log.w(TAG, lastError!!)
        }.getOrNull()
        if (fetched != null && fetched.models.isNotEmpty()) {
            lastError = null
            cached = fetched.models
            // 拉到就落盘：下一次冷启动（甚至断网）也能认出用户下载过的模型。
            saveDiskCache(context, fetched.raw)
            val pruned = pruneOrphans(context, fetched.models.map { it.id }.toSet())
            if (pruned.isNotEmpty()) Log.i(TAG, "清掉清单里已不存在的模型目录：$pruned")
            lastPruned = pruned
        }
        return list()
    }

    private fun saveDiskCache(context: Context, raw: String) {
        runCatching {
            val file = cacheFile(context)
            file.parentFile?.mkdirs()
            file.writeText(raw)
        }.onFailure { Log.w(TAG, "写清单磁盘缓存失败（不影响本次使用）：${it::class.java.simpleName}") }
    }

    /** 拉回来的原始 JSON 与解析结果一起给出去：**落盘的必须是原文**，省的再拼一遍。 */
    private class Fetched(val raw: String, val models: List<TtsModel>)

    private fun fetch(): Fetched {
        val body = http.newCall(Request.Builder().url(TTS_MANIFEST_URL).get().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
        return Fetched(body, parseManifest(body))
    }

    /** 清单 JSON → 模型列表。**抽出来是为了磁盘缓存能走同一条解析**（口径只有一份）。 */
    private fun parseManifest(body: String): List<TtsModel> {
        val root = JSONObject(body)
        val arr = root.optJSONArray("models") ?: return emptyList()
        val out = ArrayList<TtsModel>(arr.length())
        for (i in 0 until arr.length()) out += parse(arr.getJSONObject(i))
        return out
    }

    private fun parse(o: JSONObject): TtsModel = TtsModel(
        id = o.optString("id"),
        name = o.optString("name"),
        lang = o.optString("lang", "zh"),
        accent = when (o.optString("accent")) {
            "Chinese" -> Accent.Chinese
            "British" -> Accent.British
            else -> Accent.American
        },
        url = o.optString("url"),
        bytes = o.optLong("bytes"),
        sha256 = o.optString("sha256").lowercase(),
        license = o.optString("license"),
        licenseUrl = o.optString("licenseUrl"),
        attribution = o.optString("attribution"),
        note = o.optString("note"),
        modelFile = o.optString("model", "model.onnx"),
        lexicon = o.optString("lexicon", "lexicon.txt"),
        tokens = o.optString("tokens", "tokens.txt"),
        dataDir = o.optString("dataDir", ""),
        dictDir = o.optString("dictDir", ""),
        ruleFsts = o.optJSONArray("ruleFsts").toStringList(),
        ruleFars = o.optJSONArray("ruleFars").toStringList(),
        speakers = o.optInt("speakers", 1),
        voices = o.optJSONArray("voices").toVoiceList(),
    )

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val out = ArrayList<String>(length())
        for (i in 0 until length()) out += optString(i)
        return out
    }

    /** 解析 `voices: [{sid, gender, name}]`。**坏条目整条丢掉**，不要把半条塞进清单。 */
    private fun JSONArray?.toVoiceList(): List<TtsVoice> {
        if (this == null) return emptyList()
        val out = ArrayList<TtsVoice>(length())
        for (i in 0 until length()) {
            val v = optJSONObject(i) ?: continue
            if (!v.has("sid")) {
                Log.w(TAG, "清单里有一条声音没写 sid，已跳过")
                continue
            }
            out += TtsVoice(
                sid = v.optInt("sid"),
                gender = v.optString("gender"),
                name = v.optString("name"),
            )
        }
        return out
    }

    /**
     * 下载并安装一个模型。
     *
     * @return null = 装好了；非 null = 失败原因（给人看的）。
     */
    fun download(
        context: Context,
        model: TtsModel,
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): String? {
        val target = dirOf(context, model)
        val staging = File(context.filesDir, "tts/.staging-${model.id}")
        val zipFile = File(context.filesDir, "tts/.dl-${model.id}.zip")
        try {
            staging.deleteRecursively()
            zipFile.delete()
            zipFile.parentFile?.mkdirs()
            // 本轮标记已由发布任务的主线程准备；这里不能覆盖用户刚发出的取消。

            // ① 落临时文件。注册取消动作：连接一旦建立，cancelDownload 能立即掐断它。
            val call = http.newCall(Request.Builder().url(model.url).get().build())
            registry.register(model.id) { call.cancel() }
            call.execute().use { resp ->
                if (!resp.isSuccessful) return "下载失败：HTTP ${resp.code}"
                val body = resp.body ?: return "下载失败：响应为空"
                val total = body.contentLength().takeIf { it > 0 } ?: model.bytes
                var done = 0L
                FileOutputStream(zipFile).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            // 每块自查取消标记：连接还没被掐（比如 cancel 早于建连）也能在下一块退出。
                            if (registry.isCancelRequested(model.id)) {
                                throw kotlinx.coroutines.CancellationException("下载已取消")
                            }
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            }

            // ② 对字节数
            if (registry.isCancelRequested(model.id)) throw kotlinx.coroutines.CancellationException("下载已取消")
            if (model.bytes > 0 && zipFile.length() != model.bytes) {
                return "下载不完整：${zipFile.length()} ≠ ${model.bytes} 字节"
            }
            // ③ 对 sha256
            if (model.sha256.isNotBlank()) {
                val actual = sha256Of(zipFile)
                if (actual != model.sha256) {
                    return "校验失败：文件与清单不符（$actual）"
                }
            }

            // 解压进暂存目录。
            // **必须保留子目录**：英文模型的 espeak-ng-data 是几百个文件的小目录树，
            // 拍平成文件名会直接把音素化数据毁掉。
            // 路径护栏按 / 分段判，**不能用 contains("..")** —— 上游真有合法文件名里带 ".."。
            staging.mkdirs()
            ZipInputStream(zipFile.inputStream()).use { zis ->
                while (true) {
                    if (registry.isCancelRequested(model.id)) throw kotlinx.coroutines.CancellationException("下载已取消")
                    val entry = zis.nextEntry ?: break
                    val segs = entry.name.split('/', '\\').filter { it.isNotBlank() }
                    if (segs.isEmpty() || segs.any { it == ".." }) {
                        Log.w(TAG, "压缩包里有可疑路径，跳过：${entry.name}")
                        continue
                    }
                    if (entry.isDirectory) continue
                    val out = File(staging, segs.joinToString(File.separator))
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
            }

            // 缺文件就当没装成，别留下一个能选中却发不出声的半吊子
            val missing = requiredFiles(model).filter { File(staging, it).length() <= 0L }
            if (missing.isEmpty() && model.dataDir.isNotBlank() &&
                !File(staging, model.dataDir).isDirectory
            ) {
                return "模型包内容不完整，缺目录：${model.dataDir}"
            }
            if (missing.isNotEmpty()) return "模型包内容不完整，缺：${missing.joinToString()}"

            // 就位：先挪走旧的再改名，半路被打断也不会留下坏的
            if (registry.isCancelRequested(model.id)) throw kotlinx.coroutines.CancellationException("下载已取消")
            val old = File(context.filesDir, "tts/.old-${model.id}")
            if (old.exists() && !old.deleteRecursively()) return "安装失败：无法清理旧备份"
            if (target.exists() && !target.renameTo(old)) return "安装失败：无法备份现有模型"
            if (!staging.renameTo(target)) {
                // 🔴 改名失败必须把旧目录还回去——不能让一次失败把用户已装好的模型弄没。
                if (old.isDirectory && !target.exists() && !old.renameTo(target)) {
                    Log.e(TAG, "旧模型恢复失败，备份仍保留：${old.name}")
                    return "安装失败：旧模型恢复失败，备份已保留，请重试"
                }
                return "安装失败：无法写入 ${target.name}"
            }
            old.deleteRecursively()
            Log.i(TAG, "模型已安装：${model.id} → ${target.absolutePath}")
            return null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // 页面关闭或任务取消不应伪装成安装失败，也不能继续更新已经退出的页面。
            throw cancelled
        } catch (aborted: java.io.IOException) {
            // 连接被 cancelDownload 掐断时 read 抛的是 IOException——用户主动取消要按
            // 取消走（原样抛 CancellationException），不能记成「下载失败」吓用户。
            if (registry.isCancelRequested(model.id)) {
                throw kotlinx.coroutines.CancellationException("下载已取消")
            }
            Log.w(TAG, "模型下载失败：${model.id}", aborted)
            return "下载失败：${aborted.message ?: aborted::class.java.simpleName}"
        } catch (bad: Throwable) {
            Log.w(TAG, "模型下载或安装失败：${model.id}", bad)
            return "${bad::class.java.simpleName}: ${bad.message}"
        } finally {
            registry.unregister(model.id)
            staging.deleteRecursively()
            zipFile.delete()
        }
    }

    /** 删掉一个已安装的模型（腾空间用）。 */
    fun remove(context: Context, model: TtsModel) {
        dirOf(context, model).deleteRecursively()
        Log.i(TAG, "模型已删除：${model.id}")
    }

    /**
     * 清掉**清单里已经没有的**模型目录。
     *
     * 为什么必须有它：换模型时会给新模型一个新 id（`zh-aishell3` → `zh-fanchen-c`），
     * 老目录不会自己消失。少了这一步，用户手机上会永远躺着几十兆再也用不到的旧权重，
     * 而界面上**看不到任何入口**能删它（`remove` 只认清单里有的模型）——
     * 属于「用户自己没法处理」的那类垃圾。
     *
     * 只动 `filesDir/tts/` 下**不以 `.` 开头**的一级目录：`.staging-` / `.dl-` / `.old-`
     * 是下载过程中的临时名字，由 [download] 自己收尾，不归这里管。
     *
     * @param alive **刚拉到的那份清单**里的模型 id。不能图省事读 [list]（缓存/兜底）：
     *   清单没拉到时 [list] 给的是兜底清单，拿它去删会误伤只有新清单里才有的模型。
     * @return 被删掉的模型 id（给日志与走查用；正常情况是空表）。
     */
    fun pruneOrphans(context: Context, alive: Set<String>): List<String> =
        pruneOrphans(File(context.filesDir, "tts"), alive)

    /**
     * 同上，直接吃目录。**抽出来是为了能测** —— [pruneOrphans] 要 `Context`，
     * JVM 单测给不了；而这里承载的正是那条会**误删**的规则。
     *
     * 这里**不打日志**：`android.util.Log` 在 JVM 单测里是 stub，一调就抛
     * （`isReturnDefaultValues = false`），留在纯函数里就谁也测不了它。日志在调用处打。
     */
    internal fun pruneOrphans(root: File, alive: Set<String>): List<String> {
        val names = root.listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
        val removed = ArrayList<String>()
        orphanDirs(names, alive).forEach { name ->
            if (File(root, name).deleteRecursively()) removed += name
        }
        return removed
    }

    /**
     * 哪些目录该清掉。**抽成纯函数是为了能测**：`pruneOrphans` 要 `Context`，
     * JVM 单测给不了，而这里承载的正是那条会**误删**的规则 ——
     * 一旦把 `.staging-` / `.dl-` 也算进去，就会删掉正在下载中的临时目录。
     */
    internal fun orphanDirs(existing: List<String>, alive: Set<String>): List<String> =
        existing.filterNot { it.startsWith(".") || it in alive }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 兜底清单。跟 `scripts/tts_publish_models.py` 的 MODELS 保持一致 ——
     * 换模型（尤其换 sha256）时两边都要改，否则兜底那份下载下来会校验失败。
     *
     * 🔴 **兜底少一条不只是「少个选项」**：用户下载并选中的声线如果不在兜底里，
     * 那么在「没拉过清单」的页面上（单词练习/每日例句/词卡）就解析不出来 ——
     * 表现为偏好里的声音被悄悄换成系统朗读，英式在没装 en-GB 的手机上直接失败。
     * 2026-10-15 的英式 bug 就是这样：兜底里只有中文与美式，**英式那条 10-11 就发布了**。
     * 现在有 [list] 的磁盘缓存兜着，但兜底仍必须与线上清单逐条对齐（回归见
     * `SpeechCatalogTest.内置兜底清单必须与线上发布的三条模型一致`）。
     */
    internal val fallback: List<TtsModel> = listOf(
        // 2026-10-08 换掉了 zh-aishell3（vits-icefall-zh-aishell3，**8000 Hz**）。
        // 8k 的带宽只有 0–4 kHz = 电话音质，用户原话「中文离线语音特别垃圾」。
        // fanchen-C 是 16000 Hz，而且一条权重里烘着 187 个说话人 ——
        // 男女声都从这里面选（voices 里的 sid 是本机量出来的，见
        // `scripts/tts_publish_models.py` 与 `work/tmp/scan_speakers.py`）。
        //
        // ⚠️ 换了 id（zh-aishell3 → zh-fanchen-c）：判「装了没」是按**清单声明的文件名**逐个查的，
        // 所以老用户那 28MB 不会被误判成已安装；变成孤儿的旧目录由
        // [TtsModelStore.pruneOrphans] 在下次拉到清单后清掉。
        TtsModel(
            id = "zh-fanchen-c", name = "中文·多说话人（离线）",
            lang = "zh", accent = Accent.Chinese,
            url = "https://dl.useall.top/tts/vits-zh-hf-fanchen-C.zip",
            bytes = 112_764_765L,
            sha256 = "0c377e98ca3909f6e2884797903f56699af016fde631b5d9a7d098a37ecbff27",
            license = "引擎 Apache-2.0（sherpa-onnx）；模型权重为社区贡献、包内未附许可证文件，正式商用前请自行核实",
            licenseUrl = "https://huggingface.co/csukuangfj/vits-zh-hf-fanchen-C",
            attribution = "sherpa-onnx（引擎，Apache-2.0）· vits-zh-hf-fanchen-C（权重，社区贡献）",
            note = "16000 Hz，187 个说话人，男女声都有。未打包 jieba 分词词典（12MB），多音字准确率略低于完整包。",
            modelFile = "vits-zh-hf-fanchen-C.onnx", lexicon = "lexicon.txt", tokens = "tokens.txt",
            dataDir = "", dictDir = "",
            ruleFsts = listOf("date.fst", "number.fst", "phone.fst", "new_heteronym.fst"),
            ruleFars = emptyList(), speakers = 187,
            voices = listOf(
                TtsVoice(sid = 154, gender = "female", name = "中文·女声（离线）"),
                // 2026-10-08 用户盲听后从 4 个男声候选里点了「候选B」= sid 25
                // （F0 141.5 Hz，峰值 0.414 / RMS 0.0759，入选男声里最响的）。
                // 原先的 sid 21 也偏男（147.6 Hz）但只有 0.333 / 0.0428，听着发虚。
                TtsVoice(sid = 25, gender = "male", name = "中文·男声（离线）"),
            ),
        ),
        // 2026-10-05 换掉了旧的 en-ljspeech（vits-icefall-en_US-ljspeech-low）：
        // 旧包靠 espeak-ng 做音素化，包内必带 espeak-ng-data（GPL-3.0），一分发就要
        // 履行 GPL 的源码提供义务。这一条走 **lexicon.txt 词典路径**（12.4 万词），
        // dataDir 留空 = 不要 espeak-ng。代价要如实写：词典外的词读不出来。
        // 与 `scripts/tts_publish_models.py` 的 MODELS 同步改，别只改一边。
        TtsModel(
            id = "en-ljs", name = "英文女声（离线）",
            lang = "en", accent = Accent.American,
            url = "https://dl.useall.top/tts/vits-ljs.zip",
            bytes = 106_801_584L,
            sha256 = "c2f05d2d4f7917620e88000a049bdf019c9bfe399d6351fd7466e8fdada49726",
            license = "Apache-2.0（LJSpeech 数据集 public domain，权重源自 MIT 的 VITS 官方 checkpoint）",
            licenseUrl = "https://huggingface.co/csukuangfj/vits-ljs",
            attribution = "LJSpeech（数据，public domain）· VITS（权重，MIT）· sherpa-onnx（引擎，Apache-2.0）",
            note = "单说话人女声，22050 Hz。音素化走自带的 12.4 万词词典，不依赖 espeak-ng；词典外的词读不出来。",
            modelFile = "vits-ljs.onnx", lexicon = "lexicon.txt", tokens = "tokens.txt",
            dataDir = "", dictDir = "",
            ruleFsts = emptyList(),
            ruleFars = emptyList(), speakers = 1,
        ),
        // 2026-10-11 发布的英式双声线（30,326,764 字节，公开包 SHA 已回读一致）。
        // 字段与 `https://dl.useall.top/tts/manifest.json` 逐条对齐（2026-10-15 重新拉取核对）。
        // ⚠️ 词典源自美式 CMU 音素：**部分词不等同标准英音，词典外的词读不出来** ——
        // 这条限制必须原样带给用户（note 会显示在声音行上），不许拿它冒充「标准英音」。
        TtsModel(
            id = "en-vctk-british", name = "英式英语·双声线（离线）",
            lang = "en", accent = Accent.British,
            url = "https://dl.useall.top/tts/vits-vctk-british-int8.zip",
            bytes = 30_326_764L,
            sha256 = "8198f6f6545eb012c0af4da00612ce04fc3a1f5cc29075160c2d514d6eb2ea19",
            license = "模型 Apache-2.0；VITS MIT；VCTK ODC-By-1.0；词典 BSD（原文随包）",
            licenseUrl = "https://huggingface.co/csukuangfj/vits-vctk",
            attribution = "CSTR VCTK · Veaux / Yamagishi / MacDonald · VITS · CMU Dictionary / Nathan Constant",
            note = "22050 Hz，英格兰男女声。词典采用美式音素，部分词不等同标准英音；词典外词无法朗读。",
            modelFile = "vits-vctk.int8.onnx", lexicon = "lexicon.txt", tokens = "tokens.txt",
            dataDir = "", dictDir = "",
            ruleFsts = emptyList(),
            ruleFars = emptyList(), speakers = 109,
            voices = listOf(
                TtsVoice(sid = 101, gender = "female", name = "英式·女声（英格兰南部）"),
                TtsVoice(sid = 43, gender = "male", name = "英式·男声（萨里）"),
            ),
        ),
    )
}
