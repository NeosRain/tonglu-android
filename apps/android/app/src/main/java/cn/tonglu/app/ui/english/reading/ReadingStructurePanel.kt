package cn.tonglu.app.ui.english.reading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.tonglu.app.data.speech.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.ui.readingselection.characterAt
import cn.tonglu.app.data.english.reading.ReadingToken
import cn.tonglu.app.data.english.reading.SentenceComponent
import cn.tonglu.app.data.english.reading.SentenceAnalysis

/**
 * 结构面板：上方原句，下面分块下划线与主/谓/宾/定/状/补/从句标签。
 * 只渲染 [SentenceAnalysis]（主会话校验通过的），坏范围/缺字段/模型失败显示原因。
 * 第一版不画跨行弧线；标签永远是「颜色 + 文字」，不只靠颜色识别。
 */

/** 每个词元被哪些成分覆盖（按成分给出顺序）。纯函数，单测钉住嵌套与未覆盖词元。 */
data class ReadingComponentSpan(val token: ReadingToken, val roles: List<String>)

fun readingComponentSpans(tokens: List<ReadingToken>, components: List<SentenceComponent>): List<ReadingComponentSpan> =
    tokens.map { token -> ReadingComponentSpan(token, components.filter { token.id in it.tokenIds }.map { it.role }) }

/** 成分对应的原文：tokenIds 已校验为连续升序，直接按原文偏移截取，保留标点与大小写原貌。 */
fun componentOriginalText(component: SentenceComponent, tokens: List<ReadingToken>, sentenceText: String): String {
    val covered = tokens.filter { it.id in component.tokenIds }
    if (covered.isEmpty()) return ""
    return sentenceText.substring(covered.first().start, covered.last().end)
}

/** 角色颜色全部来自语义槽位（标签同时有文字）；未知角色退到 onSurfaceVariant。 */
internal fun readingRoleColor(role: String, scheme: ColorScheme): Color {
    // 角色用独立色相；不能依赖常常同为紫色的 primary/secondary/tertiary。
    val dark = scheme.surface.luminance() < 0.5f
    return when (role) {
        "主语" -> if (dark) Color(0xFF8BB8FF) else Color(0xFF205BC3)
        "谓语" -> if (dark) Color(0xFFF2A2D1) else Color(0xFF9D287A)
        "宾语" -> if (dark) Color(0xFF75D4AF) else Color(0xFF127459)
        "定语" -> if (dark) Color(0xFFEBC374) else Color(0xFF805000)
        "状语" -> if (dark) Color(0xFFC2ADFF) else Color(0xFF6639B6)
        "补语" -> if (dark) Color(0xFFFFAD93) else Color(0xFFA43620)
        "从句" -> if (dark) Color(0xFF7CD8ED) else Color(0xFF006A80)
        else -> scheme.onSurfaceVariant
    }
}

/** 结构面板底部的动作。抽成枚举是为了让「这一行该给哪几个按钮」能被单测钉住。 */
internal enum class ReadingAnalysisAction { Analyze, Retry, Settings }

/**
 * 结构面板底部动作行的内容。**纯函数**，单测钉住「NeedsConfig 也必须给出重新分析」。
 *
 * 为什么要有它：这行按钮原来按 `analysisState` 三分支写死，`NeedsConfig` 只给「去配置 AI」。
 * 而 `analysis` 只在**换句**时才复位（见 `ReadingDeepDiveState.openStructure`），
 * 所以同一句会**永远**停在 `NeedsConfig` —— 用户照着提示把 AI 配好、回到面板，
 * 反而没有出口把这一句发出去（BUGS S75；当时唯一绕法是收起浮层重开，或点别的段再点回来）。
 *
 * 抽成列表之后，「配好之后必须还能分析」从「靠谁记得」变成一条**会红的单测**。
 * 顺序即界面顺序：先「去配置 AI」再「分析这句」，因为多数人第一次落到这里时确实还没配。
 */
internal fun readingAnalysisActions(state: ReadingAnalysisUi): List<ReadingAnalysisAction> = when (state) {
    // 未配置：两个都给。用户配完回来点一下「分析这句」就能发，不必收起浮层重开。
    is ReadingAnalysisUi.NeedsConfig -> listOf(ReadingAnalysisAction.Settings, ReadingAnalysisAction.Analyze)
    // 在途：什么都不给（此时重复发起由状态机自己挡掉，界面也不该给一个点了没用的按钮）。
    is ReadingAnalysisUi.Loading, is ReadingAnalysisUi.Ready -> emptyList()
    is ReadingAnalysisUi.Failed -> listOf(ReadingAnalysisAction.Retry)
    else -> listOf(ReadingAnalysisAction.Analyze)
}

/** Only the selected component is highlighted; nested clauses must not paint the entire sentence. */
internal fun sentenceComponentHighlight(selection: ReaderSelection, component: SentenceComponent?, color: Color): AnnotatedString =
    buildAnnotatedString {
        append(selection.sentence.text)
        component?.let { part ->
            val covered = selection.tokens.filter { it.id in part.tokenIds }
            if (covered.isNotEmpty()) addStyle(SpanStyle(color = color, textDecoration = TextDecoration.Underline),
                covered.first().start, covered.last().end)
        }
    }

