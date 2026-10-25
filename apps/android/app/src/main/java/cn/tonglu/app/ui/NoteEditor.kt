package cn.tonglu.app.ui

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Publish
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import cn.tonglu.app.ui.glass.GlassActionPanel

/** 笔记 / 草稿统一用的强调色（紫色）。和书架、阅读器里的那一抹紫保持一致。 */
internal val NoteMarkdownAccent = Color(0xFF7C3AED)

internal enum class NotePanel { None, Emoji, Formula }

/**
 * 笔记编辑器。阅读器里「长按段落划线」、书架里点开一条笔记、以及「发布到讨论区」
 * 都走这一个弹窗。
 *
 * 从 AlertDialog 换成全屏 Dialog 的原因：要放工具条 + 预览 + emoji/公式面板，
 * 弹窗版挤不下，键盘一弹更是只剩两行。
 *
 * @param onPublish 传 null 就不显示「发布」按钮（只在学习页的笔记入口给）。
 */
@Composable
internal fun NoteEditorDialog(
    title: String,
    quote: String,
    initialBody: String,
    onSave: (String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
    onPublish: ((String) -> Unit)? = null,
) {
    var value by remember { mutableStateOf(TextFieldValue(initialBody, TextRange(initialBody.length))) }
    var preview by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(NotePanel.None) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val accent = NoteMarkdownAccent
    val dirty = value.text != initialBody
    val editorBackdrop = rememberLayerBackdrop()

    // 这一层原本和发布器一样是硬切出来、硬切没的（窗口动画关成 0 且没在 Compose 里补）。
    val motion = rememberOverlayMotion(
        onClose = onDismiss,
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )

    fun close() {
        if (dirty) confirmDiscard = true else motion.requestClose()
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        androidx.compose.runtime.SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setWindowAnimations(0)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().overlayMotion(motion, fade = false, onBack = { close() })) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                EditorTopBar(
                    title = title,
                    preview = preview,
                    canPublish = onPublish != null && value.text.isNotBlank(),
                    canDelete = onDelete != null,
                    onBack = { close() },
                    onTogglePreview = {
                        if (!preview) panel = NotePanel.None
                        preview = !preview
                    },
                    onPublish = { onPublish?.invoke(value.text.trim()) },
                    onDelete = { confirmDelete = true },
                    onSave = { onSave(value.text.trim()) },
                )

                if (quote.isNotBlank() && !preview) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = accent.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Text(
                            quote,
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp),
                        )
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(editorBackdrop)) {
                    if (preview) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                                MarkdownBody(
                                    markdown = value.text,
                                    fontSizeSp = 15,
                                    accentColor = accent,
                                )
                            }
                        }
                    } else {
                        BodyField(value = value, accent = accent, focus = focus, onChange = { value = it }, bottomPadding = if (panel == NotePanel.None) 72.dp else 336.dp)
                    }
                    }

                if (!preview) {
                    Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    when (panel) {
                        NotePanel.Emoji -> EmojiPanel(
                            onPick = { emoji ->
                                rememberEmoji(emoji)
                                value = value.insertText(emoji)
                            },
                            onClose = { panel = NotePanel.None },
                        )
                        NotePanel.Formula -> FormulaPanel(
                            onInsert = { tex, block ->
                                value = value.insertFormula(tex, block)
                            },
                            onClose = { panel = NotePanel.None },
                        )
                        NotePanel.None -> Unit
                    }
                    GlassActionPanel(editorBackdrop) {
                    EditorToolbar(
                        accent = accent,
                        onWrap = { pre, suf, hint -> value = value.wrapInline(pre, suf, hint) },
                        onLinePrefix = { marker -> value = value.toggleLinePrefix(marker) },
                        onCodeBlock = { value = value.insertBlock("```\n", "\n```", "code") },
                        onLink = { value = value.wrapInline("[", "](https://)", "链接文字") },
                        onEmoji = { panel = if (panel == NotePanel.Emoji) NotePanel.None else NotePanel.Emoji },
                        onFormula = { panel = if (panel == NotePanel.Formula) NotePanel.None else NotePanel.Formula },
                    )
                    }
                    }
                }
                }
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条笔记？", fontSize = 17.sp) },
            text = { Text("删掉之后其他设备也会同步消失。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete?.invoke() }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun EditorTopBar(
    title: String,
    preview: Boolean,
    canPublish: Boolean,
    canDelete: Boolean,
    onBack: () -> Unit,
    onTogglePreview: () -> Unit,
    onPublish: () -> Unit,
    onDelete: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "放弃并返回") }
        Column(Modifier.weight(1f)) {
            Text("笔记 · Markdown", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(
                title,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canDelete) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.DeleteOutline, "删除这条笔记",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onTogglePreview) {
            Icon(
                if (preview) Icons.Outlined.Edit else Icons.Outlined.Visibility,
                if (preview) "继续编辑" else "预览渲染效果",
            )
        }
        if (canPublish) {
            IconButton(onClick = onPublish) { Icon(Icons.Outlined.Publish, "发布到讨论区") }
        }
        TextButton(onClick = onSave) { Icon(Icons.Outlined.Check, null, Modifier.size(17.dp)); Spacer(Modifier.width(3.dp)); Text("保存") }
    }
}

