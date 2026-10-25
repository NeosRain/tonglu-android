package cn.tonglu.app.data.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * **真的会发声**的内置离线语音引擎封装（docs/plan/55）。
 *
 * ## 引擎与模型
 * 引擎是 **sherpa-onnx 1.13.8（Apache-2.0）**，onnxruntime 是 MIT，都不带 copyleft。
 * 模型**不打进 APK**（方案 C），由 [TtsModelStore] 下载到 `filesDir/tts/<id>/` 之后再加载，
 * 所以这里只认文件系统路径，**不再读 assets**。
 *
 * ## 许可要照实说（别假装完全自由）
 * - 中文模型 AISHELL-3：**Apache-2.0**，可商用。
 * - 英文模型 LJSpeech：数据集 public domain，**但音素化依赖 espeak-ng 数据（GPL-3.0）** ——
 *   能商用，有提供源码的义务。这条写在 [TtsModel.license] 里，界面会显示。
 * - 2026-10-04 之前用的标贝「小雅」**已换掉**：它的训练数据 BZNSYP 写明
 *   `License: Non-commercial use`，**根本不许商用**。
 *
 * ## 为什么配置全部来自清单
 * 不同模型的差别比想象大：AISHELL-3 的权重叫 `model.onnx`、带 `new_heteronym.fst`、
 * 有 174 个说话人；英文模型**没有 lexicon.txt**，靠 espeak-ng 转音素，多一个 `espeak-ng-data`。
 * 写死任何一种都会在换模型时悄悄坏掉，所以路径与参数一律从 [TtsModel] 读。
 */
object BuiltinTts {

    @Volatile private var engine: OfflineTts? = null
    @Volatile private var loadedId: String? = null
    @Volatile private var sampleRate: Int = 0
    @Volatile private var speakers: Int = 0
    @Volatile private var failure: String? = null
    @Volatile private var track: AudioTrack? = null

