package cn.tonglu.app.ui.learning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.LearningPlan
import cn.tonglu.app.data.PlanDraft
import cn.tonglu.app.data.PlanStore
import cn.tonglu.app.data.PlanTask
import cn.tonglu.app.data.PlanTaskProof
import cn.tonglu.app.data.answeredDistinctSinceBaseline
import cn.tonglu.app.data.english.EnglishAi
import cn.tonglu.app.data.english.EnglishAiException
import cn.tonglu.app.data.english.EnglishAiStore
import cn.tonglu.app.data.english.EnglishAiTurn
import cn.tonglu.app.data.focus.PomodoroController
import cn.tonglu.app.data.listening.PracticeStore
import cn.tonglu.app.data.listening.QuestionAttempt
import cn.tonglu.app.data.proof
import cn.tonglu.app.data.question.QuestionBank
import cn.tonglu.app.data.question.QuestionBankStore
import cn.tonglu.app.data.requiredQuestionCount
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 学习计划（docs/plan/56 §6.3 A4：草稿 → 确认 → 执行）。
 *
 * **这条流程的核心是「确认」这一步不能省**：
 * - 生成出来的计划先是 `draft`，显示为「建议」并带一个明确的「确认计划」按钮；
 * - 未确认前，界面直说「还没确认，不会出现在今日安排里」；
 * - 确认后才是 `active`，任务才可勾选。
 *
 * EF06（2026-10-17）后的两条硬规矩：
 * - **只输出可执行任务**：计划只从「本机有题面的真实题组」里生成，每条任务带真实
 *   `bankId` 与真实题数；本机没有可做题组时直说去下载，不再合成「做一组练习题」
 *   这种点不进去的占位卡。AI 也只做选择题（见 [PlanDraft.aiPrompt]），不能发明任务。
 * - **完成以真实作答为证**：确认计划那一刻记基准时间，之后在本任务题组里
 *   「不同题目键」作答数达到任务题数就自动勾掉（见 [autoCompleteDone]）；
 *   任务可以直接点「去做题」进 [QuestionBankScreen]，退出回到计划。
 *   有题组的任务**禁手勾**（UI 禁用 + 回调拒收双重把关）—— 勾了就是「学完了」的证据，
 *   不能靠点圆圈造出来；只有无题组的旧任务/自报任务保留手勾，并明确标「手动」。
 * - **历史数据按真实作答重算（2026-10-18 收口）**：470d2bf 之前被手勾成 DONE 的题库任务
 *   并没有基准后的作答证据，不能继续显示成完成 —— 展示层统一走 [PlanTask.proof]：
 *   有基准的按基准后不同题目计数；无基准的标「待核对」且**不计入完成度**，
 *   但**不改写**任务原始状态，也不动任何真实作答记录。界面另给
 *   「从现在起重新核对」让历史任务显式建立新基准（不是把旧手勾洗白）。
 * - **关联专注**：已确认的任务卡带「开始专注」，走 root 的 [PomodoroController]
 *   （契约 `work/pomodoro-core/CONTRACT.md`）；已有计时在跑时只提示去继续，不覆盖别人的会话。
 *
 * 有没有 AI Key 都能用：默认走离线模板（用真实题组），配了 API 才多一个「让 AI 给建议」。
 */
