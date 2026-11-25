package cn.tonglu.app.ui.diary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.diary.*
import cn.tonglu.app.ui.AppColors

@Composable
internal fun DiaryHabitsPage(state: DiaryState, data: DiaryData, close: () -> Unit, open: (String) -> Unit) {
    val week = state.week
    var archived by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val stats = remember(data.prohibitions, data.surveys, week) { ProhibitionStats.metrics(data.prohibitions, data.surveys, week) }
    val list = remember(data.prohibitions, archived, query) {
        data.prohibitions.filter { (it.archivedWeek != null) == archived && it.text.contains(query.trim(), ignoreCase = true) }
    }
    val (ordinary, stable) = list.partition { archived || !stats.getValue(it.id).stable }

    DiaryLayout(DiaryPage.HABITS.title, close, state) {
        item("add") { DiaryAction("添加不想做的事", { open(DiaryPage.HABIT_EDITOR.name) }, navigates = true) }
        val current = data.surveys.firstOrNull { it.week == week }
        if (current != null || data.prohibitions.any { ProhibitionStats.isActive(it, week) }) {
            item("current") {
                DiaryLink("本周问卷", { open(DiaryPage.SURVEY.route(week)) },
                    if (current?.submitted == true) "已完成" else summaryRange(DiaryDates.weekStart(week), DiaryDates.weekStart(week) + 6))
            }
        }
        if (DiaryReminders.surveyOutstanding(data, week - 1)) item("previous") {
            DiaryLink("补答上周问卷", { open(DiaryPage.SURVEY.route(week - 1)) })
        }
        item("ranking") { DiaryLink(DiaryPage.RANKING.title, { open(DiaryPage.RANKING.name) }, "查看每周估计次数与名次变化") }
        item("settings") { DiaryLink(DiaryPage.SETTINGS.title, { open(DiaryPage.SETTINGS.name) }) }
        item("filters") {
            DiaryChips(listOf(false, true), archived, { if (it) "清单归档" else "当前清单" }) { archived = it }
            if (data.prohibitions.size > 8) DiaryField(query, { query = it }, "查找条目")
        }

        if (list.isEmpty()) item("empty") {
            DiaryNotice(when {
                query.isNotBlank() -> "没有匹配的条目。"
                archived -> "还没有归档条目。"
                else -> "写下一件想少做或不做的事。"
            })
        }
        items(ordinary, key = { it.id }) { habit ->
            val metric = stats.getValue(habit.id)
            val detail = when {
                archived -> null
                habit.firstWeek > week -> "下周进入问卷"
                metric.answerCount == 0 -> "尚未作答"
                else -> rankEstimate(metric.estimate)
            }
            DiaryLink(habit.text, { open(DiaryPage.HABIT_EDITOR.route(habit.id)) }, detail)
        }
        if (stable.isNotEmpty()) item("stable") { DiaryHeading("已稳定 · ${stable.size}") }
        items(stable, key = { it.id }) { habit ->
            DiaryLink(habit.text, { open(DiaryPage.HABIT_EDITOR.route(habit.id)) },
                "连续守住 ${stats.getValue(habit.id).zeroStreak} 周")
        }
    }
}

@Composable
internal fun DiaryHabitEditor(state: DiaryState, data: DiaryData, id: String, close: () -> Unit) {
    val existing = data.prohibitions.firstOrNull { it.id == id }
    var text by rememberSaveable { mutableStateOf(existing?.text.orEmpty()) }
    var goalId by rememberSaveable { mutableStateOf(existing?.goalId) }
    DiaryLayout(if (existing == null) DiaryPage.HABIT_EDITOR.title else "编辑条目", close, state, footer = {
        DiaryAction(state.saveLabel("保存条目"), {
            val week = DiaryDates.week(DiaryDates.dayAt(System.currentTimeMillis()))
            val body = text.trim()
            val goal = goalId
            state.save({ latest ->
                val firstWeek = if (latest.surveys.any { it.week == week }) week + 1 else week
                val habits = if (existing == null) latest.prohibitions + Prohibition(text = body, firstWeek = firstWeek, goalId = goal)
                else latest.prohibitions.map { if (it.id == id) it.copy(text = body, goalId = goal) else it }
                latest.copy(prohibitions = habits)
            }, close)
        }, text.isNotBlank() && !state.saving)
    }) {
        item("text") { DiaryField(text, { text = it }, "我不想做什么") }
        item("goal") { DiaryGoalPicker(data.goals, goalId) { goalId = it } }
        if (existing != null && existing.archivedWeek == null) item("archive") {
            DiaryGroup {
                DiaryCaption("归档后会保留已有回答，之后的新问卷不会再抽到这条。")
                TextButton(onClick = {
                    val week = DiaryDates.week(DiaryDates.dayAt(System.currentTimeMillis()))
                    state.save({ latest ->
                        latest.copy(prohibitions = latest.prohibitions.map { if (it.id == id) it.copy(archivedWeek = week) else it })
                    }, close)
                }, enabled = !state.saving) { Text(state.saveLabel("归档此条")) }
            }
        }
    }
}