@Composable
internal fun BodyField(
    value: TextFieldValue,
    accent: Color,
    focus: FocusRequester,
    onChange: (TextFieldValue) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = 12.dp,
) {
    val scroll = rememberScrollState()
    // 光标往下走的时候把编辑区带过去，不然打字打到屏幕外
    LaunchedEffect(value.selection, value.text.length) {
        runCatching { scroll.animateScrollTo(scroll.maxValue) }
    }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = bottomPadding)
            .focusRequester(focus),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 14.sp,
            lineHeight = 23.sp,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(accent),
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) {
                    Text(
                        "写点想法…\n\n支持 Markdown：**加粗**、- 列表、> 引用、`代码`\n点下面的 ƒ 插公式，😀 插表情。",
                        fontSize = 14.sp,
                        lineHeight = 23.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
                inner()
            }
        },
    )
}

@Composable
internal fun EditorToolbar(
    accent: Color,
    onWrap: (String, String, String) -> Unit,
    onLinePrefix: (String) -> Unit,
    onCodeBlock: () -> Unit,
    onLink: () -> Unit,
    onEmoji: () -> Unit,
    onFormula: () -> Unit,
    onImage: (() -> Unit)? = null,
) {
    Surface(color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(Icons.Outlined.FormatBold, "加粗") { onWrap("**", "**", "加粗") }
            ToolButton(Icons.Outlined.FormatItalic, "斜体") { onWrap("*", "*", "斜体") }
            ToolButton(Icons.Outlined.FormatStrikethrough, "删除线") { onWrap("~~", "~~", "删除线") }
            ToolButton(Icons.Outlined.Title, "标题") { onLinePrefix("## ") }
            ToolButton(Icons.Outlined.FormatQuote, "引用") { onLinePrefix("> ") }
            ToolButton(Icons.AutoMirrored.Outlined.FormatListBulleted, "无序列表") { onLinePrefix("- ") }
            ToolButton(Icons.Outlined.FormatListNumbered, "有序列表") { onLinePrefix("1. ") }
            ToolButton(Icons.Outlined.Code, "代码块") { onCodeBlock() }
            ToolButton(Icons.Outlined.Link, "链接") { onLink() }
            if (onImage != null) ToolButton(Icons.Outlined.Link, "插入图片链接") { onImage() }
            ToolButton(Icons.Outlined.Functions, "插入公式", tint = accent) { onFormula() }
            ToolButton(Icons.Outlined.EmojiEmotions, "插入表情", tint = accent) { onEmoji() }
        }
    }
}

@Composable
private fun ToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(19.dp))
    }
}

