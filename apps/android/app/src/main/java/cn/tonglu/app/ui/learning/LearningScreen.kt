package cn.tonglu.app.ui.learning

import cn.tonglu.app.ui.*
import androidx.activity.compose.BackHandler

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.*
import kotlinx.coroutines.launch

/**
 * 目录项。分类与文章都按整行懒加载，长标题和大字号可自然增高。
 *
 * 为什么不用 LazyVerticalGrid：这一页的头部（标题 / banner / 推荐 / 标签页 / 知识网络入口）
 * 全是整宽内容，混进网格要处处写 span，反而更难读。分组渲染成整宽 Row 一样是懒加载的。
 */
private sealed interface DirectoryItem {
    val key: String
    /** 面包屑：全部 › 编程与开发 › Python 入门，点任意一级都能跳回去。 */
    data class Crumb(val path: List<LearningSection>) : DirectoryItem {
        override val key = "crumb"
    }
    data class CardRow(val cards: List<LearningSection>) : DirectoryItem {
        override val key = "cards:" + cards.joinToString(",") { it.id }
    }
    data class Label(val text: String) : DirectoryItem {
        override val key = "label:$text"
    }
    data class Article(val article: LearningArticle) : DirectoryItem {
        override val key = "article:${article.id}"
    }
}

/**
 * 是不是「可读材料」——资料库的**浏览列表**只列它。
 *
 * ## 为什么要有这条（2026-10-15，task-7「四六级入口搬到考试日历页」）
 * 目录里有两篇**只做原站导航**的"资料"（`cet4-official-entry` / `cet6-official-entry`：
 * `kind = LINK`、`licenseId = LINK_ONLY`、正文为空，点了就是跳 `cet.neea.edu.cn`）。
 * 用户的诉求是「把四六级…的入口直接放在考试页面，而不是放在资料库里面」，
 * 所以这类导航条不再出现在资料库的浏览列表里 —— 资料库只留能读的东西。
 *
 * 🔴 **数据一条没删**：收藏、搜索命中、上次阅读/续读卡照旧能打开它们（深链不破）。
 * 判据只用客户端真有的字段（`LearningArticle` 没有 `exam` 字段，Gson 会忽略未知键），
 * 全库今天正好只有这两篇命中 —— 见 `work/exam-entry-move-20261015/tools/catalog_probe.py`。
 */
private fun isReadableMaterial(article: LearningArticle): Boolean =
    !(article.kind == "LINK" && article.licenseId == "LINK_ONLY" && article.body.isBlank())

/**
 * 建目录项。三种视图：
 *   - 搜索中：平铺命中结果，不分层；
 *   - 根视图（focus 为空）：根分类的双列卡片；
 *   - 分类视图：面包屑 + 子分类卡片 + 本分类的文章。
 */
private fun directoryItems(
    catalog: LearningCatalog,
    focus: LearningSection?,
    path: List<LearningSection>,
    query: String,
): List<DirectoryItem> {
    val q = query.trim()
    val items = mutableListOf<DirectoryItem>()

    if (q.isNotBlank()) {
        val hit = catalog.articles.filter { article ->
            listOf(
                article.title, article.summary, article.author,
                catalog.sections.firstOrNull { it.id == article.sectionId }?.title.orEmpty(),
                article.tagList.joinToString(" "),
                article.body.take(2000),
            ).any { it.contains(q, true) }
        }
        return hit.map { DirectoryItem.Article(it) }
    }

    // 根视图不画面包屑（只有「全部」一个胶囊没有意义），从二级开始才需要「怎么退回去」
    if (path.isNotEmpty()) items += DirectoryItem.Crumb(path)

    // 一个只剩导航条的分类（入口搬走后的「四级」「六级」）不再显示成空卡片：
    // 资料库的分类卡要么有可读材料，要么就不出现。
    val children = catalog.sections
        .filter { it.parentId == focus?.id && hasReadableMaterial(catalog, it.id) }
        .sortedBy { it.order }
    if (children.isNotEmpty()) {
        if (focus != null) items += DirectoryItem.Label("子分类")
        children.chunked(1).forEach { items += DirectoryItem.CardRow(it) }
    }

    val articles = catalog.articles
        .filter { it.sectionId == (focus?.id ?: return@filter false) && isReadableMaterial(it) }
        .sortedBy { it.order }
    if (articles.isNotEmpty()) {
        items += DirectoryItem.Label("${articles.size} 篇资料")
        articles.forEach { items += DirectoryItem.Article(it) }
    }
    return items
}

