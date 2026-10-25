package cn.tonglu.app.data.listening

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Build
import android.util.Log
import java.io.File
import java.io.IOException

private const val TAG = "ListeningAudio"

/**
 * 播放：**只用系统 MediaPlayer，不引第三方播放器依赖**。
 *
 * 项目里原本一处音频播放都没有（连录音权限都没有），这是第一次引入。
 * 选 MediaPlayer 而不是 ExoPlayer，是因为这里只需要"播一个本地文件 + 变速 + 定位"，
 * 为一个功能拉一整套 media3 不划算。
 */
class ListeningPlayer {

    private var player: MediaPlayer? = null
    private var source: File? = null
    private var urlSource: String? = null
    private var speedValue = 1.0f

    /** 区间播放的「到点暂停」定时器（见 [playRange]）。 */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var stopAt: Runnable? = null

    /**
     * 整条音轨自然播完时的回调（seek 到末尾也算）。
     *
     * 「先听后录」要靠它：本句播完 → 自动开始录音。用回调而不是界面轮询，
     * 是因为轮询在变速/慢机上会漏过终点，回调由 MediaPlayer 自己触发更可靠。
     * 只用于**整轨结束**；单句区间结束由界面按 endMs 判断（区间不是轨尾）。
     */
    var onCompleted: (() -> Unit)? = null

    val isLoaded: Boolean get() = player != null
    val isPlaying: Boolean get() = runCatching { player?.isPlaying == true }.getOrDefault(false)
    val durationMs: Int get() = runCatching { player?.duration ?: 0 }.getOrDefault(0)
    val positionMs: Int get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    /** 载入一个本地文件。重复载入同一个文件不会重新解码。 */
    @Throws(IOException::class)
    fun load(file: File) {
        if (source == file && player != null) return
        release()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
        } catch (cause: Exception) {
            mp.release()
            throw cause
        }
        player = mp
        source = file
        mp.setOnCompletionListener { onCompleted?.invoke() }
        applySpeed()
    }

    /**
     * 载入一个**远端音频**（http/https）。真题听力的原版录音就走这条路。
     *
     * 与 [load] 一样：重复载入同一个地址不会重新解码。**这是阻塞调用**（`prepare()`），
     * 调用方要放到 IO 线程。
     */
    @Throws(IOException::class)
    fun loadUrl(url: String) {
        if (urlSource == url && player != null) return
        release()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(url)
            mp.prepare()
        } catch (cause: Exception) {
            mp.release()
            throw cause
        }
        player = mp
        urlSource = url
        mp.setOnCompletionListener { onCompleted?.invoke() }
        applySpeed()
    }

    fun play() {
        try { player?.start() } catch (cause: Exception) { Log.e(TAG, "播放失败", cause) }
    }

    /**
     * 只播**一段**（听力题「重听本题」用）。`MediaPlayer` 只在**整轨**播完时回调，
     * 区间结束得自己盯 —— 用一个 Handler 每 120ms 看一次位置，到 `endMs` 就暂停。
     */
    fun playRange(startMs: Int, endMs: Int) {
        val mp = player ?: return
        cancelStop()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // ⚠️ 带 mode 的这个重载第一个参数是 **Long**（不带 mode 的那个才是 Int）
                mp.seekTo(startMs.toLong(), MediaPlayer.SEEK_CLOSEST)
            } else {
                @Suppress("DEPRECATION")
                mp.seekTo(startMs)
            }
            mp.start()
        } catch (cause: Exception) {
            Log.e(TAG, "区间播放失败", cause)
            return
        }
        val tick = object : Runnable {
            override fun run() {
                val pos = runCatching { mp.currentPosition }.getOrDefault(endMs)
                val playing = runCatching { mp.isPlaying }.getOrDefault(false)
                if (pos >= endMs || !playing) {
                    runCatching { mp.pause() }
                    stopAt = null
                    return
                }
                handler.postDelayed(this, 120L)
            }
        }
        stopAt = tick
        handler.postDelayed(tick, 120L)
    }

    private fun cancelStop() {
        stopAt?.let { handler.removeCallbacks(it) }
        stopAt = null
    }

    fun pause() {
        try { player?.takeIf { it.isPlaying }?.pause() } catch (cause: Exception) { Log.w(TAG, "暂停失败", cause) }
    }

    fun seek(ms: Int) {
        try { player?.seekTo(ms.coerceIn(0, durationMs)) } catch (cause: Exception) { Log.w(TAG, "定位失败", cause) }
    }

    /** 变速但**保持音高**：不能用升降调冒充口音，也不能把句子变快后听不清。 */
    fun speed(value: Float) {
        speedValue = value.coerceIn(0.5f, 2.0f)
        applySpeed()
    }

    private fun applySpeed() {
        val mp = player ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            mp.playbackParams = PlaybackParams().setSpeed(speedValue).setPitch(1.0f)
        } catch (cause: Exception) {
            Log.w(TAG, "变速失败，按原速播放", cause)
        }
    }

    fun release() {
        cancelStop()
        try { player?.release() } catch (cause: Exception) { Log.w(TAG, "释放播放器失败", cause) }
        player = null
        source = null
        urlSource = null
    }
}

/**
 * 跟读录音：**只在用户按录音时才碰麦克风**，录完落在自己的私有目录，不上传。
 *
 * 权限由界面在按下的那一刻申请（见 ui/listening），这里不做权限判断，
 * 免得"没授权"被吞成"录音失败"。
 */
class ListeningRecorder(private val context: Context, private val dir: File) {

    private var recorder: MediaRecorder? = null
    private var output: File? = null

    val isRecording: Boolean get() = recorder != null

    @Throws(IOException::class)
    fun start(name: String): File {
        stop()
        dir.mkdirs()
        val file = File(dir, name)
        // API 31 起 MediaRecorder 要一个 Context；给它 applicationContext，不持有界面引用
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context.applicationContext)
        else @Suppress("DEPRECATION") MediaRecorder()
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(128_000)
            mr.setAudioSamplingRate(44_100)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
        } catch (cause: Exception) {
            try { mr.release() } catch (_: Exception) { }
            throw cause
        }
        recorder = mr
        output = file
        return file
    }

    /** 结束录音。返回落地文件；没在录就返回 null。 */
    fun stop(): File? {
        val mr = recorder ?: return null
        val file = output
        recorder = null
        output = null
        try {
            mr.stop()
        } catch (cause: Exception) {
            Log.w(TAG, "停止录音失败（多半是录得太短）", cause)
            file?.delete()
            return null
        } finally {
            try { mr.release() } catch (_: Exception) { }
        }
        // 太短的文件没有回放价值，直接丢掉，免得界面上出现一个 0 字节的"我的录音"
        if (file == null || !file.exists() || file.length() < 1024) { file?.delete(); return null }
        return file
    }

    fun cancel() {
        val mr = recorder ?: return
        recorder = null
        output?.delete()
        output = null
        try { mr.stop() } catch (_: Exception) { }
        try { mr.release() } catch (_: Exception) { }
    }
}
