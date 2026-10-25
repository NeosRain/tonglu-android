package cn.tonglu.app.ui.learning

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.LearningArticle
import cn.tonglu.app.data.ReadingLibrary
import cn.tonglu.app.data.english.EnglishRepository
import cn.tonglu.app.data.english.MyReadingArticle
import cn.tonglu.app.data.english.materials.*
import cn.tonglu.app.ui.english.examentry.*
import cn.tonglu.app.ui.readingimport.PaperReaderScreen
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** 材料选择始终留在正文下面：关闭阅读器返回原列表，保留筛选和滚动位置。 */
@Composable
internal fun ExamReadingHost(owner: String, onClose: () -> Unit,
    reader: @Composable (LearningArticle, () -> Unit) -> Unit) {
    val context = LocalContext.current
    val repository = remember { ExamMaterialsRepository(context) }
    var packed by remember { mutableStateOf<PackedExamReading?>(null) }
    var exams by remember(owner) { mutableStateOf<ExamCatalogState>(ExamCatalogState.Loading) }
    var list by remember(owner) { mutableStateOf<ArticleListState>(ArticleListState.Loading) }
    var selected by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    var openId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var article by remember(owner) { mutableStateOf<LearningArticle?>(null) }
    var opening by remember { mutableStateOf(false) }
    val nav = rememberExamEntryNavState(owner)
    // ---- 个人导入（考试精读「全文 0」时真实可用的入口）------------------------------------
    // 用户 2026-10-18 要求：考试精读只有官方链接、全文为 0 时，允许用户把自己的卷子**本机**导入，
    // 归入四级/六级考试后进入精读。这里只读本机 Room（按 owner 隔离），不上传、不公开发布。
    val englishRepository = remember(owner) { EnglishRepository(context) }
    val personalArticles by englishRepository.observeMyShelf(owner).collectAsState(initial = emptyList())
    val personalRows = remember(owner, personalArticles) { personalExamRows(context, owner, personalArticles) }
    val shownExams = remember(exams, personalRows) { mergePersonalExams(exams, personalRows) }
    val shownList = remember(list, selected, personalRows) { mergePersonalList(list, selected, personalRows) }
    val personalImportTag = when (selected) { "CET4" -> "四级"; "CET6" -> "六级"; else -> "" }
    var importOpen by rememberSaveable(owner) { mutableStateOf(false) }
    // 个人导入试卷的阅读/校对窗口（原版翻页 + 逐页 OCR；不是「一页一章」的阅读器）。
    var paperDocId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    // 🔴 每一级导航页都是**独立 Dialog 窗口**（2026-10-18 任务B：深层返回背景）。
    // 原来 Home/应试考试/文章列表塞在同一个窗口里：手势拖动时系统移动的是这**一个**窗口，
    // 露出的永远是它下面的英语 Hub（用户说的「最上一级背景」）。拆成窗口后，
    // 返回手势拖动当前页时，露出来的是下面那个窗口 = 上一页的真背景；逐级返回也自然成立。
    val scrollHolder = rememberSaveableStateHolder()
    LaunchedEffect(Unit) {
        try {
            packed = withContext(Dispatchers.IO) {
                context.assets.open("catalog.json").bufferedReader().use { packedExamReading(it.readText()) }
            }
            packed?.warnings?.forEach { android.util.Log.w("ExamReading", it) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { notice = "离线资料目录无法读取"; android.util.Log.w("ExamReading", "读取打包目录失败", error) }
    }
    LaunchedEffect(owner, packed, retry) {
        packed?.let { local ->
            // EF02：总数与全文数分开带，选择页计数才和列表同源（链接条目也是真实条目）。
            exams = ExamCatalogState.Content(local.rows.filter { it.examType != null }.groupBy { it.examType!! }
                .map { (key, rows) -> ExamOption(key, if (key == "CET4") "大学英语四级" else if (key == "CET6") "大学英语六级" else key,
                    articleCount = rows.size, fullTextCount = rows.count { it.isFullText }) })
        }
        try {
            exams = ExamCatalogState.Content(repository.options().map { ExamOption(it.key, it.title, it.articleCount, it.fullTextCount) })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            android.util.Log.w("ExamReading", "在线考试目录不可用", error)
            val local = packed
            if (local == null) exams = ExamCatalogState.Failed("目录加载失败，请重试")
            else {
                notice = "在线目录暂不可用，显示随应用资料"
                exams = ExamCatalogState.Content(local.rows.filter { it.examType != null }.groupBy { it.examType!! }
                    .map { (key, rows) -> ExamOption(key, if (key == "CET4") "大学英语四级" else if (key == "CET6") "大学英语六级" else key,
                        articleCount = rows.size, fullTextCount = rows.count { it.isFullText }) })
            }
        }
    }
    LaunchedEffect(owner, selected, packed, retry) {
        val key = selected ?: return@LaunchedEffect
        list = ArticleListState.Loading
        packed?.let { local ->
            val rows = local.rows.filter { if (key == "recommended") it.recommended && it.language == "en" && it.isFullText else it.examType == key }
            val items = withContext(Dispatchers.IO) {
                mergeLocalState(rows, { ReadingLibrary.of(context, owner).articleProgress(it)?.ratio },
                    rows.filter { it.isFullText }.map { it.articleId }.toSet())
            }
            list = ArticleListState.Content(items.map { it.entryRow() })
        }
        try {
            val items = if (key == "recommended") repository.recommended(owner) else repository.list(key, owner)
            list = ArticleListState.Content(items.map { it.entryRow() })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            android.util.Log.w("ExamReading", "在线材料列表不可用", error)
            val local = packed
            if (local == null) list = ArticleListState.Failed("材料加载失败，请重试")
            else {
                notice = "在线目录暂不可用，显示随应用资料"
                val rows = local.rows.filter { if (key == "recommended") it.recommended && it.language == "en" && it.isFullText else it.examType == key }
                val items = withContext(Dispatchers.IO) {
                    mergeLocalState(rows, { ReadingLibrary.of(context, owner).articleProgress(it)?.ratio },
                        rows.filter { it.isFullText }.map { it.articleId }.toSet())
                }
                list = ArticleListState.Content(items.map { it.entryRow() })
            }
        }
    }
    LaunchedEffect(openId, packed) {
        val id = openId ?: return@LaunchedEffect
        opening = true
        try {
            // 个人导入的**试卷**：一个入口 = 整份原版（左右翻页 + 逐页 OCR 校对），
            // 不打开「一页一章」的阅读器；id 前缀 paper:<documentId>。
            if (id.startsWith("paper:")) {
                paperDocId = id.removePrefix("paper:")
                openId = null
                return@LaunchedEffect
            }
            // 个人导入的文章全部是 `my-` 前缀（Room 里按 owner 隔离）；先于在线材料目录解析，
            // 不拿官方 SOURCE_LINK 冒充全文，也不要求后端先上线新接口。
            if (id.startsWith("my-")) {
                val row = englishRepository.myArticle(id, owner) ?: error("文章已不存在，请重新导入")
                require(row.textAvailable && row.body.isNotBlank()) {
                    "本页没有可读文字（扫描页或文字层乱码），请到「导入我的文章」里 OCR 校对后再打开"
                }
                article = englishRepository.toLearningArticle(row)
                return@LaunchedEffect
            }
            val local = packed?.articles?.get(id)
            // 随应用全文已经在本机，不为打开一篇文章等待未部署的新接口。
            val listedRevision = (list as? ArticleListState.Content)?.rows?.firstOrNull { it.articleId == id }?.revision
            val useLocal = local != null && (listedRevision.isNullOrBlank() || listedRevision == local.sourceRevision)
            val detail = if (useLocal) null else try { repository.detail(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.w("ExamReading", "在线材料详情不可用", error)
                if (local == null) throw error else {
                    notice = "在线新版暂不可用，打开随应用的离线版本"
                    null
                }
            }
            val row = detail?.row ?: packed?.rows?.firstOrNull { it.articleId == id } ?: error("材料不存在")
            if (!row.isFullText) {
                val uri = Uri.parse(row.sourceUrl)
                require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "原站链接不合法" }
                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                openId = null
            } else {
                val body = detail?.body ?: local?.body ?: error("全文暂不可用")
                require(body.isNotBlank()) { "全文暂不可用" }
                row.contentSha256?.let { expected ->
                    require(cn.tonglu.app.data.english.reading.readingDigest(body) == expected) { "正文指纹不匹配，请重试" }
                }
                val revision = row.revision.ifBlank { "body-" + cn.tonglu.app.data.english.reading.readingDigest(body) }
                article = local?.copy(body = body, sourceRevision = revision) ?: LearningArticle(row.articleId,
                    row.sectionId, row.title, row.summary, "", row.kind, row.sourceUrl, row.licenseId, row.licenseUrl,
                    row.attribution, "", revision, row.updatedAt, false, 0, body)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            Toast.makeText(context, "打开材料失败：${error.message ?: "请重试"}", Toast.LENGTH_LONG).show()
            openId = null
        } finally { opening = false }
    }
    val openArticle: (String) -> Unit = { if (!opening) { opening = true; openId = it } }
    val importPersonal: (() -> Unit)? = if (personalImportTag.isNotBlank()) ({ importOpen = true }) else null
    ExamLevelWindow(opening = opening, notice = notice,
        onClose = { OverlayDismissGuard.arm(); onClose() }) { requestClose ->
        ExamEntryHomePage(
            continueReading = null,
            onContinueReading = {},
            onOpenExamSelect = { nav.openExamSelect() },
            onOpenRecommended = { nav.openRecommended(); selected = "recommended" },
            onBack = requestClose,
        )
    }
    // 上一级窗口保持组合，作为下一级返回手势的背景（不是重画或截图）。
    if (nav.level == ExamEntryNavState.Level.EXAM_SELECT || nav.level == ExamEntryNavState.Level.EXAM_ARTICLES) {
        ExamLevelWindow(opening = opening, notice = null, onClose = { nav.back() }) { requestClose ->
            ExamSelectPage(
                examsState = shownExams,
                onRetry = { retry++ },
                scrollHolder = scrollHolder,
                onPickExam = { option ->
                    nav.openExam(option.key, option.title)
                    selected = option.key
                },
                onBack = requestClose,
            )
        }
    }
    if (nav.level == ExamEntryNavState.Level.EXAM_ARTICLES) {
        ExamLevelWindow(opening = opening, notice = null, onClose = { nav.back() }) { requestClose ->
            ArticleListPage(
                title = nav.selectedExamTitle.ifEmpty { "应试考试" },
                listState = shownList,
                filter = nav.filter,
                scrollKey = "list:${nav.listScrollKey ?: "none"}:v${nav.filterVersion}",
                scrollHolder = scrollHolder,
                onRetry = { retry++ },
                onFilterChanged = { next -> nav.updateFilter(next) },
                onOpenArticle = openArticle,
                onImportPersonal = importPersonal,
                onBack = requestClose,
            )
        }
    }
    if (nav.level == ExamEntryNavState.Level.RECOMMENDED) {
        ExamLevelWindow(opening = opening, notice = null, onClose = { nav.back() }) { requestClose ->
            ArticleListPage(
                title = "学习页推荐文章",
                listState = shownList,
                filter = nav.filter,
                scrollKey = "list:${nav.listScrollKey ?: "none"}:v${nav.filterVersion}",
                scrollHolder = scrollHolder,
                onRetry = { retry++ },
                onFilterChanged = { next -> nav.updateFilter(next) },
                onOpenArticle = openArticle,
                onBack = requestClose,
            )
        }
    }
    article?.let { current -> reader(current) { article = null; openId = null } }
    // 个人导入试卷：独立窗口叠在考试列表之上，返回露出上一页（考试列表）。
    paperDocId?.let { doc -> PaperReaderScreen(owner, doc, onClose = { paperDocId = null }) }
    // 本机导入/管理（复用「我的文章与书籍」的 PDF/EPUB/文本导入与 OCR 校对），
    // 预置分类标签＝当前考试，关闭后回到考试列表；Room Flow 会让新文章立即出现。
    if (importOpen) {
        MyReadingScreen(owner, onClose = { importOpen = false }, onOpen = {},
            reader = reader, initialTags = personalImportTag)
    }
}
/**
 * 一级导航页的独立窗口：自带进出场动画与跟手返回。
 *
 * 每级各自一个 Dialog 窗口，是「返回手势露出上一页背景」的关键：
 * 系统做预测性返回动画时移动的是**当前窗口**，它下面露出的就是上一个窗口的内容；
 * 同一窗口内切 `nav.level` 的话，露出的永远是这个窗口下面的英语 Hub。
 */
@Composable
private fun ExamLevelWindow(
    opening: Boolean,
    notice: String?,
    onClose: () -> Unit,
    content: @Composable (requestClose: () -> Unit) -> Unit,
) {
    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )
    Dialog(
        onDismissRequest = { motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false),
            color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                notice?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                }
                if (opening) LinearProgressIndicator(Modifier.fillMaxWidth())
                Box(Modifier.weight(1f)) { content(motion::requestClose) }
            }
        }
    }
}