@Composable
internal fun DiarySurveyPage(state: DiaryState, data: DiaryData, week: Long, close: () -> Unit) {
    val survey = data.surveys.firstOrNull { it.week == week }
    val available = data.prohibitions.any { ProhibitionStats.isActive(it, week) }
    LaunchedEffect(week, survey == null, available, state.saving) {
        if (survey == null && available && !state.saving && state.error == null) {
            state.save({ DiaryRules.openSurvey(it, week) })
        }
    }
    if (survey == null) {
        DiaryLayout(DiaryPage.SURVEY.title, close, state) {
            if (!available) item { DiaryNotice("这一周没有可作答的条目。") }
            else if (state.error == null) item { CircularProgressIndicator(Modifier.size(28.dp), color = AppColors.Accent) }
            else item { DiaryAction("重试生成问卷", { state.save({ DiaryRules.openSurvey(it, week) }) }, enabled = !state.saving) }
        }
        return
    }
    var answers by rememberSaveable(week) { mutableStateOf(HashMap(survey.answers)) }
    val ids = survey.questionIds()
    val habits = remember(data.prohibitions) { data.prohibitions.associateBy { it.id } }
    val footer: (@Composable () -> Unit)? = if (survey.submitted) null else {
        {
            DiaryAction(state.saveLabel("提交问卷"), {
                val submittedAnswers = answers.toMap()
                state.save({ latest ->
                    latest.copy(surveys = latest.surveys.map {
                        if (it.week == week) it.copy(answers = submittedAnswers, submitted = true) else it
                    })
                }, close)
            }, enabled = !state.saving)
        }
    }
    DiaryLayout(DiaryPage.SURVEY.title, close, state, footer = footer) {
        item("header") {
            DiaryGroup {
                val start = DiaryDates.weekStart(week)
                DiaryCaption(summaryRange(start, start + 6))
                DiaryHeading(if (survey.submitted) "已提交" else "这周做了几次？")
                DiaryCaption("已答 ${answers.size} / ${ids.size}${if (survey.submitted) "" else " · 可跳过单题"}")
                if (!survey.submitted && !survey.expanded && survey.allIds.size > ids.size) {
                    TextButton(onClick = {
                        state.save({ latest ->
                            latest.copy(surveys = latest.surveys.map { if (it.week == week) it.copy(expanded = true) else it })
                        })
                    }, enabled = !state.saving) { Text("全部作答（${survey.allIds.size} 题）") }
                }
            }
        }
        items(ids, key = { it }) { id ->
            DiaryGroup {
                Text(habits.getValue(id).text)
                if (survey.submitted) DiaryCaption(answers[id]?.label ?: "已跳过")
                else {
                    DiaryChips(WeeklyAnswer.entries, answers[id], { it.label }) { selected ->
                        answers = HashMap(answers).apply { put(id, selected) }
                    }
                    TextButton(onClick = { answers = HashMap(answers).apply { remove(id) } }) { Text("跳过") }
                }
            }
        }
    }
}

@Composable
internal fun DiaryRankingPage(state: DiaryState, data: DiaryData, close: () -> Unit) {
    val ranks = remember(data.prohibitions, data.surveys, state.week) {
        ProhibitionStats.leaderboard(data.prohibitions, data.surveys, state.week)
    }
    val context = LocalContext.current
    val share = rememberDiaryShare()
    val colors = DiaryShareColors(AppColors.CanvasOpaque.toArgb(), AppColors.SurfaceOpaque.toArgb(),
        AppColors.TextPrimary.toArgb(), AppColors.TextSecondary.toArgb(), AppColors.Accent.toArgb())
    DiaryLayout(DiaryPage.RANKING.title, close, state) {
        if (ranks.isEmpty()) item("empty") { DiaryNotice("同一条目至少回答两周后，才会进入排行榜。") }
        items(ranks, key = { it.item.id }) { rank ->
            DiaryGroup {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${rank.position}", style = MaterialTheme.typography.headlineSmall, color = AppColors.Accent)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        DiaryHeading(rank.item.text)
                        DiaryCaption(rankEstimate(rank.stats.estimate))
                        DiaryCaption(rankStatus(rank))
                    }
                }
                TextButton(onClick = { share { shareRank(context, rank, data.example, colors) } }) { Text("分享这一条") }
            }
        }
    }
}
