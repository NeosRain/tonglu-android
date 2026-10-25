package cn.tonglu.app.ui.english.books

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.LearningArticle
import cn.tonglu.app.data.ReadingLibrary
import cn.tonglu.app.data.english.EnglishAiException
import cn.tonglu.app.data.english.EnglishAiStore
import cn.tonglu.app.data.english.books.BookArticleCoverage
import cn.tonglu.app.data.english.books.BookArticleDraft
import cn.tonglu.app.data.english.books.BookArticleGenerator
import cn.tonglu.app.data.english.books.BookArticleRecord
import cn.tonglu.app.data.english.books.BookArticleStore
import cn.tonglu.app.data.english.books.PersonalWordBook
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.data.english.reading.readingDigest
import cn.tonglu.app.ui.rememberOverlayMotion
import kotlinx.coroutines.launch
import java.util.UUID

/** EF10：保存的 AI 文章在阅读器里的稳定 id 前缀（阅读进度/点词选区都按它分桶）。 */
const val BOOK_ARTICLE_ID_PREFIX = "book-article:"

/**
 * EF10 收口：把 [BookArticleRecord] 转成阅读器能读的 [LearningArticle]。
 *
 * 复用现有阅读器链路（ArticleReader + 精读浮层 + 点词 + 阅读进度），**不另造阅读器**：
 * - id 带 [BOOK_ARTICLE_ID_PREFIX]，保存后稳定，阅读进度/对话/分析缓存都落在同一个桶里；
 * - `sourceRevision` 取正文 digest（与 MyReading 同一条纪律：内容不变 revision 不变）；
 * - AI 生成标记写进 author/许可/署名与 changeNote —— 阅读器标题下方和「署名与许可」里都看得见；
 * - `sourceUrl` 为空：正文完全来自本机词书生成，没有可打开的「原文地址」可冒充。
 */
fun BookArticleRecord.toLearningArticle(): LearningArticle {
    val body = paragraphs.joinToString("\n\n").trim()
    return LearningArticle(
        id = BOOK_ARTICLE_ID_PREFIX + id,
        sectionId = "my-reading",
        title = title,
        summary = "AI 生成 · 难度 $level · 词覆盖 ${(coverageRatio * 100).toInt()}%",
        author = "AI 生成",
        kind = "MARKDOWN",
        sourceUrl = "",
        licenseId = "个人词书生成 · 仅本机",
        licenseUrl = "",
        attribution = "由个人词书《$bookName》生成；AI 生成、未经人工审阅，仅供本机个人学习，未上传服务器。",
        changeNote = "AI 依据个人词书词汇生成",
        sourceRevision = readingDigest(body).take(16),
        updatedAt = java.time.Instant.ofEpochMilli(createdAt).toString(),
        featured = false,
        order = 0,
        body = body,
    )
}

/**
 * EF10 用个人词书生成自然分级文章：
 * 选词（≤40）→ 篇幅/难度 → 走用户自配 AI 通道生成 → 覆盖/难度统计 → 保存（AI 生成标记 + 仅本机）。
 *
 * 🔴 覆盖统计由 [BookArticleGenerator.coverage] 自算（含词形变化容忍），不信任模型自报；
 * 未覆盖词如实列出并给「重新生成」入口；无 AI 配置或失败时**保留全部选择**，可恢复重试。
 */