/** 个人导入的文章按标签归入考试：只有明确写了四级/六级才出现，绝不猜测或反向冒充。 */
internal fun personalTagsMatchExam(tags: String, examKey: String): Boolean {
    val parts = tags.split("|").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    return when (examKey) {
        "CET4" -> parts.any { it.contains("四级") || it == "cet4" || it == "cet-4" }
        "CET6" -> parts.any { it.contains("六级") || it == "cet6" || it == "cet-6" }
        else -> false
    }
}

/**
 * Room 里的个人文章 → 考试列表行（只读本机）。
 *
 * 2026-10-18 任务B：**试卷与文章分开** ——
 * - 带 `试卷` 标签的整份文档 = **一个** entry（`paper:<documentId>`），打开原版翻页+逐页 OCR 校对，
 *   不再把 24 页拆成 24 条「可精读文章」（用户明确说这种「点一页读一页」很难用）；
 * - 普通文章仍按可读页/全文逐条展示。
 */
private fun personalExamRows(context: android.content.Context, owner: String,
    rows: List<MyReadingArticle>): Map<String, List<ReadingArticleRow>> {
    if (rows.isEmpty()) return emptyMap()
    val library = ReadingLibrary.of(context, owner)
    val result = mutableMapOf<String, MutableList<ReadingArticleRow>>()
    // 同一份导入文档（documentId）归一组；单个文章（无 documentId）自己一组。
    val groups = rows.groupBy { it.documentId ?: it.id }
        .entries.sortedByDescending { entry -> entry.value.maxOf { it.addedAt } }
    for ((docKey, groupRows) in groups) {
        val first = groupRows.sortedBy { it.chapterIndex }.firstOrNull() ?: continue
        val key = when {
            personalTagsMatchExam(first.tags, "CET4") -> "CET4"
            personalTagsMatchExam(first.tags, "CET6") -> "CET6"
            else -> continue
        }
        if (first.tags.split("|").contains("试卷")) {
            val pending = groupRows.count { !it.textAvailable }
            result.getOrPut(key) { mutableListOf() } += ReadingArticleRow(
                articleId = "paper:" + docKey,
                revision = "paper-" + docKey + "-" + pending,
                title = first.documentTitle ?: first.title,
                subtitle = "个人导入试卷 · ${groupRows.size} 页 · 待校对 $pending 页 · 仅本机",
                progress = null,
                cached = false,
                fullText = true,
                examMeta = null,
            )
            continue
        }
        for (row in groupRows.sortedByDescending { it.addedAt }) {
            if (!row.textAvailable || row.body.isBlank()) continue
            val displayTitle = row.documentTitle?.let { "$it · ${row.title}" } ?: row.title
            result.getOrPut(key) { mutableListOf() } += ReadingArticleRow(
                articleId = row.id,
                // 正文经过 OCR 校对后会变长，用长度参与 revision —— 列表 key 跟着变，旧滚动状态自然重置。
                revision = "personal-" + row.addedAt + "-" + row.body.length,
                title = displayTitle,
                subtitle = "个人导入 · 仅本机 · " + row.sourceNote.take(80),
                progress = library.articleProgress(row.id)?.ratio,
                cached = false,
                fullText = true,
                examMeta = null,
            )
        }
    }
    return result
}