@Composable
internal fun LearningPlanPane(
    owner: String,
    /** 旧调用点兼容参数（EnglishHubScreen 归 AI1，本轮不改它）：生成已改用本屏自载的真实题库清单，此参数仅保留签名。 */
    @Suppress("UNUSED_PARAMETER") bankTitles: List<String>,
    bookName: String?,
    onClose: () -> Unit,
    /** 有 Key 时可选：调用方给一个「拿建议文本」的挂起函数；无 Key 传 null（本屏会自己走 EnglishAi 通道）。 */
    aiSuggest: (suspend () -> String)? = null,
) {
    val context = LocalContext.current
    val store = remember(owner) { PlanStore(context) }
    val scope = rememberCoroutineScope()

    var plans by remember(owner) { mutableStateOf(store.load(owner)) }
    // 本机题库与作答记录：EF06 的资源白名单与完成回写都从这里来，不靠调用方传名字。
    var banks by remember(owner) { mutableStateOf<List<QuestionBank>>(emptyList()) }
    var attempts by remember(owner) { mutableStateOf<List<QuestionAttempt>>(emptyList()) }
    // 作答记录是否已读进来：读到之前不拿空列表下「未完成」结论。
    var practiceLoaded by remember(owner) { mutableStateOf(false) }
    // 「读不出来」≠「没有记录」：读盘失败时完成度一律显示「无法核对」，不冒充 0/N。
    var practiceFailed by remember(owner) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var expanded by remember(owner) { mutableStateOf<String?>(null) }
    // 从任务点进「做题」的题组 id；null = 没开。
    var openBankId by remember(owner) { mutableStateOf<String?>(null) }
    val aiReady = remember { EnglishAiStore(context).load().ready() }
    // 关联专注：root 的番茄钟核心 API（契约见 work/pomodoro-core/CONTRACT.md）。
    // C 只发起与提示，不画计时 UI —— 阶段/轮次/剩余时间全部归 root 的状态机与浮层。
    val pomodoro = remember { PomodoroController.get(context) }

    fun persist() { store.save(owner, plans) }
    fun dismiss() { OverlayDismissGuard.arm(); onClose() }

    /** 从任务卡发起关联专注：taskId 记进计时会话，root 侧凭它回写来源。 */
    fun beginFocus(taskId: String, label: String) {
        scope.launch {
            try {
                pomodoro.bindOwner(owner)
                val started = pomodoro.startTask(taskId, label)
                notice = if (started) "「$label」的专注计时已开始，到番茄钟里看进度。"
                else "已有一个进行中的计时，去番茄钟继续就好；这份安排没有被覆盖。"
            } catch (cause: Exception) {
                notice = "专注没启动（${cause.message ?: "未知原因"}），稍后再试。"
            }
        }
    }

    /** 每个题组的**不同错题键**数（重答同一题只算一道）。 */
    fun wrongCounts(): Map<String, Int> = attempts.asSequence()
        .filter { !it.correct && it.questionKey.contains(':') }
        .groupBy { it.questionKey.substringBefore(':') }
        .mapValues { (_, rows) -> rows.map { it.questionKey }.toSet().size }

    /** EF06 完成回写：基准之后、本任务题组的不同题目键数 ≥ 任务题数 → 自动勾掉。 */
    fun autoCompleteDone() {
        if (attempts.isEmpty()) return
        val after = plans.map { p ->
            if (!p.active) p else p.copy(tasks = p.tasks.map { t ->
                // 只做「升级」：无题组/无基准/无目标题数的任务不自动判定（展示见 PlanTask.proof）。
                if (t.isDone() || t.bankId == null || t.baselineAt == null) t
                else {
                    val required = t.requiredQuestionCount(banks)
                    if (required > 0 && t.answeredDistinctSinceBaseline(attempts) >= required)
                        t.copy(status = PlanTask.DONE) else t
                }
            })
        }
        if (after != plans) {
            plans = after
            persist()
            notice = "有任务按你的实际作答自动勾掉了，去任务里看看。"
        }
    }

    /** 加载本机题库 + 作答记录，回来后顺手跑一次完成回写。 */
    fun refreshPracticeData() {
        scope.launch {
            val (bs, read) = withContext(Dispatchers.IO) {
                val loadedBanks = runCatching { QuestionBankStore.banks(context) }.getOrDefault(emptyList())
                // 严格读（失败抛异常）：完成度是要向用户下结论的，不能把「读不出来」当「0 条作答」。
                loadedBanks to runCatching { PracticeStore.of(context, owner).exportAttempts() }
            }
            banks = bs
            attempts = read.getOrDefault(emptyList())
            practiceLoaded = true
            practiceFailed = read.isFailure
            if (!practiceFailed) autoCompleteDone()
        }
    }
    LaunchedEffect(owner) { refreshPracticeData() }

    fun generate(useAi: Boolean) {
        if (banks.none { it.questionList.isNotEmpty() }) {
            notice = "本机还没有可做的题组：先到「做题」页挑一个下载，再来排计划。"
            return
        }
        busy = true; notice = null
        scope.launch {
            try {
                val fallback = { PlanDraft.template(bookName, banks, wrongCounts()) }
                val draft = if (useAi) {
                    val text = try {
                        if (aiSuggest != null) aiSuggest()
                        else {
                            val config = EnglishAiStore(context).load()
                            if (!config.ready()) throw EnglishAiException("还没有配置 AI，先在「AI 设置」里填好。")
                            EnglishAi.chat(config, listOf(EnglishAiTurn("user", PlanDraft.aiPrompt(banks))),
                                maxTokens = 500, temperature = 0.4)
                        }
                    } catch (cause: Exception) {
                        notice = "AI 建议没拿到（${cause.message ?: "未知原因"}），已改用离线模板生成。"
                        null
                    }
                    if (text.isNullOrBlank()) fallback() else PlanDraft.fromAi(text, banks, fallback)
                } else fallback()
                // 🔴 重生成时**替换掉旧的未确认建议**，不要叠一堆。
                // 2026-10-05 走查实证：原来的 `listOf(draft) + plans` 每点一次就多一份，
                // 连点两次会出现**两条标题一模一样的「建议（还没确认）」**，用户根本分不清
                // 该确认哪一条。已确认的（active）保留不动 —— 那是用户的既有安排。
                plans = listOf(draft) + plans.filter { it.active }
                persist()
            } finally { busy = false }
        }
    }

    // 弹层自识别左边缘拖动（Dialog 收不到系统进度，见 EnglishHubScreen 里的说明）。
    val motion = rememberOverlayMotion(
        onClose = { dismiss() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )
    Dialog(onDismissRequest = { motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { dismiss() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回英语") }
                    Text("学习计划", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("计划由「建议」和「已确认」两步组成：先生成一份建议，你自己确认后它才会生效、" +
                        "才会出现在今日安排里。任务来自你本机已有的题组，可以直接点「去做题」；" +
                        "完成以你在里面的实际作答为准，不是生成了就算学完。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { generate(false) }, enabled = !busy) { Text("生成 3 天计划") }
                        if (aiSuggest != null || aiReady) {
                            OutlinedButton(onClick = { generate(true) }, enabled = !busy) { Text("让 AI 给建议") }
                        }
                    }
                    if (aiSuggest == null && !aiReady) {
                        Text("没有配置 AI 也能用：这里按你本机的题组离线生成一份。配置了 API 才会多出「让 AI 给建议」。",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在生成建议…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    if (plans.isEmpty() && !busy) {
                        Text(if (banks.none { it.questionList.isNotEmpty() })
                            "本机还没有可做的题组。先到英语页的「做题」挑一个下载，再回来排计划。"
                            else "还没有计划。点上面生成一份建议看看。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    plans.forEach { plan ->
                        PlanCard(
                            plan = plan,
                            banks = banks,
                            attempts = attempts,
                            loaded = practiceLoaded,
                            failed = practiceFailed,
                            expanded = expanded == plan.id,
                            onToggleExpand = { expanded = if (expanded == plan.id) null else plan.id },
                            onConfirm = {
                                // EF06：确认那一刻记基准时间 —— 之后这个任务只认基准之后的真实作答。
                                plans = plans.map { if (it.id == plan.id) it.copy(active = true,
                                    tasks = it.tasks.map { t ->
                                        if (t.bankId != null && t.baselineAt == null)
                                            t.copy(baselineAt = System.currentTimeMillis()) else t
                                    }) else it }
                                persist(); notice = "计划已确认，现在会出现在今日安排里。"
                            },
                            onDiscard = {
                                plans = plans.filterNot { it.id == plan.id }; persist()
                                notice = "已删除这份建议。"
                            },
                            onToggleTask = { taskId ->
                                // 完成诚实性：有题组的任务只认真实作答回写，手勾在数据层也拒收
                                //（UI 的 enabled 只是第一道门，这里是不依赖 UI 状态的第二道）。
                                val manual = plan.tasks.firstOrNull { it.id == taskId }?.bankId == null
                                if (manual) {
                                    plans = plans.map { p -> if (p.id != plan.id) p else p.copy(
                                        // 勾选只允许发生在已确认的计划上 —— draft 还能改还能删。
                                        tasks = p.tasks.map { t -> if (t.id != taskId) t
                                            else t.copy(status = if (t.isDone()) PlanTask.PENDING else PlanTask.DONE) }) }
                                    persist()
                                }
                            },
                            onRebaseline = { taskId ->
                                // 历史题库任务没有确认基准，无法把作答归因到计划之后。
                                // 这里不是把旧手勾洗白：清掉旧状态、从此刻起重新设基准，
                                // 之后的作答才按「不同题目」计数；历史作答记录一条不改。
                                val now = System.currentTimeMillis()
                                plans = plans.map { p ->
                                    if (p.id != plan.id) p else p.copy(tasks = p.tasks.map { t ->
                                        if (t.id != taskId || t.bankId == null || t.baselineAt != null) t
                                        else t.copy(baselineAt = now, status = PlanTask.PENDING)
                                    })
                                }
                                persist()
                                notice = "已从此刻起重新核对这项任务：之后的作答按不同题目计数；以前的作答记录仍然保留，只是不算进这次任务的完成。"
                            },
                            onOpenBank = { bankId -> openBankId = bankId },
                            onBeginFocus = ::beginFocus,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
    notice?.let { text -> AlertDialog(containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = { notice = null }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { notice = null }) { Text("知道了") } }) }

    // EF06：从任务直接进真实做题页（整屏子层，关闭回到计划）。
    // 题组可能在停留期间被删 —— 找不到就收掉这个子层，不能画一个空壳。
    LaunchedEffect(openBankId, banks) {
        val bid = openBankId ?: return@LaunchedEffect
        if (banks.none { it.id == bid && it.questionList.isNotEmpty() }) openBankId = null
    }
    openBankId?.let { bid ->
        Dialog(onDismissRequest = { openBankId = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            QuestionBankScreen(
                owner = owner,
                onClose = { openBankId = null },
                onPracticeChanged = { refreshPracticeData() },
                initialBankId = bid,
            )
        }
    }
}

@Composable
private fun PlanCard(
    plan: LearningPlan,
    banks: List<QuestionBank>,
    attempts: List<QuestionAttempt>,
    /** 作答记录是否已读进来：没读完前不下「未完成」的结论。 */
    loaded: Boolean,
    /** 作答记录读取失败：完成度显示「无法核对」，不冒充 0/N。 */
    failed: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    onToggleTask: (String) -> Unit,
    /** 历史题库任务缺确认基准时，用户显式从此刻起重新建立基准（不改作答记录）。 */
    onRebaseline: (String) -> Unit,
    onOpenBank: (String) -> Unit,
    onBeginFocus: (String, String) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val unverifiedCount = if (plan.active && loaded && !failed) plan.unverifiedDoneCount(attempts, banks) else 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(plan.goal, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(
                        when {
                            !plan.active -> "建议（还没确认）"
                            !loaded -> "已确认 · 正在核对实际作答…"
                            failed -> "已确认 · 作答记录读取失败，完成度无法核对"
                            else -> "已确认 · ${plan.progressText(attempts, banks)} 完成"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (plan.active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (unverifiedCount > 0) {
                        Text("其中 $unverifiedCount 项是历史手动勾选、没有真实作答证据，已标「待核对」，不计入完成。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (plan.active && loaded && failed) {
                        Text("本机作答记录读取失败，这次没有核对完成度，也没有改动任何记录。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onToggleExpand) { Text(if (expanded) "收起" else "查看任务") }
            }
            if (!plan.active) {
                Text("这份只是建议。确认后任务才会生效，也才会同步到你在其它设备上的计划。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                plan.tasks.forEach { task ->
                    val bank = task.bankId?.let { id -> banks.firstOrNull { it.id == id } }
                    val openable = plan.active && bank != null && bank.questionList.isNotEmpty()
                    // 完成诚实性（EF06 收口）：完成度一律从真实作答现算，不沿用历史手勾状态。
                    // 作答记录没读完前 proof 为 null —— 先显示「核对中」，不拿空记录误判成未完成。
                    val proof = if (task.bankId != null && (!loaded || failed)) null else task.proof(attempts, banks)
                    val done = proof?.showsDone == true
                    val manualTask = task.bankId == null
                    val unverified = proof == PlanTaskProof.UNVERIFIED
                    val demoted = proof == PlanTaskProof.PENDING && task.isDone() && !manualTask
                    val required = task.requiredQuestionCount(banks)
                    val answered = task.answeredDistinctSinceBaseline(attempts)
                    val canToggle = plan.active && manualTask
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { if (canToggle) onToggleTask(task.id) },
                            enabled = canToggle, modifier = Modifier.size(36.dp)) {
                            Icon(if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                when {
                                    done && manualTask -> "已手动勾选"
                                    done -> "已达成（实际作答）"
                                    unverified -> "待核对（历史手勾，缺少基准后的真实作答证据）"
                                    demoted -> "未完成（历史手勾已按实际作答重算）"
                                    failed -> "作答记录读取失败，完成度无法核对"
                                    proof == null -> "正在核对本机作答记录"
                                    else -> if (manualTask) "未完成，可手动勾选" else "未完成（以实际作答为准，不可手勾）"
                                },
                                tint = if (done) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(task.title, fontWeight = if (done) FontWeight.Normal else FontWeight.Medium)
                            if (done && manualTask && plan.active) {
                                Text("手动勾选（非实际作答记录）",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (unverified) {
                                Text("待核对：这项是历史题库任务的勾选，没有确认基准后的真实作答证据，不计入完成（你的作答记录没有被改动）。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (demoted) {
                                Text("之前的手动勾选不算完成：确认后实际作答 $answered/$required 题，还没达到。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (failed && task.bankId != null) {
                                Text("本机作答记录读取失败，这项任务的完成度这次无法核对（记录没有被改动）。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else if (proof == null) {
                                Text("正在读取本机作答记录，稍后按实际作答显示完成度。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (task.successRule.isNotBlank()) Text("算完成：" + task.successRule,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            task.dueDay?.let { Text("第 ${it + 1} 天", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (plan.active && task.bankId != null) {
                                if (openable) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        TextButton(onClick = { onOpenBank(task.bankId!!) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                            Text("去做题 →")
                                        }
                                        // 关联专注：接口已落地（pomodoro-core 契约），按原 HANDOFF 约定此时才渲染。
                                        TextButton(onClick = { onBeginFocus(task.id, task.title) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                            Text("开始专注")
                                        }
                                    }
                                    when {
                                        done -> Text("已由实际作答自动完成：确认后不同题目 $answered/$required 题。",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        task.baselineAt == null -> {
                                            Text("历史任务没有确认基准：现在答题也不能归因到计划之后、不会自动勾。可以从此刻起重新核对。",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            TextButton(onClick = { onRebaseline(task.id) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                                Text("从现在起重新核对")
                                            }
                                        }
                                        demoted -> Text("确认后实际作答 $answered/$required 题，达到 $required 题会自动勾。",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        required > 0 -> Text("答满 $required 题自动勾，不能手动勾。",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                } else if (!loaded) {
                                    Text("正在读取本机题组与作答记录…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else if (failed) {
                                    Text("作答记录读取失败，无法确认这项任务是否达到完成条件。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text("题组已不在本机；重新生成一份建议可换成现有题组。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else if (plan.active && manualTask) {
                                Text("手动任务：点圆圈自己勾，不算真实作答记录。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!plan.active) {
                    Button(onClick = onConfirm) { Text("确认计划") }
                    OutlinedButton(onClick = onDiscard) { Text("删除建议") }
                } else {
                    Text("题库任务只认确认后的真实作答（不同题目数达标自动勾）；历史勾选无证据的标「待核对」、不计完成；没有题组的旧任务才手动勾（会标「手动」），不算真实作答记录。",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
