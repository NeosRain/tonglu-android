package cn.tonglu.app.ui.learning

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import cn.tonglu.app.ui.AppColors
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.english.*
import cn.tonglu.app.data.english.DailySentenceStore
import cn.tonglu.app.data.listening.PracticeMode
import cn.tonglu.app.data.listening.PracticeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** 今日目标按实际作答的不同单词计数，不把打开页面算作学习。 */
internal fun englishGoalRemaining(today: Int, goal: Int): Int = (goal.coerceAtLeast(1) - today.coerceAtLeast(0)).coerceAtLeast(0)

/**
 * 做题那条线的完成记录（来自 [PracticeStore]，与听力/题库**共用同一份作答存储**）。
 *
 * 为什么单列出来（plan §5 第 2 条要求「四个入口都带**可验证的完成记录**」）：
 * 今日卡的单词进度读的是 `english_events`，而做题记录存在 `PracticeStore` 里 ——
 * 两条线各记各的，**以前卡上完全看不到做题那条线**：练了半天，卡片还说「今日已练 0 个词」，
 * 看起来像没生效。这里把三个数如实读出来一起显示。
 */
internal data class PracticeRecord(
    /**
     * 今日**例句**线的**作答**数。
     *
     * ⚠️ 目前**恒为 0**，而且**不是**卡上那句「例句：今日已朗读 N 句」的数据源 ——
     * 例句页的挖空是**纯展示**（把词换成横线、自己心里补、再点「看原文」对照），
     * 没有输入也没有判定，**根本不存在作答**。留这个字段只是为了：
     * 将来例句真做了「可判定的填空」时，不用再改结构。
     * 例句那条线现在读的是 DailySentenceStore.spokenToday()（朗读计次，按天归零）。
     */
    val todaySentence: Int = 0,
    /** 今日**题库**自测（含词汇诊断）做了几题。 */
    val todayQuiz: Int = 0,
    /** 今日**听说材料自带题**做了几题。 */
    val todayMaterial: Int = 0,
    val answered: Int = 0,
    val wrong: Int = 0,
) {
    /**
     * 「一次练习」这条线 = **除例句外的一切作答**（题库自测 + 听说材料自带题）。
     * 用户口径：单词走单词的，答案走答案的 —— 例句的朗读不是答案，所以单列一条线。
     */
    val todayPractice: Int get() = todayQuiz + todayMaterial
}

/**
 * 从作答记录里算「今日已练多少题 / 累计答过多少题 / 错题多少」。
 *
 * 抽成**纯函数**是为了能被单测钉住：`store` 要文件系统，JVM 测试给不了；
 * 而这里正是最容易写错的一步 —— 「今日」必须用**当天 0 点**而不是 24 小时前，
 * 「练了几题」必须按 `questionKey` **去重**（同一题连答三次算一题）。
 */
internal fun practiceRecordOf(
    rows: List<cn.tonglu.app.data.listening.QuestionAttempt>,
    dayStart: Long,
    wrongKeys: Set<String>,
): PracticeRecord = PracticeRecord(
    todaySentence = distinctToday(rows, dayStart) { it.mode == PracticeMode.SENTENCE },
    todayQuiz = distinctToday(rows, dayStart) { it.mode == PracticeMode.QUIZ },
    todayMaterial = distinctToday(rows, dayStart) { it.mode == PracticeMode.MATERIAL },
    answered = rows.map { it.questionKey }.distinct().size,
    wrong = wrongKeys.size,
)

/**
 * 「今日做了几题」的统一算法：只数今天的、按题目键去重、可再按来源筛选。
 *
 * 抽出来是因为三条线（例句 / 题库 / 材料）必须**用同一把尺子**——
 * 各写各的迟早会出现「同一次作答在 A 线算 1、在 B 线算 2」。
 */
private fun distinctToday(
    rows: List<cn.tonglu.app.data.listening.QuestionAttempt>,
    dayStart: Long,
    match: (cn.tonglu.app.data.listening.QuestionAttempt) -> Boolean,
): Int = rows.filter { it.answeredAt >= dayStart && match(it) }.map { it.questionKey }.distinct().size

