package cn.tonglu.app.ui.learning

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.listening.PracticeStore
import cn.tonglu.app.data.question.BankQuestion
import cn.tonglu.app.data.question.Difficulty
import cn.tonglu.app.data.question.QuestionBank
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.glass.GlassTopBar
import cn.tonglu.app.ui.glass.GlassTokens
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 词汇诊断（docs/plan/56 §4.4 · docs/plan/57 §5.3 T6）。
 *
 * **这不是考试，也不预测分数。** 它做的事：按难度层从已有题库里抽 30 题左右，
 * 让你 5–8 分钟内快速过一遍，最后给一份「各难度层大致掌握情况」的**自评参考**。
 *
 * 四条**写死在实现里**的纪律（§4.4 原话，改动前先看）：
 *  1. **不称 IRT / CAT**。这是一套**未标定**的题组，没有项目反应理论的参数，
 *     说成「自适应测试」是骗人。界面上一个字都不提。
 *  2. **不宣称能预测四六级分数**。抽的是自编练习题，与任何考试的分数无换算关系。
 *  3. **含「不认识」选项**。词汇自测里「不认识」是个真实且有价值的结果，
 *     逼人四选一是错的；选了它记一次「未掌握」，**不伪造一个对错**。
 *  4. **如实说是自评参考**，不是诊断结论，更不是能力测评。
 *
 * 抽样策略：三层各取固定份数（basic 10 / core 12 / advanced 8，各层不足则取满），
 * 合计封顶 30 —— 简单、可复现、5–8 分钟能做完；**刻意不做难度自适应**（见纪律 1）。
 */
private data class DiagnosisPage(
    val number: Int, val index: Int, val finished: Boolean,
    val pick: DiagnosisPick?, val results: List<DiagnosisTierResult>,
)

