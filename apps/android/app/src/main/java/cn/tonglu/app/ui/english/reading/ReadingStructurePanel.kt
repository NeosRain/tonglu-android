package cn.tonglu.app.ui.english.reading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
    is ReadingAnalysisUi.Loading -> emptyList()
    is ReadingAnalysisUi.Failed -> listOf(ReadingAnalysisAction.Retry)
    else -> listOf(ReadingAnalysisAction.Analyze)
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
    var openComponent by remember(selection) { mutableStateOf<SentenceComponent?>(null) }
    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("句子结构", fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("收起") }
            }
            // 原句：覆盖到的词加下划线与成分色；未覆盖的保持原样。
            val scheme = MaterialTheme.colorScheme
            val analysis = (analysisState as? ReadingAnalysisUi.Ready)?.analysis
            if (analysis != null) {
                val annotated = buildAnnotatedString {
                    append(selection.sentence.text)
                    analysis.components.forEach { component ->
                        val covered = selection.tokens.filter { it.id in component.tokenIds }
                        if (covered.isNotEmpty()) {
                            addStyle(SpanStyle(color = readingRoleColor(component.role, scheme),
                                textDecoration = TextDecoration.Underline),
                                covered.first().start, covered.last().end)
                        }
                    }
                }
                StructureSentenceText(annotated, selection, onPickWord)
                Text("译文 · AI 生成，可能有误", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                Text(analysis.translation, fontSize = 15.sp, lineHeight = 23.sp,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 2.dp))
                analysis.uncertainty?.takeIf { it.isNotBlank() }?.let {
                    Surface(shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier
                            .fillMaxWidth().padding(top = 8.dp)) {
                        Text("模型说明歧义：$it", fontSize = 12.sp, lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp))
                    }
                }
                // 成分标签：颜色 + 文字，**横向流动**排布（与 ArticleReader 的 FlowRow 一致）——
                // 一个成分一行会把面板撑到十几屏，用户要滚很久才看得见下面的总结。
                FlowRow(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    analysis.components.forEach { component ->
                        val color = readingRoleColor(component.role, scheme)
                        FilterChip(selected = openComponent === component,
                            onClick = { openComponent = if (openComponent === component) null else component },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = color, selectedLabelColor = color, selectedContainerColor = color.copy(alpha = 0.12f)),
                            label = { Text(component.role) })
                    }
                }
                // 展开的解释卡统一显示在标签行下方（整宽，不挤压标签流）：点哪个标签就显示哪一段。
                openComponent?.let { component ->
                    Surface(shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(componentOriginalText(component, selection.tokens, selection.sentence.text),
                                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(component.explanation, fontSize = 13.sp, lineHeight = 19.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                Text(analysis.summary, fontSize = 13.sp, lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            } else {
                StructureSentenceText(AnnotatedString(selection.sentence.text), selection, onPickWord)
                when (analysisState) {
                    is ReadingAnalysisUi.Loading -> Column(Modifier.padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().heightIn(min = 2.dp))
                        Text("正在分析这一句（AI 生成，可能有误）…", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is ReadingAnalysisUi.Failed -> Text("这次分析没有成功：${analysisState.reason}",
                        fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp))
                    is ReadingAnalysisUi.NeedsConfig -> Text(
                        "句子结构分析要用你自己的 AI 接口。普通阅读和查词不受影响。",
                        fontSize = 13.sp, lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                    else -> Text("点「分析这句」查看句子成分。分析由 AI 生成，可能有误。",
                        fontSize = 13.sp, lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 渲染完全由 [readingAnalysisActions] 决定 —— 单测钉住那个纯函数，这里不许再自己分支。
                readingAnalysisActions(analysisState).forEach { action ->
                    when (action) {
                        ReadingAnalysisAction.Settings -> TextButton(onClick = onOpenAiSettings) { Text("去配置 AI") }
                        ReadingAnalysisAction.Analyze -> TextButton(onClick = onRetryAnalysis) { Text("分析这句") }
                        ReadingAnalysisAction.Retry -> TextButton(onClick = onRetryAnalysis) { Text("重试") }
                    }
                }
                TextButton(onClick = onAsk) { Text("问 AI") }
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
