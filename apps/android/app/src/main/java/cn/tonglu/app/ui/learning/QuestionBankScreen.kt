package cn.tonglu.app.ui.learning

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Quiz
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.ApiFactory
import cn.tonglu.app.data.english.AiScope
import cn.tonglu.app.data.english.EnglishAiConfig
import cn.tonglu.app.data.english.EnglishAiStore
import cn.tonglu.app.data.listening.PracticeStore
import cn.tonglu.app.data.listening.PracticeMode
import cn.tonglu.app.data.question.BankQuestion
import cn.tonglu.app.data.question.Difficulty
import cn.tonglu.app.data.question.QuestionBank
import cn.tonglu.app.data.question.QuestionBankStore
import cn.tonglu.app.data.question.SubjectiveStore
import cn.tonglu.app.ui.AiScopeDialog
import cn.tonglu.app.ui.CampusBrowserScreen
import cn.tonglu.app.ui.EnglishAiSettingsDialog
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import kotlinx.coroutines.launch

/**
 * 真题原站导航入口（plan/58 L1 + HANDOFF-2026-10-10 §六）。
 *
 * 只跳转、不镜像：新东方版权声明明文禁止未授权转载/复制，官方站只提供大纲与报名服务。
 * 两个入口的可用性都经过 2026-10-10 在线核实：
 *  - [XDF] 收录至 2026 年 6 月考次、持续更新、含答案解析与 PDF 版声明；
 *  - [NEEA] 是报名/大纲/查分的权威入口，**没有**历年真题下载（网上说有的都是内容农场假话）。
 */
internal data class RealPaperSite(val title: String, val url: String) {
    companion object {
        /** 新东方四六级官网（真题 + 答案 + 解析）。旧 2012 专题页已废弃，别换回去。 */
        val XDF = RealPaperSite("四六级真题原站", "https://cet4-6.xdf.cn/")

        /** 官方考试网（报名 / 大纲 / 查分）。 */
        val NEEA = RealPaperSite("官方考试网", "https://cet.neea.edu.cn")
    }
}

/**
 * 独立题库（docs/plan/56 §5.3 T3/T4/T6 / docs/plan/57）。
 *
 * 这是「不靠任何材料也能做题」的那条路。与材料自带题的关系：
 * 两条路**共用同一个作答存储**（[PracticeStore]），只是题目键不同 ——
 * 材料题是 `legacy:<materialId>:<qid>`，题库题是 `<bankId>:<qid>`。
 * 因此错题本、做过几次这类派生视图天然跨两者统一，不需要合并两份数据。
 *
 * 三级页面（route）：
 *  - `list`  题组列表（内置练习 + 云端公开题组 + 当前账号私有题组）
 *  - `quiz`  单题作答（在本组题里前进/后退）
 *  - `diagnosis` 词汇诊断（T6：按难度层抽题，见 [VocabDiagnosisPane]）
 *
 * 四条纪律：
 * 1. **未提交不露答案** —— 判分只在提交后读 `answer`，作答前连渲染的机会都没有。
 * 2. **打开列表不自动下载** —— 远端题库显示成「待下载」，点了才下（不替用户花流量）。
 * 3. **离线可用** —— 已下载的题库与 assets 题库完全离线；没网时清单退回本机缓存。
 * 4. **打标** —— 自编练习与真题整理件分开展示，题组保留来源说明。
 */
/**
 * 从今日卡点「错题复习」时，挑哪个题组开进去。
 *
 * 规则：**错题最多的那个组**；没有错题返回 null（调用方据此不开任何组）。
 * 为什么是「最多」而不是「第一个有错题的」：用户点「错题复习」想要的是一次练得动的复习，
 * 不是把只有 1 道错题的组翻出来。
 *
 * 错题键的形状是 `<bankId>:<questionId>`（见 `PracticeStore.QuestionAttempt.questionKey`），
 * 所以按 `"<bankId>:"` 前缀数。**抽成纯函数是为了能单测** —— `banks` + `store` 都要
 * 文件系统/网络，JVM 测试给不了，而「前缀匹配写错」会让某个组的错题永远数成 0
 * （界面表现只是「点了没反应」，不报错）。
 */
internal fun bankWithMostWrong(bankIds: List<String>, wrongKeys: Set<String>): String? =
    bankIds.map { id -> id to wrongKeys.count { it.startsWith("$id:") } }
        .filter { it.second > 0 }
        .maxByOrNull { it.second }?.first

/**
 * 这一题**没有任何可播音频**，而它的题干本来就在录音里。
 *
 * 判据取两个**结构性**信号，不做文案匹配：
 *  1. 题库 id 的题型段是 `listening` —— [examBankId] 与本仓库
 *     `scripts/cet_pdf_to_banks.py` 的 TYPE_SLUG 共用这套命名，是稳定契约；
 *  2. 本题的 `audioUrl` 与 `audioText` 都为空（原音直链与合成朗读两条路都没接）。
 *
 * 为什么要单列这一个判据：真题听力**卷面只印选项、题干在录音里**。没有音频就没有题干，
 * 界面却照样渲染「四个选项 + 提交答案」，用户只能瞎猜，猜错还被当成真错题收进错题本。
 * 数据证据：生产 5 份听力题库里 4 份（100/125 题）这两个字段全空，见
 * `work/bank-gap-20261015/GAP.md`。
 *
 * ⚠️ 只加一条**如实说明**，不改判分、不改题库可见性、不伪造任何题面 ——
 * 「这题还算不算分」是产品决定，不在这处能定的范围内。
 */
