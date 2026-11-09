@file:OptIn(com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi::class)
package cn.tonglu.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor

internal data class NoteDocument(val text: String, val html: String? = null, val markdown: String? = null)

/** The untouched original remains byte-exact, including indentation and trailing blank lines. */
internal class NoteEditorSession(private val original: NoteDocument) {
    val state = RichTextState(historyLimit = 60).apply {
        if (original.html.isNullOrBlank()) setText(original.text) else setHtml(original.html)
    }
    private val loadedHtml = state.toHtml()
    fun document(): NoteDocument {
        val html = state.toHtml()
        return if (html == loadedHtml) original else NoteDocument(state.toText(), html, state.toMarkdown())
    }
}

@Composable
internal fun NoteTextEditor(session: NoteEditorSession, modifier: Modifier = Modifier) {
    BasicRichTextEditor(state = session.state, modifier = modifier,
        textStyle = TextStyle(color = AppColors.TextPrimary, fontSize = 20.sp, lineHeight = 32.sp),
        cursorBrush = SolidColor(AppColors.Accent),
        decorationBox = { field -> Box {
            if (session.state.toText().isEmpty()) Text("开始写这篇笔记…", color = AppColors.TextMuted, fontSize = 20.sp)
            field()
        } })
}

@Composable
internal fun NoteTextView(session: NoteEditorSession, modifier: Modifier = Modifier) {
    BasicRichTextEditor(state = session.state, modifier = modifier, readOnly = true,
        textStyle = TextStyle(color = AppColors.TextPrimary, fontSize = 20.sp, lineHeight = 32.sp),
        cursorBrush = SolidColor(AppColors.Accent))
}

@Composable
internal fun NoteFormattingToolbar(session: NoteEditorSession, modifier: Modifier = Modifier, focusEditor: () -> Unit) {
    val state = session.state
    var styleMenu by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(enabled = state.history.canUndo, onClick = { state.history.undo(); focusEditor() }) {
            Icon(Icons.AutoMirrored.Outlined.Undo, "撤销", tint = if (state.history.canUndo) AppColors.TextPrimary else AppColors.TextMuted)
        }
        IconButton(enabled = state.history.canRedo, onClick = { state.history.redo(); focusEditor() }) {
            Icon(Icons.AutoMirrored.Outlined.Redo, "重做", tint = if (state.history.canRedo) AppColors.TextPrimary else AppColors.TextMuted)
        }
        Box {
            TextButton(onClick = { styleMenu = true }) { Icon(Icons.Outlined.TextFields, null); Spacer(Modifier.width(4.dp)); Text("文字", color = AppColors.TextPrimary) }
            DropdownMenu(styleMenu, onDismissRequest = { styleMenu = false }, containerColor = AppColors.SurfaceOpaque) {
                val span = state.currentSpanStyle
                fun apply(action: () -> Unit) { action(); styleMenu = false; focusEditor() }
                @Composable fun item(label: String, selected: Boolean, action: () -> Unit) {
                    DropdownMenuItem(text = { Text(label) }, trailingIcon = { if (selected) Icon(Icons.Outlined.Check, null, tint = AppColors.Accent) }, onClick = { apply(action) })
                }
                item("加粗", span.fontWeight == FontWeight.Bold) { state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) }
                item("斜体", span.fontStyle == FontStyle.Italic) { state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic)) }
                item("下划线", span.textDecoration?.contains(TextDecoration.Underline) == true) { state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline)) }
                item("大标题", state.currentHeadingStyle == HeadingStyle.H1) { state.setHeadingStyle(HeadingStyle.H1) }
                item("小标题", state.currentHeadingStyle == HeadingStyle.H2) { state.setHeadingStyle(HeadingStyle.H2) }
                item("正文", state.currentHeadingStyle == HeadingStyle.Normal) { state.setHeadingStyle(HeadingStyle.Normal) }
            }
        }
        Box {
            TextButton(onClick = { listMenu = true }) { Icon(Icons.Outlined.FormatListBulleted, null); Spacer(Modifier.width(4.dp)); Text("列表", color = AppColors.TextPrimary) }
            DropdownMenu(listMenu, onDismissRequest = { listMenu = false }, containerColor = AppColors.SurfaceOpaque) {
                DropdownMenuItem(text = { Text("项目列表") }, trailingIcon = { if (state.isUnorderedList) Icon(Icons.Outlined.Check, null) }, onClick = { state.toggleUnorderedList(); listMenu = false; focusEditor() })
                DropdownMenuItem(text = { Text("编号列表") }, trailingIcon = { if (state.isOrderedList) Icon(Icons.Outlined.Check, null) }, onClick = { state.toggleOrderedList(); listMenu = false; focusEditor() })
                DropdownMenuItem(text = { Text("取消列表") }, onClick = {
                    if (state.isUnorderedList) state.toggleUnorderedList() else if (state.isOrderedList) state.toggleOrderedList()
                    listMenu = false; focusEditor()
                }, enabled = state.isList)
            }
        }
    }
}