/** 某分类自己 + 所有后代分类的 id。 */
private fun sectionIds(catalog: LearningCatalog, sectionId: String): Set<String> {
    val ids = mutableSetOf<String>()
    fun collect(id: String) {
        if (!ids.add(id)) return
        catalog.sections.filter { it.parentId == id }.forEach { collect(it.id) }
    }
    collect(sectionId)
    return ids
}

/** 某分类下（含所有子分类）的**可读材料**总数 —— 卡片上要显示。 */
private fun countArticles(catalog: LearningCatalog, sectionId: String): Int {
    val ids = sectionIds(catalog, sectionId)
    return catalog.articles.count { it.sectionId in ids && isReadableMaterial(it) }
}

/**
 * 这个分类（含所有后代分类）里还有没有可读材料。
 *
 * 没有的分类整张卡片都不出现 —— 否则四六级入口搬走后，资料库里会留下
 * 两张「0 篇」的空卡片，等于又把考试的名字摆在了资料库里。
 */
private fun hasReadableMaterial(catalog: LearningCatalog, sectionId: String): Boolean {
    val ids = sectionIds(catalog, sectionId)
    return catalog.articles.any { it.sectionId in ids && isReadableMaterial(it) }
}

/**
 * 某分类的平均阅读进度（0~1）。
 *
 * 用「所有资料各自进度的平均值」而不是「读完的篇数占比」：只读了一半也算数，
 * 卡片上那根细蓝条才有变化感。分类里没有资料时返回 0。
 */
private fun sectionProgress(catalog: LearningCatalog, library: ReadingLibrary, sectionId: String): Float {
    val ids = sectionIds(catalog, sectionId)
    val articles = catalog.articles.filter { it.sectionId in ids && isReadableMaterial(it) }
    if (articles.isEmpty()) return 0f
    return articles.sumOf { (library.articleProgress(it.id)?.score ?: 0f).toDouble() }.toFloat() / articles.size
}

