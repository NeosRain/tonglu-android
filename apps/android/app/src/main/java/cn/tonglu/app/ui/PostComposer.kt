package cn.tonglu.app.ui
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import cn.tonglu.app.data.POST_BODY_MAX
import cn.tonglu.app.data.PostBodyLimit
import cn.tonglu.app.ui.glass.GlassActionPanel

import android.view.WindowManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** 广场主题色（和 TongluApp 里的 Accent 一致；2026-10-11 随浅色 Accent 加深到 #2563EB）。 */
internal val PostComposerAccent = Color(0xFF2563EB)

/** 发布时可选的分类。「学习笔记」是新增的 —— 正文是 Markdown，可能带公式。 */
internal val PostCategories: List<Pair<String, String>> = listOf(
    "校园墙" to "WALL",
    "二手" to "SECOND_HAND",
    "求助" to "HELP",
    "失物" to "LOST_FOUND",
    "学习笔记" to "NOTES",
)

/**
 * 发帖 / 发笔记的全屏编辑器。
 *
 * 和笔记编辑器共用同一套工具条与面板（`EditorToolbar` / `EmojiPanel` / `FormulaPanel`），
 * 差别只在多一个分类和标题 —— 笔记发布到讨论区需要一个能搜到的标题。
 */
@Composable
internal fun PostComposerDialog(
    onPublish: (category: String, title: String?, body: String, done: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
    initialCategory: String = "WALL",
    initialTitle: String = "",
    initialBody: String = "",
    heading: String = "发布内容",
    accent: Color = PostComposerAccent,
    /** 可选分类 chips；null = 广场全部分类。社团帖传单元素列表（C0 契约：社团讨论不分交易/求助类）。 */
    categories: List<Pair<String, String>>? = null,
    /**
     * 贴附的学习资料包（47 §3 P4）。`null` = 没贴。
     *
     * 选择动作交给上层（[onPickPack]）：资料清单要网络与资料库上下文，
     * 编辑器这里不该自己去拉 —— 它只负责「显示已选 / 能清掉」。
     */
    linkedPack: cn.tonglu.app.data.LinkedPackDto? = null,
    /** 点「附一份资料」→ 由上层打开资料选择层。 */
    onPickPack: (() -> Unit)? = null,
    /** 清掉已贴的资料。 */
    onClearPack: () -> Unit = {},
) {
    var category by remember { mutableStateOf(initialCategory) }
    var title by remember { mutableStateOf(initialTitle) }
    var value by remember { mutableStateOf(TextFieldValue(initialBody, TextRange(initialBody.length))) }
    var preview by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(NotePanel.None) }
    var submitting by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // 这一层原本是「窗口动画关成 0 + Compose 里也不补」，所以是硬切出来、硬切没的 ——
    // 用户 2026-10-04 点名的「突兀」就是这个。现在走统一的一站式方案。
    val motion = rememberOverlayMotion(
        onClose = onDismiss,
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
        backEnabled = !submitting,
    )

    // 无论从哪里关（返回按钮 / 点外部 / 左边缘拖松手），都先播退出动画再真正 dismiss。
    fun closeComposer() { motion.requestClose() }

    Dialog(
        onDismissRequest = { if (!submitting) closeComposer() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        androidx.compose.runtime.SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setWindowAnimations(0)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().overlayMotion(motion, fade = false)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (!submitting) closeComposer() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                    Text(
                        if (submitting) "审核中…" else heading,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (!submitting && !preview &&
                        (PostBodyLimit.shouldWarn(value.text) || PostBodyLimit.overLimit(value.text))
                    ) {
                        // 正文长度提示。放在标题与按钮之间：左边那个 Text 是 weight(1f)，
                        // 会把多出来的宽度吃掉，所以**按钮不会因为这段文字出现/消失而移动**。
                        //
                        // 只在 18000 字之后出现（PostBodyLimit.WARN_AT）—— 平时不占地方，
                        // 快撞顶时才提醒，正好是用户还来得及收尾的时候。
                        val over = PostBodyLimit.overLimit(value.text)
                        Text(
                            if (over) "已超 ${PostBodyLimit.overBy(value.text)} 字"
                            else "还可输入 ${PostBodyLimit.remaining(value.text)} 字",
                            fontSize = 11.sp,
                            color = if (over) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                    if (!submitting) {
                        IconButton(onClick = {
                            if (!preview) panel = NotePanel.None
                            preview = !preview
                        }) {
                            Icon(
                                if (preview) Icons.Outlined.Edit else Icons.Outlined.Visibility,
                                if (preview) "继续编辑" else "预览渲染效果",
                            )
                        }
                        TextButton(
                            // 上限是后端 `CreatePostDto.body @Length(1, 20000)`。
                            // **这里不截断正文** —— 超限只是拦住发布，让用户自己删，
                            // 免得把他写了一半的长文悄悄砍掉（见 data/PostLimits.kt 的注释）。
                            enabled = value.text.isNotBlank() && value.text.length <= POST_BODY_MAX,
                            onClick = {
                                submitting = true
                                onPublish(category, title.trim().ifBlank { null }, value.text.trim()) { submitting = false }
                            },
                        ) {
                            Icon(Icons.Outlined.Send, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("发布")
                        }
                    }
                }

                if (submitting) {
                    LinearProgressRow("正在提交并审核，请稍候")
                }

                if (!preview) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        (categories ?: PostCategories).forEach { (label, key) ->
                            Surface(
                                color = if (category == key) accent else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .padding(vertical = 2.dp)
                                    .pressable(scale = 0.94f, enabled = !submitting) { category = key },
                            ) {
                                Text(
                                    label,
                                    color = if (category == key) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it.take(100) },
                        placeholder = { Text("标题（可选）", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    // 贴附资料包（47 §3 P4）。已贴就显示卡片 + 能换/能清；没贴就是一个入口按钮。
                    // onPickPack = null（社团帖：C1 未给社团路由暴露资料包引用）→ 整行不显示。
                    if (onPickPack != null || linkedPack != null) Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { onPickPack?.invoke() }, enabled = !submitting) {
                            Icon(Icons.Outlined.LibraryBooks, null, Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (linkedPack == null) "附一份资料" else "换一份")
                        }
                        if (linkedPack != null) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onClearPack, enabled = !submitting) { Text("移除") }
                        }
                    }
                    if (linkedPack != null) {
                        Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp)) {
                            PostPackCard(pack = linkedPack)
                        }
                    }
                }

                val composerBackdrop = rememberLayerBackdrop()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(composerBackdrop)) {
                    if (preview) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            if (title.isNotBlank()) {
                                Text(
                                    title,
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp),
                                )
                            }
                            Box(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
                                MarkdownBody(markdown = value.text, fontSizeSp = 15, accentColor = accent)
                            }
                        }
                    } else {
                        BodyField(value = value, accent = accent, focus = focus, onChange = { value = it }, bottomPadding = if (panel == NotePanel.None) 72.dp else 336.dp)
                    }
                    }

                if (!preview && !submitting) {
                    Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 8.dp)) {
                    when (panel) {
                        NotePanel.Emoji -> EmojiPanel(
                            onPick = { emoji -> rememberEmoji(emoji); value = value.appendText(emoji) },
                            onClose = { panel = NotePanel.None },
                        )
                        NotePanel.Formula -> FormulaPanel(
                            onInsert = { tex, block -> value = value.appendFormula(tex, block) },
                            onClose = { panel = NotePanel.None },
                        )
                        NotePanel.None -> Unit
                    }
                    GlassActionPanel(composerBackdrop) {
                    EditorToolbar(
                        accent = accent,
                        onWrap = { pre, suf, hint -> value = value.appendWrap(pre, suf, hint) },
                        onLinePrefix = { marker -> value = value.appendLinePrefix(marker) },
                        onCodeBlock = { value = value.appendBlock("```\n", "\n```", "code") },
                        onLink = { value = value.appendWrap("[", "](https://)", "链接文字") },
                        onImage = { value = value.appendWrap("![", "](https://)", "图片说明") },
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
}

@Composable
private fun LinearProgressRow(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------
// 插入算子
//
// 和 NoteEditor 里那套逻辑一样，但作用在「发布编辑器自己的」TextFieldValue 上。
// 没有把它们提到公共文件是因为每个编辑器的状态本来就在各自 composable 里，
// 硬抽一层只会多一层间接。
// ---------------------------------------------------------------------------

private fun TextFieldValue.appendText(text: String): TextFieldValue {
    val start = selection.min.coerceIn(0, this.text.length)
    val end = selection.max.coerceIn(0, this.text.length)
    val next = this.text.substring(0, start) + text + this.text.substring(end)
    return TextFieldValue(next, TextRange(start + text.length))
}

private fun TextFieldValue.appendWrap(prefix: String, suffix: String, hint: String): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    return if (start == end) {
        val next = text.substring(0, start) + prefix + hint + suffix + text.substring(end)
        TextFieldValue(next, TextRange(start + prefix.length, start + prefix.length + hint.length))
    } else {
        val picked = text.substring(start, end)
        val next = text.substring(0, start) + prefix + picked + suffix + text.substring(end)
        TextFieldValue(next, TextRange(start + prefix.length, start + prefix.length + picked.length))
    }
}

private fun TextFieldValue.appendBlock(prefix: String, suffix: String, hint: String): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    val picked = text.substring(start, end).ifEmpty { hint }
    val next = text.substring(0, start) + prefix + picked + suffix + text.substring(end)
    val caret = start + prefix.length
    return TextFieldValue(next, TextRange(caret, caret + picked.length))
}

private fun TextFieldValue.appendFormula(tex: String, block: Boolean): TextFieldValue {
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    if (block) {
        val lead = if (start == 0 || text[start - 1] == '\n') "" else "\n"
        val insert = "$lead$$$$$tex$$$$\n"
        val next = text.substring(0, start) + insert + text.substring(end)
        val contentStart = start + lead.length + 2
        return TextFieldValue(next, TextRange(contentStart, contentStart + tex.length))
    }
    val next = text.substring(0, start) + "\$$tex\$" + text.substring(end)
    val caret = start + 1
    return TextFieldValue(next, TextRange(caret, caret + tex.length))
}

private fun TextFieldValue.appendLinePrefix(marker: String): TextFieldValue {
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
