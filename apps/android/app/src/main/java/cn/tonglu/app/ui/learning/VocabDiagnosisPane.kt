package cn.tonglu.app.ui.learning

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
@Composable
internal fun VocabDiagnosisPane(
    banks: List<QuestionBank>,
    store: PracticeStore,
    onExit: () -> Unit,
    onAnswered: () -> Unit,
) {
    val pool = remember(banks) { buildDiagnosisSet(banks) }
    var index by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }

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
                        Text("词汇自测", fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall)
                        if (!finished && pool.isNotEmpty()) {
                            Text("第 ${index + 1} / ${pool.size} 题",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (pool.isEmpty()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("本机还没有可用的题组 —— 先去题库页下载一份再来。",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    return@Column
                }

                if (!finished && index < pool.size) {
                    LinearProgressIndicator(
                        progress = { index.toFloat() / pool.size },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    )
                }

                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (finished) {
                        item {
                            DiagnosisResult(pool = pool, store = store, revision = revision)
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = {
                                    index = 0; finished = false
                                }) { Text("再测一次") }
                                Button(onClick = { motion.requestClose() }) { Text("完成") }
                            }
                        }
                    } else {
                        val pick = pool[index]
                        item(key = "${pick.bank.id}:${pick.question.id}") {
                            BankQuestionCardForDiagnosis(
                                bank = pick.bank,
                                question = pick.question,
                                store = store,
                                onAnswered = {
                                    revision++
                                    onAnswered()
                                    if (index < pool.size - 1) index++ else finished = true
                                },
                            )
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(
                                    onClick = { if (index > 0) index-- },
                                    enabled = index > 0,
                                ) { Text("上一题") }
                                Button(
                                    onClick = {
                                        if (index < pool.size - 1) index++ else finished = true
                                    },
                                ) { Text(if (index < pool.size - 1) "跳过这题" else "结束自测") }
                            }
                        }
                        item {
                            Text(
                                "自测结果只作自评参考，不是考试成绩。" +
                                    "题目为平台自编练习题，不代表任何考试的分数。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item { Spacer(Modifier.size(24.dp)) }
                }
            }
        }
    }
}

/** 一道诊断题：复用题库的题卡（带「不认识」选项）。 */
@Composable
private fun BankQuestionCardForDiagnosis(
    bank: QuestionBank,
    question: BankQuestion,
    store: PracticeStore,
    onAnswered: () -> Unit,
) {
    // 复用 QuestionBankScreen 里那份带 allowUnknown 的题卡（同文件私有 → 这里转发）
    BankQuestionCardInternal(
        bank = bank, question = question, store = store,
        onAnswered = onAnswered, allowUnknown = true,
    )
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

/**
 * 自测结果：按难度层给「做得怎么样」的**自评参考**。
 *
 * 记分口径**如实**：每层显示「对 / 总」，并说明这是自评参考。
 * 不换算成分数、不给等级、不推断水平标签（见文件头纪律 2、4）。
 */
@Composable
private fun DiagnosisResult(pool: List<DiagnosisPick>, store: PracticeStore, revision: Int) {
    val byTier = remember(pool, revision) {
        Difficulty.order.mapNotNull { tier ->
            val items = pool.filter { it.question.difficulty == tier }
            if (items.isEmpty()) return@mapNotNull null
            // 「对过」= 这道题历史上至少答对过一次（PracticeStats.correct > 0）。
            // 不做「掌握度百分比」这种我们没测量过的东西（见文件头纪律）。
            val correct = items.count { pick ->
                (store.statsOf(pick.bank.keyOf(pick.question))?.correct ?: 0) > 0
            }
            Triple(tier, correct, items.size)
        }
    }
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("自测小结", fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall)
            Text(
                "下面是各难度层的作答情况，用来看看自己目前对哪一档更熟。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for ((tier, correct, total) in byTier) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(Difficulty.labelOf(tier), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f))
                    Text("$correct / $total",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                "说明：这是一套题量很小的自编练习题，只作自评参考，" +
                    "不能用来预测任何考试的分数，也不是能力测评结论。" +
                    "同难度层的题每次抽取相同，方便前后比较。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
