package cn.tonglu.app.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.tonglu.app.data.ApiFactory
import cn.tonglu.app.data.SpeechProblem
import cn.tonglu.app.data.english.*
import cn.tonglu.app.data.speech.Accent
import cn.tonglu.app.data.speech.SpeechPreference
import cn.tonglu.app.data.speech.SpeechResult
import cn.tonglu.app.data.speech.SpeechPurpose
import cn.tonglu.app.data.speech.SpeechRequest
import cn.tonglu.app.data.speech.SpeechService
import cn.tonglu.app.data.speechHint
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

/** Native JustByHeart-style workflow. Source attribution: assets/english/NOTICE.txt.
 * Shared book/word identifiers are ready for a future PC client; no cloud sync claim.
 */
@Composable
/** `initialQuery` 是精听跟读那边"去英语页查这个词"带进来的；为空就是正常打开。 */
internal fun EnglishStudyScreen(
    owner: String,
    initialQuery: String = "",
    /**
     * 初始筛选：`"all"` / `"favorites"` / `"wrong"`。
     *
     * 加这个参数是为了让「我的收藏」能从英语首页**一步进来** —— 原来只能先进单词页
     * 再点一次筛选，用户反馈找不到收藏在哪（2026-10-08）。
     * 非 `"all"` 时直接落在词表页（`route = "library"`），和带词进来的处理一致。
     */
    initialFilter: String = "all",
    /** 今日入口直达练习；未下载词书时仍先选书。 */
    startOnReady: Boolean = false,
    /**
     * 首次学会一个词时回调（成长等级学习线 `words.learned`，plan/66 §2.3）。
     * 🔴 只在「第一次记住」时 +1，复习答对不计 —— 判据在 `EnglishRepository.answer`
     * 的返回值里，那里才看得到词的上一次状态。
     */
    onWordLearned: () -> Unit = {},
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { EnglishRepository(context) }
    val scope = rememberCoroutineScope()
    val settings by remember(owner) { repository.dao.settings(owner) }.collectAsStateWithLifecycle(initialValue = null)
    val effective = settings ?: EnglishSettings(owner)
    var catalog by remember { mutableStateOf<List<EnglishBook>>(emptyList()) }
    var imported by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // 带词进来、或带筛选进来时直接落在词表页，不然用户会看到一个空首页，不知道东西被带到哪去了
    val entryRoute = if (initialQuery.isNotBlank() || initialFilter != "all") "library" else "home"
    var route by remember(owner) { mutableStateOf(entryRoute) }
    // 词库在云端：清单用这个拉，下载也用它（4MB 的词库，读超时给到 90 秒）
    val api = remember { ApiFactory.create(readTimeoutSeconds = 90L) }
    var downloaded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var booksOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var filter by rememberSaveable(owner) { mutableStateOf(initialFilter) }
    var query by rememberSaveable(owner) { mutableStateOf(initialQuery) }
    var rows by remember { mutableStateOf<List<EnglishWord>>(emptyList()) }
    var limit by remember { mutableIntStateOf(100) }
    var detail by remember { mutableStateOf<EnglishWord?>(null) }
    var startedFromToday by remember(owner) { mutableStateOf(false) }
    var session by remember { mutableStateOf<List<EnglishWord>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }
    var correctCount by remember { mutableIntStateOf(0) }
    var mode by rememberSaveable(owner) { mutableStateOf("cards") }
    // EF05 断点续答：会话进度按 owner 落盘。退出/进程被杀后重开，答到哪题就从哪题继续；
    // 完成的一刻断点即清，恢复路径上不存在「有断点但已答完」——不会误报完成。
    val checkpoint = remember(owner) { EnglishPracticeCheckpoint(context, owner) }
    var sessionReview by remember { mutableStateOf(false) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // AI：配置进加密存储，对话只留内存。没配密钥时界面引导去设置，不发请求。
    val aiStore = remember { EnglishAiStore(context) }
    var aiConfig by remember { mutableStateOf(aiStore.load()) }
    var aiWord by remember { mutableStateOf<EnglishWord?>(null) }
    var aiSettingsOpen by remember { mutableStateOf(false) }
    var goalDraft by remember { mutableStateOf("") }
    val book = catalog.firstOrNull { it.id == effective.bookId }
    val dayStart = remember(clock) { Calendar.getInstance().apply {
        timeInMillis = clock; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis }
    val stats by remember(owner, effective.bookId, clock) { repository.dao.stats(owner, effective.bookId, clock, dayStart) }
        .collectAsStateWithLifecycle(initialValue = EnglishStats())
    // 朗读走统一 SpeechService（data/speech/）：调用方只说「读什么、什么口音、干什么用」，
    // 不直接碰系统引擎。失败仍要分清楚是「没引擎」「缺英语语音包」还是「音量为 0」——
    // 这三种都表现为点了没声音，但修法完全不同（见 SpeechProblem）。
    var speechProblem by remember { mutableStateOf<SpeechProblem?>(null) }
    val speech = remember { SpeechService(context) { problem -> speechProblem = problem } }
    val speechPreference = remember(owner) { SpeechPreference(context) }
    var pronunciationAccent by remember(owner) { mutableStateOf(speechPreference.accent(owner)) }
    DisposableEffect(speech) { speech.start(); onDispose { speech.shutdown() } }
    // 从系统语音设置装完语言包回来，得重新探测一次能力：
    // 当前实例还是「那时没有语音」的那个，不重探就只能杀进程重开（plan/56 §4.2 第 5 项）。
    // 只在确实没就绪时做 —— 已经能读的时候没必要每次回前台都重建引擎。
    LifecycleResumeEffect(Unit) {
        if (!speech.systemReady) speech.refreshSystem()
        onPauseOrDispose { }
    }
    // 用偏好里的声音（默认系统朗读）读单词。口音沿用用户选的那条。
    fun speak(word: EnglishWord, text: String = word.english, purpose: SpeechPurpose = SpeechPurpose.Word) {
        val accent = speechPreference.accent(owner)
        // 返回值必须接住：Unavailable（选的离线模型还没下载）和 Degraded（声线语言跟内容对不上）
        // 以前都被丢掉，界面上一声不吭，用户只能以为坏了 —— 而声音设置页是一直接住并显示原因的。
        when (val result = speech.speak(
            SpeechRequest(
                text = text, accent = accent, voiceId = speechPreference.voiceId(owner),
                // 口音要落到语言标签上：以前固定 Locale.US，选英式照样念美式。
                locale = if (accent == Accent.British) "en-GB" else "en",
                purpose = purpose, utteranceId = "${word.id}-${purpose.name}",
            )
        )) {
            SpeechResult.Spoken -> Unit
            is SpeechResult.Failed -> speechProblem = result.problem
            is SpeechResult.Unavailable -> notice = result.reason
            is SpeechResult.Degraded -> notice = result.reason
        }
    }
    // 🔴 2026-10-09 修「英语学习界面返回卡死」。
    //  真机复现：进「单词」→ 切到二级页（词库 / 记录 / 练习）→ 按返回
    //  → **整屏文案变空、点哪都没反应、连按 3 次返回也出不去**，只能杀进程重进。
    //  病根是**顺序**：老写法是 `onClose = { back() }` 配
    //  `Dialog(onDismissRequest = { motion.requestClose() })`，而 requestClose() 是
    //  「**先播退场动画、再调 onClose**」—— 可 back() 在二级页只做内部跳转，**层并不会关**。
    //  于是这一层被动画到 alpha = 0：看不见、却还活着、还吃触摸；而且 closing 已置 true，
    //  之后的 requestClose() 直接 return → 这一层**永远关不掉**。
    //  改法：**先判断、后动画**。返回键与顶栏 ← 都走 back()，只有真的要关时才 requestClose()。
    //  ⚠️ motion 必须声明在 back() **之前** —— Kotlin 的局部函数不能引用后面才声明的变量。
    val motion = rememberOverlayMotion(
        onClose = { speech.stop(); OverlayDismissGuard.arm(); onClose() },   // 动画播完才走到这
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
        // 二级页里「返回」另有含义（内部返回）→ 不让 motion 接管，
        // 免得一次手势把整层关掉、或者反过来把层卡成透明。
        backEnabled = route == entryRoute && !saving,
    )
    fun back() {
        if (saving) return
        if (route != entryRoute) { route = entryRoute; speech.stop() } else motion.requestClose()
    }
    /**
     * 选中一本词书：没下过就先下载（带进度），下完再导入。
     *
     * 两步分开是有意的 —— 下载是「网络的事」，导入是「本机的事」，
     * 混在一起失败时只会看到一句「词书导入失败」，谁也不知道到底卡在网还是卡在解析。
     */
    suspend fun importBook(selected: EnglishBook) {
        busy = true; error = null; imported = null
        var phase = "导入"
        try {
            if (!repository.isDownloaded(selected)) {
                phase = "下载"
                downloading = selected.id; progress = 0
                EnglishBookStore.download(context, api, selected) { progress = it }
                downloaded = EnglishBookStore.downloadedIds(context)
            }
            repository.ensureBook(selected); imported = selected.id
        }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (cause: Exception) {
            android.util.Log.e("EnglishBooks", "$phase failed for ${selected.id}", cause)
            error = if (phase == "下载") "词库下载失败：${cause.message ?: "请检查网络后重试"}"
                else "词书导入失败，请重试。"
        }
        finally { downloading = null; busy = false }
    }
    LaunchedEffect(Unit) {
        downloaded = EnglishBookStore.downloadedIds(context)
        try { catalog = repository.catalog(api) } catch (_: Exception) { error = "词书目录读取失败，请重新打开。" }
        downloaded = EnglishBookStore.downloadedIds(context)
    }
    // 换到一本**已经下过**的词书时自动导入；没下过的不动 ——
    // 打开英语页就悄悄下 2.6MB 的四級词库，是拿用户的流量替他做决定。
    LaunchedEffect(book?.id) {
        val target = book ?: return@LaunchedEffect
        if (imported == target.id || !repository.isDownloaded(target)) return@LaunchedEffect
        importBook(target)
    }
    // EF05 断点恢复：上次没答完的一组，重开屏幕（或杀进程重进）原样续答。
    // 只在同词书时恢复；词单对不上号（重导词库 / 删书）就如实丢弃断点，不硬凑一组。
    // 🔴 完成语义：断点在答完最后一题的一刻已被清除，所以这里恢复出来的会话
    // 必然 index < 词单长度 —— 练习页只会落在题目上，不存在「恢复出完成页」。
    LaunchedEffect(book?.id) {
        val target = book ?: return@LaunchedEffect
        val saved = try { checkpoint.load() } catch (_: Exception) { null } ?: return@LaunchedEffect
        if (saved.bookId != target.id || saved.index >= saved.wordIds.size) { checkpoint.clear(); return@LaunchedEffect }
        try {
            val byId = repository.dao.wordsByIds(saved.wordIds).associateBy { it.id }
            val words = saved.wordIds.mapNotNull { byId[it] }
            if (words.size != saved.wordIds.size) { checkpoint.clear(); return@LaunchedEffect }
            session = words
            index = saved.index
            correctCount = saved.correctCount
            mode = saved.mode
            sessionReview = saved.review
            route = "practice"
            notice = "已恢复上次没答完的一组（${saved.index}/${words.size}），从第 ${saved.index + 1} 题继续。"
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { checkpoint.clear() }
    }
    LaunchedEffect(Unit) { while (true) { delay(60_000); clock = System.currentTimeMillis() } }
    LaunchedEffect(route, query, filter, limit, imported, stats.favorites, stats.wrong) {
        if (route != "library" || imported != book?.id || book == null) return@LaunchedEffect
        delay(180)
        try { rows = repository.dao.browse(owner, book.id, query.trim(), filter, limit) }
        catch (_: Exception) { error = "无法读取单词，请重试。" }
    }
    // EF05：把当前进度事实写进断点。只记「答到第几题、对几题」，不记答案；
    // 会话为空或已答完就清（完成的一刻断点消失 = 恢复永远不会渲染完成页）。
    fun persistCheckpoint() {
        try {
            val target = book
            if (target == null || session.isEmpty() || index >= session.size) { checkpoint.clear(); return }
            checkpoint.save(EnglishPracticeCheckpoint.Data(
                review = sessionReview, mode = mode, bookId = target.id,
                wordIds = session.map { it.id }, index = index,
                correctCount = correctCount, savedAt = System.currentTimeMillis()))
        } catch (io: Exception) {
            // 落盘失败不挡作答：最坏结果是这次退出后从上上题恢复，绝不会丢已保存的作答。
            android.util.Log.w("EnglishStudy", "练习断点保存失败", io)
        }
    }

    fun start(review: Boolean) {
        val selected = book ?: return
        if (busy || saving || imported != selected.id) return
        // 例句填空只挑带例句的词，所以查询要跟着题型走，不然题目出不来。
        val limit = sessionLimit(effective.dailyGoal)
        val needExample = mode == "cloze"
        scope.launch {
            busy = true
            try {
                val now = System.currentTimeMillis()
                val words = if (review) {
                    if (needExample) repository.dao.dueWordsWithExample(owner, selected.id, now, limit)
                    else repository.dao.dueWords(owner, selected.id, now, limit)
                } else {
                    if (needExample) repository.dao.newWordsWithExample(owner, selected.id, limit)
                    else repository.dao.newWords(owner, selected.id, limit)
                }
                if (words.isEmpty()) notice = if (review) "当前没有到期单词。"
                    else if (needExample) "这本词书暂时没有可用的例句题，换个题型试试。"
                    else "这本词书的新词已学完，可以复习或换一本。"
                else {
                    session = words; index = 0; correctCount = 0; route = "practice"
                    sessionReview = review
                    // 断点从第 0 题就落盘（EF05）：之后每答一题刷新，退出/杀进程都能恢复。
                    persistCheckpoint()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (cause: Exception) { notice = "无法开始学习：${cause.localizedMessage ?: "请重试"}" }
            finally { busy = false }
        }
    }
    LaunchedEffect(startOnReady, imported, book?.id, busy) {
        if (startOnReady && !startedFromToday && book != null && imported == book.id && !busy) {
            startedFromToday = true
            // 查询当前数据库，避免首次统计 Flow 尚未发值时跳过到期复习。
            try {
                val due = repository.dao.dueWords(owner, book.id, System.currentTimeMillis(), 1)
                start(review = due.isNotEmpty())
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (cause: Exception) { error = "读取复习任务失败：${cause.localizedMessage ?: "请重试"}" }
        }
    }
    fun saveGoal(goal: Int) {
        scope.launch {
            try { repository.dao.settings(effective.copy(dailyGoal = goal.coerceIn(0, MAX_GOAL), updatedAt = System.currentTimeMillis())) }
            catch (_: Exception) { notice = "目标保存失败，请重试。" }
        }
    }
    fun favorite(word: EnglishWord) { scope.launch {
        try { repository.favorite(owner, word) } catch (_: Exception) { notice = "收藏保存失败，请重试。" }
    } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                val backup = repository.export(owner, effective)
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use {
                    it.write(backup.toByteArray(Charsets.UTF_8))
                } ?: throw java.io.IOException("无法创建文件") }
                notice = "学习记录已导出。"
            } catch (_: Exception) { notice = "导出失败，请重新选择位置。" }
        }
    }
    // 弹层：Dialog 是**独立 Window**，系统把手势进度派给 Activity，这里只拿得到
    // 「已经返回完」那一次回调 —— 所以自识别左边缘拖动（selfDrag = true）。
    // 与内联层共用同一套 EdgeBackState 和同一条曲线，观感才是统一的。
    // ⚠️ 原来那条裸 `BackHandler` 必须删掉：它和外壳都注册在 OnBackPressedDispatcher 上，
    // 同时存在会一次返回退两层（OverlayMotion.kt 顶部已记）。
    // motion 已在上面（back() 之前）声明 —— 见那段「先判断、后动画」的注释。
    Dialog(onDismissRequest = { back() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false, onBack = { back() }, backEnabled = !saving), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }, enabled = !saving) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    Text(if (route == "practice") "单词练习" else "英语学习", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { aboutOpen = true }) { Icon(Icons.Outlined.Info, "来源与许可") }
                }
                if (route != "practice" || mode != "cards") Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(Accent.British to "英音", Accent.American to "美音").forEach { (accent, label) ->
                        EnglishChip(pronunciationAccent == accent, onClick = {
                            pronunciationAccent = accent
                            speechPreference.save(owner, null, accent)
                        }, label = { Text(label) })
                    }
                    TextButton(onClick = {
                        val text = session.getOrNull(index)?.english ?: "Hello, welcome to Tonglu."
                        when (val result = speech.speak(SpeechRequest(text, accent = pronunciationAccent,
                            voiceId = speechPreference.voiceId(owner), purpose = SpeechPurpose.Preview))) {
                            SpeechResult.Spoken -> Unit
                            is SpeechResult.Failed -> speechProblem = result.problem
                            is SpeechResult.Unavailable -> notice = result.reason
                            is SpeechResult.Degraded -> notice = result.reason
                        }
                    }) { Text("试听") }
                }
                if (route != "practice") Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("home" to "学习", "library" to "词库", "stats" to "记录").forEach { (id, label) ->
                        EnglishChip(selected = route == id, onClick = { route = id; filter = "all"; limit = 100 }, label = { Text(label) })
                    }
                }
                if (busy) {
                    // 下载中有确定进度（词库 1~4MB，用户盯着看呢）；导入阶段是本机活儿，走不确定进度
                    if (downloading != null) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                error?.let { message -> Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { scope.launch { book?.let { importBook(it) } } }) { Text("重试") }
                } }
                AnimatedContent(route, modifier = Modifier.weight(1f).fillMaxWidth(), label = "english-route") { page ->
                    when (page) {
                        "home" -> LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxSize()) {
                            item { EnglishPanel {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(book?.name ?: "准备词书", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                                        // 词库在云端：没下过的那本要告诉用户「要下多大」，别让用户对着 0 词发愣
                                        Text(if (book == null) "正在读取云端词库…"
                                            else if (book.id in downloaded) "${book.count} 个词条 · 已下载到本机"
                                            else "${book.count} 个词条 · 未下载（${bookSize(book)}）",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(onClick = { booksOpen = true }, enabled = !busy) { Text("换词书") }
                                }
                                LinearProgressIndicator(progress = { stats.studied.toFloat() / (book?.count ?: 1).coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                                Text("已练习 ${stats.studied} · 待复习 ${stats.due}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                            item {
                                Text("每次练多少词", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                                Text(if (effective.dailyGoal > 0) "一次取 ${effective.dailyGoal} 个 · 今天已练 ${stats.today} 个"
                                    else "不限数量：一次把剩下的都取出来（最多 $MAX_GOAL 个）",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    GOAL_PRESETS.forEach { goal ->
                                        EnglishChip(selected = effective.dailyGoal == goal, onClick = { saveGoal(goal) },
                                            label = { Text("$goal 词") })
                                    }
                                    EnglishChip(selected = effective.dailyGoal == 0, onClick = { saveGoal(0) }, label = { Text("不限") })
                                }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(goalDraft, onValueChange = { goalDraft = it.filter(Char::isDigit).take(4) },
                                        modifier = Modifier.weight(1f), singleLine = true, label = { Text("自定义 0~$MAX_GOAL") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                                    Button(onClick = {
                                        val value = goalDraft.toIntOrNull()
                                        if (value == null || value > MAX_GOAL) notice = "请填 0 到 $MAX_GOAL 之间的数字（0 表示不限）。"
                                        else { saveGoal(value); goalDraft = "" }
                                    }, enabled = goalDraft.isNotBlank(), modifier = Modifier.heightIn(min = 52.dp)) { Text("应用") }
                                }
                            }
                            item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MODES.forEach { (id,label) ->
                                    EnglishChip(selected = mode == id, onClick = { mode = id }, label = { Text(label) })
                                }
                            } }
                            if (book != null && book.id !in downloaded) item {
                                Button(onClick = { scope.launch { importBook(book) } }, enabled = !busy,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                                    Text(if (downloading == book.id) "下载中 $progress%" else "下载词书（${bookSize(book)}）")
                                }
                            }
                            item { Button(onClick = { start(false) }, enabled = imported == book?.id && book != null && !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("开始学新词") } }
                            item { OutlinedButton(onClick = { start(true) }, enabled = imported == book?.id && book != null && !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("复习到期单词 · ${stats.due}") } }
                            item { EnglishPanel {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    TextButton(onClick = { filter = "favorites"; route = "library"; limit = 100 }, Modifier.weight(1f)) { Text("收藏 ${stats.favorites}") }
                                    TextButton(onClick = { filter = "wrong"; route = "library"; limit = 100 }, Modifier.weight(1f)) { Text("错词 ${stats.wrong}") }
                                }
                                Text("记录保存在本机。跨端云同步尚未接入。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                        }
                        "library" -> Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                            OutlinedTextField(query, onValueChange = { query = it; limit = 100 }, modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("搜索英文或中文释义") }, singleLine = true, leadingIcon = { Icon(Icons.Outlined.Search, null) })
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("all" to "全部", "favorites" to "收藏", "wrong" to "错词").forEach { (id,label) ->
                                    EnglishChip(selected = filter == id, onClick = { filter = id; limit = 100 }, label = { Text(label) })
                                }
                                TextButton(onClick = { booksOpen = true }, enabled = !busy) { Text(book?.name ?: "换词书") }
                            }
                            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                                if (rows.isEmpty() && !busy) item { Text("没有匹配的单词", Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                items(rows, key = { it.id }) { word -> EnglishPanel(modifier = Modifier.clickable { detail = word }) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(word.english, fontSize = 19.sp, fontWeight = FontWeight.Medium)
                                        EnglishPhonetics(word, MaterialTheme.typography.bodySmall)
                                    }
                                    Text(word.chinese, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } }
                                if (rows.size == limit) item { TextButton(onClick = { limit += 100 }, modifier = Modifier.fillMaxWidth()) { Text("加载更多") } }
                            }
                        }
                        "stats" -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item { EnglishPanel {
                                Text("${book?.name.orEmpty()} · 学习记录", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                                Text("累计练习 ${stats.studied} 词\n今天练习 ${stats.today} 词\n到期复习 ${stats.due} 词", lineHeight = 28.sp)
                                Text("记住：1、3、7、14、30、60 天后复习；忘记：5 分钟后重试。采用固定间隔，非 FSRS。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } }
                            item { OutlinedButton(onClick = { aiSettingsOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                // 只显示模型名，绝不回显密钥
                                Text(if (aiConfig.ready()) "AI 设置 · 已配置 ${aiConfig.model}" else "AI 设置 · 未填密钥") } }
                            item { OutlinedButton(onClick = { export.launch("english-learning-backup.json") }, modifier = Modifier.fillMaxWidth()) { Text("导出学习记录") } }
                            item { Text("收藏与答题记录保存在独立 SQLite 数据库，不依赖联网。旧校园词卡记录保留在原位置。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            item { Text("AI 讲解用的是你自己填的接口，密钥只存本机、请求不经过同路服务器；不配置也能离线背单词（导出文件里不含密钥）。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        "practice" -> {
                            val current = session.getOrNull(index)
                            // AnimatedContent 的离场页仍在组合；空会话不代表完成。
                            if (session.isNotEmpty() && index >= session.size) Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(52.dp))
                                Text("这一组练完了", fontSize = 24.sp, modifier = Modifier.padding(top = 20.dp))
                                Text("${session.size} 个单词 · $correctCount 次记住 / 答对", Modifier.padding(vertical = 12.dp))
                                Button(onClick = { route = entryRoute; speech.stop() }) { Text("返回学习") }
                            } else if (current != null) key(current.id) { EnglishPractice(current, mode, index + 1, session.size, saving, repository, owner,
                                accent = pronunciationAccent, onPronounce = { accent ->
                                    pronunciationAccent = accent; speechPreference.save(owner, null, accent); speak(current)
                                },
                                onSpeak = { speak(current) }, onReadExample = { text, purpose -> speak(current, text, purpose) }, onFavorite = { favorite(current) }, onAskAi = { aiWord = current },
                                onAnswer = { correct ->
                                    if (!saving) {
                                        saving = true
                                        scope.launch {
                                        try {
                                            val firstLearned = repository.answer(owner, book!!, current, mode, correct)
                                            if (correct) correctCount++
                                            // 只在「首次学会」时上报学习线，复习答对重复上报会被服务端
                                            // 按 words.learned 的日上限截断，但语义上就不该发。
                                            if (firstLearned) onWordLearned()
                                            index++; speech.stop(); clock = System.currentTimeMillis()
                                            // 每答完一题刷新断点（EF05）；答完最后一题时这里自动转成清除
                                            // ——「有断点」从此永远等于「确实没答完」。
                                            persistCheckpoint()
                                        } catch (bad: Exception) { android.util.Log.e("EnglishStudy", "单词评分保存失败", bad); notice = "回答未保存，请重试。" }
                                        finally { saving = false }
                                        }
                                    }
                                }) }
                        }
                    }
                }
            }
        }
    }
    if (booksOpen) AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { booksOpen = false }, title = { Text("选择词书") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (catalog.isEmpty()) item { Text("云端词库还没读到，请检查网络后重试。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(catalog, key = { it.id }) { candidate ->
                val ready = candidate.id in downloaded
                ListItem(headlineContent = { Text(candidate.name) },
                    supportingContent = {
                        Column {
                            Text("${candidate.category} · ${candidate.count} 词 · ${if (ready) "已下载" else bookSize(candidate)}")
                            // 逐本显示真实出处与许可（plan §5 第 3 条）。
                            // 以前底下一句话写死「都来自 KyleBing」—— 只要上架一本别的来源，
                            // 那句话就开始说谎。现在每本说自己的，缺登记也如实写「未登记」。
                            Text(provenanceLine(candidate.source, candidate.license),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    trailingContent = { if (candidate.id == effective.bookId) Icon(Icons.Outlined.Check, "已选择", tint = MaterialTheme.colorScheme.primary)
                        else if (!ready) Icon(Icons.Outlined.Download, "未下载", tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier.clickable(enabled = !busy) {
                        booksOpen = false; rows = emptyList(); session = emptyList(); limit = 100; query = ""
                        checkpoint.clear()   // 主动换书 = 明确放弃上一组（EF05）：断点一并清，不留幽灵恢复
                        scope.launch {
                            try { repository.dao.settings(effective.copy(bookId = candidate.id, updatedAt = System.currentTimeMillis())) }
                            catch (_: Exception) { notice = "词书选择未保存，请重试。" }
                            // 没下过的当场下载：用户点这一下的意思就是「我要背这本」
                            if (!repository.isDownloaded(candidate)) importBook(candidate)
                        }
                    })
            }
            // 出处可查（docs/plan/56 §4.3）。🔴 **不再写死某一个来源**：
            // 每本书的出处与许可显示在各自行里（`provenanceLine`），这里只给通用指向 ——
            // 写死「都来自 KyleBing」在上架第二家来源的那一刻就变成虚假署名。
            item {
                Text(
                    "每本词库的出处与许可标在各自那一行；版本号即上游 commit。" +
                        "完整署名与许可证全文见 App 内「关于」中的开源许可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }, confirmButton = { TextButton(onClick = { booksOpen = false }) { Text("关闭") } })
    detail?.let { word ->
        val progress by remember(owner, word.id) { repository.dao.observeWord(owner, word.id) }.collectAsStateWithLifecycle(initialValue = null)
        AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { detail = null }, title = { Text(word.english) }, text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(word.chinese) }
                word.pronunciation?.let { item { Text("美 /$it/") } }
                word.ukPronunciation?.let { item { Text("英 /$it/") } }
                word.example?.let { item { ClickableExample(it) { text, purpose -> speak(word, text, purpose) } } }
                word.exampleTranslation?.let { item { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                word.phrases?.let { item { Text(it) } }
            }
        }, confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } }, dismissButton = {
            Row { TextButton(onClick = { aiWord = word; aiSettingsOpen = false }) { Text("问 AI") }
                TextButton(onClick = { favorite(word) }) { Text(if (progress?.favorite == true) "取消收藏" else "收藏") }
                TextButton(onClick = { speak(word) }) { Text("发音") } }
        })
    }
    speechProblem?.let { problem ->
        val (hint, openable) = speechHint(problem)
        AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { speechProblem = null },
            title = { Text("发不出声音") }, text = { Text(hint) },
            confirmButton = { TextButton(onClick = { speechProblem = null }) { Text("知道了") } },
            dismissButton = {
                if (openable) Row {
                    TextButton(onClick = {
                        if (!speech.installVoiceData() && !speech.openSystemSettings()) notice = "这台设备没有可跳转的语音设置页，请到系统设置里搜索「文字转语音」。"
                        speechProblem = null
                    }) { Text("语音数据") }
                    TextButton(onClick = {
                        if (!speech.openSystemSettings()) notice = "这台设备没有可跳转的语音设置页，请到系统设置里搜索「文字转语音」。"
                        speechProblem = null
                    }) { Text("系统设置") }
                }
            })
    }
    notice?.let { text -> AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { notice = null }, text = { Text(text) }, confirmButton = { TextButton(onClick = { notice = null }) { Text("知道了") } }) }
    aiWord?.let { word -> EnglishAiDialog(owner = owner, word = word, config = aiConfig,
        onOpenSettings = { aiSettingsOpen = true }, onDismiss = { aiWord = null }) }
    if (aiSettingsOpen) EnglishAiSettingsDialog(store = aiStore, initial = aiConfig,
        onSaved = { aiConfig = it }, onDismiss = { aiSettingsOpen = false })
    if (aboutOpen) AlertDialog(containerColor = MaterialTheme.colorScheme.surface, onDismissRequest = { aboutOpen = false }, title = { Text("来源与许可") }, text = {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("流程参考 JustByHeart（MIT），界面改为 Compose；词库来自 KyleBing/english-vocabulary（BSD-3-Clause），并非 Qwerty 原版。发音使用系统 TTS。") }
            listOf("JustByHeart.txt", "EnglishVocabulary.txt").forEach { file -> item {
                Text(remember(file) { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }, style = MaterialTheme.typography.bodySmall)
            } }
        }
    }, confirmButton = { TextButton(onClick = { aboutOpen = false }) { Text("关闭") } }, dismissButton = {
        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/cdz-hy/JustByHeart"))) }.onFailure { notice = "无法打开浏览器" } }) { Text("上游项目") }
    })
}

/**
 * 例句填空：把单词（含屈折变化，如 abused）挖成空。
 * 词库里有一部分词没有例句，这时退化成「看释义写单词」，不出残缺题目。
 */
private fun clozeOf(word: EnglishWord): String {
    val example = word.example?.takeIf { it.isNotBlank() } ?: return word.chinese
    val pattern = Regex("(?i)\\b${Regex.escape(word.english)}\\w*")
    if (!pattern.containsMatchIn(example)) return word.chinese
    val line = example.lineSequence().firstOrNull { pattern.containsMatchIn(it) }?.trim().orEmpty()
    return if (line.isBlank()) word.chinese else pattern.replace(line, "____")
}

/** 词库大小的人话版本。清单里 bytes 为 0（出厂清单没带）时给空串，界面上就不显示。 */
private fun bookSize(book: EnglishBook): String = when {
    book.bytes <= 0 -> ""
    book.bytes >= 1024 * 1024 -> "%.1f MB".format(book.bytes / 1024f / 1024f)
    else -> "${(book.bytes / 1024f).roundToInt()} KB"
}

/** 单次取词上限：0 表示不限时用这个数兜底，避免一次把上万个词塞进内存。 */
private const val MAX_GOAL = 2000
private val GOAL_PRESETS = listOf(10, 20, 50, 100, 200, 500)
/** 0 = 不限（取到 MAX_GOAL 为止）；其余按用户填的数字取。 */
private fun sessionLimit(goal: Int) = if (goal <= 0) MAX_GOAL else goal.coerceIn(1, MAX_GOAL)
/** 题型。顺序即界面顺序；`cloze` 依赖例句，取词时会筛掉没有例句的词。 */
private val MODES = listOf("cards" to "翻卡", "choice" to "选择题", "reverse" to "看中文选英文",
    "spelling" to "拼写", "cloze" to "例句填空", "listen" to "听音选义")

@Composable
internal fun EnglishChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = label, colors = FilterChipDefaults.filterChipColors(
        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        selectedLabelColor = MaterialTheme.colorScheme.primary))
}

@Composable
private fun EnglishPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    MiuixCard(modifier = modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

/**
 * 英美音标并排。词库里两个字段是分开的（覆盖率：美音 94%~99%、英音 95%~99%），
 * 缺哪个就不显示哪个 —— 不用占位符凑，免得看起来像「数据没加载出来」。
 * 听音题与拼写题由调用方决定是否展示（那两种题型里音标等于答案）。
 */
@Composable
private fun EnglishPhonetics(word: EnglishWord, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val us = word.pronunciation?.takeIf { it.isNotBlank() }
    val uk = word.ukPronunciation?.takeIf { it.isNotBlank() }
    if (us == null && uk == null) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (us != null) Text("美 /$us/", style = style, color = muted)
        if (uk != null) Text("英 /$uk/", style = style, color = muted)
    }
}

@Composable
private fun EnglishPractice(word: EnglishWord, mode: String, number: Int, total: Int, saving: Boolean,
    repository: EnglishRepository, owner: String, accent: Accent, onPronounce: (Accent) -> Unit,
    onSpeak: () -> Unit, onReadExample: (String, SpeechPurpose) -> Unit, onFavorite: () -> Unit,
    onAskAi: () -> Unit, onAnswer: (Boolean) -> Unit) {
    var revealed by remember(word.id) { mutableStateOf(false) }
    var typed by remember(word.id) { mutableStateOf("") }
    var checked by remember(word.id) { mutableStateOf<Boolean?>(null) }
    var choices by remember(word.id) { mutableStateOf<List<String>>(emptyList()) }
    val progress by remember(owner, word.id) { repository.dao.observeWord(owner, word.id) }.collectAsStateWithLifecycle(initialValue = null)
    // 听音题在答对之前不显示单词，否则就不是「听」了。
    val hideWord = mode == "listen" && checked == null
    val cloze = remember(word.id) { clozeOf(word) }
    LaunchedEffect(word.id, mode) {
        choices = when (mode) {
            "choice", "listen" -> (repository.dao.distractors(word.wordBank, word.id, word.chinese)
                .map { it.chinese }.distinct().take(3) + word.chinese).shuffled()
            "reverse" -> (repository.dao.distractorWords(word.wordBank, word.id, word.english)
                .map { it.english }.distinct().take(3) + word.english).shuffled()
            else -> emptyList()
        }
        if (mode == "listen") onSpeak()
    }
    if (mode == "cards") {
        cn.tonglu.app.ui.english.wordreview.WordReviewPage(word, number, total, saving, progress?.favorite == true,
            accent, onPronounce, onReadExample, onFavorite, onAskAi, onAnswer)
        return
    }
    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("$number / $total", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                IconButton(onClick = onAskAi, enabled = !saving) { Icon(Icons.Outlined.Forum, "问 AI 怎么背", tint = MaterialTheme.colorScheme.primary) }
                IconButton(onClick = onFavorite, enabled = !saving) { Icon(if (progress?.favorite == true) Icons.Outlined.Star else Icons.Outlined.StarBorder, "收藏单词", tint = MaterialTheme.colorScheme.primary) }
            }
        } }
        item { LinearProgressIndicator(progress = { (number - 1).toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) }
        item { EnglishPanel(modifier = Modifier.animateContentSize()) {
            when {
                // 听音题：只给声音，答完才亮单词
                hideWord -> {
                    Text("听发音，选出中文意思", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onSpeak) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("再听一次") }
                }
                mode == "cloze" -> {
                    Text(cloze, fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
                    Text("提示：${word.chinese}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                mode == "spelling" && checked == null -> Text(word.chinese, fontSize = 23.sp, lineHeight = 32.sp)
                mode == "reverse" -> Text(word.chinese, fontSize = 23.sp, lineHeight = 32.sp)
                else -> Text(word.english, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold)
            }
            if (!hideWord && mode != "spelling") {
                EnglishPhonetics(word)
                TextButton(onClick = onSpeak) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("听发音") }
            }
            if (revealed || checked != null) {
                if (mode != "spelling") Text(word.chinese, fontSize = 20.sp)
                word.example?.let { ClickableExample(it, onReadExample) }
                word.exampleTranslation?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else if (mode == "cards") Text("先回忆意思，再翻开答案", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        when (mode) {
            "cards" -> {
                item { OutlinedButton(onClick = { revealed = !revealed }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (revealed) "隐藏释义" else "查看释义") } }
            }
            "spelling", "cloze" -> {
                item { OutlinedTextField(typed, onValueChange = { if (checked == null) typed = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(if (mode == "cloze") "填入空缺的单词" else "输入英文") }, enabled = checked == null && !saving,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii)) }
                item { Button(onClick = { checked = typed.trim().equals(word.english.trim(), ignoreCase = true); revealed = true },
                    enabled = typed.isNotBlank() && checked == null && !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("检查答案") } }
                if (checked == null) item { TextButton(onClick = { checked = false; revealed = true }, enabled = !saving) { Text("不会，查看答案") } }
            }
            "choice", "listen" -> items(choices, key = { it }) { choice ->
                OutlinedButton(onClick = { checked = choice == word.chinese; revealed = true }, enabled = checked == null && !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(choice, Modifier.fillMaxWidth()) }
            }
            "reverse" -> items(choices, key = { it }) { choice ->
                OutlinedButton(onClick = { checked = choice == word.english; revealed = true }, enabled = checked == null && !saving,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(choice, Modifier.fillMaxWidth()) }
            }
        }
        if (mode != "cards" && checked != null) {
            item { Text(if (checked == true) "答对了" else "再练一次，答案是 ${word.english} · ${word.chinese}", color = if (checked == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        }
    }
    // 评分按钮固定在滚动内容外，长例句不能把操作挤出屏幕。
    if (mode == "cards") BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        if (maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onAnswer(false) }, enabled = canGradeCard(saving), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (saving) "保存中…" else "不记得") }
            Button(onClick = { onAnswer(true) }, enabled = canGradeCard(saving), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (saving) "保存中…" else "记住了") }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onAnswer(false) }, enabled = canGradeCard(saving), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(if (saving) "保存中…" else "不记得") }
            Button(onClick = { onAnswer(true) }, enabled = canGradeCard(saving), modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text(if (saving) "保存中…" else "记住了") }
        }
    } else if (checked != null) Button(onClick = { onAnswer(checked == true) }, enabled = !saving,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(min = 52.dp)) {
        Text(if (saving) "保存中…" else "下一词")
    }
    }
}
