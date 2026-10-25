package cn.tonglu.app.ui

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import cn.tonglu.app.data.LearningCatalog
import cn.tonglu.app.data.ReadingBookmarkRecord
import cn.tonglu.app.data.ReadingCounts
import cn.tonglu.app.data.ReadingLibrary
import cn.tonglu.app.data.ReadingNoteRecord
import cn.tonglu.app.data.ReadingProgressRecord
import kotlinx.coroutines.launch

/**
 * 从「我的阅读」跳回正文时要带的东西。
 *
 * 用 data class 而不是三四个位置参数：改一次签名就要顺着看一圈调用点，
 * 还容易和 `onOpen(articleId, anchor, documentPath)` 这种顺序搞混。
 */
internal data class ShelfTarget(
    val articleId: String,
    val documentPath: String,
    val anchor: String? = null,
    /** 段落笔记要精确跳到第几个 Markdown 块；-1 表示按锚点或上次位置恢复。 */
    val index: Int = -1,
)

/** 学习页里的「我的阅读」入口卡。和知识网络入口并排，都是整宽的一行。 */
@Composable
internal fun ReadingEntryCard(reading: ReadingCounts, onOpen: () -> Unit, containerColor: Color = Color.Unspecified) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (containerColor == Color.Unspecified) primary.copy(alpha = 0.09f) else containerColor,
        modifier = Modifier.fillMaxWidth().pressable(scale = 0.985f, onClick = onOpen),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.MenuBook, null, tint = primary, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("我的阅读", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (reading.total > 0) {
                        "继续读 ${reading.progress} 篇 · 书签 ${reading.bookmarks} 枚 · 笔记 ${reading.notes} 条"
                    } else {
                        "进度、书签、笔记都会跟着账号走"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                tint = primary.copy(alpha = 0.8f),
            )
        }
    }
}

/** 待确认的删除动作。不用 Pair —— 位置解构在 Kotlin 里没有命名，读起来全靠猜。 */
internal data class PendingDelete(val kind: String, val id: String, val label: String)

/**
 * 我的阅读：继续读 / 书签 / 笔记。
 *
 * 和「今日更新」「知识网络」一样用全屏 Dialog —— 底部导航是盖在内容之上的浮层，
 * 内嵌进学习页的话列表最后几行会被导航栏挡死。
 */