@Composable
internal fun LearningScreen(
    search: String, owner: String, topInset: Dp = 0.dp,
    onClearSearch: () -> Unit = {},
    visible: Boolean = true,
    aiRequest: Int = 0, signedIn: Boolean = false,
    onAsk: suspend (String, List<LearningExcerpt>, String) -> LearningAnswer,
    onTranslate: suspend (String?, List<String>) -> LearningBlockTranslation,
    /** 拉一次在线正文。返回 true 表示本机正文变了，这里会重新读一遍目录。 */
    onSyncFeed: suspend () -> Boolean = { false },
    /** 进度 / 书签 / 笔记有变动。上层用它做防抖后上传云端。 */
    onReadingChanged: () -> Unit = {},
    /** 做题作答记录有变动（题库题 / 材料题都算）。上层用它做防抖后上传云端（T5）。 */
    onPracticeChanged: () -> Unit = {},
    /** 本次阅读停留秒数（plan/66 学习线）。只传秒数，不传读了哪篇。 */
    onReadingSeconds: (Int) -> Unit = {},
    /** 首次学会一个单词（plan/66 学习线 words.learned）。 */
    onWordLearned: () -> Unit = {},
    /** 把一条笔记发到讨论区（从「我的阅读 → 笔记」里点发布）。 */
    onPublishNote: ((title: String, body: String) -> Unit)? = null,
    /**
     * 从广场帖子的资料卡「加入学习」后直接打开某份精听材料（47 §3 P4）。
     * 值变化（非空）时进精听工作区；用完由上层清回 null —— 不清的话用户返回学习页
     * 会被立刻再拉进精听，出不去。
     */
    openListeningMaterial: String? = null,
    /** 上面那个 id 已被消费，请求上层清空。 */
    onListeningMaterialOpened: () -> Unit = {},
    /**
     * 考试日历页入口（`docs/plan/54` M6）。**入口放在学习页而不是首页**：M2 的硬约束要求
     * 「关掉倒计时开关后首页不留任何痕迹」，所以整页的入口也跟着挪到这里。
     */
    onOpenExamCalendar: () -> Unit = {},
    onOpenCompetitions: () -> Unit = {},
    /**
     * 从考试详情「去备考」过来时，直接打开英语中心的哪个 route（"home"/"words"/"listening"）。
     * 与 openListeningMaterial 同一套路：非空即消费，用完立刻回调清空。
     */
    examStudyRoute: String? = null,
    /** 上面那个 route 已被消费，请求上层清空。 */
    onExamStudyRouteOpened: () -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember(owner) { ReadingStore(context, owner) }
    var catalog by remember { mutableStateOf<LearningCatalog?>(null) }
    var loadError by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var libraryOpen by rememberSaveable(owner) { mutableStateOf(false) }
    var personalOpen by rememberSaveable(owner) { mutableStateOf(false) }
    val homeListState = rememberLazyListState()
    val libraryListState = rememberLazyListState()
    var dialog by rememberSaveable(stateSaver = LearningDialogSaver) { mutableStateOf<LearningDialog>(LearningDialog.None) }
    // Every dismissal and replacement passes through this exit, including child layers.
    val closeDialog: (LearningDialog) -> Unit = { next -> OverlayDismissGuard.arm(); dialog = next }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    /** 进入分类的路径（分类 id 栈）。空 = 目录根视图，显示根分类网格卡片。 */
    var focusPath by rememberSaveable { mutableStateOf(listOf<String>()) }
    /** 进度 / 书签 / 笔记变动后 +1，用来让卡片重算（这些数据不走 Compose 状态）。 */
    var libraryRevision by remember { mutableIntStateOf(0) }
    var favorites by remember(owner) { mutableStateOf(store.favorites) }
    var lastArticle by remember(owner) { mutableStateOf(store.lastArticle) }
    var handledAiRequest by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(aiRequest) { if (aiRequest > handledAiRequest) { handledAiRequest = aiRequest; dialog = LearningDialog.Ai() } }
    // 广场资料卡加入学习后过来：直接进精听工作区的那份材料。
    // 打开后立刻回调清空请求 —— 否则用户从精听返回学习页时又会被拽回去，等于卡住。
    LaunchedEffect(openListeningMaterial) {
        val id = openListeningMaterial ?: return@LaunchedEffect
        dialog = LearningDialog.EnglishHub(route = "listening", initialMaterialId = id)
        onListeningMaterialOpened()
    }
    // 考试详情「去备考」过来：直接落在英语中心的对应分页（四六级是同一个词库，用 words）。
    // 同样立刻回调清空 —— 不清的话从英语中心返回学习页又会被拽进去。
    LaunchedEffect(examStudyRoute) {
        val route = examStudyRoute ?: return@LaunchedEffect
        dialog = LearningDialog.EnglishHub(route = route)
        onExamStudyRouteOpened()
    }
    LaunchedEffect(reload) {
        loadError = false
        try {
            catalog = LearningRepository.load(context)
            // 先把内容显示出来（打包的正文 + 上次缓存的在线正文），再去问服务端有没有新版。
            // 有新版才重新读目录 —— 这一趟是网络请求，不能让首屏等它。
            if (onSyncFeed()) catalog = LearningRepository.load(context)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            loadError = true
        }
    }
    val data = catalog ?: LearningCatalog.Empty
    val catalogQuery = search
    val focus = data.sections.firstOrNull { it.id == focusPath.lastOrNull() }
    val focusTrail = focusPath.mapNotNull { id -> data.sections.firstOrNull { it.id == id } }
    // 目录结构变了（比如换了个目录）就退回根视图，免得停在一个不存在的分类上
    LaunchedEffect(data, focusPath) {
        if (focusPath.isNotEmpty() && data.sections.none { it.id == focusPath.last() }) focusPath = emptyList()
    }
    val items = remember(data, focusPath, catalogQuery, tab, favorites, libraryRevision) {
        if (tab == 0 || catalogQuery.isNotBlank()) directoryItems(data, focus, focusTrail, catalogQuery)
        else data.articles.filter { a -> (tab != 2 || a.id in favorites) &&
            // 「最近收录」是浏览列表 → 只列可读材料；「收藏」是用户自己存下来的（深链）→ 不过滤。
            (tab == 2 || isReadableMaterial(a)) &&
            (search.isBlank() || listOf(a.title, a.summary, a.author, a.body.take(1500))
                .any { it.contains(search.trim(), true) }) }
            .sortedByDescending { it.updatedAt }.map { DirectoryItem.Article(it) }
    }
    LaunchedEffect(focusPath, tab, catalogQuery) { libraryListState.scrollToItem(0) }
    val open: (LearningArticle) -> Unit = { article ->
        store.recordOpen(article); libraryRevision++
        dialog = LearningDialog.Reader(article.id)
        lastArticle = article.id; store.lastArticle = article.id
    }
    // 个人书架观察元数据，续读只取最后一章，避免学习主页加载全部书籍的正文。
    val personalRepository = remember(owner) { cn.tonglu.app.data.english.EnglishRepository(context) }
    val personalScope = rememberCoroutineScope()
    val myShelf by remember(owner) { personalRepository.observeMyShelf(owner) }.collectAsState(initial = emptyList())
    var personalLast by remember(owner, lastArticle) { mutableStateOf<LearningArticle?>(null) }
    LaunchedEffect(owner, lastArticle, myShelf.firstOrNull { it.id == lastArticle }?.addedAt) {
        personalLast = null
        if (lastArticle.startsWith("my-")) {
            try { personalLast = personalRepository.myArticle(lastArticle, owner)?.let(personalRepository::toLearningArticle) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (cause: Exception) { android.widget.Toast.makeText(context, cause.message ?: "个人续读记录读取失败", android.widget.Toast.LENGTH_LONG).show() }
        }
    }
    val lastArticleObj = data.articles.firstOrNull { it.id == lastArticle } ?: personalLast
    val browsing = libraryOpen || search.isNotBlank()
    fun closeLibrary() {
        if (search.isNotBlank()) onClearSearch()
        else if (tab == 0 && focusPath.isNotEmpty()) focusPath = focusPath.dropLast(1)
        else libraryOpen = false
    }
    BackHandler(enabled = visible && browsing && dialog == LearningDialog.None && !personalOpen) { closeLibrary() }
    // 个人书架始终保留在阅读器下面。读完或返回只关闭正文，不跳进英语或重建书架。
    if (personalOpen) MyReadingScreen(
        owner = owner,
        onClose = { OverlayDismissGuard.arm(); personalOpen = false },
        onOpen = open,
        onReadingHistory = { dialog = LearningDialog.Reading },
    )
    LearningDialogs(
        dialog = dialog,
        // 记录页只需要标题元数据；个人正文仍由阅读器按当前账号和章节单独读取。
        catalog = if (dialog == LearningDialog.Reading || dialog is LearningDialog.NoteEditor || dialog is LearningDialog.DeleteReading)
            data.copy(articles = data.articles + myShelf.map(personalRepository::toLearningArticle)) else data,
        store = store, favorites = favorites,
        lastArticle = lastArticleObj, search = search, owner = owner, signedIn = signedIn,
        onAsk = onAsk, onTranslate = onTranslate, onPublishNote = onPublishNote,
        onShow = { dialog = it }, onClose = closeDialog,
        onOpen = { target ->
            personalScope.launch {
                try {
                    val article = data.articles.firstOrNull { it.id == target.articleId }
                        ?: personalRepository.myArticle(target.articleId, owner)?.let(personalRepository::toLearningArticle)
                        ?: error("文章不存在或不属于当前账号")
                    closeDialog(target)
                    store.recordOpen(article); libraryRevision++
                    lastArticle = article.id; store.lastArticle = article.id
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (cause: Exception) { android.widget.Toast.makeText(context, cause.message ?: "文章无法打开", android.widget.Toast.LENGTH_LONG).show() }
            }
        },
        onFavorite = { id, enabled -> store.favorite(id, enabled); favorites = store.favorites },
        onLibraryChanged = { libraryRevision++; lastArticle = store.lastArticle; onReadingChanged() },
        onPracticeChanged = onPracticeChanged,
        onReadingSeconds = onReadingSeconds,
        onWordLearned = onWordLearned,
    )

    if (!browsing) {
        val recentProgress = lastArticleObj?.let { store.library.articleProgress(it.id) }
        LearningHome(
            listState = homeListState, topInset = topInset,
            personalCount = myShelf.map { it.documentId ?: it.id }.distinct().size,
            publicCount = catalog?.articles?.count(::isReadableMaterial),
            recent = lastArticleObj, progress = recentProgress?.score ?: 0f,
            finished = recentProgress?.finished == true,
            onPersonal = { personalOpen = true }, onLibrary = { libraryOpen = true },
            onContinue = { lastArticleObj?.let(open) },
            onEnglish = { dialog = LearningDialog.EnglishHub() },
            onFocus = { cn.tonglu.app.ui.focus.PomodoroUi.open() },
            onExams = onOpenExamCalendar, onCompetitions = onOpenCompetitions,
        )
        return
    }
    LazyColumn(
        state = libraryListState,
        modifier = Modifier.fillMaxSize().learningScrollViewport(topInset),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topInset + 8.dp, bottom = 116.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "library-heading") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { closeLibrary() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级") }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("书库", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("让好内容，离你近一点", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                }
            }
        }
        if (catalogQuery.isBlank()) item(key = "tabs") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("分类", "最近收录", "收藏").forEachIndexed { index, title ->
                    FilterChip(selected = tab == index, onClick = { tab = index; focusPath = emptyList() }, label = { Text(title) })
                }
            }
        }
        item("results-heading") {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (catalogQuery.isNotBlank()) "搜索结果" else focus?.title ?: listOf("按兴趣探索", "最近收录", "我的收藏")[tab],
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(if (catalogQuery.isNotBlank() || tab != 0) "${items.count { it is DirectoryItem.Article }} 篇"
                    else "${focus?.let { countArticles(data, it.id) } ?: data.articles.count(::isReadableMaterial)} 篇资料",
                    style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
            }
        }
        if (loadError) item("load-error") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (catalog == null) "书库暂时无法读取" else "在线更新失败，当前显示本机书库",
                    Modifier.weight(1f), color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { reload++ }) { Text("重试") }
            }
        }
        if (catalog == null) item(key = "load") {
            if (!loadError) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
        }
        items(items, key = { it.key }, contentType = { it::class }) { item ->
            when (item) {
                is DirectoryItem.Crumb -> CrumbBar(
                    trail = item.path,
                    onJump = { depth -> focusPath = focusPath.take(depth) },
                )
                is DirectoryItem.CardRow -> Row(
                    Modifier.fillMaxWidth().animateItem().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item.cards.forEach { section ->
                        SectionCard(
                            section = section,
                            articleCount = countArticles(data, section.id),
                            childCount = data.sections.count { it.parentId == section.id },
                            progress = sectionProgress(data, store.library, section.id),
                            modifier = Modifier.weight(1f),
                            onClick = { focusPath = focusPath + section.id },
                        )
                    }

                }
                is DirectoryItem.Label -> Text(
                    item.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
                is DirectoryItem.Article -> ArticleRow(
                    article = item.article,
                    library = store.library,
                    modifier = Modifier.animateItem(),
                    onOpen = { open(item.article) },
                    markAsRecentlyRead = item.article.id == lastArticleObj?.id,
                )
            }
        }
        if (catalog != null && items.none { it is DirectoryItem.Article || it is DirectoryItem.CardRow }) item(key = "empty") {
            LibraryEmpty(if (catalogQuery.isNotBlank()) "没有找到相关资料" else if (tab == 2) "把喜欢的内容留下来" else "这里还没有资料",
                if (catalogQuery.isNotBlank()) "换个标题、作者或关键词试试" else if (tab == 2) "阅读时点收藏，下次就能在这里找到。" else "先看看其他分类吧。")
        }
        if (catalogQuery.isBlank() && focusPath.isEmpty()) item("library-tools") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { dialog = LearningDialog.Updates }) { Text("资料更新") }
                TextButton(onClick = { dialog = LearningDialog.Graph }) { Text("知识网络") }
            }
        }
    }
}