@Composable
internal fun VocabDiagnosisPane(
    banks: List<QuestionBank>, store: PracticeStore,
    onExit: () -> Unit, onAnswered: () -> Unit,
    loading: Boolean = false, loadError: String? = null,
) {
    val availablePool = remember(banks) { buildDiagnosisSet(banks) }
    val saver = remember { listSaver<DiagnosisRound, String>(
        save = { it.snapshot() }, restore = { DiagnosisRound.restore(it) }) }
    var round by rememberSaveable(store, stateSaver = saver) { mutableStateOf(DiagnosisRound()) }
    // A background catalog refresh must not replace questions in an active round.
    var frozenPool by remember(store) { mutableStateOf<List<DiagnosisPick>?>(null) }
    LaunchedEffect(store, banks, loading, round.poolSignature) {
        if (frozenPool == null && banks.isNotEmpty()) {
            if (round.poolSignature.isNotEmpty()) {
                val signature = round.poolSignature
                frozenPool = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { restoreDiagnosisPool(signature, banks) }
            } else if (availablePool.isNotEmpty()) {
                val signature = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { diagnosisPoolSignature(availablePool) }
                round = round.bindPool(signature)
                frozenPool = availablePool
            }
        }
    }
    val pool = frozenPool.orEmpty()
    val restoring = round.poolSignature.isNotEmpty() && frozenPool == null
    // Parent account scopes isolate the saved holder; the signature survives asynchronous reloads.
    val questionStates = key(round.poolSignature) { rememberSaveableStateHolder() }
    val page = DiagnosisPage(round.number, round.index, round.finished,
        if (round.finished) null else pool.getOrNull(round.index),
        if (round.finished) diagnosisResults(pool, round) else emptyList())
    val pageFrame = LearningAnimationFrame("${round.number}:${round.index}:${round.finished}", page)
    val latestPage = rememberUpdatedState(pageFrame.key)
    val readyMotion = remember { MutableTransitionState(false) }
    readyMotion.targetState = pool.isNotEmpty()
    val progress by animateFloatAsState(
        targetValue = if (pool.isEmpty()) 0f else round.answers.size.toFloat() / pool.size,
        animationSpec = tween(220), label = "diagnosis-progress")
    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onExit() },
        predictive = OverlayMotion.enabledState, selfDrag = true)
    Dialog(onDismissRequest = { motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = MaterialTheme.colorScheme.background) {
            val backdrop = rememberLayerBackdrop()
            val barHeight = maxOf(GlassTokens.TopBarHeightDp.dp, GlassTokens.TopBarHeightDp.dp * LocalDensity.current.fontScale)
            val headerInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() +
                barHeight + GlassTokens.TopBarVerticalMarginDp.dp
            val headerClip = with(LocalDensity.current) { headerInset.toPx() }
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                // 外层裁掉原始文字，内层仍记录完整采样，玻璃只呈现模糊副本。
                Box(Modifier.fillMaxSize().clip(GenericShape { size, _ ->
                    addRect(Rect(0f, headerClip.coerceAtMost(size.height), size.width, size.height))
                }).layerBackdrop(backdrop)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)), contentAlignment = Alignment.TopCenter) {
                    androidx.compose.animation.AnimatedVisibility(pool.isEmpty(), modifier = Modifier.widthIn(max = 880.dp).fillMaxSize(),
                        enter = fadeIn(tween(120)), exit = fadeOut(tween(120))) {
                        AnimatedContent(targetState = when {
                            loadError != null -> loadError
                            loading -> "loading"
                            restoring -> "restore"
                            availablePool.isNotEmpty() -> "loading"
                            else -> "empty"
                        }, label = "diagnosis-availability") { state ->
                            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                                .padding(start = 20.dp, end = 20.dp, top = headerInset + 16.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (state == "loading") {
                                    CircularProgressIndicator(Modifier.size(28.dp))
                                    Text(if (restoring) "正在恢复本轮题组…" else "正在读取本机题组…")
                                } else {
                                    Text(when (state) {
                                        "empty" -> "本机还没有可用的题组，先去题库页下载一份再来。"
                                        "restore" -> "本轮题组暂时无法恢复，作答记录已保留。可以稍后返回，或重新开始。"
                                        else -> state
                                    })
                                    if (state == "restore" && availablePool.isNotEmpty()) Button(onClick = {
                                        if (round.poolSignature.isNotEmpty() && frozenPool == null) round = DiagnosisRound(number = round.number + 1)
                                    }) { Text("重新开始自测") }
                                    OutlinedButton(onClick = { motion.requestClose() }) { Text("返回后重试") }
                                }
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(visibleState = readyMotion, modifier = Modifier.widthIn(max = 880.dp).fillMaxSize(),
                        enter = fadeIn(tween(180, delayMillis = 120)) + slideInVertically(tween(220)) { it / 32 }, exit = fadeOut(tween(120))) {
                        AnimatedContent(targetState = pageFrame, label = "diagnosis-page",
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                val direction = if (targetState.content.number != initialState.content.number ||
                                    targetState.content.finished || targetState.content.index >= initialState.content.index) 1 else -1
                                (fadeIn(tween(170, delayMillis = 90)) + slideInHorizontally(tween(240)) { direction * it / 8 }) togetherWith
                                    (fadeOut(tween(90)) + slideOutHorizontally(tween(180)) { -direction * it / 12 })
                            }) { renderedFrame ->
                            val rendered = renderedFrame.content
                            fun current() = latestPage.value == renderedFrame.key && round.number == rendered.number &&
                                round.index == rendered.index && round.finished == rendered.finished &&
                                motion.canHandleBack && readyMotion.isIdle && readyMotion.currentState && transition.currentState == EnterExitState.Visible &&
                                transition.targetState == EnterExitState.Visible
                            val active = current()
                            LaunchedEffect(round.number, transition.currentState == transition.targetState) {
                                if (transition.currentState == transition.targetState && round.number > 0) {
                                    pool.forEach { questionStates.removeState("${round.number - 1}:${it.bank.keyOf(it.question)}") }
                                }
                            }
                            LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = headerInset + 16.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (rendered.finished) {
                                    item { DiagnosisResult(rendered.results) }
                                    item {
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(enabled = active, onClick = { if (current()) round = round.restart() }) { Text("再测一次") }
                                            Button(enabled = active, onClick = { if (current()) motion.requestClose() }) { Text("完成") }
                                        }
                                    }
                                } else {
                                    val pick = rendered.pick
                                    if (pick != null) {
                                        val questionKey = pick.bank.keyOf(pick.question)
                                        item {
                                            questionStates.SaveableStateProvider("${rendered.number}:$questionKey") {
                                                BankQuestionCardInternal(bank = pick.bank, question = pick.question, store = store,
                                                    interactionEnabled = active, isInteractionCurrent = { current() }, allowUnknown = true,
                                                    onGraded = { correct, unknown ->
                                                        if (current()) round = round.answer(questionKey, correct, unknown)
                                                    },
                                                    onResetAnswer = { if (current()) round = round.retry(questionKey) },
                                                    onAnswered = { if (current()) onAnswered() })
                                            }
                                        }
                                        item {
                                            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                OutlinedButton(enabled = active && rendered.index > 0,
                                                    onClick = { if (current()) round = round.previous() }) { Text("上一题") }
                                                Button(enabled = active, onClick = { if (current()) round = round.next(pool.size) }) {
                                                    Text(if (rendered.index == pool.lastIndex) "查看本轮小结"
                                                        else if (questionKey in round.answers) "下一题" else "跳过这题")
                                                }
                                            }
                                        }
                                        item { Text("自测结果只作自评参考，不是考试成绩。提交后可查看解析，再进入下一题。",
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                                item { Spacer(Modifier.size(24.dp)) }
                            }
                        }
                    }
                }
                GlassTopBar(backdrop, Modifier.align(Alignment.TopCenter), barHeight = barHeight) {
                    IconButton(onClick = { motion.requestClose() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回题组列表", tint = AppColors.TextPrimary)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("词汇自测", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall,
                            color = AppColors.TextPrimary)
                        Text(when {
                            round.finished -> "本轮自测小结"
                            pool.isNotEmpty() -> "第 ${round.index + 1} / ${pool.size} 题 · 已作答 ${round.answers.size} 题"
                            else -> "准备本轮题组"
                        }, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                        AnimatedVisibility(!round.finished && pool.isNotEmpty()) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 抽题结果：一对 (题库, 题目)。 */
internal data class DiagnosisPick(val bank: QuestionBank, val question: BankQuestion)

/** 各难度层抽多少题。合计封顶 30（§4.4：30–40 题、5–8 分钟）。 */
private const val PICK_BASIC = 10
private const val PICK_CORE = 12
private const val PICK_ADVANCED = 8
private const val PICK_MAX = 30

/**
 * 按难度层抽题。
 *
 * **确定性抽取**（按各层题目的自然顺序取前 N 个），不是随机：
 * 同一份题库每次进来自测的题一样，结果可比；随机抽会让「上次 8/10、这次 6/10」
 * 分不清是水平变了还是题变了。各层不足时取满该层，不跨层凑数（那会歪曲难度分布）。
 */
internal fun buildDiagnosisSet(banks: List<QuestionBank>): List<DiagnosisPick> {
    val quota = mapOf(
        Difficulty.BASIC to PICK_BASIC,
        Difficulty.CORE to PICK_CORE,
        Difficulty.ADVANCED to PICK_ADVANCED,
    )
    val picks = mutableListOf<DiagnosisPick>()
    for (tier in Difficulty.order) {
        val want = quota[tier] ?: 0
        if (want <= 0) continue
        val tierQuestions = banks.flatMap { bank ->
            bank.questionList.filter { it.difficulty == tier && it.gradable && it.keySafe }
                .map { DiagnosisPick(bank, it) }
        }
        picks += tierQuestions.take(want)
    }
    return picks.take(PICK_MAX)
}

@Composable
private fun DiagnosisResult(results: List<DiagnosisTierResult>) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("本轮自测小结", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Text("只统计这一次自测的作答，历史记录继续保留。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            results.forEach { tier ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(Difficulty.labelOf(tier.tier), fontWeight = FontWeight.SemiBold)
                        Text("本轮答对 ${tier.correct} / ${tier.total}", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                    }
                    Text("答错 ${tier.incorrect} · 不认识 ${tier.unknown} · 跳过 ${tier.skipped}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("这是一套题量很小的练习题，只作自评参考，不能预测考试分数。同难度层每次抽取相同，便于比较。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
