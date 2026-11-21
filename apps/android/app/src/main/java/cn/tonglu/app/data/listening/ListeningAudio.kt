package cn.tonglu.app.data.listening

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "ListeningAudio"
internal const val LISTENING_AUDIO_TIMEOUT_MS = 8_000L

internal class ListeningNetworkUnavailable : IOException(MESSAGE) {
    companion object {
        const val MESSAGE = "原音需联网，当前网络不可用"
    }
}

internal suspend fun awaitListeningAudio(
    hasNetwork: Boolean,
    timeoutMs: Long = LISTENING_AUDIO_TIMEOUT_MS,
    prepare: suspend () -> Unit,
) {
    if (!hasNetwork) throw ListeningNetworkUnavailable()
    withTimeoutOrNull(timeoutMs) { prepare() } ?: throw ListeningNetworkUnavailable()
}

internal data class ListeningPlayback(
    val durationMs: Int? = null,
    val positionMs: Int = 0,
    val isPlaying: Boolean = false,
) {
    val isLoaded: Boolean get() = durationMs != null
}

internal class ListeningSpeed {
    private var value = 1.0f

    fun change(speed: Float, isPlaying: Boolean, apply: (Float) -> Unit) {
        value = speed.coerceIn(0.5f, 2.0f)
        if (isPlaying) apply(value)
    }

    fun onPlay(apply: (Float) -> Unit) = apply(value)
}

/** 在主线程操作系统 MediaPlayer。准备、用户操作和媒体回调共用一份播放状态。 */
internal class ListeningPlayer {

    private var player: MediaPlayer? = null
    private var source: File? = null
    private var urlSource: String? = null
    private val speed = ListeningSpeed()

    private val handler = Handler(Looper.getMainLooper())
    private var playRequested = false
    private val range = AudioRangePlayback()
    private val tick = object : Runnable {
        override fun run() {
            val mp = player ?: return
            updatePlayback(mp)
            if (player !== mp) return
            if (range.onPosition(positionMs)) {
                pause()
                return
            }
            if (playRequested || isPlaying) handler.postDelayed(this, 100L)
        }
    }

    private val state = MutableStateFlow(ListeningPlayback())
    val playback = state.asStateFlow()
    val isLoaded: Boolean get() = player != null
    val isPlaying: Boolean get() = playback.value.isPlaying
    val durationMs: Int get() = playback.value.durationMs ?: 0
    val positionMs: Int get() = playback.value.positionMs

    fun currentPositionMs(): Int {
        player?.let(::updatePlayback)
        return positionMs
    }

    /** 整轨自然结束。精听跟读用它在末句播完时进入录音。 */
    var onCompleted: (() -> Unit)? = null
    var onError: ((IOException) -> Unit)? = null

