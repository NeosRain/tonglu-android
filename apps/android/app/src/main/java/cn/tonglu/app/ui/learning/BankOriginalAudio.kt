package cn.tonglu.app.ui.learning

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import cn.tonglu.app.data.listening.ListeningPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 * 1. **点「播放原音」才加载**（`Loading` → `Ready`，加载在 IO 线程）；
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
    val player = remember(questionKey) { ListeningPlayer() }

    var loading by remember(questionKey) { mutableStateOf(false) }
    var ready by remember(questionKey) { mutableStateOf(false) }
    var playing by remember(questionKey) { mutableStateOf(false) }
    var notice by remember(questionKey) { mutableStateOf<String?>(null) }

    // 时间轴由服务端给；**收口只认合法区间**（见 AudioRange 的说明）。
    val segment = remember(questionKey, startMs, endMs, durationMs) {
        AudioRange.clamp(startMs, endMs, durationMs)
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    /** 加载后才播。给了 [range] 就只播这一段（到点自己停）。 */
    fun ensureLoadedAndPlay(range: AudioRange.Segment? = null) {
        scope.launch {
            loading = true
            notice = null
            val failure = runCatching { withContext(Dispatchers.IO) { player.loadUrl(url) } }
                .exceptionOrNull()
            loading = false
            if (failure != null) {
                ready = false
                notice = "原音加载失败：${failure.message ?: failure.javaClass.simpleName}"
                return@launch
            }
            ready = true
            player.onCompleted = { playing = false }
            if (range != null) player.playRange(range.startMs, range.endMs) else player.play()
            playing = true
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                enabled = !loading,
                onClick = {
                    notice = null
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
                        // 音量 0 时播了也是听不见 —— 明说，并且**省掉这次加载**
                        notice = "媒体音量为零，请调高音量后播放。"
                        return@OutlinedButton
                    }
                    if (playing) {
                        player.pause()
                        playing = false
                    } else if (ready) {
                        player.play()
                        playing = true
                    } else {
                        ensureLoadedAndPlay()
                    }
                },
            ) {
                Text(
                    when {
                        loading -> "正在加载原音…"
                        playing -> "暂停"
                        ready -> "播放原音"
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
                    onClick = {
                        notice = null
                        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
                            notice = "媒体音量为零，请调高音量后播放。"
                            return@OutlinedButton
                        }
                        if (ready) {
                            player.playRange(seg.startMs, seg.endMs)
                            playing = true
                        } else {
                            ensureLoadedAndPlay(seg)
                        }
                    },
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
        notice?.let {
            Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error)
        }
    }
}
