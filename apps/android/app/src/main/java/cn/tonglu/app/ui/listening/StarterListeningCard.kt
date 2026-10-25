package cn.tonglu.app.ui.listening

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.speech.*

/** 本项目原创入门练习，CC0-1.0；不是四六级真题，声音由设备系统TTS合成。 */
private val starterSentences = listOf(
    "Could you tell me where the library is?" to "你能告诉我图书馆在哪里吗？",
    "I would like to join the study group this afternoon." to "我想参加今天下午的学习小组。",
    "We can learn from each other and make progress together." to "我们可以互相学习，一起进步。",
)

@Composable
internal fun StarterListeningCard() {
    val context = LocalContext.current
    var problem by remember { mutableStateOf<String?>(null) }
    val speech = remember(context) { SpeechService(context) { problem = "系统朗读不可用：$it，请在英语的声音设置中检查引擎。" } }
    var accent by remember { mutableStateOf(Accent.American) }
    var revealed by remember { mutableStateOf(emptySet<Int>()) }
    DisposableEffect(speech) {
        speech.start()
        onDispose { speech.stop(); speech.shutdown() }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("先听三句，再跟着说", style = MaterialTheme.typography.titleMedium)
            Text("原创入门练习 · 系统合成朗读 · 无需下载词书或配置 AI",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Accent.American, Accent.British).forEach { option ->
                    FilterChip(selected = accent == option, onClick = { accent = option }, label = { Text(option.label) })
                }
            }
            starterSentences.forEachIndexed { index, sentence ->
                Text(sentence.first, style = MaterialTheme.typography.bodyLarge)
                if (index in revealed) Text(sentence.second, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        problem = null
                        when (val result = speech.speak(SpeechRequest(sentence.first,
                            locale = if (accent == Accent.British) "en-GB" else "en-US",
                            accent = accent, purpose = SpeechPurpose.General, utteranceId = "starter-$index"))) {
                            SpeechResult.Spoken -> Unit
                            is SpeechResult.Degraded -> problem = result.reason
                            is SpeechResult.Unavailable -> problem = result.reason
                            is SpeechResult.Failed -> problem = "朗读失败：${result.problem}，请检查系统英语语音数据。"
                        }
                    }) { Text("听一句") }
                    TextButton(onClick = { revealed = if (index in revealed) revealed - index else revealed + index }) {
                        Text(if (index in revealed) "隐藏释义" else "看中文")
                    }
                }
            }
            TextButton(onClick = { speech.stop() }) { Text("停止朗读") }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("先听，再照着读；这里不进行语音评分，也不把点击播放算作做题成绩。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