    /** 载入一个本地文件。重复载入同一个文件不会重新解码。 */
    @Throws(IOException::class)
    fun load(file: File) {
        if (source == file && isLoaded) return
        release()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(file.absolutePath)
            mp.prepare()
            usePreparedPlayer(mp)
            source = file
        } catch (cause: Exception) {
            releaseMedia(mp)
            throw cause
        }
    }

    /** 在主线程等待异步准备。超时、切题或离开页面时丢弃本次播放器。 */
    suspend fun loadUrl(url: String, hasNetwork: Boolean) {
        if (hasNetwork && urlSource == url && isLoaded) return
        release()
        var pending: MediaPlayer? = null
        try {
            awaitListeningAudio(hasNetwork) {
                suspendCancellableCoroutine<Unit> { result ->
                    val mp = MediaPlayer()
                    pending = mp
                    mp.setOnPreparedListener { if (result.isActive) result.resume(Unit) }
                    mp.setOnErrorListener { _, what, extra ->
                        if (result.isActive) result.resumeWithException(mediaError(what, extra))
                        true
                    }
                    mp.setDataSource(url)
                    mp.prepareAsync()
                }
            }
            val mp = checkNotNull(pending)
            mp.setOnPreparedListener(null)
            usePreparedPlayer(mp)
            urlSource = url
            pending = null
        } finally {
            // 释放尚在准备的远端流可能等待网络清理，不能挡住失败提示。
            pending?.let { mp -> CoroutineScope(Dispatchers.IO).launch { releaseMedia(mp) } }
        }
    }

    private fun usePreparedPlayer(mp: MediaPlayer) {
        val duration = mp.duration.coerceAtLeast(0)
        player = mp
        state.value = ListeningPlayback(durationMs = duration)
        mp.setOnErrorListener { _, what, extra ->
            fail(mp, mediaError(what, extra))
            true
        }
        mp.setOnSeekCompleteListener {
            if (player === mp) {
                if (range.onSeekComplete()) {
                    start(mp)
                } else {
                    updatePlayback(mp)
                }
            }
        }
        mp.setOnCompletionListener {
            if (player === mp && !range.isSeeking) {
                playRequested = false
                range.clear()
                refreshPlayback()
                if (player === mp) onCompleted?.invoke()
            }
        }
    }

    fun play(): Boolean {
        val mp = player ?: return false
        range.clear()
        return start(mp)
    }

    /** 只播「重听本题」的时间轴区间。定位完成后才播，避免从旧位置出声。 */
    fun playRange(startMs: Int, endMs: Int) {
        val mp = player ?: return
        val segment = AudioRange.clamp(startMs, endMs, durationMs) ?: return
        if (range.isSeeking) return
        pause()
        if (player !== mp) return
        range.begin(segment)
        try {
            seekTo(mp, segment.startMs)
        } catch (cause: IllegalStateException) {
            fail(mp, IOException("区间播放失败", cause))
        }
    }

    private fun start(mp: MediaPlayer): Boolean {
        try {
            mp.start()
            speed.onPlay { applySpeed(mp, it) }
        } catch (cause: IllegalStateException) {
            fail(mp, IOException("播放失败", cause))
            return false
        }
        playRequested = true
        refreshPlayback()
        return player === mp
    }

    fun pause() {
        val mp = player ?: return
        range.clear()
        try {
            if (playRequested || mp.isPlaying) mp.pause()
            playRequested = false
            refreshPlayback()
        } catch (cause: IllegalStateException) {
            fail(mp, IOException("暂停失败", cause))
        }
    }

    fun seek(ms: Int) {
        val mp = player ?: return
        try {
            seekTo(mp, ms.coerceIn(0, durationMs))
        } catch (cause: IllegalStateException) {
            fail(mp, IOException("定位失败", cause))
        }
    }

    private fun seekTo(mp: MediaPlayer, ms: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mp.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST)
        } else {
            @Suppress("DEPRECATION")
            mp.seekTo(ms)
        }
    }

    /** 保持音高。暂停时只记下倍速，写入非零 playbackParams 会启动 MediaPlayer。 */
    fun speed(value: Float) {
        val mp = player
        if (mp == null) {
            speed.change(value, isPlaying = false) {}
        } else {
            try {
                speed.change(value, playRequested && mp.isPlaying) { applySpeed(mp, it) }
            } catch (cause: IllegalStateException) {
                fail(mp, IOException("读取播放状态失败", cause))
            }
        }
    }

    private fun applySpeed(mp: MediaPlayer, speed: Float) {
        try {
            mp.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1.0f)
        } catch (cause: IllegalArgumentException) {
            Log.w(TAG, "播放器不支持该倍速", cause)
        } catch (cause: IllegalStateException) {
            Log.w(TAG, "无法调整播放倍速", cause)
        }
    }

    private fun refreshPlayback() {
        handler.removeCallbacks(tick)
        tick.run()
    }

    private fun updatePlayback(mp: MediaPlayer) {
        try {
            state.value = playback.value.copy(positionMs = mp.currentPosition.coerceAtLeast(0), isPlaying = mp.isPlaying)
        } catch (cause: IllegalStateException) {
            fail(mp, IOException("读取播放状态失败", cause))
        }
    }

    private fun fail(mp: MediaPlayer, cause: IOException) {
        if (player !== mp) return
        Log.w(TAG, "音频播放失败", cause)
        release()
        onError?.invoke(cause)
    }

    fun release() {
        handler.removeCallbacks(tick)
        val mp = player
        player = null
        source = null
        urlSource = null
        playRequested = false
        range.clear()
        state.value = ListeningPlayback()
        mp?.let(::releaseMedia)
    }
}

private fun mediaError(what: Int, extra: Int) = IOException("媒体错误（$what/$extra）")

private fun releaseMedia(mp: MediaPlayer) {
    try { mp.release() } catch (cause: IllegalStateException) { Log.w(TAG, "释放播放器失败", cause) }
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
