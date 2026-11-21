package cn.tonglu.app.ui.learning

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.listening.AudioRange
import cn.tonglu.app.data.listening.ListeningNetworkUnavailable
import cn.tonglu.app.data.listening.ListeningPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 真题听力的**原版录音**播放条。
 *
 * 与「自编听力」那条路（`QuestionBankScreen` 里的 [EnglishPromptAudio]）**彻底分开**：
 * - 这里放的是第三方整理件里的**原版录音**（`BankQuestion.audioUrl`）；
 * - 那条放的是**合成朗读**（`BankQuestion.audioText`）。
 *
 * 🔴 两条路不许混：合成音绝不填进 `audioUrl`，界面也各写各的说明。
 *
 * ## 为什么**不在组合时就加载**（2026-10-10 改）
 *
 * 第一版在 `LaunchedEffect` 里直接 `loadUrl` —— 于是**题目一出现就开始拉整段 mp3**
 * （六级真题那条 **25MB**）。三个后果：切题时重拉一次、静音时也白拉、滚动/切题那一下
 * 更容易顿。现在改成：
 * 1. **点播放才异步准备**，断网或 8 秒未就绪时允许重试；
 * 2. **音量 0 时连加载都不做**，直接提示调音量（省一次 25MB 流量）；
 * 3. 离开这一题 `release()`，不留后台播放。
 */
@Composable
internal fun BankOriginalAudio(
    url: String,
    questionKey: String,
    /** 服务端给的时间轴（毫秒）；`-1` 表示没有。 */
    startMs: Int = -1,
    endMs: Int = -1,
    /** 本机已知音轨时长（毫秒）；未知传 0。 */
    durationMs: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember(questionKey, url) { ListeningPlayer() }
    val playback by player.playback.collectAsState()

    var loading by remember(player) { mutableStateOf(false) }
    var loadJob by remember(player) { mutableStateOf<Job?>(null) }
    var failure by remember(player) { mutableStateOf<String?>(null) }
    var notice by remember(player) { mutableStateOf<String?>(null) }
    var retryRange by remember(player) { mutableStateOf<AudioRange.Segment?>(null) }

    // 时间轴由服务端给；**收口只认合法区间**（见 AudioRange 的说明）。
    val segment = AudioRange.clamp(startMs, endMs, playback.durationMs ?: durationMs)

    fun hasInternet(): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    DisposableEffect(player) {
        player.onError = {
            failure = if (hasInternet()) "原音播放失败，请重试" else ListeningNetworkUnavailable.MESSAGE
        }
        onDispose {
            loadJob?.cancel()
            player.onError = null
            player.release()
        }
    }

    /** 加载后才播。给了 [range] 就只播这一段（到点自己停）。 */
    fun ensureLoadedAndPlay(range: AudioRange.Segment? = null) {
        if (loading) return
        notice = null
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
            notice = "媒体音量为零，请调高音量后播放。"
            return
        }
        retryRange = range
        failure = null
        fun play() {
            if (range == null) player.play() else player.playRange(range.startMs, range.endMs)
        }
        val hasNetwork = hasInternet()
        if (hasNetwork && player.isLoaded) {
            play()
            return
        }
        loading = true
        loadJob = scope.launch {
            try {
                player.loadUrl(url, hasNetwork)
                play()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: ListeningNetworkUnavailable) {
                failure = ListeningNetworkUnavailable.MESSAGE
            } catch (cause: Exception) {
                Log.w("BankOriginalAudio", "原音加载失败", cause)
                failure = if (hasInternet()) "原音加载失败，请重试" else ListeningNetworkUnavailable.MESSAGE
            } finally {
                loading = false
            }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                enabled = !loading,
                onClick = {
                    when {
                        failure != null -> ensureLoadedAndPlay(retryRange)
                        player.isPlaying -> player.pause()
                        else -> ensureLoadedAndPlay()
                    }
                },
            ) {
                Text(
                    when {
                        loading -> "正在加载原音…"
                        failure != null -> "重试"
                        playback.isPlaying -> "暂停"
                        else -> "播放原音"
                    }
                )
            }
            Text("原版录音", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            // 有句级时间轴时给「重听本题」：从该题材料开头播到本题提问念完。
            // 没有时间轴（或区间不合法）时**不显示** —— 宁缺勿滥，见 AudioRange。
            segment?.let { seg ->
                OutlinedButton(
                    enabled = !loading,
                    onClick = { ensureLoadedAndPlay(seg) },
                ) { Text("重听本题") }
            }
        }
        Text(
            if (segment != null) {
                "真题听力原音（第三方整理件；来源与权利说明见题库说明）。" +
                    "「重听本题」从本题材料开头播到提问结束（%.1f–%.1f 秒）。"
                        .format(segment.startMs / 1000f, segment.endMs / 1000f)
            } else {
                "真题听力原音（第三方整理件；来源与权利说明见题库说明）。" +
                    "听力题的题干在录音里，卷面只印选项。整段一条，按题号顺序播放。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        (notice ?: failure)?.let {
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}
