package cn.tonglu.app.ui.readingselection

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import cn.tonglu.app.ui.ReaderParagraphSelection
import cn.tonglu.app.ui.ReaderTapAction
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import org.intellij.markdown.ast.ASTNode

/** 两种模式共用上游 Markdown 行内排版；精读只增加命中和背景，不把源码软换行画出来。 */
@Composable
internal fun ReaderInteractiveParagraph(
    original: String, node: ASTNode, style: TextStyle, active: Boolean,
    articleId: String, revision: String, title: String, highlight: Color,
    onSelection: (ReaderParagraphSelection) -> Unit,
) {
    val settings = annotatorSettings()
    val rich = buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        buildMarkdownAnnotatedString(original, node, settings)
        pop()
    }
    // 精读时点链接文字用于查词；退出精读后 LinkAnnotation 恢复正常导航。
    val text = if (active) AnnotatedString(rich.text, rich.spanStyles, rich.paragraphStyles) else rich
    var layout by remember(node.startOffset, rich.text, style) { mutableStateOf<TextLayoutResult?>(null) }
    var selected by remember(node.startOffset, rich.text) { mutableStateOf<IntRange?>(null) }
    val latestSelection by rememberUpdatedState(onSelection)
    val gestures = if (!active) Modifier else Modifier.pointerInput(rich.text) {
        fun select(position: androidx.compose.ui.geometry.Offset, word: Boolean) {
            val measured = layout ?: return
            // 落点 → 真正被按住的字符；判据与理由见 `characterAt`（BUGS S118：直接拿
            // getOffsetForPosition 的结果当守卫，会把落在字间分界附近的正常点词静默吞掉）。
            val offset = measured.characterAt(position, text.text.length) ?: return
            // 冻结的是用户看到的正文快照，绝不把 Markdown 链接地址当成句子里的单词。
            val hit = readingTextHit(text.text, 0, text.length, offset, word, plainText = true) ?: return
            selected = hit.start until hit.end
            latestSelection(ReaderParagraphSelection(articleId,
                "$revision:visible-v1:${node.startOffset}", text.text, hit.start, hit.end,
                title, if (word) ReaderTapAction.Word else ReaderTapAction.Structure, hit.wordOffset))
        }
        detectTapGestures(onTap = { select(it, true) }, onLongPress = { select(it, false) })
    }
    val modifier = Modifier.fillMaxWidth().then(gestures).drawBehind {
        if (active) selected?.let { span ->
            layout?.let { measured -> drawPath(measured.getPathForRange(span.first, span.last + 1), highlight) }
        }
    }
    if (active) DisableSelection { BasicText(text, style = style, modifier = modifier, onTextLayout = { layout = it }) }
    else BasicText(text, style = style, modifier = modifier, onTextLayout = { layout = it })
}