@Composable
private fun CrumbBar(trail: List<LearningSection>, onJump: (Int) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 参数是「保留几层」：点「全部」= take(0)，点第 i 个分类 = take(i + 1)
        CrumbChip("全部", selected = trail.isEmpty(), color = MaterialTheme.colorScheme.primary) { onJump(0) }
        trail.forEachIndexed { index, section ->
            Text("›", color = muted.copy(alpha = 0.55f), fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 3.dp))
            CrumbChip(section.title, selected = index == trail.lastIndex, color = section.sectionColor) {
                onJump(index + 1)
            }
        }
    }
}

@Composable
private fun CrumbChip(text: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (selected) color.copy(alpha = 0.14f) else Color.Transparent,
        modifier = Modifier.pressable(scale = 0.94f, onClick = onClick),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** 分类使用整行，长标题和大字号也能自然展开。 */
@Composable
private fun SectionCard(section: LearningSection, articleCount: Int, childCount: Int, progress: Float,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    val icon = when {
        section.title.contains("编程") -> Icons.Outlined.Code
        section.title.contains("计算机") -> Icons.Outlined.Computer
        section.title.contains("英语") -> Icons.Outlined.Translate
        section.title.contains("生活") -> Icons.Outlined.Coffee
        else -> Icons.Outlined.AutoStories
    }
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(22.dp),
        color = learningCardColor(AppColors.SurfaceOpaque)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(icon, null, Modifier.padding(13.dp).size(25.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(section.description, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$articleCount 篇资料" + if (childCount > 0) " · $childCount 个分类" else "",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (progress > .005f) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp))
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = AppColors.TextSecondary)
        }
    }
}

@Composable
private fun ArticleRow(article: LearningArticle, library: ReadingLibrary, modifier: Modifier,
    onOpen: () -> Unit, markAsRecentlyRead: Boolean = false) {
    val context = LocalContext.current
    val record = library.articleProgress(article.id)
    val score = record?.score ?: 0f
    val finished = library.finishedCount(article.id) > 0
    Surface(modifier, color = learningCardColor(AppColors.SurfaceOpaque), shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LibraryCover(article.title, if (article.kind == "LINK") "导航" else "文章")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (markAsRecentlyRead) Text("上次阅读", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(article.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (article.summary.isNotBlank()) Text(article.summary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                Text("${article.author} · " + if (article.kind == "LINK") "原文导航" else "约 ${maxOf(1, article.body.length / 400)} 分钟",
                    style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                if (score > .005f || finished) {
                    Text(if (finished) "已读完" else "已读 ${(score * 100).toInt()}%", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(progress = { if (finished) 1f else score.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(3.dp))
                }
            }
            if (article.sourceUrl.isNotBlank()) IconButton(onClick = { copyArticleSource(context, article) }) {
                Icon(Icons.Outlined.ContentCopy, if (article.isGitHub) "复制 GitHub 原文链接" else "复制原文链接",
                    tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** All learning cards share the same frost/opacity when a wallpaper is enabled. */
internal fun learningCardColor(fallback: Color, tint: Color? = null): Color {
    if (!cn.tonglu.app.ui.theme.ThemeCanvas.wallpaperActive) return fallback
    val base = cn.tonglu.app.ui.AppColors.SurfaceOpaque
    val tinted = if (tint == null) base else androidx.compose.ui.graphics.lerp(base, tint, 0.05f)
    return cn.tonglu.app.ui.theme.ThemeCanvas.resolveCard(tinted, cn.tonglu.app.ui.AppTheme.darkState)
}
