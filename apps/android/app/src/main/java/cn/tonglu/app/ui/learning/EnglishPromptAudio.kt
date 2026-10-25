package cn.tonglu.app.ui.learning

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.SessionStore
import cn.tonglu.app.data.speechHint
import cn.tonglu.app.data.SpeechProblem
import cn.tonglu.app.data.speech.*

/** 自编听力使用合成朗读，不冒充考试原音；换题和退层时释放声音。 */
@Composable
internal fun EnglishPromptAudio(text: String, questionKey: String) {
    val context = LocalContext.current
    val owner = remember { SessionStore(context.applicationContext).ownerKey() }
    val preference = remember(owner) { SpeechPreference(context) }
    var notice by remember(questionKey) { mutableStateOf<String?>(null) }
    var failed by remember(questionKey) { mutableStateOf(false) }
    val service = remember(questionKey) {
        SpeechService(context) { problem ->
            failed = true
            notice = when (problem) {
                SpeechProblem.NO_ENGINE -> "系统没有可用语音引擎，请到英语首页的声音设置检查。"
                SpeechProblem.NO_ENGLISH_VOICE -> "系统缺少英语语音数据，请在声音设置安装。"
                SpeechProblem.NO_BRITISH_VOICE -> "系统缺少英式语音，不会用美式替代。"
                SpeechProblem.MUTED -> "媒体音量为零，请调高音量。"
                SpeechProblem.INIT_FAILED -> "语音引擎初始化失败，请稍后重试。"
                SpeechProblem.PLAY_FAILED -> "朗读失败，请检查声音设置。"
            }
        }
    }
    DisposableEffect(service) {
        service.start()
        onDispose { service.stop(); service.shutdown() }
    }
    fun play(accent: Accent) {
        failed = false
        notice = null
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
            failed = true
            notice = "媒体音量为零，请调高音量后播放。"
            return
        }
        // 系统缺英语数据时优先使用已安装的美式离线声音，不自动下载模型。
        val fallback = if (accent == Accent.American && !service.systemReady) {
            SpeechCatalog.all(false, context).firstOrNull {
                it.source == SoundSource.BuiltinVoice && it.accent == Accent.American && it.available && it.installed
            }
        } else null
        val result = service.speak(SpeechRequest(
            text = text, accent = accent,
            locale = if (accent == Accent.British) "en-GB" else "en-US",
            voiceId = fallback?.id ?: preference.voiceId(owner), purpose = SpeechPurpose.General,
            utteranceId = "$questionKey-${accent.name}",
        ))
        when (result) {
            SpeechResult.Spoken -> if (!failed) notice = if (fallback != null) "系统英语不可用，本次使用美式离线声音 · ${fallback.name}" else "正在合成朗读 · ${accent.label}"
            is SpeechResult.Degraded -> notice = result.reason
            is SpeechResult.Unavailable -> { failed = true; notice = result.reason }
            is SpeechResult.Failed -> { failed = true; notice = speechHint(result.problem).first }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Text("自编听力 · 合成语音，非考试原音", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { play(Accent.American) }) { Text("美音播放") }
            TextButton(onClick = { play(Accent.British) }) { Text("英音播放") }
            TextButton(onClick = { service.stop(); notice = "已停止"; failed = false }) { Text("停止") }
        }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

