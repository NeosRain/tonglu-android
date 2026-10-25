package cn.tonglu.app.data.speech

import android.media.AudioAttributes
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Main-thread controller. A new request invalidates every callback from the old one. */
internal class AudioPackPlayer(
    context: Context,
    private val store: AudioPackStore,
    private val scope: CoroutineScope,
    private val status: (String, String?) -> Unit,
) {
    private var generation = 0L
    private var job: Job? = null
    private var player: MediaPlayer? = null
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
    }
    private var focused = false

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (focused) audioManager.abandonAudioFocus(focusListener)
        focused = false
    }

    fun stop() {
        generation++
        job?.cancel(); job = null
        player?.release(); player = null
        abandonFocus()
        Log.i("TongluAudioPack", "stop generation=$generation")
        status("已停止", null)
    }

    fun play(items: List<PackedAudio>, speed: Float = 1f) {
        require(speed.isFinite() && speed in 0.25f..4f)
        stop()
        val token = generation
        val requestedAt = SystemClock.elapsedRealtime()
        job = scope.launch {
            for ((index, item) in items.withIndex()) {
                if (token != generation) return@launch
                status("正在准备…", item.id)
                try {
                    val file = store.file(item)
                    if (token != generation) return@launch
                    items.getOrNull(index + 1)?.let { next ->
                        launch {
                            try { store.file(next) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) {
                                if (token == generation) status("下一句预取失败，播放时将重试：${e.message}", item.id)
                            }
                        }
                    }
                    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
                        val media = MediaPlayer(); player = media
                        media.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_MEDIA).build())
                        media.setOnPreparedListener {
                            if (token == generation && continuation.isActive) {
                                @Suppress("DEPRECATION")
                                val granted = if (focused) AudioManager.AUDIOFOCUS_REQUEST_GRANTED else
                                    audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                                if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                                    continuation.resumeWith(Result.failure(IllegalStateException("暂时无法获得音频播放权限，请稍后重试")))
                                    return@setOnPreparedListener
                                }
                                focused = true
                                Log.i("TongluAudioPack", "prepared id=${item.id} voice=${item.voiceId} generation=$token elapsedMs=${SystemClock.elapsedRealtime() - requestedAt}")
                                it.playbackParams = android.media.PlaybackParams().setPitch(1f).setSpeed(speed)
                                status("正在播放", item.id); it.start()
                            }
                        }
                        media.setOnCompletionListener {
                            if (token == generation && continuation.isActive) continuation.resumeWith(Result.success(Unit))
                        }
                        media.setOnErrorListener { _, what, extra ->
                            if (token == generation && continuation.isActive) continuation.resumeWith(
                                Result.failure(IllegalStateException("音频播放失败（$what/$extra）")))
                            true
                        }
                        try { media.setDataSource(file.absolutePath); media.prepareAsync() }
                        catch (e: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(e)) }
                    }
                    player?.release(); player = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (token == generation) { player?.release(); player = null; abandonFocus(); status(e.message ?: "播放失败，请重试", null) }
                    return@launch
                }
            }
            if (token == generation) { abandonFocus(); status("播放完成", null) }
        }
    }
}
