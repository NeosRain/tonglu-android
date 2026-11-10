package cn.tonglu.app.ui.learning

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.ui.draw.rotate
import cn.tonglu.app.ui.glass.GlassTokens
import cn.tonglu.app.ui.glass.GlassTopBar
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.CancellationException
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
import cn.tonglu.app.data.practiceDate
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

    var plans by remember(owner) { mutableStateOf<List<LearningPlan>>(emptyList()) }
    var plansLoading by remember(owner) { mutableStateOf(true) }
    var plansLoadError by remember(owner) { mutableStateOf<String?>(null) }
    var readRevision by remember(owner) { mutableIntStateOf(0) }
    LaunchedEffect(owner, readRevision) {
        plansLoading = true; plansLoadError = null
        try { plans = withContext(Dispatchers.IO) { store.load(owner) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) { plansLoadError = "计划读取失败，原文件已保留：${cause.localizedMessage}" }
        finally { plansLoading = false }
    }
    // 本机题库与作答记录：EF06 的资源白名单与完成回写都从这里来，不靠调用方传名字。
    var banks by remember(owner) { mutableStateOf<List<QuestionBank>>(emptyList()) }
    var attempts by remember(owner) { mutableStateOf<List<QuestionAttempt>>(emptyList()) }
    // 作答记录是否已读进来：读到之前不拿空列表下「未完成」结论。
    var practiceLoaded by remember(owner) { mutableStateOf(false) }
    // 「读不出来」≠「没有记录」：读盘失败时完成度一律显示「无法核对」，不冒充 0/N。
    var practiceFailed by remember(owner) { mutableStateOf(false) }
    var banksLoadError by remember(owner) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    // 从任务点进「做题」的题组 id；null = 没开。
    var openBankId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var catalogOpen by rememberSaveable(owner) { mutableStateOf(false) }
    val aiReady = remember { EnglishAiStore(context).load().ready() }
    // 关联专注：root 的番茄钟核心 API（契约见 work/pomodoro-core/CONTRACT.md）。
    // C 只发起与提示，不画计时 UI —— 阶段/轮次/剩余时间全部归 root 的状态机与浮层。
    val pomodoro = remember { PomodoroController.get(context) }

    fun persist(next: List<LearningPlan>, message: String? = null) {
        if (busy || plansLoading || plansLoadError != null) return
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(owner, next) }
                plans = next
                notice = message
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { notice = "修改未保存，原计划仍保留：${cause.localizedMessage}" }
            finally { busy = false }
        }
    }
    fun dismiss() { OverlayDismissGuard.arm(); onClose() }

    /** 从任务卡发起关联专注：taskId 记进计时会话，root 侧凭它回写来源。 */
    fun beginFocus(taskId: String, label: String) {
        if (busy || plansLoading || plansLoadError != null || plans.none { it.active && it.tasks.any { task -> task.id == taskId } }) return
        scope.launch {
            try {
                pomodoro.bindOwner(owner)
                val started = pomodoro.startTask(taskId, label)
                notice = if (started) "「$label」的专注计时已开始，到番茄钟里看进度。"
                else "已有一个进行中的计时，去番茄钟继续就好；这份安排没有被覆盖。"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) {
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
        if (busy || plansLoading || plansLoadError != null || attempts.isEmpty()) return
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
            persist(after)
        }
    }

    /** 加载本机题库 + 作答记录，回来后顺手跑一次完成回写。 */
    var practiceRevision by remember(owner) { mutableIntStateOf(0) }
    fun refreshPracticeData() { practiceRevision++ }
    LaunchedEffect(owner, practiceRevision) {
        practiceLoaded = false
        val (bs, read) = withContext(Dispatchers.IO) {
            val loadedBanks = runCatching { QuestionBankStore.banks(context, null) }
            // 严格读（失败抛异常）：完成度是要向用户下结论的，不能把「读不出来」当「0 条作答」。
            loadedBanks to runCatching { PracticeStore.of(context, owner).exportAttempts() }
        }
        banks = bs.getOrDefault(emptyList())
        banksLoadError = bs.exceptionOrNull()?.let { "题组读取失败：${it.localizedMessage}" }
        attempts = read.getOrDefault(emptyList())
        practiceLoaded = true
        practiceFailed = read.isFailure
    }
    LaunchedEffect(plans, attempts, banks, practiceLoaded, practiceFailed, plansLoading, plansLoadError) {
        if (practiceLoaded && !practiceFailed) autoCompleteDone()
    }

    fun generate(useAi: Boolean) {
        if (busy || plansLoading || plansLoadError != null || !practiceLoaded || banksLoadError != null) return
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
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (cause: Exception) {
                        notice = "AI 建议没拿到（${cause.message ?: "未知原因"}），已改用离线模板生成。"
                        null
                    }
                    if (text.isNullOrBlank()) fallback() else PlanDraft.fromAi(text, banks, fallback)
                } else fallback()
                // 🔴 重生成时**替换掉旧的未确认建议**，不要叠一堆。
                // 2026-10-05 走查实证：原来的 `listOf(draft) + plans` 每点一次就多一份，
                // 连点两次会出现**两条标题一模一样的「建议（还没确认）」**，用户根本分不清
                // 该确认哪一条。已确认的（active）保留不动 —— 那是用户的既有安排。
                val next = listOf(draft) + plans.filter { it.active }
                withContext(Dispatchers.IO) { store.save(owner, next) }
                plans = next
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { notice = "计划未保存，原计划仍保留：${cause.localizedMessage}" }
            finally { busy = false }
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
        val backdrop = rememberLayerBackdrop()
        val headerInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() +
            GlassTokens.TopBarHeightDp.dp + GlassTokens.TopBarVerticalMarginDp.dp
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(Modifier.widthIn(max = 880.dp).fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = headerInset + 20.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        item("intro") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("接下来三天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                Text("用本机题组，排出每天的学习安排。", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item("actions") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val ready = !busy && !plansLoading && plansLoadError == null && practiceLoaded && banksLoadError == null
                                FilledTonalButton(onClick = { generate(false) }, enabled = ready, shape = RoundedCornerShape(18.dp)) {
                                    Icon(Icons.Outlined.TaskAlt, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("生成 3 天计划")
                                }
                                if (aiSuggest != null || aiReady) OutlinedButton(onClick = { generate(true) }, enabled = ready, shape = RoundedCornerShape(18.dp)) {
                                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("AI 建议")
                                }
                            }
                        }
                        if (plansLoading || !practiceLoaded || busy) item("loading") {
                            Row(Modifier.animateItem(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                Text(if (busy) "正在生成或保存计划…" else "正在读取本机计划与作答…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (plansLoadError != null || banksLoadError != null || practiceFailed) item("read-errors") {
                            Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (plansLoadError != null) {
                                    Text(plansLoadError.orEmpty(), color = MaterialTheme.colorScheme.error)
                                    OutlinedButton(onClick = { readRevision++ }, enabled = !plansLoading && !busy) { Text("重新读取计划") }
                                }
                                if (banksLoadError != null || practiceFailed) {
                                    Text(banksLoadError ?: "作答记录读取失败，暂时无法核对完成度。", color = MaterialTheme.colorScheme.error)
                                    OutlinedButton(onClick = { refreshPracticeData() }, enabled = practiceLoaded && !busy) { Text("重新读取题组与作答") }
                                }
                            }
                        }
                        if (plans.isEmpty() && !plansLoading && plansLoadError == null && !busy && practiceLoaded && banksLoadError == null) item("empty") {
                            Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (banks.none { it.questionList.isNotEmpty() }) "本机还没有可做的题组，先去题库准备一份。" else "还没有计划，先生成一份建议。",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (banks.none { it.questionList.isNotEmpty() }) OutlinedButton(onClick = { catalogOpen = true }) { Text("打开题库") }
                            }
                        }
                        items(plans, key = { it.id }) { plan ->
                            val enabled = !busy && !plansLoading && plansLoadError == null
                            PlanCard(plan = plan, banks = banks, attempts = attempts, loaded = practiceLoaded,
                                failed = practiceFailed || banksLoadError != null, expanded = expanded == plan.id,
                                enabled = enabled, modifier = Modifier.animateItem(),
                                onToggleExpand = { if (enabled) expanded = if (expanded == plan.id) null else plan.id },
                                onConfirm = {
                                    if (plans.any { it.id == plan.id && !it.active }) {
                                        val now = System.currentTimeMillis()
                                        persist(plans.map { current -> if (current.id != plan.id) current else current.copy(active = true,
                                            tasks = current.tasks.map { task -> if (task.bankId != null && task.baselineAt == null) task.copy(baselineAt = now) else task }) })
                                    }
                                },
                                onDiscard = {
                                    if (plans.any { it.id == plan.id && !it.active }) persist(plans.filterNot { it.id == plan.id })
                                },
                                onToggleTask = { taskId ->
                                    val current = plans.firstOrNull { it.id == plan.id && it.active }
                                    if (current?.tasks?.any { it.id == taskId && it.bankId == null } == true) {
                                        persist(plans.map { p -> if (p.id != current.id) p else p.copy(tasks = p.tasks.map { task ->
                                            if (task.id != taskId) task else task.copy(status = if (task.isDone()) PlanTask.PENDING else PlanTask.DONE)
                                        }) })
                                    }
                                },
                                onRebaseline = { taskId ->
                                    val current = plans.firstOrNull { it.id == plan.id && it.active }
                                    if (current?.tasks?.any { it.id == taskId && it.bankId != null && it.baselineAt == null } == true) {
                                        val now = System.currentTimeMillis()
                                        persist(plans.map { p -> if (p.id != current.id) p else p.copy(tasks = p.tasks.map { task ->
                                            if (task.id == taskId && task.bankId != null && task.baselineAt == null) task.copy(baselineAt = now, status = PlanTask.PENDING) else task
                                        }) }, "已从此刻起重新核对，原作答记录仍然保留。")
                                    }
                                },
                                onOpenBank = { bankId -> if (enabled && motion.canHandleBack && plans.any { it.id == plan.id && it.active }) openBankId = bankId },
                                onBeginFocus = ::beginFocus)
                        }
                    }
                }
                GlassTopBar(backdrop, Modifier.align(Alignment.TopCenter)) {
                    IconButton(onClick = { motion.requestClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回英语") }
                    Text("学习计划", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (openBankId == null && !catalogOpen) notice?.let { text -> AlertDialog(containerColor = MaterialTheme.colorScheme.surface,
        onDismissRequest = { notice = null }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { notice = null }) { Text("知道了") } }) }

    // EF06：从任务直接进真实做题页（整屏子层，关闭回到计划）。
    // 题组可能在停留期间被删 —— 找不到就收掉这个子层，不能画一个空壳。
    LaunchedEffect(openBankId, banks, practiceLoaded) {
        if (!practiceLoaded) return@LaunchedEffect
        val bid = openBankId ?: return@LaunchedEffect
        if (banks.none { it.id == bid && it.questionList.isNotEmpty() }) openBankId = null
    }
    if (openBankId != null || catalogOpen) {
        fun closeBank() { openBankId = null; catalogOpen = false; refreshPracticeData() }
        QuestionBankScreen(
            owner = owner,
            onClose = { closeBank() },
            onPracticeChanged = { refreshPracticeData() },
            initialBankId = openBankId,
        )
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
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onToggleExpand: () -> Unit,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
    onToggleTask: (String) -> Unit,
    /** 历史题库任务缺确认基准时，用户显式从此刻起重新建立基准（不改作答记录）。 */
    onRebaseline: (String) -> Unit,
    onOpenBank: (String) -> Unit,
    onBeginFocus: (String, String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(200), label = "plan-expand-icon")
    val completion by animateFloatAsState(
        targetValue = if (loaded && !failed && plan.tasks.isNotEmpty()) plan.doneCount(attempts, banks).toFloat() / plan.tasks.size else 0f,
        animationSpec = tween(240), label = "plan-completion")
    OutlinedCard(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(plan.goal, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val status = when {
                        !plan.active -> "待确认 · ${plan.tasks.size} 项任务"
                        !loaded -> "正在核对进度…"
                        failed -> "暂时无法核对进度"
                        else -> "已确认 · ${plan.progressText(attempts, banks)} 完成"
                    }
                    AnimatedContent(targetState = status, label = "plan-status", transitionSpec = {
                        fadeIn(tween(160, delayMillis = 80)) togetherWith fadeOut(tween(80))
                    }) { rendered -> Text(rendered, style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant) }
                }
                IconButton(onClick = onToggleExpand, enabled = enabled) {
                    Icon(Icons.Outlined.ExpandMore, if (expanded) "收起" else "查看任务", Modifier.rotate(angle))
                }
            }
            AnimatedVisibility(plan.active && loaded && !failed) {
                LinearProgressIndicator(progress = { completion }, modifier = Modifier.fillMaxWidth(),
                    color = colors.primary, trackColor = colors.background, drawStopIndicator = {})
            }
            AnimatedVisibility(expanded) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = when { maxWidth >= 760.dp -> 3; maxWidth >= 520.dp -> 2; else -> 1 }
                    val taskWidth = (maxWidth - 10.dp * (columns - 1)) / columns
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    plan.tasks.forEach { task -> key(task.id) {
                        val bank = task.bankId?.let { id -> banks.firstOrNull { it.id == id } }
                        val openable = plan.active && bank != null && bank.questionList.isNotEmpty()
                        val proof = if (task.bankId != null && (!loaded || failed)) null else task.proof(attempts, banks)
                        val done = proof?.showsDone == true
                        val manual = task.bankId == null
                        val required = task.requiredQuestionCount(banks)
                        val answered = task.answeredDistinctSinceBaseline(attempts)
                        Surface(Modifier.width(taskWidth), shape = RoundedCornerShape(18.dp), color = colors.background) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                val description = when {
                                    done && manual -> "已手动勾选"
                                    done -> "已达成（实际作答）"
                                    proof == PlanTaskProof.UNVERIFIED -> "待核对（缺少确认基准）"
                                    failed && !manual -> "暂时无法核对作答"
                                    else -> if (manual) "未完成，可手动勾选" else "未完成（以实际作答为准，不可手勾）"
                                }
                                if (manual) IconButton(enabled = enabled && plan.active, onClick = { onToggleTask(task.id) }, modifier = Modifier.size(36.dp)) {
                                    Icon(if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, description,
                                        tint = if (done) colors.primary else colors.onSurfaceVariant)
                                } else Icon(if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, description,
                                    Modifier.padding(top = 2.dp).size(24.dp), tint = if (done) colors.primary else colors.onSurfaceVariant)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(listOfNotNull(task.dueDay?.let { "第 ${it + 1} 天" + (task.practiceDate()?.let { date -> " · $date" } ?: "") } ?: if (manual) "手动任务" else "题组任务",
                                        if (task.kind == "review") "错题复习" else null, if (required > 0) "$required 题" else null).joinToString(" · "),
                                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                    Text(task.resourceRef?.takeIf { task.bankId != null && it.isNotBlank() } ?: task.title,
                                        style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    when {
                                        !plan.active -> Text("确认后开始", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                        manual -> Text("手动记录，不计为实际作答", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                        !loaded -> Text("正在读取作答…", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                        failed -> Text("作答读取失败，稍后重试", style = MaterialTheme.typography.bodySmall, color = colors.error)
                                        task.baselineAt == null -> Text("历史任务缺少确认基准，需要重新核对。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                        else -> Text("${if (task.dueDay != null) "当日已答" else "已答"} $answered / $required · 答满自动完成", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    }
                                    if (plan.active && task.bankId != null) {
                                        if (openable) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            FilledTonalButton(onClick = { onOpenBank(task.bankId) }, enabled = enabled, shape = RoundedCornerShape(14.dp),
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) { Text("去做题") }
                                            TextButton(onClick = { onBeginFocus(task.id, task.title) }, enabled = enabled,
                                                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) { Text("开始专注") }
                                            if (task.baselineAt == null) TextButton(onClick = { onRebaseline(task.id) }, enabled = enabled,
                                                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) { Text(if (task.dueDay == null) "从现在起重新核对" else "以今天为第1天核对") }
                                        } else if (loaded && !failed) Text("题组暂不可用，可重新生成建议。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                    } else if (manual && task.successRule.isNotBlank()) Text(task.successRule,
                                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                }
                            }
                        }
                    } }
                    }
                }
            }
            AnimatedContent(targetState = plan.active, label = "plan-confirm-actions", transitionSpec = {
                fadeIn(tween(160, delayMillis = 80)) togetherWith fadeOut(tween(80))
            }) { active ->
                if (!active) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("确认后开始执行，也可以重新生成或删除。", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = onConfirm, enabled = enabled && !plan.active, shape = RoundedCornerShape(16.dp)) { Text("确认计划") }
                        OutlinedButton(onClick = onDiscard, enabled = enabled && !plan.active, shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface)) { Text("删除建议") }
                    }
                } else Spacer(Modifier.height(0.dp))
            }
        }
    }
}