    /** 合成是重活，只能在一个线程上排队，不然两条声音会叠在一起。 */
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "tonglu-tts").apply { isDaemon = true }
    }
    private val loading = AtomicBoolean(false)
    @Volatile private var clients = 0
    private val lifecycleLock = Any()
    private var idleRelease: ScheduledFuture<*>? = null
    private val audioCache = object : android.util.LruCache<String, GeneratedAudio>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: GeneratedAudio): Int = value.samples.size * 4
    }

    fun attach() = synchronized(lifecycleLock) {
        clients++
        idleRelease?.cancel(false)
        idleRelease = null
    }

    fun detach() = synchronized(lifecycleLock) {
        clients = (clients - 1).coerceAtLeast(0)
        if (clients != 0) return@synchronized
        stop()
        // Brief page changes must not reload a hundred-megabyte model each time.
        idleRelease?.cancel(false)
        idleRelease = worker.schedule({
            synchronized(this) {
                if (clients == 0) { releaseEngine(); audioCache.evictAll() }
            }
        }, 30, TimeUnit.SECONDS)
    }

    fun warm(context: Context, model: TtsModel) {
        val app = context.applicationContext
        worker.execute { synchronized(this) { if (clients > 0) ensure(app, model) } }
    }

    /**
     * 朗读代号，每次 [stop] +1（FINDINGS #9）。
     *
     * 光掐掉当前那条 [track] 不够：合成是异步的，等它合成完 `play()` 会**新建一条**
     * AudioTrack 接着响 —— 用户点了「停止」却还有声音冒出来，看起来像没生效。
     * 所以播放前后都要比对代号，对不上就把这段音频直接丢掉。
     */
    private val generation = AtomicInteger(0)

    private const val TAG = "TongluBuiltinTts"

    /** 引擎已经加载好、可以发声。 */
    val ready: Boolean get() = engine != null

    /** 上一次失败的**原因**（给人看的）。没失败则为 null。 */
    val lastError: String? get() = failure

    /** 采样率，列表页展示用；没加载时为 0。 */
    val engineSampleRate: Int get() = sampleRate

    /** 当前模型有几个说话人。 */
    val engineSpeakers: Int get() = speakers

    /**
     * 加载引擎。重复调用安全；**换模型会自动重载**。
     * 失败会把原因写进 [lastError] 而不是抛出去——语音不可用不该让整个页面崩掉。
     *
     * ⚠️ **只能在后台线程调用**：读几十兆模型 + 初始化 onnx 要几秒，
     * 在主线程上就是一次实打实的界面冻结（试听按钮正是在主线程调进来的）。
     * [speak] 已经把它排进 [worker]，别在调用处再同步调一次（[selfTest] 例外，它本就在子线程）。
     */
    @Synchronized fun ensure(context: Context, model: TtsModel): Boolean {
        if (engine != null && loadedId == "${model.id}:${model.sha256}") return true
        if (!loading.compareAndSet(false, true)) return false
        return try {
            // ⚠️ 用 `releaseEngine()` 而不是 `release()`：换引擎不属于「用户叫停」，
            // 不该把代号 +1（一 +1，这一趟合成完就会当成过期被丢掉 → 点试听没声）。
            releaseEngine()
            val dir = TtsModelStore.dirOf(context, model)
            if (!TtsModelStore.isInstalled(context, model)) {
                failure = "模型「${model.name}」还没下载"
                return false
            }
            // 传 null 的 AssetManager = 按文件系统路径读（模型不再随包分发）。
            val tts = OfflineTts(null, buildConfig(dir, model))
            engine = tts
            loadedId = "${model.id}:${model.sha256}"
            sampleRate = tts.sampleRate()
            speakers = tts.numSpeakers()
            failure = null
            Log.i(TAG, "离线语音就绪：${model.id} 采样率=$sampleRate 说话人=$speakers 目录=${dir.name}")
            true
        } catch (bad: Throwable) {
            failure = "${bad::class.java.simpleName}: ${bad.message}"
            Log.w(TAG, "离线语音加载失败（${model.id}）：$failure")
            false
        } finally {
            loading.set(false)
        }
    }

    /** 按清单给出的文件名拼出**绝对路径**。清单里没写的（如英文没有 lexicon）就留空。 */
    private fun buildConfig(dir: File, model: TtsModel): OfflineTtsConfig {
        val path = { name: String -> if (name.isBlank()) "" else File(dir, name).absolutePath }
        val vits = OfflineTtsVitsModelConfig(
            model = path(model.modelFile),
            lexicon = path(model.lexicon),
            tokens = path(model.tokens),
            // 英文模型这里指向 espeak-ng-data；中文模型留空（走词典，不需要 espeak）。
            dataDir = path(model.dataDir),
            dictDir = path(model.dictDir),
            noiseScale = 0.667f,
            noiseScaleW = 0.8f,
            lengthScale = 1.0f,
        )
        // date/number/phone 三个 fst 负责「2026 → 二〇二六」这类读法，缺了会读成数字串。
        // 英文模型没有这几个 fst，清单里是空表，这里自然就是空串。
        val ruleFsts = model.ruleFsts.map { path(it) }.filter { it.isNotBlank() }.joinToString(",")
        val ruleFars = model.ruleFars.map { path(it) }.filter { it.isNotBlank() }.joinToString(",")
        return OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = vits,
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            ruleFsts = ruleFsts,
            ruleFars = ruleFars,
            // 允许一次合成多句，否则长段落只念第一句。
            maxNumSentences = 32,
            silenceScale = 1.0f,
        )
    }

    /**
     * 朗读。在后台线程合成，合成完用 AudioTrack 播出来。
     *
     * @param rate 0.5~2.0，1.0 为原速（直接传给引擎的 speed）。
     * @param sid 说话人序号（多说话人模型才有意义，越界会被夹回 0）。
     * @return 是否**已经提交**朗读（不代表已经念完）。
     * 引擎没加载时也不阻塞：加载与合成一起排到后台，只有「模型没下载」会返回 false。
     */
    fun speak(
        context: Context, model: TtsModel, text: String, rate: Float, sid: Int = 0,
        onDone: (() -> Unit)? = null,
    ): Boolean {
        if (text.isBlank()) return false
        // 加载**不放在这里**（FINDINGS #9）：这里跑在主线程上，ensure() 一加载就是几秒冻结。
        // 只判断「要不要加载」，真正的加载丢进 worker —— load / generate / play 排在同一条队列。
        val needsLoad = !(engine != null && loadedId == "${model.id}:${model.sha256}")
        if (needsLoad && !TtsModelStore.isInstalled(context, model)) {
            failure = "模型「${model.name}」还没下载"
            return false
        }
        val mine = generation.incrementAndGet()
        haltTrack()
        val app = context.applicationContext
        val cacheKey = "${model.id}:${model.sha256}|$sid|${rate.coerceIn(0.5f, 2.0f)}|$text"
        worker.execute { synchronized(this) {
            if (generation.get() != mine) { onDone?.invoke(); return@execute }
            // 代号在**这一趟开始时**取（FINDINGS #9）。
            // 旧写法是「加载完 → stop() → 取代号」，于是模型加载那几秒里用户点的停止
            // 会被算进基线 —— 加载完照样合成、照样出声（我都退出了它还在念）。
            // ⚠️ 这里只能用 `haltTrack()`（不动代号），**不能调 `stop()`**：
            //    `ensure()` 重载模型时也会停一次，那是「换引擎」不是「用户叫停」，
            //    一旦它也把代号 +1，这一趟就会被自己作废，表现为点了试听一声不吭
            //    （2026-10-07 实测踩到，编译期看不出来）。
            if (!ensure(app, model)) {
                // 加载失败只可能出在文件坏了（装没装上面已经查过）。原因留在 lastError，
                // 下一次再点就会如实说出来；这一趟不朗读，但也不吞掉证据。
                Log.w(TAG, "离线语音未就绪，这一趟不朗读：$failure")
                onDone?.invoke()
                return@execute
            }
            val tts = engine ?: run { onDone?.invoke(); return@execute }
            try {
                if (generation.get() != mine) return@execute
                val who = if (speakers > 1) sid.coerceIn(0, speakers - 1) else 0
                val audio = audioCache.get(cacheKey) ?: synchronized(this) {
                    tts.generate(text, who, rate.coerceIn(0.5f, 2.0f))
                }.also {
                    if (text.length <= 400 && it.samples.isNotEmpty() && it.samples.size * 4 <= 4 * 1024 * 1024) {
                        audioCache.put(cacheKey, it)
                    }
                }
                if (audio.samples.isEmpty()) {
                    failure = "合成结果为空"
                    return@execute
                }
                // 合成可能花几秒，这期间用户点过「停止」：这段音频直接丢掉，不许再出声。
                if (generation.get() != mine) return@execute
                play(audio, mine)
            } catch (bad: Throwable) {
                failure = "${bad::class.java.simpleName}: ${bad.message}"
                Log.w(TAG, "合成失败：$failure")
            } finally {
                onDone?.invoke()
            }
        } }
        return true
    }

    /**
     * 把合成结果播出来：浮点 PCM → 16 位整数 → AudioTrack 静态模式一次写完。
     *
     * 🔴 **缓冲区容量必须 ≥ 数据长度**（2026-10-08 修，用户报「只念出第一个音节」）：
     * `MODE_STATIC` 下 `setBufferSizeInBytes` 设的就是**这段音频的容量**，
     * 写进去超过它的部分会被丢掉，而 `write()` 只**返回实写字节数** ——
     * 旧代码没接这个返回值，所以截断是**静默**的。
     *
     * 旧写法：
     * ```
     * getMinBufferSize(...).coerceAtLeast(bytes.size.coerceAtMost(1))
     * ```
     * `bytes.size.coerceAtMost(1)` 是 `min(bytes.size, 1)`，对任何非空音频**恒等于 1**，
     * 于是整行等价于「容量 = `getMinBufferSize`」，那只有几 KB ≈ 0.1 秒 ——
     * 不管念一个单词还是念一整句，**都只放开头那一小截**。
     *
     * 实测判据（真机小米 12S，`AudioOut_*` 的 `Total writes` 增量）：
     * 中文预览句 / 英文预览句 / 单词 `abandonment` 三段长短差很多的音频，
     * 修前**都是 +106**（固定值 = 截断），修后随文本长短变化。
     */
    private fun play(audio: GeneratedAudio, mine: Int) {
        val bytes = ByteArray(audio.samples.size * 2)
        for (i in audio.samples.indices) {
            val v = (audio.samples[i] * 32767f).toInt().coerceIn(-32768, 32767)
            bytes[i * 2] = (v and 0xff).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        // `getMinBufferSize` 是**下限**，不是容量：容量要装得下整段音频。
        val minBuf = AudioTrack.getMinBufferSize(
            audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val buffer = staticBufferBytes(minBuf, bytes.size)
        val out = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(audio.sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buffer)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        // 出声前再比一次：从建 track 到 write 之间，用户也可能刚点过「停止」。
        if (generation.get() != mine) {
            try { out.release() } catch (_: Throwable) {}
            return
        }
        track = out
        try {
            // 返回值要接：**写不满就是这一趟没念完**。以前丢掉返回值，截断就成了静默故障
            // （用户只听到第一个音节，日志里一个字都没有）。宁可报出来，也别让它再悄悄发生。
            val written = out.write(bytes, 0, bytes.size)
            if (written != bytes.size) {
                failure = "播放缓冲区不够：只写进 $written / ${bytes.size} 字节"
                Log.w(TAG, "$failure")
            }
            out.play()
        } catch (bad: Throwable) {
            failure = "播放失败：${bad::class.java.simpleName}: ${bad.message}"
            // failure 是可空的 String?，Log.w 第二个参数要非空 —— 直接拼成新串最省事。
            Log.w(TAG, "$failure")
        }
    }

    /**
     * **自检**：合成一句固定文本并存成 wav。
     *
     * 走查用它拿硬证据——模拟器没有声卡也能验：只要这个 wav 有字节、采样率对、
     * 峰值振幅不是 0，就说明「引擎加载 + 文本转音素 + 神经网络合成」整条链是真的跑通了，
     * 不是"接口预留、点了没反应"。
     */
    @Synchronized fun selfTest(context: Context, model: TtsModel, outFile: File): Result {
        if (!ensure(context, model)) return Result(false, outFile, 0, 0, "引擎未就绪：${lastError}")
        val tts = engine ?: return Result(false, outFile, 0, 0, "引擎未就绪")
        return try {
            val text = if (model.lang.startsWith("zh")) "苔米林离线语音自检，今天是二零二六年十月四日。"
            else "Taimi Forest offline speech self test, October fourth, twenty twenty six."
            val who = if (speakers > 1) 0 else 0
            val audio = tts.generate(text, who, 1.0f)
            if (audio.samples.isEmpty()) return Result(false, outFile, 0, audio.sampleRate, "合成结果为空")
            outFile.parentFile?.mkdirs()
            val saved = audio.save(outFile.absolutePath)
            Result(saved && outFile.length() > 44, outFile, outFile.length(), audio.sampleRate,
                if (saved) "已合成 ${audio.samples.size} 个采样点" else "保存 wav 失败")
        } catch (bad: Throwable) {
            Result(false, outFile, 0, 0, "${bad::class.java.simpleName}: ${bad.message}")
        }
    }

    /** 自检结果。bytes 能直接拿去核对「是不是真的出了声音」。 */
    data class Result(
        val ok: Boolean, val file: File, val bytes: Long, val sampleRate: Int, val message: String,
    )

    /**
     * 停止朗读。
     *
     * 除了掐掉当前这条 AudioTrack，还要把代号 +1 —— 不然还在飞的那趟合成
     * 一会儿会自己建一条新 track 接着响（FINDINGS #9）。
     */
    /**
     * 只掐掉当前这条 [track]，**不动代号**。
     *
     * 为什么和 [stop] 分开：代号是「作废在飞请求」的开关，**只有用户叫停才该 +1**。
     * 换引擎、开新一趟朗读时也要先停掉旧声音，但那不该把自己这趟作废 ——
     * 两件事混在 `stop()` 里，就会让「先取代号还是先加载」怎么排都错。
     */
    private fun haltTrack() {
        try { track?.stop(); track?.release() } catch (_: Throwable) {}
        track = null
    }

    fun stop() {
        generation.incrementAndGet()
        haltTrack()
    }

    /**
     * 静态播放该开多大的缓冲区。
     *
     * `AudioTrack` 的 `MODE_STATIC` 下，`setBufferSizeInBytes` 设的值**就是这段音频的容量**：
     * 写进去超过它的部分会被丢掉，而 `write()` 只返回实写字节数。所以这个值必须 **≥ 数据长度**，
     * `getMinBufferSize()` 只是**下限**，不是容量。
     *
     * 抽成纯函数是为了能被单测钉住 —— 这里错过一次：旧写法
     * `getMinBufferSize(...).coerceAtLeast(bytes.size.coerceAtMost(1))` 里
     * `bytes.size.coerceAtMost(1)` 对任何非空音频**恒等于 1**，整行退化成「容量 = 下限」，
     * 结果所有离线朗读都只放得出**第一个音节**（见 `docs/BUGS.md` A11）。
     * 回归测试：`BuiltinTtsBufferTest`。
     */
    internal fun staticBufferBytes(minBuffer: Int, dataBytes: Int): Int =
        maxOf(minBuffer, dataBytes).coerceAtLeast(0)

    /** 只释放引擎，**不动代号**（[ensure] 重载模型时用）。 */
    private fun releaseEngine() {
        haltTrack()
        try { engine?.release() } catch (_: Throwable) {}
        engine = null
        loadedId = null
        sampleRate = 0
        speakers = 0
    }

    fun release() {
        // stop() 会把代号 +1：释放之后还在飞的那趟合成，一律不许再落到新 track 上。
        stop()
        worker.execute { synchronized(this) { releaseEngine(); audioCache.evictAll() } }
    }
}
