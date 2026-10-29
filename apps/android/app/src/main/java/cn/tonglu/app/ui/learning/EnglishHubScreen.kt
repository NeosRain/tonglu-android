package cn.tonglu.app.ui.learning

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.unit.sp
import cn.tonglu.app.ui.AppColors
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.LearningArticle
import cn.tonglu.app.data.ReadingLibrary
import cn.tonglu.app.data.ReadingProgressRecord
import cn.tonglu.app.data.ReadingStore
import cn.tonglu.app.ui.EnglishStudyScreen
import cn.tonglu.app.ui.english.books.PersonalBooksScreen
import cn.tonglu.app.ui.english.books.toLearningArticle
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import cn.tonglu.app.ui.listening.ListeningScreen
import cn.tonglu.app.ui.speech.VoiceSettingsScreen
import kotlinx.coroutines.flow.first

/**
 * 英语唯一一级入口（47 §2 / 48 §1、§3）。
 *
 * 首页以四六级精读、继续阅读和个人文章为主，单词等能力收进「学习工具」。
 * 复用原能力屏，不显示尚未实现的假入口。
 * 返回链：能力屏返回 → 回英语首页；英语首页返回 → 回学习页。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EnglishHubScreen(
    owner: String,
    initialRoute: String = "home",
    onClose: () -> Unit,
    /** 进来就直接打开的精听材料（47 §3 P4：从广场资料卡加入后直接打开）。 */
    initialMaterialId: String? = null,
    /** 做题作答记录有变动（T5）：由题库页冒泡上来，交给上层防抖推云端。 */
    onPracticeChanged: () -> Unit = {},
    /** 首次学会一个单词（成长等级学习线 words.learned，plan/66 §2.3）。 */
    onWordLearned: () -> Unit = {},
    /**
     * 首页「今日文章」卡用：最近在读的那篇。null = 本机还没有读过任何文章。
     *
     * 为什么由上层传而不是这里自己查：文章目录（`LearningCatalog`）归资料区，
     * Hub 是它的下游。Hub 自己再去读一遍目录，就会有两份「当前文章」的口径。
     */
    lastArticle: LearningArticle? = null,
    /** 那篇文章的阅读进度（0~1）。null = 没有进度记录，按钮显示「开始精读」而不是「继续阅读」。 */
    lastArticleRatio: Float? = null,
    materialReader: @Composable (LearningArticle, () -> Unit) -> Unit = { _, _ -> },
) {
    // route: "home" = 英语首页；"words" = 单词；"favorites" = 我的收藏（单词页带收藏筛选进来）；
    // "listening" = 听说；"voice" = 声音设置；
    // "questions" = 独立题库（B4）；"daily" = 每日例句（B5）；"plan" = 学习计划（B5）。
    var route by rememberSaveable(owner) { mutableStateOf(initialRoute) }
    // 阅读器只覆盖当前英语页面；保留 Hub 和能力屏的来源、滚动及输入状态。
    val context = androidx.compose.ui.platform.LocalContext.current
    val hubStore = remember(owner) { ReadingStore(context, owner) }
    // Hub 自己记住「刚从这一页打开/读完的文章」，不依赖外层下一次重组才刷新主动作卡。
    var hubReading by remember(owner) { mutableStateOf<HubReading?>(null) }
    fun progressOf(article: LearningArticle): ReadingProgressRecord? =
        runCatching { ReadingLibrary.of(context, owner).articleProgress(article.id) }.getOrNull()
    fun trackArticle(article: LearningArticle) {
        val record = progressOf(article)
        hubReading = HubReading(article, record?.ratio, record?.finished ?: false)
    }
    fun refreshTrackedArticle(article: LearningArticle) = trackArticle(article)
    var readingArticle by remember(owner) { mutableStateOf<LearningArticle?>(null) }
    val openArticle: (LearningArticle) -> Unit = { article ->
        trackArticle(article)
        readingArticle = article
    }

    // 查词非空 = 叠一个查词子层。**关掉只清这一个状态，route 不动** ——
    // 于是从「听说 / 每日例句 / 单词」点词进来，返回时那一屏连同它的进度都还在。
    // 以前是冒泡给 LearningDialogs 换成顶层 English 层，那会把整个 Hub 卸载（FINDINGS #6）。
    var lookupQuery by remember(owner) { mutableStateOf<String?>(null) }

    // 🔴 查词子层**只在能力屏存在时才被渲染**（见函数末尾的 `if (abilityScreen != null)`），
    //    但 `lookupQuery` 不会跟着 route 走 —— 一旦它非空而能力屏已经关掉，就会留下一个
    //    「看不见、却还活着」的子层：下次进能力屏它先冒出来，并且**先吃掉返回**，
    //    表现就是用户报的「点了返回退到英语界面后，既退不出去、也点不进去」。
    //    所以 route 一回到首页就把这个子层状态清掉，别让它跨层残留。
    LaunchedEffect(route) { if (route == "home") lookupQuery = null }

    // 计划生成要用「真实的题组名与词书名」，不能拿「任务1/任务2」占位。
    // 这里异步取一次本机清单 —— 取不到就传空，模板会退化成不带名字的通用任务（而不是崩）。
    var planBankTitles by remember(owner) { mutableStateOf<List<String>>(emptyList()) }
    var planBookName by remember(owner) { mutableStateOf<String?>(null) }
    var diagnosisBanks by remember(owner) { mutableStateOf<List<cn.tonglu.app.data.question.QuestionBank>>(emptyList()) }
    var diagnosisError by remember(owner) { mutableStateOf<String?>(null) }
    LaunchedEffect(owner) {
        try {
            diagnosisBanks = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                cn.tonglu.app.data.question.QuestionBankStore.banks(context)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (cause: Exception) { diagnosisError = "题组读取失败：${cause.localizedMessage ?: "请重新进入"}" }
    }
    LaunchedEffect(owner) {
        planBankTitles = runCatching {
            cn.tonglu.app.data.question.QuestionBankStore.banks(context).map { it.title }
        }.getOrDefault(emptyList())
        planBookName = runCatching {
            val repository = cn.tonglu.app.data.english.EnglishRepository(context)
            val settings = repository.dao.settings(owner).first()
            val id = settings?.bookId ?: "kylebing-cet4"
            cn.tonglu.app.data.english.EnglishBookStore.catalog(context, null).firstOrNull { it.id == id }?.name
        }.getOrNull()
    }

    // 主动作卡的数据：优先用本次在 Hub 内打开/读完的那篇，其次才是外层传入的最近阅读。
    // 以前只认外层值：从考试/推荐/我的文章打开时不会更新 store.lastArticle，读完后
    // 「继续英语阅读」还停在旧百分比或空态，用户说「读完了它也用不了」。
    val cardArticle = hubReading?.article ?: lastArticle
    // finished 不拿 ratio 猜：读到底时 ratio 仍在 0.9x，只有 ReadingLibrary 的 finished 才是真完成。
    val storedProgress by produceState<ReadingProgressRecord?>(
        initialValue = null,
        cardArticle?.id, hubReading?.ratio, hubReading?.finished, owner,
    ) {
        val id = cardArticle?.id ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { ReadingLibrary.of(context, owner).articleProgress(id) }.getOrNull()
        }
    }
    val cardRatio = if (hubReading != null) {
        hubReading?.ratio ?: storedProgress?.ratio
    } else {
        storedProgress?.ratio ?: lastArticleRatio
    }
    val cardFinished = hubReading?.finished == true || storedProgress?.finished == true

    // 处在能力屏时，**Hub 照常画在下面**，能力屏叠在它上面（见函数末尾）。
    //
    // 2026-10-08 改了这里：原来写的是
    //     if (abilityScreen != null) { abilityScreen(); …; return }
    // —— 能力屏一开，Hub 首页**整个不组合**，于是能力屏往右滑时底下什么都没有，
    // 用户看到的是主题底色，报「无法显示上一页」。那和第 2 项（TongluApp 的提前 return）
    // 是**同一个病**，只是发生在 Dialog 这一层。
    //
    // 现在 Hub 一直组合着，能力屏是它**上面**的一层：滑开就能看见 Hub。
    // 顺带的好处是 route 收回 "home" 时 Hub 的滚动位置原样保留（原本就是这么设计的）。
    val abilityScreen: (@Composable () -> Unit)? = when (route) {
        "today" -> { { EnglishStudyScreen(owner, startOnReady = true, directSessionExit = true, onWordLearned = onWordLearned, onClose = { route = "home" }) } }
        "wordSettings" -> { { EnglishStudyScreen(owner, onWordLearned = onWordLearned, onClose = { route = "home" }) } }
        "readingHistory" -> { { cn.tonglu.app.ui.english.intensive.ReadingHistoryScreen(owner, onClose = { route = "home" }) } }
        "words" -> { { EnglishStudyScreen(owner, initialQuery = "", startOnReady = true, directSessionExit = true, onWordLearned = onWordLearned, onClose = { route = "home" }) } }
        // 单开一条「收藏」路由，而不是让用户自己进单词页再点「收藏」筛选 ——
        // 用户问过「收藏是干什么的、在哪看」，说明入口不可发现（2026-10-08）。
        "favorites" -> { {
            EnglishStudyScreen(owner, initialQuery = "", initialFilter = "favorites",
                onWordLearned = onWordLearned, onClose = { route = "home" })
        } }
        "listening" -> { {
            ListeningScreen(owner, onClose = { route = "home" },
                onLookupWord = { word -> lookupQuery = word },
                initialMaterialId = initialMaterialId)
        } }
        "voice" -> { { VoiceSettingsScreen(owner, onClose = { route = "home" }) } }
        "tools" -> { { EnglishAuxiliaryTools(onSelect = { route = it }, onClose = { route = "home" }) } }
        "questions" -> { {
            QuestionBankScreen(owner, onClose = { route = "home" }, onPracticeChanged = onPracticeChanged)
        } }
        // 今日卡的第四个入口（plan §5 第 2 条）：直达「只练错题」。
        // 以前只能 做题 → 选题组 → 组内右上角，三级里才找得到 = 等于没有入口。
        "wrong" -> { {
            QuestionBankScreen(owner, onClose = { route = "home" },
                onPracticeChanged = onPracticeChanged, wrongReview = true)
        } }
        "daily" -> { {
            DailySentencePane(owner, onClose = { route = "home" },
                onLookupWord = { word -> lookupQuery = word })
        } }
        "plan" -> { {
            LearningPlanPane(owner, bankTitles = planBankTitles, bookName = planBookName,
                onClose = { route = "home" })
        } }
        "diagnosis" -> { {
            VocabDiagnosisPane(banks = diagnosisBanks,
                store = cn.tonglu.app.data.listening.PracticeStore.of(context, owner),
                onExit = { route = "home" }, onAnswered = onPracticeChanged)
        } }
        // §4 入口改造（2026-10-13）：专项能力不再平铺在首页，收进这一屏。
        // 首页只留「一条主线（继续读文章）+ 今日词汇 + 三个次级入口」——
        // 功能一个没删，每一项仍然通向原来那一屏，只是不再跟主动作抢第一眼。
        "more" -> { {
            EnglishMoreToolsPane(
                diagnosisProblem = diagnosisError,
                onDaily = { route = "daily" },
                onPlan = { route = "plan" },
                onDiagnosis = { route = "diagnosis" },
                onClose = { route = "home" })
        } }
        // 「我的精读文章」（2026-10-13）：用户自己添加卷子里的四级/六级阅读文章。
        // 精读链路（查词/加入今日/收藏/恢复）复用已验过的部分，这里只管文章的增删与打开。
        "myReading" -> { {
            MyReadingScreen(owner, onClose = { route = "home" }, onOpen = openArticle,
                reader = { article, close ->
                    // 自建文章读完后同样立刻刷新 Hub 的主动作卡（不必等退出英语页再进）。
                    materialReader(article) {
                        close()
                        refreshTrackedArticle(article)
                    }
                })
        } }
        // 个人词书（EF04）：B 的 PersonalBooksScreen —— 词书管理 / 批量删除 / 导入 / 拍照识词 /
        // 词书生成文章都从这一个入口进，不再各画空壳页。
        "myBooks" -> { {
            PersonalBooksScreen(owner, onClose = { route = "home" },
                // EF10 收口：保存的文章转成 LearningArticle 后走与「我的精读文章」同一条原有阅读器通路
                // （materialReader → LearningDialogs.Reader → ArticleReader），点词/进度/逐层返回都复用。
                onOpenArticle = { record -> openArticle(record.toLearningArticle()) })
        } }
        "intensive" -> { {
            cn.tonglu.app.ui.english.intensive.IntensiveReadingScreen(owner, onClose = { route = "home" },
                onLookup = { lookupQuery = it })
        } }
        else -> null
    }

    // 弹层自识别左边缘拖动：Dialog 是**独立 Window**，系统把手势进度派给 Activity，
    // 这里只能拿到「已经返回完」那一次回调 —— 想跟手就得自己认。
    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
        backEnabled = abilityScreen == null && readingArticle == null,
    )
    Dialog(
        onDismissRequest = { if (abilityScreen != null) route = "home" else motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false,
            backEnabled = abilityScreen == null && readingArticle == null), color = AppColors.CanvasOpaque) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { motion.requestClose() }) {   // 走 motion：与返回键同一条退场动画
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回学习页")
                    }
                    Text("英语", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    // 48 §6：声音设置放右上角。只在这里设入口，不铺第二个假开关。
                    TextButton(onClick = { route = "voice" }) { Text("声音设置") }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item("intro") {
                        Column(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("读懂一篇英语", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
                            Text("从四六级阅读开始，逐段理解，逐句精读。", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                        }
                    }
                    item("english-destinations") {
                        EnglishDestinationPager(onReading = { route = "intensive" }, onWords = { route = "words" })
                    }
                    item("word-tools") {
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { route = "wordSettings" }) { Text("单词设置") }
                            TextButton(onClick = { route = "myBooks" }) { Text("我的单词书") }
                            TextButton(onClick = { route = "readingHistory" }) { Text("精读历史") }
                        }
                    }
                    item("reading") {
                        Text("继续阅读", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 12.dp))
                        EnglishReadingCard(article = cardArticle, ratio = cardRatio, finished = cardFinished,
                            onOpen = openArticle, onChoose = { route = "intensive" })
                    }
                    item("library") {
                        EnglishEntryCard(Icons.Outlined.MenuBook, "我的文章与书籍", "导入 PDF、EPUB 或文本", { route = "myReading" })
                    }
                    item("tools") {
                        EnglishEntryCard(Icons.Outlined.Widgets, "学习工具", "听说、练习与其他辅助工具", { route = "tools" })
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    // 能力屏：画在 Hub **之上**（不是把它替换掉）。
    // ⚠️ 顺序要紧：Dialog 是独立 Window，**后进组合的后加进 WindowManager，所以在上层**。
    // 把这段挪到上面去，用户就又看不到上一页了 —— 而且不会有任何编译错误。
    if (abilityScreen != null) {
        abilityScreen()
        // 查词是**叠在能力屏之上**的子层（照 Reader/Ai 的做法），底下那一屏保持存活。
        // key 保证换一个词就是一份全新界面 —— 否则 rememberSaveable 会把上一个词残留下来。
        lookupQuery?.let { word ->
            androidx.compose.runtime.key(word) {
                EnglishStudyScreen(owner, initialQuery = word, onWordLearned = onWordLearned, onClose = { lookupQuery = null })
            }
        }
    }
    readingArticle?.let { article ->
        materialReader(article) {
            readingArticle = null
            refreshTrackedArticle(article)
        }
    }
}