internal fun questionHasNoAudio(bank: QuestionBank, question: BankQuestion): Boolean =
    bank.id.endsWith("-listening") &&
        question.audioUrl.isNullOrBlank() && question.audioText.isNullOrBlank()

@Composable
internal fun QuestionBankScreen(
    owner: String,
    onClose: () -> Unit,
    /** 有人提交了新作答（T5）：交给上层防抖后推云端。**不在答题路径上直接发网络请求** ——
     *  一次自测会连着提交几十题，防抖必须放在上层。 */
    onPracticeChanged: () -> Unit = {},
    /**
     * 今日卡「错题复习」直达（plan §5 第 2 条要求四入口）。置 true 时自动：
     * 挑**错题最多**的题组 → 直接开它的练习面板 → 开局只练错题。
     * 挑「错题最多」而不是「第一个有错题的」：用户点「错题复习」想要的是一次练得动的复习，
     * 不是把 1 道错题的组翻出来。
     */
    wrongReview: Boolean = false,
    /**
     * EF06：学习计划任务直达。置为某题组 id 时，清单到位后**自动开进该组**（只开一次），
     * 用户退出练习看到正常目录，再退一层回计划。null = 普通入口，行为不变。
     */
    initialBankId: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(owner) { PracticeStore.of(context, owner) }
    val api = remember { ApiFactory.create(readTimeoutSeconds = 90L) }

    // 登录态：私有题库那条路服务端挂了 `JwtAuthGuard`，没登录就不显示这一段。
    // 只在这里取一次令牌，**不改调用链签名** —— `owner` 本来就是 `SessionStore.ownerKey()` 派生的，
    // 换账号时 `owner` 变了，这里自然跟着重算（所以 key 用 owner）。
    val bearer = remember(owner, context) {
        runCatching { cn.tonglu.app.data.SessionStore(context.applicationContext).read()?.token }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
    }

    var banks by remember(owner) { mutableStateOf<List<QuestionBank>>(emptyList()) }
    var loading by remember(owner) { mutableStateOf(true) }
    // 每个题库的下载进度（0~100）；不在下载中的 id 不在表里。
    var progress by remember(owner) { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    // 让错题数在提交后立刻刷新。
    var revision by remember { mutableIntStateOf(0) }

    var instructionsOpen by remember(owner) { mutableStateOf(false) }
    var catalogSection by rememberSaveable(owner) { mutableStateOf("papers") }
    var catalogFilter by rememberSaveable(owner) { mutableStateOf("cet4") }
    var catalogYear by rememberSaveable(owner) { mutableIntStateOf(0) }
    var expandedGroups by remember(owner) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    val catalogRows = remember(banks, catalogSection, catalogFilter, catalogYear, expandedGroups) {
        val selected = if (catalogSection == "papers" && catalogYear != 0)
            banks.filter { examGroupId(it)?.startsWith("$catalogFilter-$catalogYear-") == true } else banks
        when (catalogSection) {
            "mine" -> emptyList()
            "drills" -> bankCatalogRows(banks, "other", expandedGroups)
            else -> bankCatalogRows(selected, catalogFilter, expandedGroups)
        }
    }
    val answeredKeys = remember(owner, revision) { store.attempts().map { it.questionKey }.toSet() }

    // 首次进入：本机 assets 先顶上（不空屏），再异步拉远端清单。
    LaunchedEffect(owner) {
        banks = QuestionBankStore.banks(context)
        loading = false
        runCatching { QuestionBankStore.banks(context, api) }
            .onSuccess { remote ->
                if (remote.isNotEmpty()) banks = mergeBanks(banks, remote)
            }
            .onFailure { error = it.message }
    }

    // 「我的题库」（scope=PRIVATE，docs/plan/58 §4）：**与公开清单分开**拉、分开显示。
    //   mine == null  → 没登录、或拉取失败且无缓存（界面说「读不到」，不说「你没有」）
    //   mine == 空表  → 拉到了，你确实一份都没传（界面说「你还没有上传」）
    //   mine == 非空  → 逐份列出，可下载后直接做
    var mine by remember(owner, bearer) { mutableStateOf<List<QuestionBank>?>(null) }
    var mineLoading by remember(owner, bearer) { mutableStateOf(false) }
    LaunchedEffect(owner, bearer) {
        if (bearer.isNullOrBlank()) { mine = null; return@LaunchedEffect }
        QuestionBankStore.cachedMyBanks(context, owner)?.let { mine = it }
        mineLoading = true
        val fresh = QuestionBankStore.myBanks(context, api, bearer, owner)
        if (fresh != null) mine = fresh
        mineLoading = false
    }

    // route: "list"；否则是正在做的题库 id。
    var openBankId by remember(owner) { mutableStateOf<String?>(null) }
    var diagnosisOpen by remember(owner) { mutableStateOf(false) }
    var realPaperSite by remember(owner) { mutableStateOf<RealPaperSite?>(null) }

    // 弹层自识别左边缘拖动（Dialog 收不到系统进度，见 EnglishHubScreen 里的说明）。
    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )
    Dialog(
        onDismissRequest = { motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { motion.requestClose() }) {   // 走 motion：与返回键同一条退场动画
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回英语")
                    }
                    Text("做题", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("选择考次，下载后离线练习", fontWeight = FontWeight.SemiBold)
                            Text("真题为第三方整理件，专项为自编练习。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { realPaperSite = RealPaperSite.XDF }) { Text("真题原站") }
                                TextButton(onClick = { realPaperSite = RealPaperSite.NEEA }) { Text("官方考试网") }
                                TextButton(onClick = { instructionsOpen = !instructionsOpen }) { Text(if (instructionsOpen) "收起说明" else "说明") }
                            }
                            if (instructionsOpen) {
                                Text("题组下载后保存在本机。不同来源、套卷的选项顺序可能不同，请核对原卷。" +
                                    "官方考试网用于报名、大纲与查分，不提供历年真题下载。" +
                                    "自编练习不是真题；练习结果不预测考试分数。来源和权利说明见各题组。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    item {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("papers" to "真题整理", "drills" to "专项练习", "mine" to "我的题库").forEach { (key, title) ->
                                FilterChip(selected = catalogSection == key, onClick = { catalogSection = key }, label = { Text(title) })
                            }
                        }
                    }
                    if (catalogSection == "mine" && bearer.isNullOrBlank()) item {
                        Text("登录后查看你的个人题库", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // 诊断属于专项，不挤占真题考次选择。
                    if (catalogSection == "drills") item {
                        OutlinedCard(
                            onClick = { if (banks.any { it.size > 0 }) diagnosisOpen = true },
                            enabled = banks.any { it.size > 0 },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
                                    shape = RoundedCornerShape(14.dp)) {
                                    Icon(Icons.Outlined.Timeline, null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.padding(12.dp).size(22.dp))
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("词汇自测", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "按难度抽 30 题左右，5–8 分钟；用来看看自己目前在哪一档。" +
                                            "不做分数预测。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    // 「我的题库」—— 只有登录后才出现，且**注明它不对外**
                    // （用户最担心的就是「我存进来的题会不会被别人看到」）。
                    if (catalogSection == "mine" && !bearer.isNullOrBlank()) {
                        item {
                            Text("我的题库", fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                        item {
                            Text("你自己上传的题库，只有你这个账号看得到，不对其他用户开放。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        when {
                            mineLoading && mine == null -> item {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Text("正在读取你的题库…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            mine == null -> item {
                                Text("暂时读不到你的题库（网络或登录态异常）。已下载过的仍然可以在下面用。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                            mine.orEmpty().isEmpty() -> item {
                                Text("还没有已同步的个人题库。个人题库只对当前账号可见。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            else -> itemsIndexed(mine.orEmpty(), key = { _, b -> "mine-${b.id}" }) { _, bank ->
                                val downloading = progress[bank.id]
                                BankCard(
                                    bank = bank,
                                    wrongCount = remember(owner, bank.id, revision) {
                                        store.wrongKeys().count { it.startsWith("${bank.id}:") }
                                    },
                                    downloading = downloading != null,
                                    progressPercent = downloading,
                                    badge = "私有",
                                    onClick = {
                                        if (bank.remotePending && !QuestionBankStore.isDownloaded(context, bank.id)) {
                                            progress = progress + (bank.id to 0)
                                            scope.launch {
                                                runCatching {
                                                    QuestionBankStore.downloadMine(
                                                        context, api, bearer, bank.id,
                                                        expectBytes = bank.remoteBytes,
                                                        expectSha256 = bank.remoteSha256,
                                                    ) { p -> progress = progress + (bank.id to p) }
                                                }.onSuccess { downloaded ->
                                                    mine = mine.orEmpty().map { if (it.id == downloaded.id) downloaded else it }
                                                }.onFailure { error = it.message }
                                                progress = progress - bank.id
                                                if (QuestionBankStore.isDownloaded(context, bank.id)) openBankId = bank.id
                                                else error = error ?: "题库下载失败，请稍后再试"
                                            }
                                        } else {
                                            openBankId = bank.id
                                        }
                                    },
                                )
                            }
                        }
                    }

                    if (catalogSection != "mine") {
                    item {
                        Text(if (catalogSection == "papers") "按考次选题" else "自编专项练习", fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                    if (loading) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("正在读取题组…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else if (banks.isEmpty()) {
                        item {
                            Text("本机暂时没有可用的题组。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    if (catalogSection == "papers") item {
                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("cet4" to "四级", "cet6" to "六级").forEach { (value, label) ->
                                    FilterChip(selected = catalogFilter == value, onClick = { catalogFilter = value; catalogYear = 0; expandedGroups = emptyMap() }, label = { Text(label) })
                                }
                            }
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = catalogYear == 0, onClick = { catalogYear = 0 }, label = { Text("全部年份") })
                                examBankGroups(banks).filter { it.level == catalogFilter }.map { it.year }.distinct().forEach { year ->
                                    FilterChip(selected = catalogYear == year, onClick = { catalogYear = year; expandedGroups = emptyMap() }, label = { Text("$year 年") })
                                }
                            }
                        }
                    }
                    if (!loading && catalogRows.isEmpty()) item {
                        Text("这个分类暂时没有题组。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    itemsIndexed(catalogRows, key = { _, row -> row.key }) { _, row ->
                        if (row is BankCatalogRow.Group) {
                            OutlinedCard(onClick = { expandedGroups = if (row.expanded) emptyMap() else mapOf(row.group.id to true) },
                                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(row.group.title, fontWeight = FontWeight.SemiBold)
                                        Text("${row.group.banks.size} 个题型 · 第三方整理件", style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(if (row.expanded) "收起" else "展开", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        } else if (row is BankCatalogRow.Bank) {
                            val bank = row.bank
                            val downloading = progress[bank.id]
                            BankCard(
                                bank = bank,
                                answeredCount = bank.questionList.count { it.gradable && bank.keyOf(it) in answeredKeys },
                                displayTitle = if (row.grouped) bank.title.substringAfterLast(" · ") else bank.title,
                                compact = row.grouped,
                                wrongCount = remember(owner, bank.id, revision) {
                                    store.wrongKeys().count { it.startsWith("${bank.id}:") }
                                },
                                downloading = downloading != null,
                                progressPercent = downloading,
                                onClick = {
                                    if (bank.remotePending && !QuestionBankStore.isDownloaded(context, bank.id)) {
                                        error = null
                                        progress = progress + (bank.id to 0)
                                        scope.launch {
                                            runCatching {
                                                QuestionBankStore.download(context, api, bank.id,
                                                    expectBytes = bank.remoteBytes,
                                                    expectSha256 = bank.remoteSha256) { p ->
                                                    progress = progress + (bank.id to p)
                                                }
                                            }.onSuccess { downloaded ->
                                                banks = banks.map { if (it.id == downloaded.id) downloaded else it }
                                            }.onFailure { error = it.message }
                                            progress = progress - bank.id
                                            // 下载完直接进去做题
                                            if (QuestionBankStore.isDownloaded(context, bank.id)) openBankId = bank.id
                                            else error = error ?: "题库下载失败，请稍后再试"
                                        }
                                    } else {
                                        openBankId = bank.id
                                    }
                                },
                            )
                        }
                    }
                    }
                    error?.let { msg ->
                        item {
                            Text("提示：$msg", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    item {
                        Text(
                            "听说材料里的题目，也会计入错题本。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    item { Spacer(Modifier.size(24.dp)) }
                }
            }
        }
    }
    // 原站也是目录的子层，关层后仍回到题库；不抓取或再分发第三方试卷。
    // 两个入口都只做导航（plan/58 L1 + HANDOFF §六）：新东方 cet4-6.xdf.cn 收录到
    // 2026.6 且持续更新（旧 2012 专题 daxue.xdf.cn/zhuanti/yingyu46ji13 已废弃）；
    // 官方 cet.neea.edu.cn 是报名/大纲/查分的权威入口。
    realPaperSite?.let { site ->
        // 目录是独立窗口，浏览器只是一层画布，必须用新窗口承载才能显示在目录上方。
        Dialog(
            onDismissRequest = { OverlayDismissGuard.arm(); realPaperSite = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            CampusBrowserScreen(
                url = site.url,
                title = site.title,
                onClose = { OverlayDismissGuard.arm(); realPaperSite = null },
            )
        }
    }
    // 题库目录保持在下面，练习和自测只是其上方的子层，返回时露出原目录。
    if (diagnosisOpen) {
        VocabDiagnosisPane(
            banks = banks,
            store = store,
            onExit = { diagnosisOpen = false },
            onAnswered = { revision++; onPracticeChanged() },
        )
    }

    // 今日卡「错题复习」直达：等题库清单到位后自动挑一个组开进去（只挑一次）。
    // `wrongReviewBankId` 记住「这次是复习进来的」，退出时清掉 —— 否则用户从目录再点别的组，
    // 还会被当成错题复习，开局强行只显示错题。
    var wrongReviewBankId by remember(owner) { mutableStateOf<String?>(null) }
    LaunchedEffect(wrongReview, banks, owner) {
        if (!wrongReview || wrongReviewBankId != null || banks.isEmpty()) return@LaunchedEffect
        val best = bankWithMostWrong(banks.map { it.id }, store.wrongKeys()) ?: return@LaunchedEffect
        wrongReviewBankId = best
        openBankId = best
    }

    // EF06：计划任务直达。与错题复习同一模式 —— 只自动开一次，退出练习后停在目录，
    // 不会每次回到本屏都强行把用户塞回同一组。
    var planBankOpened by remember(owner) { mutableStateOf<String?>(null) }
    LaunchedEffect(initialBankId, banks, owner) {
        if (initialBankId == null || planBankOpened != null || banks.isEmpty()) return@LaunchedEffect
        if (banks.none { it.id == initialBankId && it.questionList.isNotEmpty() }) return@LaunchedEffect
        planBankOpened = initialBankId
        openBankId = initialBankId
    }

    val openBank = openBankId?.let { id -> banks.firstOrNull { it.id == id } }
    if (openBank != null) {
        BankQuizPane(
            bank = openBank,
            owner = owner,
            store = store,
            onExit = { openBankId = null; wrongReviewBankId = null },
            onAnswered = { revision++; onPracticeChanged() },
            initialOnlyWrong = wrongReview && openBank.id == wrongReviewBankId,
        )
    }


}

/** 合并本机与远端清单：以远端为准，本机有题面的优先保留题面（避免已下载的被空元数据顶掉）。 */
internal fun mergeBanks(local: List<QuestionBank>, remote: List<QuestionBank>): List<QuestionBank> {
    val byId = LinkedHashMap<String, QuestionBank>()
    for (bank in remote) byId[bank.id] = bank
    for (bank in local) {
        val existing = byId[bank.id]
        // 本机有题面、远端是空壳 → 用本机的（题面已在磁盘上）
        if (existing == null || (bank.size > 0 && existing.size == 0)) byId[bank.id] = bank
    }
    return byId.values.toList()
}

@Composable
private fun BankCard(
    bank: QuestionBank,
    wrongCount: Int,
    downloading: Boolean,
    progressPercent: Int?,
    badge: String? = null,
    answeredCount: Int = 0,
    displayTitle: String = bank.title,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        enabled = !downloading,
        modifier = Modifier.fillMaxWidth().padding(start = if (compact) 16.dp else 0.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.padding(if (compact) 9.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                shape = RoundedCornerShape(14.dp)) {
                Icon(if (bank.remotePending) Icons.Outlined.Download else Icons.Outlined.Quiz, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(if (compact) 6.dp else 12.dp).size(if (compact) 20.dp else 24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(displayTitle, fontWeight = FontWeight.SemiBold)
                    if (badge != null) {
                        Surface(color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(6.dp)) {
                            Text(badge, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                if (bank.remotePending) {
                    Text("云端题组 · 约 ${formatBytes(bank.remoteBytes)} · 点一下下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    // 题量与难度分布：如实列出，不写「覆盖四级全部考点」这种没依据的话。
                    Text(
                        "${bank.size} 题 · " + Difficulty.order
                            .mapNotNull { d -> bank.countOf(d).takeIf { it > 0 }?.let { "${Difficulty.labelOf(d)} $it" } }
                            .joinToString(" / "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (answeredCount > 0) {
                    val total = bank.questionList.count { it.gradable }
                    Text("已练 $answeredCount / $total 题 · ${if (answeredCount < total) "点开继续" else "可重新练习"}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { (progressPercent ?: 0) / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
                if (wrongCount > 0) {
                    Text("错题 $wrongCount 道待复习", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            if (!downloading) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatBytes(bytes: Int): String = when {
    bytes <= 0 -> "大小未知"
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

/** 一题在列表里的作答状态。 */
private enum class ProgressMark { None, Correct, Wrong }

/**
 * 题库作答页：在本组题里逐题做，顶部可切「全部 / 只练错题」。
 *
 * 与材料题的 `QuestionPane` 保持同一套交互语义（未提交不露答案、追加式记录、
 * 提交后才渲染解析），但**不复用那个 Composable** —— 它绑定 `ListeningMaterial`
 * （要显示「听原文第 N 句」），题库题可能只绑定整卷录音，不一定有逐句时间轴。
 */
@Composable
private fun BankQuizPane(
    bank: QuestionBank,
    owner: String,
    store: PracticeStore,
    onExit: () -> Unit,
    onAnswered: () -> Unit,
    /**
     * 从今日卡的「错题复习」直接进来时置 true：**开局就只练错题**。
     * 以前只能自己进组、再点右上角「只练错题」——入口藏在三级里，等于没有。
     */
    initialOnlyWrong: Boolean = false,
) {
    var revision by remember { mutableIntStateOf(0) }
    val wrongKeys = remember(owner, bank.id, revision) { store.wrongKeys().filter { it.startsWith("${bank.id}:") }.toSet() }
    var reviewKeys by remember(owner, bank.id) { mutableStateOf(wrongKeys) }
    val wrongHere = reviewQuestions(bank, reviewKeys)

    // 没有错题时**不**预置成 true：那会开出一个空列表，看起来像卡住。
    var onlyWrong by remember(owner, bank.id) { mutableStateOf(initialOnlyWrong && wrongKeys.isNotEmpty()) }
    var index by rememberSaveable(owner, bank.id) {
        mutableIntStateOf(nextUnansweredIndex(bank, store.attempts().map { it.questionKey }.toSet()))
    }
    // B5 A2：题目内「问 AI」。配置进加密存储；没配就引导去设置，不发请求。
    val context = androidx.compose.ui.platform.LocalContext.current
    val aiStore = remember { EnglishAiStore(context) }
    var aiConfig by remember { mutableStateOf(aiStore.load()) }
    var aiScope by remember(bank.id) { mutableStateOf<AiScope?>(null) }
    var aiQuestionKey by remember(owner, bank.id) { mutableStateOf("") }
    var aiSettingsOpen by remember { mutableStateOf(false) }
    // 原卷/答案直链（第三方整理件，公开可下）—— 用 App 内浏览器打开，不内嵌、不转发。
    var paperUrl by remember(bank.id) { mutableStateOf<String?>(null) }

    val list = if (onlyWrong) wrongHere else bank.questionList
    // 切筛选后下标可能越界 —— 收敛回合法范围，不显示「第 3 / 2 题」这种。
    val safeIndex = index.coerceIn(0, (list.size - 1).coerceAtLeast(0))

    // 弹层自识别左边缘拖动（Dialog 收不到系统进度，见 EnglishHubScreen 里的说明）。
    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onExit() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )
    Dialog(
        onDismissRequest = { motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { motion.requestClose() }) {   // 走 motion：与返回键同一条退场动画
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回题组列表")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(bank.title, fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall)
                        Text(if (list.isEmpty()) "没有可做的题" else "第 ${safeIndex + 1} / ${list.size} 题",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !onlyWrong, onClick = { onlyWrong = false; index = 0 },
                                label = { Text("全部题（${bank.size}）") })
                            FilterChip(
                                selected = onlyWrong,
                                onClick = { if (wrongKeys.isNotEmpty()) { reviewKeys = wrongKeys; onlyWrong = true; index = 0 } },
                                enabled = onlyWrong || wrongKeys.isNotEmpty(),
                                label = {
                                    Text(if (onlyWrong) "本轮错题（${wrongHere.size}）" else if (wrongKeys.isEmpty()) "错题（空）" else "只练错题（${wrongKeys.count { it.startsWith("${bank.id}:") }}）")
                                },
                            )
                        }
                    }
                    // 显示题组发布时的来源，第三方整理件不冒充官方发布。
                    item {
                        Text(bank.sourceNote, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // 原卷/答案解析直链（第三方整理件，公开可下）。只给入口，不内嵌文件。
                    bank.sourceLink?.let { url ->
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { paperUrl = url }) { Text("下载原卷 PDF") }
                                Text("第三方整理件，仅供学习", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (list.isEmpty()) {
                        item {
                            Text("错题里暂时没有题 —— 先做几道，答错的会自动收进来。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        val question = list[safeIndex]
                        item(key = bank.keyOf(question)) {
                            BankQuestionCardInternal(
                                bank = bank, question = question, store = store, owner = owner,
                                onAnswered = { revision++; onAnswered() },
                                questionIndex = safeIndex,
                                onAskAi = { scope ->
                                    aiQuestionKey = bank.keyOf(question)
                                    if (aiConfig.ready()) aiScope = scope else aiSettingsOpen = true
                                },
                            )
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = { if (safeIndex > 0) index = safeIndex - 1 },
                                    enabled = safeIndex > 0,
                                ) { Text("上一题") }
                                Button(
                                    onClick = { if (safeIndex < list.size - 1) index = safeIndex + 1 },
                                    enabled = safeIndex < list.size - 1,
                                ) { Text("下一题") }
                                Text("${safeIndex + 1} / ${list.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item { Spacer(Modifier.size(24.dp)) }
                }
            }
        }
    }
    // B5 A2：题目内「问 AI」。范围键带 bank.id，换题组不会串上一组的历史。
    aiScope?.let { scope ->
        AiScopeDialog(
            owner = owner,
            scopeKey = aiQuestionKey,
            scope = scope,
            title = "问 AI · ${bank.title}",
            config = aiConfig,
            quickQuestions = listOf("这题考什么？", "为什么这个选项对？", "其它选项错在哪？", "帮我梳理相关语法点"),
            onOpenSettings = { aiSettingsOpen = true },
            onDismiss = { aiScope = null },
        )
    }
    if (aiSettingsOpen) EnglishAiSettingsDialog(store = aiStore, initial = aiConfig,
        onSaved = { aiConfig = it }, onDismiss = { aiSettingsOpen = false })

    // 原卷页是**独立窗口**，必须自己一层 Dialog 才压得住上面的做题层（同 N11 那次的教训）。
    paperUrl?.let { url ->
        Dialog(
            onDismissRequest = { OverlayDismissGuard.arm(); paperUrl = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            CampusBrowserScreen(
                url = url,
                title = "原卷 / 答案解析",
                onClose = { OverlayDismissGuard.arm(); paperUrl = null },
            )
        }
    }
}

private val okGreen = Color(0xFF2E7D32)
private val badRed = Color(0xFFC62828)

/**
 * 一道题卡（题库与诊断共用）。
 *
 * `allowUnknown`：T6 诊断用的「不认识」选项。它**不是**题目自带的选项，
 * 单独渲染在选项之下，选了它就不参与判分，只记一次「不认识」。
 *
 * 声明为 internal 是为了让 `VocabDiagnosisPane` 复用同一份实现 ——
 * 题卡的判分/不露答案/记录写入逻辑**只该有一份**，两处各写一遍必然走样。
 */
@Composable
internal fun BankQuestionCardInternal(
    bank: QuestionBank,
    question: BankQuestion,
    store: PracticeStore,
    /** 主观题作答按账号分桶存，必须知道是谁的（FINDINGS #5：不分桶会把 A 的草稿给 B）。 */
    owner: String = "",
    onAnswered: () -> Unit,
    allowUnknown: Boolean = false,
    /** 题目序号（从 0 起），用于「问 AI」面板显示「正在问：第 N 题」。 */
    questionIndex: Int = 0,
    /** 有 AI 配置时给一个「问 AI」入口；为 null 时不显示（如诊断页不提供）。 */
    onAskAi: ((AiScope) -> Unit)? = null,
) {
    val questionKey = bank.keyOf(question)
    var chosen by remember(questionKey) { mutableIntStateOf(-1) }
    var submitted by remember(questionKey) { mutableStateOf(false) }
    var wasCorrect by remember(questionKey) { mutableStateOf(false) }
    var markedUnknown by remember(questionKey) { mutableStateOf(false) }
    val stats = remember(questionKey, submitted) { store.statsOf(questionKey) }

    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Difficulty.labelOf(question.difficulty),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // 原音优先：真题听力卷面只印选项，题干在录音里 —— 所以放音要在题面之前。
            val originalAudio = question.audioUrl?.takeIf { it.isNotBlank() }
            if (originalAudio != null) {
                BankOriginalAudio(originalAudio, questionKey, question.audioStartMs, question.audioEndMs)
            } else {
                question.audioText?.takeIf { it.isNotBlank() }?.let { EnglishPromptAudio(it, questionKey) }
            }
            // 听力卷没有任何音源时必须当场说明。以前这里什么都不显示，用户看到的是
            // 「一句让他去听录音的提示 + 四个选项 + 提交答案」—— 题干根本听不到，只能瞎猜，
            // 猜错还会被当成真错题收进错题本。判据见 [questionHasNoAudio]。
            if (questionHasNoAudio(bank, question)) {
                Text(
                    "本题所属卷没有接入音频：听力题干在录音里，卷面只印选项，所以这里听不到题干。" +
                        "可先点上方「下载原卷 PDF」对照原卷，或到「专项」里做自编听力（合成朗读）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(question.prompt.ifBlank { "（这道题没有题面）" }, fontWeight = FontWeight.SemiBold)

            if (bank.subjective && question.type == "prompt") {
                Text("主观练习 · 不自动判分", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge)
                Text("先按题干自行写作或翻译，再对照原卷资料。这里不会给出虚假的对错或考试分数。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // 本地草稿 + 提交 + 笔记（plan §5 第 4 条）。
                // 🔴 三步都**不产生对错**：没有标准答案下标，机器判不了分，
                // 也不写进 PracticeStore（那张表的 correct 会被错题本读走，
                // 塞一条假的进去，错题本就会冒出一道根本判不了的题）。
                SubjectiveAnswerBox(owner = owner, questionKey = questionKey)
                onAskAi?.let { ask ->
                    TextButton(onClick = { ask(cn.tonglu.app.data.english.EnglishAiScopes.question(
                        questionIndex, question.prompt, emptyList(), -1, question.explanation)) }) {
                        Text("问 AI · 练习思路")
                    }
                }
            } else if (!question.gradable) {
                // 数据不全的题老实说做不了，不摆一排点了没反应的选项。
                Text("这道题的数据不完整（缺选项或答案），本机不做判分。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else if (!submitted) {
                // 未提交：只渲染选项，不泄露任何对错信息。
                question.options.forEachIndexed { i, option ->
                    BankChoiceRow(option, chosen == i, letter(i), BankChoiceState.Neutral) {
                        chosen = i; markedUnknown = false
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        if (chosen < 0) return@Button
                        val correct = chosen == question.answer
                        wasCorrect = correct
                        submitted = true
                        try {
                            // 记录里带 bankId 派生的题目键：跨题库不串（docs/plan/57 §一）。
                            store.record(
                                questionId = question.id,
                                materialId = bank.id,
                                materialTitle = bank.title,
                                chosen = chosen, correct = correct,
                                questionKey = questionKey,
                                mode = PracticeMode.QUIZ,
                            )
                            onAnswered()
                        } catch (cause: Exception) {
                            android.util.Log.e("PracticeStore", "题库作答写入失败", cause)
                        }
                    }) { Text("提交答案") }
                    if (chosen >= 0) TextButton(onClick = { chosen = -1 }) { Text("清除选择") }
                    // B5 A2：做题时直接问 AI（范围明确是「第 N 题」）。放在未提交页也提供 ——
                    // 「这题不会、题干看不懂」正是最需要问的时候，不能只让答完才问。
                    onAskAi?.let { ask ->
                        TextButton(onClick = { ask(cn.tonglu.app.data.english.EnglishAiScopes.question(
                            questionIndex, question.prompt, question.options, question.answer, question.explanation)) }) {
                            Text("问 AI")
                        }
                    }
                }
                if (allowUnknown) {
                    // T6：诊断里允许「不认识」——判分把「不认识」算作未掌握，
                    // **不伪造一个对错**（docs/plan/56 §4.4）。
                    TextButton(onClick = {
                        markedUnknown = true
                        wasCorrect = false
                        submitted = true
                        try {
                            store.record(
                                questionId = question.id,
                                materialId = bank.id,
                                materialTitle = bank.title,
                                chosen = -1, correct = false,
                                questionKey = questionKey,
                                mode = PracticeMode.QUIZ,
                            )
                            onAnswered()
                        } catch (cause: Exception) {
                            android.util.Log.e("PracticeStore", "题库作答写入失败", cause)
                        }
                    }) { Text("不认识这个词 / 这句") }
                }
            } else {
                question.options.forEachIndexed { i, option ->
                    val state = when {
                        i == question.answer -> BankChoiceState.Correct
                        i == chosen -> BankChoiceState.Wrong
                        else -> BankChoiceState.Neutral
                    }
                    BankChoiceRow(option, chosen == i, letter(i), state, null)
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (markedUnknown) {
                        Icon(Icons.Outlined.Close, null, tint = badRed, modifier = Modifier.size(18.dp))
                        Text("已记为「不认识」，正确答案是 ${letter(question.answer)}",
                            color = badRed, fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(if (wasCorrect) Icons.Outlined.Check else Icons.Outlined.Close, null,
                            tint = if (wasCorrect) okGreen else badRed, modifier = Modifier.size(18.dp))
                        Text(if (wasCorrect) "答对了" else "答错了，正确答案是 ${letter(question.answer)}",
                            color = if (wasCorrect) okGreen else badRed, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (!question.audioText.isNullOrBlank()) {
                    Text("听力原文", style = MaterialTheme.typography.labelLarge)
                    Text(question.audioText.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
                if (question.explanation.isNotBlank()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("解析", style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold)
                            Text(question.explanation, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        chosen = -1; submitted = false; wasCorrect = false; markedUnknown = false
                    }) { Text("再练这一题") }
                    stats?.let {
                        Text(it.label, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private enum class BankChoiceState { Neutral, Correct, Wrong }

@Composable
private fun BankChoiceRow(
    label: String, selected: Boolean, marker: String,
    state: BankChoiceState, onClick: (() -> Unit)?,
) {
    val border = when (state) {
        BankChoiceState.Correct -> okGreen
        BankChoiceState.Wrong -> badRed
        BankChoiceState.Neutral ->
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    }
    val container = when (state) {
        BankChoiceState.Correct -> okGreen.copy(alpha = 0.12f)
        BankChoiceState.Wrong -> badRed.copy(alpha = 0.12f)
        BankChoiceState.Neutral ->
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else MaterialTheme.colorScheme.surface
    }
    OutlinedCard(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = container),
        border = androidx.compose.foundation.BorderStroke(1.dp, border),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(marker, fontWeight = FontWeight.SemiBold, color = border)
            Text(label, modifier = Modifier.weight(1f))
            when (state) {
                BankChoiceState.Correct -> Icon(Icons.Outlined.Check, "正确", tint = okGreen,
                    modifier = Modifier.size(18.dp))
                BankChoiceState.Wrong -> Icon(Icons.Outlined.Close, "你选的", tint = badRed,
                    modifier = Modifier.size(18.dp))
                BankChoiceState.Neutral -> Unit
            }
        }
    }
}

private fun letter(index: Int): String =
    if (index in 0..25) ('A' + index).toString() else "${index + 1}"

/**
 * 主观题的「写 → 交 → 记」三件套（plan §5 第 4 条）。
 *
 * 🔴 **全程不判分**。这里只有三件事：写着的一版（草稿）、交上去的那一版（提交快照）、
 * 对照原卷后自己的笔记。没有对错、没有分数、也不进错题本 ——
 * 主观题没有标准答案下标，任何「判分」都是编的。
 *
 * 为什么提交要**单独一步**而不是「保存即提交」：
 * 用户会反复改（写第二版、第三版），如果保存即提交，就分不清「我当时交的是哪一版」。
 * 所以草稿继续可改，提交定快照；交完又改了会明确提醒，让用户自己决定要不要再交。
 */
@Composable
private fun SubjectiveAnswerBox(owner: String, questionKey: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context, owner) { SubjectiveStore(context, owner) }
    var saved by remember(owner, questionKey) { mutableStateOf(store.of(questionKey)) }
    var draft by remember(owner, questionKey) { mutableStateOf(saved.draft) }
    var note by remember(owner, questionKey) { mutableStateOf(saved.note) }
    var hint by remember(owner, questionKey) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            label = { Text("我的作答（草稿）") },
            placeholder = { Text("先自己写一版，写完点下面的「提交这一版」") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (draft.isBlank()) { hint = "还没写内容，先写点什么再提交。"; return@Button }
                store.submit(questionKey, draft)
                saved = store.of(questionKey)
                hint = "已提交这一版。交完还可以继续改，改完再提交一次就会覆盖快照。"
            }) { Text(if (saved.hasSubmitted) "再提交一版" else "提交这一版") }
            TextButton(onClick = {
                store.saveDraft(questionKey, draft)
                saved = store.of(questionKey)
                hint = "草稿已存在本机，没提交。"
            }) { Text("只存草稿") }
        }
        // 提交快照：明确告诉用户「交上去的是这一版」，以及交完又改了没有。
        if (saved.hasSubmitted) {
            Text("已提交的一版：", style = MaterialTheme.typography.labelMedium)
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(saved.submitted, Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium)
            }
            if (saved.draftNewerThanSubmit && draft != saved.submitted) {
                Text("你交完之后又改了草稿 —— 想更新提交版本的话，再点一次「再提交一版」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
        androidx.compose.material3.OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            label = { Text("对照笔记") },
            placeholder = { Text("照原卷或解析看完之后，自己想记下来的东西") },
        )
        TextButton(onClick = {
            store.saveNote(questionKey, note)
            saved = store.of(questionKey)
            hint = "笔记已存在本机。"
        }) { Text("保存笔记") }
        hint?.let { Text(it, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