@Composable
internal fun ReadingStructurePanel(
    selection: ReaderSelection,
    analysisState: ReadingAnalysisUi,
    onRetryAnalysis: () -> Unit,
    onAsk: () -> Unit,
    onOpenAiSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onPickWord: ((Int) -> Unit)? = null,
) {
    var details by rememberSaveable(selection.sentence.hash) { mutableStateOf(false) }
    var openComponent by remember(selection) { mutableStateOf<SentenceComponent?>(null) }
    var speechProblem by remember(selection) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val speech = remember(context, selection) {
        SpeechService(context) { speechProblem = "原句朗读失败，请检查本机英语语音数据或语音引擎" }
    }
    DisposableEffect(speech, lifecycle) {
        speech.start()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) speech.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); speech.shutdown() }
    }
    fun speakSentence() {
        speechProblem = null
        when (val result = speech.speak(SpeechRequest(text = selection.sentence.text,
            voiceId = SpeechCatalog.systemVoice().id, purpose = SpeechPurpose.Sentence))) {
            SpeechResult.Spoken -> Unit
            is SpeechResult.Failed -> speechProblem = result.hint
            is SpeechResult.Unavailable -> speechProblem = result.reason
            is SpeechResult.Degraded -> speechProblem = result.reason
        }
    }
    val analysis = (analysisState as? ReadingAnalysisUi.Ready)?.analysis
    Surface(modifier = modifier.fillMaxWidth().heightIn(max = 640.dp),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("句子精读", fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("收起") }
            }
            if (analysisState is ReadingAnalysisUi.NeedsConfig) Text("先配置 AI 接口；朗读和查词仍可使用",
                fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp))
            // The header and actions stay reachable while a long sentence/explanation scrolls.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                val color = openComponent?.let { readingRoleColor(it.role, MaterialTheme.colorScheme) }
                    ?: MaterialTheme.colorScheme.onSurface
                StructureSentenceText(sentenceComponentHighlight(selection, if (details) openComponent else null, color), selection, onPickWord)
                if (analysis != null) {
                    Text("译文 · AI 生成，可能有误", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                    Text(analysis.translation, fontSize = 16.sp, lineHeight = 25.sp,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 4.dp))
                    Text("理解这句", fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 16.dp))
                    Text(analysis.summary, fontSize = 14.sp, lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    analysis.uncertainty?.takeIf(String::isNotBlank)?.let {
                        Text("需要留意：$it", fontSize = 13.sp, lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                    TextButton(onClick = { details = !details; if (!details) openComponent = null }) {
                        Text(if (details) "收起语法细节" else "展开语法细节（${analysis.components.size}）")
                    }
                    if (details) {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            analysis.components.forEach { component ->
                                val roleColor = readingRoleColor(component.role, MaterialTheme.colorScheme)
                                FilterChip(selected = openComponent === component,
                                    onClick = { openComponent = if (openComponent === component) null else component },
                                    colors = FilterChipDefaults.filterChipColors(labelColor = roleColor,
                                        selectedLabelColor = roleColor, selectedContainerColor = roleColor.copy(alpha = 0.12f)),
                                    label = { Text(component.role) })
                            }
                        }
                        openComponent?.let { component ->
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(componentOriginalText(component, selection.tokens, selection.sentence.text),
                                        fontSize = 15.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(component.explanation, fontSize = 14.sp, lineHeight = 22.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                                }
                            }
                        }
                    }
                } else {
                    when (analysisState) {
                        is ReadingAnalysisUi.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                        is ReadingAnalysisUi.Failed -> Text(analysisState.reason, fontSize = 13.sp, lineHeight = 20.sp,
                            color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
                        is ReadingAnalysisUi.NeedsConfig -> Text("句子解释需要你自己的 AI 接口。原句朗读和查词仍可使用。",
                            fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp))
                        else -> Text("先读原句，需要帮助时点「分析这句」查看译文和解释。不会自动发起 AI 请求。",
                            fontSize = 13.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp))
                    }
                }
                speechProblem?.let { Text(it, fontSize = 13.sp, lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { speakSentence() }) { Text("朗读原句") }
                TextButton(onClick = { speech.stop() }) { Text("停止") }
                readingAnalysisActions(analysisState).forEach { action ->
                    when (action) {
                        ReadingAnalysisAction.Settings -> TextButton(onClick = onOpenAiSettings) { Text("去配置 AI") }
                        ReadingAnalysisAction.Analyze -> TextButton(onClick = onRetryAnalysis) { Text("分析这句") }
                        ReadingAnalysisAction.Retry -> TextButton(onClick = onRetryAnalysis) { Text("重试") }
                    }
                }
                TextButton(onClick = onAsk) { Text("问 AI") }
                if (speechProblem != null) TextButton(onClick = { speech.openSystemSettings() }) { Text("语音引擎设置") }
            }
        }
    }
}

/** 原句仍是一段文字；按真实字形偏移定位词元，重复词各自对应，不拆成按钮行。 */
@Composable
private fun StructureSentenceText(text: AnnotatedString, selection: ReaderSelection, onPickWord: ((Int) -> Unit)?) {
    var layout by remember(selection, text) { mutableStateOf<TextLayoutResult?>(null) }
    val latestPick by androidx.compose.runtime.rememberUpdatedState(onPickWord)
    Text(text, fontSize = 17.sp, lineHeight = 26.sp, color = MaterialTheme.colorScheme.onSurface,
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().pointerInput(selection, onPickWord != null) {
            if (onPickWord != null) detectTapGestures { position ->
                val measured = layout ?: return@detectTapGestures
                // 与正文同一套落点判定（BUGS S118）：不能直接拿 getOffsetForPosition 的结果当守卫。
                val offset = measured.characterAt(position, text.text.length) ?: return@detectTapGestures
                ReaderSelection.tokenAt(selection.tokens, offset)?.let { latestPick?.invoke(it.id) }
            }
        })
    if (onPickWord != null) Text("点句中的单词，查看原型、释义与发音", fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}