@Composable
internal fun EnglishTodayCard(
    owner: String, refreshKey: String,
    onWords: () -> Unit, onSentences: () -> Unit, onQuestions: () -> Unit,
    /** 第四个入口（plan §5 第 2 条）。以前「错题复习」只出现在做题卡的副标题里，**没有入口**。 */
    onWrong: () -> Unit = onQuestions,
) {
    val context = LocalContext.current
    val repository = remember(context) { EnglishRepository(context) }
    val practiceStore = remember(context, owner) { PracticeStore.of(context, owner) }
    val sentenceStore = remember(context, owner) { DailySentenceStore(context, owner) }
    var practice by remember(owner) { mutableStateOf(PracticeRecord()) }
    var spokenSentences by remember(owner) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var settings by remember(owner) { mutableStateOf(EnglishSettings(owner)) }
    var editingGoal by remember(owner) { mutableStateOf(false) }
    var stats by remember(owner) { mutableStateOf<EnglishStats?>(null) }
    var bookName by remember(owner) { mutableStateOf<String?>(null) }
    var error by remember(owner) { mutableStateOf<String?>(null) }
    var retry by remember(owner) { mutableIntStateOf(0) }
    var saving by remember(owner) { mutableStateOf(false) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); clock = System.currentTimeMillis() } }
    LaunchedEffect(owner, retry, refreshKey) {
        try {
            error = null
            repository.dao.settings(owner).collectLatest { saved ->
                settings = saved ?: EnglishSettings(owner)
                bookName = repository.dao.book(settings.bookId)?.name
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) { error = "读取词书设置失败：${cause.localizedMessage ?: "请重试"}" }
    }
    LaunchedEffect(owner, settings.bookId, clock, retry) {
        stats = null
        try {
            val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            repository.dao.stats(owner, settings.bookId, clock, dayStart).collect { stats = it }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) { error = "读取学习记录失败：${cause.localizedMessage ?: "请重试"}" }
    }
    // 做题那条线的完成记录。**读失败要如实显示**，不能退化成 0（那会假装「今天没练」）。
    LaunchedEffect(owner, refreshKey, retry) {
        try {
            val dayStart = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            practice = withContext(Dispatchers.IO) {
                spokenSentences = sentenceStore.spokenToday()
                practiceRecordOf(practiceStore.attempts(), dayStart, practiceStore.wrongKeys())
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) { error = "读取做题记录失败：${cause.localizedMessage ?: "请重试"}" }
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceOpaque)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("今日进度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = { editingGoal = !editingGoal }) {
                    Text(if (editingGoal) "收起目标" else "目标 ${settings.dailyGoal} 词")
                }
            }
            val current = stats
            val goal = settings.dailyGoal.coerceAtLeast(1)
            if (current == null) Text("正在读取学习记录…", color = AppColors.TextSecondary)
            else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${current.today}", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = AppColors.Accent)
                    Text("/ $goal 词", modifier = Modifier.padding(bottom = 5.dp), color = AppColors.TextSecondary)
                }
                LinearProgressIndicator(progress = { (current.today.toFloat() / goal).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("待复习 ${current.due} · 错词 ${current.wrong} · 已学 ${current.studied}",
                    style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
            Text(bookName ?: "选一本喜欢的词书，开始第一步。", style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary)
            if (editingGoal) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(10, 20, 30).forEach { goal ->
                    FilterChip(selected = settings.dailyGoal == goal, enabled = !saving,
                        onClick = {
                            saving = true
                            scope.launch {
                                try { repository.dao.settings(settings.copy(dailyGoal = goal, updatedAt = System.currentTimeMillis())) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (cause: Exception) { error = "保存目标失败：${cause.localizedMessage ?: "请重试"}" }
                                finally { saving = false }
                            }
                        }, label = { Text("$goal 个") })
                }
            }
            Button(onClick = onWords, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp)) {
                Text(when {
                    bookName == null -> "选择词书，开始学习"
                    (current?.due ?: 0) > 0 -> "复习到期单词"
                    current != null && englishGoalRemaining(current.today, goal) == 0 -> "今日目标已完成 · 继续学"
                    else -> "继续学习单词"
                })
            }
            HorizontalDivider(color = AppColors.Divider)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSentences, modifier = Modifier.weight(1f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("每日例句")
                        Text("今日朗读 $spokenSentences 句", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = onQuestions, modifier = Modifier.weight(1f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("练习记录")
                        Text("今日 ${practice.todayPractice} 题", style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = onWrong, enabled = practice.wrong > 0, modifier = Modifier.weight(1f)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("错题复习")
                        Text(if (practice.wrong > 0) "待复习 ${practice.wrong} 题" else "暂无错题", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            error?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { retry++ }) { Text("重新读取") }
            }
        }
    }
}