@Composable
internal fun EmojiPanel(onPick: (String) -> Unit, onClose: () -> Unit) {
    val groups = remember(RecentEmojis.size) {
        buildList {
            if (RecentEmojis.isNotEmpty()) add("最近" to RecentEmojis.toList())
            addAll(EmojiGroups)
        }
    }
    var index by remember { mutableIntStateOf(if (RecentEmojis.isEmpty()) 0 else 0) }
    val current = groups.getOrElse(index.coerceIn(0, groups.lastIndex)) { groups.first() }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), modifier = Modifier.fillMaxWidth().height(250.dp)) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader("表情") { onClose() }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                groups.forEachIndexed { i, (name, _) ->
                    FilterChip(
                        selected = i == index,
                        onClick = { index = i },
                        label = { Text(name, fontSize = 12.sp) },
                    )
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(8),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(current.second) { emoji ->
                    Surface(
                        color = Color.Transparent,
                        shape = RoundedCornerShape(9.dp),
                        modifier = Modifier.height(40.dp).pressable(scale = 0.9f, onClick = { onPick(emoji) }),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(emoji, fontSize = 21.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FormulaPanel(onInsert: (String, Boolean) -> Unit, onClose: () -> Unit) {
    var group by remember { mutableIntStateOf(0) }
    var block by remember { mutableStateOf(false) }
    val groups = FormulaGroups
    val current = groups[group.coerceIn(0, groups.lastIndex)]

    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), modifier = Modifier.fillMaxWidth().height(268.dp)) {
        Column(Modifier.fillMaxSize()) {
            PanelHeader("数学公式") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = block,
                        onClick = { block = !block },
                        label = { Text("独占一行", fontSize = 11.sp) },
                    )
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, "收起公式面板", modifier = Modifier.size(18.dp))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                groups.forEachIndexed { i, (name, _) ->
                    FilterChip(
                        selected = i == group,
                        onClick = { group = i },
                        label = { Text(name, fontSize = 12.sp) },
                    )
                }
            }
            Text(
                "点一下就插进笔记；插进去的是 LaTeX 源码，切到预览能看到排版效果。",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(current.second) { (label, tex) ->
                    Surface(
                        shape = RoundedCornerShape(9.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.height(38.dp).pressable(scale = 0.94f, onClick = { onInsert(tex, block) }),
                    ) {
                        Box(Modifier.fillMaxSize().padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                            Text(label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(title: String, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        trailing()
    }
}

// ---------------------------------------------------------------------------
// TextFieldValue 的插入算子
// ---------------------------------------------------------------------------

/** 在光标处插入纯文本（emoji 用）。 */
private fun TextFieldValue.insertText(text: String): TextFieldValue {
    val sel = selection
    val start = sel.min.coerceIn(0, this.text.length)
    val end = sel.max.coerceIn(0, this.text.length)
    val next = this.text.substring(0, start) + text + this.text.substring(end)
    return TextFieldValue(next, TextRange(start + text.length))
}

/** 用前后缀包住选中内容；没选中就插入占位符并把它选中，直接打字即可替换。 */
private fun TextFieldValue.wrapInline(prefix: String, suffix: String, hint: String): TextFieldValue {
    val sel = selection
    val start = sel.min.coerceIn(0, text.length)
    val end = sel.max.coerceIn(0, text.length)
    return if (start == end) {
        val next = text.substring(0, start) + prefix + hint + suffix + text.substring(end)
        TextFieldValue(next, TextRange(start + prefix.length, start + prefix.length + hint.length))
    } else {
        val picked = text.substring(start, end)
        val next = text.substring(0, start) + prefix + picked + suffix + text.substring(end)
        TextFieldValue(next, TextRange(start + prefix.length, start + prefix.length + picked.length))
    }
}

/** 插入带换行的块（代码块），光标落在中间的占位符上。 */
private fun TextFieldValue.insertBlock(prefix: String, suffix: String, hint: String): TextFieldValue {
    val sel = selection
    val start = sel.min.coerceIn(0, text.length)
    val end = sel.max.coerceIn(0, text.length)
    val picked = text.substring(start, end).ifEmpty { hint }
    val next = text.substring(0, start) + prefix + picked + suffix + text.substring(end)
    val caret = start + prefix.length
    return TextFieldValue(next, TextRange(caret, caret + picked.length))
}

/** 公式插入：行内 `$...$`，或独占一行的块级 `$$...$$`。整段 LaTeX 选中，方便直接改。 */
private fun TextFieldValue.insertFormula(tex: String, block: Boolean): TextFieldValue {
    val sel = selection
    var start = sel.min.coerceIn(0, text.length)
    var end = sel.max.coerceIn(0, text.length)
    val before = text
    if (block) {
        // 块级公式前后各留一个空行，Markdown 才认它是独立段落
        val lead = if (start == 0 || before[start - 1] == '\n') "" else "\n"
        val tail = if (end == before.length || before[end] == '\n') "\n" else "\n\n"
        val insert = "$lead$$$$$tex$$$$\n"
        val next = before.substring(0, start) + insert + before.substring(end)
        val contentStart = start + lead.length + 2
        return TextFieldValue(next, TextRange(contentStart, contentStart + tex.length))
    }
    // 行内：前后补空格，避免 `$` 贴着中文时被后面的正则当成普通字符
    val next = before.substring(0, start) + "\$$tex\$" + before.substring(end)
    start += 1
    return TextFieldValue(next, TextRange(start, start + tex.length))
}

/**
 * 行级前缀（标题 / 引用 / 列表）。再点一次同一种前缀就取消 ——
 * 否则工具条上的按钮就成了单向操作，写错了只能手动删。
 */
private fun TextFieldValue.toggleLinePrefix(marker: String): TextFieldValue {
    val caret = selection.min.coerceIn(0, text.length)
    val lineStart = text.lastIndexOf('\n', caret - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
    val line = text.substring(lineStart, lineEnd)
    val indent = line.length - line.trimStart().length
    val bodyStart = lineStart + indent
    return if (line.trimStart().startsWith(marker)) {
        val next = text.removeRange(bodyStart, bodyStart + marker.length)
        TextFieldValue(next, TextRange((caret - marker.length).coerceAtLeast(bodyStart)))
    } else {
        val next = text.substring(0, bodyStart) + marker + text.substring(bodyStart)
        TextFieldValue(next, TextRange(caret + marker.length))
    }
}