@Composable
private fun EnglishAuxiliaryTools(onSelect: (String) -> Unit, onClose: () -> Unit) {
    val motion = rememberOverlayMotion(onClose = { OverlayDismissGuard.arm(); onClose() }, predictive = OverlayMotion.enabledState, selfDrag = true)
    Dialog(onDismissRequest = { motion.requestClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = AppColors.Canvas) {
            LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { motion.requestClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    Text("学习工具", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                } }
                item { EnglishEntryCard(Icons.Outlined.Mic, "听说", "精听与跟读", { onSelect("listening") }) }
                item { EnglishEntryCard(Icons.Outlined.Quiz, "题组与错题", "练习与复习", { onSelect("questions") }) }
                item { EnglishEntryCard(Icons.Outlined.Translate, "词汇与查词", "辅助阅读时使用", { onSelect("words") }) }
                item { EnglishEntryCard(Icons.Outlined.Bookmarks, "单词收藏", "回看收藏", { onSelect("favorites") }) }
                item { EnglishEntryCard(Icons.Outlined.LocalLibrary, "我的单词书", "收藏、合并与导入词表", { onSelect("myBooks") }) }
                item { EnglishEntryCard(Icons.Outlined.Widgets, "更多工具", "例句、计划与诊断", { onSelect("more") }) }
            }
        }
    }
}

