package cn.tonglu.app.ui.diary

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.diary.*
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.AppTheme
import cn.tonglu.app.ui.OriginAlertDialog
import cn.tonglu.app.ui.launchOrigin
import java.util.Calendar

@Composable
internal fun DiaryPlanEditor(state: DiaryState, data: DiaryData, id: String, close: () -> Unit) {
    val existing = data.plans.firstOrNull { it.id == id }
    var text by rememberSaveable { mutableStateOf(existing?.text.orEmpty()) }
    var goalId by rememberSaveable { mutableStateOf(existing?.goalId) }
    DiaryLayout(if (existing == null) DiaryPage.PLAN_EDITOR.title else "编辑计划", close, state, footer = {
        DiaryAction(state.saveLabel("保存计划"), {
            val item = existing?.copy(text = text.trim(), goalId = goalId)
                ?: DiaryPlan(text = text.trim(), day = state.today, goalId = goalId)
            state.save({ latest ->
                val plans = if (existing == null) latest.plans + item else latest.plans.map { if (it.id == id) item else it }
                latest.copy(plans = plans)
            }, close)
        }, text.isNotBlank() && !state.saving)
    }) {
        item("text") { DiaryField(text, { text = it }, "要做什么") }
        item("goal") { DiaryGoalPicker(data.goals, goalId) { goalId = it } }
    }
}

@Composable
internal fun DiaryGoalPicker(goals: List<DiaryGoal>, selected: String?, onSelect: (String?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = goals.firstOrNull { it.id == selected }?.title
    DiaryGroup {
        DiaryCaption("关联目标（可选）")
        if (goals.isEmpty()) DiaryCaption("可在「灵感与计划」添加长期目标。") else {
            TextButton(onClick = { open = true }) { Text(label ?: "选择目标") }
        }
    }
    if (open) OriginAlertDialog(
        onDismissRequest = { open = false },
        title = { Text("关联目标") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                item { TextButton(onClick = { closeWith { onSelect(null); open = false } }) { Text("不关联") } }
                items(goals, key = { it.id }) { goal ->
                    TextButton(onClick = { closeWith { onSelect(goal.id); open = false } }) { Text(goal.title) }
                }
            }
        },
        confirmButton = { TextButton(onClick = this::dismiss) { Text("取消") } },
        containerColor = AppColors.SurfaceOpaque,
        titleContentColor = AppColors.TextPrimary,
        textContentColor = AppColors.TextSecondary,
    )
}

@Composable
internal fun DiaryGoalsPage(state: DiaryState, data: DiaryData, close: () -> Unit, open: (String) -> Unit) {
    DiaryLayout(DiaryPage.GOALS.title, close, state) {
        item("new") { DiaryAction("添加目标", { open(DiaryPage.GOAL_EDITOR.name) }, navigates = true) }
        if (data.goals.isEmpty()) item("empty") { DiaryNotice("添加目标后，就能在计划里关联它。") }
        items(data.goals, key = { it.id }) { goal ->
            DiaryLink(goal.title, { open(DiaryPage.GOAL_DETAIL.route(goal.id)) }, goal.deadline?.let { "截止 ${DiaryDates.date(it)}" })
        }
    }
}

@Composable
internal fun DiaryGoalEditor(state: DiaryState, existing: DiaryGoal?, close: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(existing?.title.orEmpty()) }
    var deadline by rememberSaveable { mutableStateOf(existing?.deadline) }
    val context = LocalContext.current
    val dark = AppTheme.darkState
    DiaryLayout(if (existing == null) DiaryPage.GOAL_EDITOR.title else "编辑目标", close, state, footer = {
        DiaryAction(state.saveLabel("保存目标"), {
            val item = existing?.copy(title = title.trim(), deadline = deadline) ?: DiaryGoal(title = title.trim(), deadline = deadline)
            state.save({ latest ->
                latest.copy(goals = if (existing == null) latest.goals + item else latest.goals.map { if (it.id == item.id) item else it })
            }, close)
        }, title.isNotBlank() && !state.saving)
    }) {
        item("title") { DiaryField(title, { title = it }, "目标") }
        item("deadline") {
            DiaryGroup {
                DiaryCaption("截止日期（可选）")
                TextButton(onClick = {
                    val date = Calendar.getInstance().apply { timeInMillis = DiaryDates.atTime(deadline ?: state.today, 12, 0) }
                    DatePickerDialog(context, diaryPickerTheme(dark), { _, year, month, day ->
                        val picked = Calendar.getInstance().apply { clear(); set(year, month, day, 12, 0) }
                        deadline = DiaryDates.dayAt(picked.timeInMillis)
                    }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH)).show()
                }) { Text(deadline?.let(DiaryDates::date) ?: "选择日期") }
                if (deadline != null) TextButton(onClick = { deadline = null }) { Text("清除日期") }
            }
        }
    }
}

internal fun diaryPickerTheme(dark: Boolean): Int =
    if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert

@Composable
internal fun DiaryGoalDetail(state: DiaryState, data: DiaryData, goal: DiaryGoal, close: () -> Unit, open: (String) -> Unit) {
    val plans = data.plans.filter { it.goalId == goal.id }.sortedByDescending { it.day }
    val ideas = data.ideas.filter { it.goalId == goal.id && it.review != IdeaReview.DISCARD }.sortedByDescending { it.recordedAt }
    DiaryLayout(DiaryPage.GOAL_DETAIL.title, close, state) {
        item("goal") {
            DiaryGroup {
                DiaryHeading(goal.title)
                goal.deadline?.let { DiaryCaption("截止 ${DiaryDates.date(it)}") }
                DiaryCaption("关联计划 ${plans.size} 件 · 完成 ${plans.count { it.done }} 件")
                TextButton(onClick = { open(DiaryPage.GOAL_EDITOR.route(goal.id)) }, modifier = Modifier.launchOrigin()) { Text("编辑目标") }
            }
        }
        if (plans.isEmpty() && ideas.isEmpty()) item("empty") { DiaryNotice("这项目标还没有关联记录。") }
        items(plans, key = { it.id }) { plan -> DiaryPlanSnapshot(plan) }
        if (ideas.isNotEmpty()) item("ideas") { DiaryHeading("关联灵感 · ${ideas.size}") }
        items(ideas, key = { "idea:${it.id}" }) { idea ->
            DiaryGroup {
                Text(idea.text)
                DiaryCaption(DiaryDates.timestamp(idea.recordedAt))
            }
        }
    }
}

internal fun LazyListScope.diarySummaryPlans(plans: List<DiaryPlan>, goals: List<DiaryGoal>) {
    for (done in listOf(true, false)) {
        val group = plans.filter { it.done == done }
        item("plan-header:$done") { DiaryHeading("${if (done) "已完成" else "未完成"} · ${group.size}") }
        items(group, key = { "plan:${it.id}" }) { plan ->
            DiaryPlanSnapshot(plan, goals.firstOrNull { it.id == plan.goalId }?.title)
        }
    }
}

@Composable
private fun DiaryPlanSnapshot(plan: DiaryPlan, goal: String? = null) {
    DiaryGroup {
        Text(plan.text)
        val detail = listOfNotNull(DiaryDates.date(plan.day), if (plan.done) "已完成" else "未完成", goal)
        DiaryCaption(detail.joinToString(" · "))
    }
}
