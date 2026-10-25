package cn.tonglu.app.ui.english.wordreview

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.english.EnglishWord
import cn.tonglu.app.data.speech.Accent
import cn.tonglu.app.data.speech.SpeechPurpose
import cn.tonglu.app.ui.exampleTokens
import cn.tonglu.app.ui.canGradeCard

/** A reading surface with persistent recall controls; scoring stays in the host. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun WordReviewPage(word: EnglishWord, number: Int, total: Int, saving: Boolean,
    favorite: Boolean, accent: Accent, onPronounce: (Accent) -> Unit,
    onRead: (String, SpeechPurpose) -> Unit, onFavorite: () -> Unit,
    onAskAi: () -> Unit, onAnswer: (Boolean) -> Unit) {
    var revealed by rememberSaveable(word.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(number.toString() + " / " + total, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = onFavorite, enabled = !saving) {
                Icon(if (favorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                    if (favorite) "取消收藏" else "收藏单词", tint = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "单词更多操作") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("询问 AI 助记") }, onClick = { menu = false; onAskAi() }, enabled = !saving)
                }
            }
        }
        LinearProgressIndicator(progress = { (number - 1).toFloat() / total.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().height(2.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .5f)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Text(word.english, fontSize = if (word.english.length > 18) 36.sp else 44.sp,
                            lineHeight = 52.sp, fontWeight = FontWeight.Medium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(Accent.American to word.pronunciation, Accent.British to word.ukPronunciation).forEach { (kind, ipa) ->
                                if (!ipa.isNullOrBlank()) TextButton(onClick = { onPronounce(kind) }, enabled = !saving,
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 8.dp)) {
                                    Text(if (kind == Accent.American) "美" else "英", style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(10.dp))
                                    Text("/" + ipa + "/", fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f, fill = false))
                                    Spacer(Modifier.width(10.dp))
                                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, if (kind == Accent.American) "播放美式发音" else "播放英式发音", Modifier.size(20.dp))
                                }
                            }
                            if (word.pronunciation.isNullOrBlank() && word.ukPronunciation.isNullOrBlank()) {
                                TextButton(onClick = { onPronounce(accent) }, enabled = !saving,
                                    contentPadding = PaddingValues(0.dp)) {
                                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, null); Spacer(Modifier.width(8.dp)); Text("听发音")
                                }
                            }
                        }
                    }
                }
            }
            if (revealed) {
                item {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("释义", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(word.chinese, fontSize = 22.sp, lineHeight = 34.sp)
                    }
                }
                word.example?.takeIf { it.isNotBlank() }?.let { example -> item {
                    HorizontalDivider()
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("例句", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge)
                            IconButton(onClick = { onRead(example, SpeechPurpose.Sentence) }, enabled = !saving) {
                                Icon(Icons.AutoMirrored.Outlined.VolumeUp, "朗读例句")
                            }
                        }
                        ReviewExample(example, word.english, onRead)
                        word.exampleTranslation?.takeIf { it.isNotBlank() }?.let {
                            Text(it, lineHeight = 28.sp, fontSize = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } }
                word.phrases?.takeIf { it.isNotBlank() }?.let { phrases -> item {
                    HorizontalDivider()
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("常见搭配", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(phrases, fontSize = 17.sp, lineHeight = 28.sp)
                    }
                } }
            } else item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("先想一想，这个词是什么意思？", fontSize = 20.sp, lineHeight = 30.sp)
                    Text("回想后展开释义，也可以直接选择记忆情况。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Surface(shadowElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { revealed = !revealed }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    Text(if (revealed) "收起释义" else "展开释义与例句")
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    if (maxWidth < 280.dp || LocalDensity.current.fontScale > 1.5f) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onAnswer(false) }, enabled = canGradeCard(saving), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("忘记了") }
                        Button(onClick = { onAnswer(true) }, enabled = canGradeCard(saving), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (saving) "保存中…" else "认识") }
                    } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { onAnswer(false) }, enabled = canGradeCard(saving), modifier = Modifier.weight(1f).heightIn(min = 54.dp)) { Text("忘记了") }
                        Button(onClick = { onAnswer(true) }, enabled = canGradeCard(saving), modifier = Modifier.weight(1f).heightIn(min = 54.dp)) { Text(if (saving) "保存中…" else "认识") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewExample(text: String, target: String, read: (String, SpeechPurpose) -> Unit) {
    val highlight = MaterialTheme.colorScheme.primary
    val annotated = remember(text, target, highlight) {
        buildAnnotatedString {
            exampleTokens(text).forEach { token ->
                if (token.spoken) {
                    pushStringAnnotation("word", token.text)
                    if (token.text.equals(target, ignoreCase = true)) withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.SemiBold)) { append(token.text) }
                    else append(token.text)
                    pop()
                } else append(token.text)
            }
        }
    }
    ClickableText(annotated, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 32.sp,
        color = MaterialTheme.colorScheme.onSurface), onClick = { offset ->
        annotated.getStringAnnotations("word", offset, offset).firstOrNull()?.let { read(it.item, SpeechPurpose.Word) }
    })
}