/** Hub 内本次打开/读完的文章：让主动作卡不依赖外层下一次重组。 */
private data class HubReading(
    val article: LearningArticle,
    val ratio: Float?,
    val finished: Boolean,
)

@Composable
private fun EnglishEntryCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = AppColors.SurfaceOpaque),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), shape = RoundedCornerShape(14.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(9.dp).size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 首页主动作：「今日文章」。
 *
 * §4 要求首页有**一个**主动作，而不是七张并列卡。这里显示上次在读的那篇，
 * 有进度就「继续阅读」，没有就「开始精读」。
 *
 * 🔴 刻意**不显示级别**：`LearningArticle` 里根本没有级别字段（`kind` 是 MARKDOWN/LINK
 * 这种格式类型，不是四级/六级）。§4 写的是「其他**真实**级别」——既然数据里没有，
 * 就不编一个出来。宁可少一行字，也不要让用户以为「这篇标着四级」是系统判断的。
 */
@Composable
private fun EnglishReadingCard(
    article: LearningArticle?,
    ratio: Float?,
    /** 阅读器按「最后一项完全露出」记的完成标记；ratio 读到底也常停在 0.9x，不能拿它冒充 100%。 */
    finished: Boolean = false,
    onOpen: (LearningArticle) -> Unit,
    onChoose: () -> Unit,
) {
    val progress = ratio?.coerceIn(0f, 1f) ?: 0f
    OutlinedCard(
        onClick = { if (article != null) onOpen(article) else onChoose() },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = AppColors.SurfaceOpaque),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("继续英语阅读", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (article == null) {
                // 本机一篇文章都没有。如实说，并且不摆一个点了没反应的按钮。
                Text("选一篇英文文章开始", fontWeight = FontWeight.SemiBold)
                Text("直接选择考试材料或推荐文章，读过的英文文章会留在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onChoose, modifier = Modifier.fillMaxWidth()) { Text("选择英文文章") }
            } else {
                // EF08：标题最多 3 行收敛，元信息 1 行 —— 卡片高度随内容自适应，
                // 长标题/大字号下不会把「继续阅读」按钮和下面的入口挤出一屏；
                // 收敛的不是信息（标题在阅读器里仍完整），只是首页卡的摘要层级。
                Text(article.title, fontWeight = FontWeight.SemiBold,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        finished -> "已读完"
                        progress > 0f -> "已读 ${(progress * 100).toInt()}%"
                        else -> "${article.author} · 还没开始"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Button(onClick = { onOpen(article) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            finished -> "回看文章"
                            progress > 0f -> "继续阅读"
                            else -> "开始精读"
                        }
                    )
                }
            }
        }
    }
}

/** 两列入口保留足够点击空间，文字随字号自然增高。 */
@Composable
private fun EnglishPracticeTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceOpaque)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 140.dp).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(color = AppColors.Accent.copy(alpha = .10f), shape = RoundedCornerShape(12.dp)) {
                Icon(icon, null, tint = AppColors.Accent, modifier = Modifier.padding(9.dp).size(23.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
    }
}

/**
 * 「更多学习工具」（§4 入口改造）。
 *
 * 装的是从首页挪下来的专项能力：每日例句、学习计划、词汇诊断。
 * **一个功能都没删** —— 每一项仍然通向原来那一屏，只是不再跟「继续读文章」抢第一眼。
 */
@Composable
internal fun EnglishMoreToolsPane(
    diagnosisProblem: String? = null,
    onDaily: () -> Unit,
    onPlan: () -> Unit,
    onDiagnosis: () -> Unit,
    onClose: () -> Unit,
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
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { motion.requestClose() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回英语首页")
                    }
                    Text("更多学习工具", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        // 每日例句无 Key 也能用（句子取自用户自己的词书），是学习入口不是 AI 入口。
                        EnglishEntryCard(
                            icon = Icons.Outlined.FormatQuote,
                            title = "每日例句",
                            subtitle = "每天 3 句 · 取自你的词书 · 可朗读、可挖空、可收藏（离线可用）",
                            onClick = onDaily,
                        )
                    }
                    item {
                        EnglishEntryCard(
                            icon = Icons.Outlined.EventNote,
                            title = "学习计划",
                            subtitle = "生成建议 · 确认后生效 · 完成以作答为准（离线可用）",
                            onClick = onPlan,
                        )
                    }
                    item {
                        // 题组读取失败的原因带到这里显示 —— 挪走入口但不能把错误信息一起弄丢，
                        // 否则用户点进去只会看到一张空列表，不知道是「没有」还是「读失败」。
                        EnglishEntryCard(
                            icon = Icons.Outlined.Quiz,
                            title = "词汇诊断",
                            subtitle = diagnosisProblem ?: "用已有题组找薄弱点 · 不预测考试分数",
                            onClick = onDiagnosis,
                        )
                    }
                }
            }
        }
    }
}
