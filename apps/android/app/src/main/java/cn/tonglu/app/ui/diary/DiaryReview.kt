package cn.tonglu.app.ui.diary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.diary.*

@Composable
internal fun DiarySummaryPage(state: DiaryState, data: DiaryData, close: () -> Unit) {
    val today = state.today
    val start = DiaryRules.summaryStart(data, today)
    val plans = DiaryRules.summaryPlans(data, today)
    val ideas = DiaryRules.summaryIdeas(data, today)
    val blockers = remember(data.goals, data.prohibitions, data.surveys, state.week) { DiaryRules.goalBlockers(data, state.week) }
    var text by rememberSaveable { mutableStateOf("") }
    var decisions by rememberSaveable { mutableStateOf(HashMap<String, IdeaReview>()) }

    DiaryLayout(DiaryPage.SUMMARY.title, close, state, footer = {
        DiaryAction(state.saveLabel("保存小结"), {
            val now = System.currentTimeMillis()
            val savedDecisions = decisions.toMap()
            val reflection = text
            state.save({ DiaryRules.saveSummary(it, DiaryDates.dayAt(now), now, reflection, savedDecisions) }, close)
        }, enabled = !state.saving)
    }) {
        item("reflection") {
            DiaryCaption(summaryRange(start, today))
            Spacer(Modifier.height(8.dp))
            DiaryField(text, { text = it }, "写几句话（可选）", lines = 3)
        }
        diarySummaryPlans(plans, data.goals)
        item("idea-heading") { DiaryHeading("这段时间的灵感 · ${ideas.size}") }
        items(ideas, key = { it.id }) { idea ->
            DiaryGroup {
                Text(idea.text)
                if (idea.tags.isNotEmpty()) DiaryCaption(idea.tags.joinToString(" · "))
                DiaryChips(IdeaReview.entries, decisions[idea.id] ?: IdeaReview.KEEP, { it.label }) { review ->
                    decisions = HashMap(decisions).apply { put(idea.id, review) }
                }
            }
        }
        diaryBlockers(blockers)
    }
}

@Composable
internal fun DiarySummaryDetail(state: DiaryState, summary: DiarySummary, goals: List<DiaryGoal>, close: () -> Unit) {
    DiaryLayout(DiaryPage.SUMMARY_DETAIL.title, close, state) {
        item("text") {
            DiaryGroup {
                DiaryCaption(summaryRange(summary.fromDay, summary.throughDay))
                if (summary.text.isNotBlank()) Text(summary.text)
            }
        }
        diarySummaryPlans(summary.plans, goals)
        item("idea-heading") { DiaryHeading("回顾过的灵感 · ${summary.ideas.size}") }
        items(summary.ideas.size) { index ->
            val idea = summary.ideas[index]
            DiaryGroup {
                Text(idea.text)
                DiaryCaption(idea.review.label)
            }
        }
        diaryBlockers(summary.blockers)
    }
}

private fun LazyListScope.diaryBlockers(blockers: List<GoalBlocker>) {
    if (blockers.isEmpty()) return
    item("blockers-heading") { DiaryHeading("影响目标的习惯") }
    items(blockers.size) { index ->
        val blocker = blockers[index]
        DiaryGroup {
            DiaryHeading(blocker.goal)
            blocker.habits.forEach { Text(it) }
        }
    }
}

internal fun summaryRange(fromDay: Long, throughDay: Long): String =
    if (fromDay == throughDay) DiaryDates.date(fromDay)
    else "${DiaryDates.date(fromDay)} 至 ${DiaryDates.date(throughDay)}"

private sealed interface TimelineItem {
    val id: String
    val recordedAt: Long

    data class Inspiration(val idea: Idea) : TimelineItem {
        override val id get() = idea.id
        override val recordedAt get() = idea.recordedAt
    }

    data class Review(val summary: DiarySummary) : TimelineItem {
        override val id get() = summary.id
        override val recordedAt get() = summary.recordedAt
    }
}

@Composable
internal fun DiaryHistoryPage(state: DiaryState, data: DiaryData, close: () -> Unit, open: (String) -> Unit) {
    val timeline = remember(data.ideas, data.summaries) {
        (data.ideas.filter { it.review != IdeaReview.DISCARD }.map(TimelineItem::Inspiration) +
            data.summaries.map(TimelineItem::Review)).sortedByDescending { it.recordedAt }
    }
    val context = LocalContext.current
    val share = rememberDiaryShare()
    DiaryLayout(DiaryPage.HISTORY.title, close, state) {
        item("export") {
            DiaryGroup {
                DiaryHeading("导出灵感与小结")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { share { exportDiary(context, data, json = false) } }, enabled = timeline.isNotEmpty()) {
                        Text("纯文本")
                    }
                    TextButton(onClick = { share { exportDiary(context, data, json = true) } }, enabled = timeline.isNotEmpty()) {
                        Text("JSON")
                    }
                }
            }
        }
        if (timeline.isEmpty()) item("empty") { DiaryNotice("记下灵感或写完小结后，就能在这里回顾。") }
        items(timeline, key = { it.id }) { entry ->
            when (entry) {
                is TimelineItem.Inspiration -> DiaryGroup {
                    DiaryCaption("灵感 · ${DiaryDates.timestamp(entry.recordedAt)}")
                    Text(entry.idea.text)
                    if (entry.idea.tags.isNotEmpty()) DiaryCaption(entry.idea.tags.joinToString(" · "))
                    if (entry.idea.review == IdeaReview.PLAN) DiaryCaption("已转成计划")
                }
                is TimelineItem.Review -> DiaryLink(
                    "小结 · ${summaryRange(entry.summary.fromDay, entry.summary.throughDay)}",
                    { open(DiaryPage.SUMMARY_DETAIL.route(entry.id)) },
                    entry.summary.text.takeIf { it.isNotBlank() }?.let { if (it.length > 100) it.take(100) + "…" else it },
                )
            }
        }
    }
}

@Composable
internal fun <T> DiaryChips(values: List<T>, selected: T?, label: (T) -> String, select: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { select(value) },
                label = { Text(label(value)) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}