@Composable
fun BookArticleScreen(owner: String, book: PersonalWordBook, onClose: () -> Unit,
    /** 点了「精读」：把保存的文章交给上层的现有阅读器（MyReading / ArticleReader）打开。null = 不显示入口。 */
    onOpenArticle: ((BookArticleRecord) -> Unit)? = null) {
    val context = LocalContext.current
    val store = remember(owner) { BookArticleStore(context, owner) }
    // 阅读进度按 owner 分桶存在 ReadingLibrary 里，和「我的精读文章」同一条链路：这里只读来显示「读到 x%」。
    val library = remember(owner) { ReadingLibrary.of(context, owner) }
    val scope = rememberCoroutineScope()
    var selectedKeys by remember(owner) { mutableStateOf(book.words.take(BookArticleGenerator.MAX_WORDS_PER_ARTICLE).map { it.key }.toSet()) }
    var level by rememberSaveable(owner) { mutableStateOf(BookArticleGenerator.levels.first()) }
    var lengthLabel by rememberSaveable(owner) { mutableStateOf(BookArticleGenerator.lengths.keys.first()) }
    var generating by remember(owner) { mutableStateOf(false) }
    var draft by remember(owner) { mutableStateOf<BookArticleDraft?>(null) }
    var coverage by remember(owner) { mutableStateOf<BookArticleCoverage?>(null) }
    var notice by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var savedNotice by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var savedArticles by remember(owner) { mutableStateOf<List<BookArticleRecord>>(emptyList()) }
    var viewingSavedId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    /** 当前预览对应的已保存记录：只有它才能进阅读器（未保存草稿没有稳定 id，进度无处落）。 */
    var savedRecord by remember(owner) { mutableStateOf<BookArticleRecord?>(null) }

    fun reloadSaved() {
        scope.launch {
            try { savedArticles = store.snapshot().articles }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (cause: Exception) { notice = cause.message ?: "文章读取失败" }
        }
    }
    LaunchedEffect(owner) { reloadSaved() }

    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState, selfDrag = true,
    )
    fun back() {
        when {
            draft != null -> { draft = null; viewingSavedId = null; savedRecord = null }
            else -> motion.requestClose()
        }
    }

    fun generate() {
        val chosen = book.words.filter { it.key in selectedKeys }
        if (chosen.isEmpty()) { notice = "先选至少一个词。"; return }
        scope.launch {
            generating = true; notice = null; savedNotice = null; viewingSavedId = null; savedRecord = null
            try {
                val config = EnglishAiStore(context).load()
                val result = BookArticleGenerator.generate(config, chosen, level, BookArticleGenerator.lengths[lengthLabel]!!)
                draft = result
                coverage = BookArticleGenerator.coverage(result, chosen)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (configError: EnglishAiException) { notice = configError.message ?: "AI 配置或请求失败，请检查 AI 设置。" }
            catch (cause: Exception) { notice = cause.message ?: "生成失败，稍后重试。" }
            finally { generating = false }
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = { back() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false, onBack = { back() }), color = AppColors.Canvas) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级") }
                    Text(if (draft == null) "《${book.name}》生成文章" else "文章预览",
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
                    when {
                        draft == null && !generating -> TextButton(onClick = { generate() }) { Text("生成") }
                        generating -> Text("生成中…", style = MaterialTheme.typography.bodySmall)
                        draft != null && viewingSavedId == null -> TextButton(onClick = {
                            val current = draft ?: return@TextButton
                            val currentCoverage = coverage ?: return@TextButton
                            scope.launch {
                                try {
                                    val record = BookArticleRecord(id = UUID.randomUUID().toString(), bookId = book.id,
                                        bookName = book.name, title = current.title, paragraphs = current.paragraphs,
                                        level = level, wordKeys = currentCoverage.matchedKeys + currentCoverage.missedKeys,
                                        coverageRatio = currentCoverage.coverageRatio)
                                    store.save(record)
                                    savedRecord = record
                                    savedNotice = if (onOpenArticle == null) "已保存（仅本机）。"
                                        else "已保存（仅本机）；点下方「进入精读」用原阅读器打开。"
                                    reloadSaved()
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (cause: Exception) { notice = cause.message ?: "保存失败" }
                            }
                        }) { Text("保存") }
                    }
                }
                notice?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }
                savedNotice?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
                }
                if (draft == null) {
                    SelectBody(book, selectedKeys, level, lengthLabel, savedArticles,
                        progressOf = { record -> library.articleProgress(BOOK_ARTICLE_ID_PREFIX + record.id)?.ratio },
                        onToggle = { key ->
                            selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key
                        }, onLevel = { level = it }, onLength = { lengthLabel = it },
                        onOpenArticle = onOpenArticle,
                        onOpenSaved = { record ->
                            viewingSavedId = record.id
                            savedRecord = record
                            draft = BookArticleDraft(record.title, record.paragraphs)
                            coverage = null
                        },
                        onDeleteSaved = { id ->
                            scope.launch {
                                try { store.delete(id); reloadSaved() }
                                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (cause: Exception) { notice = cause.message ?: "删除失败" }
                            }
                        })
                } else {
                    PreviewBody(draft!!, coverage, savedRecord = savedRecord,
                        onOpenArticle = onOpenArticle, onRegenerate = { generate() })
                }
            }
        }
    }
}