@Composable
internal fun ReadingShelfScreen(
    catalog: LearningCatalog,
    library: ReadingLibrary,
    onOpen: (ShelfTarget) -> Unit,
    onChanged: () -> Unit,
    onClose: () -> Unit,
    /**
     * 「把这条笔记发到讨论区」。传 null 就不显示发布按钮。
     * 回调在**已经落库之后**触发 —— 否则会出现「发出去了，但笔记本身没保存」。
     */
    editing: ReadingNoteRecord? = null,
    pending: PendingDelete? = null,
    onEdit: (ReadingNoteRecord) -> Unit,
    onPendingDelete: (PendingDelete) -> Unit,
    onCloseChild: () -> Unit,
    onPublishNote: ((title: String, body: String) -> Unit)? = null,
) {
    val motion = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val latestClose by rememberUpdatedState(onClose)
    fun dismiss(after: () -> Unit = latestClose) {
        if (!closing) {
            closing = true
            OverlayDismissGuard.arm()
            scope.launch { motion.animateTo(0f, tween(OverlayMotion.EXIT_MS, easing = FastOutSlowInEasing)); after() }
        }
    }
    // 跟手返回：**叠加**在这一层已有的进出场动画上（不替换，理由见 OverlayMotion）。
    val edgeBack = rememberEdgeBackState()
    val predictiveBack = OverlayMotion.enabledState
    LaunchedEffect(Unit) { edgeBack.onCommit = { dismiss() } }
    LaunchedEffect(Unit) { motion.animateTo(1f, tween(OverlayMotion.ENTER_MS, easing = FastOutSlowInEasing)) }
    val backProgress = rememberEdgeBackProgressState(edgeBack)

    var tab by rememberSaveable { mutableIntStateOf(0) }
    /** 本页自己改过数据之后 +1。库不是 Compose 状态，得手动敲一下才重算。 */
    var revision by remember { mutableIntStateOf(0) }
    val bump = { revision++; onChanged() }

    val titles = remember(catalog) { catalog.articles.associate { it.id to it.title } }
    val progressList = remember(revision, library) { library.recentProgress(40) }
    val bookmarkList = remember(revision, library) { library.bookmarks() }
    val noteList = remember(revision, library) { library.notes() }

    Dialog(
        onDismissRequest = { dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setWindowAnimations(0)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxSize()
                .edgeSwipeBack(edgeBack, enabled = predictiveBack)
                .graphicsLayer {
                    alpha = motion.value
                    translationY = (1f - motion.value) * 32.dp.toPx()
                    applyEdgeBack(backProgress.value, edgeBack.fromLeft, fade = false)
                },
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { dismiss() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回学习页")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("我的阅读", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            "进度、书签、笔记跟着账号走",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        "继续读" to progressList.size,
                        "书签" to bookmarkList.size,
                        "笔记" to noteList.size,
                    ).forEachIndexed { index, entry ->
                        FilterChip(
                            selected = tab == index,
                            onClick = { tab = index },
                            label = { Text("${entry.first} ${entry.second}") },
                        )
                    }
                }
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (tab) {
                        0 -> {
                            if (progressList.isEmpty()) item(key = "empty") {
                                ShelfEmpty("还没有阅读记录。随便打开一篇，读到哪里都会记在这里。")
                            }
                            items(progressList, key = { "p:${it.article}@${it.document}" }) { record ->
                                ProgressEntry(
                                    record = record,
                                    title = titles[record.article].orEmpty().ifBlank { record.article },
                                    onOpen = {
                                        onOpen(ShelfTarget(record.article, record.document))
                                        dismiss()
                                    },
                                    onRemove = {
                                        onPendingDelete(PendingDelete("progress", "${record.article}@${record.document}",
                                            titles[record.article].orEmpty().ifBlank { record.article }))
                                    },
                                )
                            }
                        }
                        1 -> {
                            if (bookmarkList.isEmpty()) item(key = "empty") {
                                ShelfEmpty("阅读时点书签按钮，位置和摘录都会存下来。")
                            }
                            items(bookmarkList, key = { "b:${it.key}" }) { record ->
                                BookmarkEntry(
                                    record = record,
                                    title = titles[record.article].orEmpty().ifBlank { record.article },
                                    onOpen = {
                                        onOpen(ShelfTarget(record.article, record.document, record.anchor))
                                        dismiss()
                                    },
                                    onRemove = {
                                        onPendingDelete(PendingDelete("bookmark", record.key, record.text))
                                    },
                                )
                            }
                        }
                        else -> {
                            if (noteList.isEmpty()) item(key = "empty") {
                                ShelfEmpty("长按正文里的一段可以划线写想法，也可以整篇写一条笔记。")
                            }
                            items(noteList, key = { "n:${it.key}" }) { record ->
                                NoteEntry(
                                    record = record,
                                    title = titles[record.article].orEmpty().ifBlank { record.article },
                                    onEdit = { onEdit(record) },
                                    onOpen = {
                                        onOpen(ShelfTarget(record.article, record.document, null,
                                            if (record.isWholeArticle) -1 else record.startOffset))
                                        dismiss()
                                    },
                                    onRemove = {
                                        onPendingDelete(PendingDelete("note", record.key, record.text))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { record ->
        val noteTitle = titles[record.article].orEmpty().ifBlank { record.article }
        NoteEditorDialog(
            title = noteTitle,
            quote = record.quote.orEmpty(),
            initialBody = record.text,
            onDismiss = { onCloseChild() },
            onDelete = {
                library.removeNote(record.key)
                onCloseChild()
                bump()
            },
            onSave = { body ->
                library.upsertNote(
                    id = record.key, articleId = record.article, documentPath = record.document,
                    startOffset = record.startOffset, endOffset = record.endOffset,
                    quote = record.quote.orEmpty(), before = record.before.orEmpty(), body = body,
                )
                onCloseChild()
                bump()
            },
            onPublish = onPublishNote?.let { publish ->
                { body ->
                    // 发布前先保存：用户是点了「发布」，不是在点「保存」；
                    // 发出去的内容和本地笔记不一致是最难解释的一类 bug。
                    library.upsertNote(
                        id = record.key, articleId = record.article, documentPath = record.document,
                        startOffset = record.startOffset, endOffset = record.endOffset,
                        quote = record.quote.orEmpty(), before = record.before.orEmpty(), body = body,
                    )
                    onCloseChild()
                    bump()
                    publish(noteTitle, body)
                }
            },
        )
    }

    pending?.let { request ->
        AlertDialog(
            onDismissRequest = { onCloseChild() },
            title = { Text("删除这条${if (request.kind == "progress") "阅读记录" else if (request.kind == "bookmark") "书签" else "笔记"}？") },
            text = {
                Text(
                    request.label.take(60).ifBlank { "删除之后其他设备也会同步消失。" },
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (request.kind) {
                        "bookmark" -> library.removeBookmark(request.id)
                        "note" -> library.removeNote(request.id)
                        else -> {
                            val parts = request.id.split('@')
                            library.removeProgress(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" })
                        }
                    }
                    onCloseChild()
                    bump()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { onCloseChild() }) { Text("取消") } },
        )
    }
}

@Composable
private fun ShelfEmpty(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp,
            lineHeight = 21.sp, modifier = Modifier.padding(horizontal = 24.dp))
    }
}

/** 继续读：进度条 + 百分比 + 章节名。 */
@Composable
private fun ProgressEntry(
    record: ReadingProgressRecord,
    title: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().pressable(scale = 0.985f, onClick = onOpen),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                    if (record.finished) {
                        Spacer(Modifier.width(6.dp))
                        Text("读完", fontSize = 9.sp, color = primary, fontWeight = FontWeight.SemiBold)
                    }
                }
                Text(
                    buildString {
                        documentLabel(record.document).takeIf { it.isNotEmpty() }?.let { append("$it · ") }
                        append("${(record.score * 100).toInt()}%")
                        shelfTime(record.updatedAt).takeIf { it.isNotEmpty() }?.let { append(" · $it") }
                    },
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                LinearProgressIndicator(
                    progress = { record.score },
                    color = primary,
                    trackColor = primary.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth().padding(top = 7.dp, end = 10.dp).height(3.dp),
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.DeleteOutline, "从继续读里移除", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 书签：摘录当标题，位置和分类名当副标题。 */
@Composable
private fun BookmarkEntry(
    record: ReadingBookmarkRecord,
    title: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().pressable(scale = 0.985f, onClick = onOpen),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Bookmarks, null, tint = Color(0xFFD97706), modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text(record.text.ifBlank { "书签" }, fontSize = 14.sp, lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(title)
                        documentLabel(record.document).takeIf { it.isNotEmpty() }?.let { append(" · $it") }
                        shelfTime(record.createdAt).takeIf { it.isNotEmpty() }?.let { append(" · $it") }
                    },
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.DeleteOutline, "删除书签", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 笔记：引用原文（灰底斜体感）+ 自己写的话。 */
@Composable
private fun NoteEntry(
    record: ReadingNoteRecord,
    title: String,
    onEdit: () -> Unit,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val accent = Color(0xFF7C3AED)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().pressable(scale = 0.985f, onClick = onEdit),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp)) {
            Column(Modifier.weight(1f)) {
                if (record.isWholeArticle) {
                    Text("整篇笔记", fontSize = 11.sp, color = accent, fontWeight = FontWeight.SemiBold)
                } else {
                    Surface(shape = RoundedCornerShape(8.dp), color = accent.copy(alpha = 0.08f)) {
                        Text(record.quote.orEmpty(), fontSize = 12.sp, lineHeight = 18.sp, maxLines = 3,
                            overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp))
                    }
                }
                Text(record.text.ifBlank { "（还没写内容）" }, fontSize = 13.sp, lineHeight = 20.sp,
                    maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (record.isWholeArticle) 4.dp else 8.dp))
                Text(
                    buildString {
                        append(title)
                        documentLabel(record.document).takeIf { it.isNotEmpty() }?.let { append(" · $it") }
                        shelfTime(record.updatedAt).takeIf { it.isNotEmpty() }?.let { append(" · $it") }
                    },
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp),
                )
                TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                    Text("跳到原文", fontSize = 11.sp)
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.DeleteOutline, "删除笔记", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 章节路径只留最后一段，`book/02-getting-started.md` → `02-getting-started.md`。 */
private fun documentLabel(path: String): String =
    if (path.isBlank()) "" else path.substringAfterLast('/').removeSuffix(".md")

/** 相对时间。超过一周就直接给日期，别让人心算「23 天前」。 */
private fun shelfTime(millis: Long): String {
    if (millis <= 0L) return ""
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            .format(java.util.Date(millis))
    }
}