/** 考试目录计数补上个人导入：有可精读全文时，「全文 0」不再显示成死路。 */
private fun mergePersonalExams(state: ExamCatalogState,
    personal: Map<String, List<ReadingArticleRow>>): ExamCatalogState {
    if (personal.isEmpty()) return state
    fun decorate(options: List<ExamOption>): List<ExamOption> {
        val known = options.map { it.key }.toSet()
        val merged = options.map { option ->
            val extra = personal[option.key]?.size ?: 0
            if (extra == 0) option else option.copy(
                articleCount = option.articleCount + extra,
                fullTextCount = option.fullTextCount + extra,
            )
        }
        val extraExams = personal.filterKeys { it !in known }.map { (key, rows) ->
            ExamOption(key,
                if (key == "CET4") "大学英语四级" else if (key == "CET6") "大学英语六级" else key,
                articleCount = rows.size, fullTextCount = rows.size)
        }
        return merged + extraExams
    }
    return when (state) {
        is ExamCatalogState.Content -> ExamCatalogState.Content(decorate(state.exams))
        // 在线目录失败时，本机导入的文章仍要能读：直接用个人文章组出考试目录。
        is ExamCatalogState.Failed -> ExamCatalogState.Content(decorate(emptyList()))
        ExamCatalogState.Loading -> state
    }
}

/** 文章列表补上个人导入：排在官方条目之前，失败态也能读本机文章。 */
private fun mergePersonalList(state: ArticleListState, selected: String?,
    personal: Map<String, List<ReadingArticleRow>>): ArticleListState {
    val extra = selected?.let { personal[it] }.orEmpty()
    if (extra.isEmpty()) return state
    return when (state) {
        is ArticleListState.Content -> ArticleListState.Content(extra + state.rows)
        is ArticleListState.Failed -> ArticleListState.Content(extra)
        ArticleListState.Loading -> state
    }
}