@Composable
private fun SelectBody(book: PersonalWordBook, selected: Set<String>, level: String, lengthLabel: String,
    savedArticles: List<BookArticleRecord>,
    /** 已保存文章的阅读进度（0~1）；null = 还没读过。 */
    progressOf: (BookArticleRecord) -> Float?,
    onToggle: (String) -> Unit, onLevel: (String) -> Unit, onLength: (String) -> Unit,
    onOpenArticle: ((BookArticleRecord) -> Unit)?,
    onOpenSaved: (BookArticleRecord) -> Unit, onDeleteSaved: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text("难度", fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BookArticleGenerator.levels.forEach { option ->
                    FilterChip(selected = level == option, onClick = { onLevel(option) }, label = { Text(option) })
                }
            }
            Text("篇幅", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BookArticleGenerator.lengths.keys.forEach { option ->
                    FilterChip(selected = lengthLabel == option, onClick = { onLength(option) },
                        label = { Text(option.substringBefore("（")) })
                }
            }
            Text("已选 ${selected.size} / ${BookArticleGenerator.MAX_WORDS_PER_ARTICLE}（一次最多 40 词，避免硬塞整本书）",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp))
            Text("生成走你自己的 AI 配置（「AI 设置」里填的接口）；无配置或失败时选择会保留，可直接重试。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (book.words.isEmpty()) item { Text("这本词书还没有词条。") }
        if (savedArticles.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text("已保存（AI 生成 · 仅本机）", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
            }
            items(savedArticles, key = { it.id }) { record ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { onOpenSaved(record) }) {
                        Text(record.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val read = progressOf(record)
                        Text("${record.bookName} · ${record.level} · 覆盖 ${(record.coverageRatio * 100).toInt()}% · AI 生成" +
                            (read?.let { " · 读到 ${(it * 100).toInt()}%" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (onOpenArticle != null) TextButton(onClick = { onOpenArticle(record) }) { Text("精读") }
                    TextButton(onClick = { onDeleteSaved(record.id) }) { Text("删除") }
                }
            }
        }
        items(book.words, key = { it.key }) { word ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = word.key in selected, onCheckedChange = { onToggle(word.key) })
                Column(Modifier.weight(1f)) {
                    Text(word.surface, fontWeight = FontWeight.Medium)
                    if (word.meaning.isNotBlank()) Text(word.meaning, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PreviewBody(draft: BookArticleDraft, coverage: BookArticleCoverage?,
    savedRecord: BookArticleRecord?, onOpenArticle: ((BookArticleRecord) -> Unit)?, onRegenerate: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("AI 生成", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(draft.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
        items(draft.paragraphs) { paragraph -> Text(paragraph, style = MaterialTheme.typography.bodyMedium) }
        if (savedRecord != null && onOpenArticle != null) {
            item {
                Button(onClick = { onOpenArticle(savedRecord) }, modifier = Modifier.fillMaxWidth()) {
                    Text("进入精读（原阅读器 · 可点词/记进度）")
                }
            }
        }
        coverage?.let { result ->
            item {
                HorizontalDivider()
                Text("覆盖：${result.matchedKeys.size}/${result.matchedKeys.size + result.missedKeys.size}（${(result.coverageRatio * 100).toInt()}%，含词形变化）",
                    style = MaterialTheme.typography.bodySmall)
                if (result.missedKeys.isNotEmpty()) {
                    Text("未覆盖：${result.missedKeys.joinToString("、")}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("文中较长词 ${result.extraLongWords.size} 个（粗略难度参考，非分级结论）" +
                    if (result.extraLongWords.isNotEmpty()) "：${result.extraLongWords.take(10).joinToString("、")}" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = onRegenerate) { Text("重新生成") }
                }
            }
        }
    }
}
