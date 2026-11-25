package cn.tonglu.app.data.diary

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class DiaryRulesTest {
    @Test
    fun reminderCountsLocalCalendarDaysAndCanBeTurnedOff() {
        val data = DiaryData(ideas = listOf(Idea(text = "灵感", recordedAt = 0, day = 100)))
        assertEquals(102L, DiaryRules.summaryDueDay(data))
        assertEquals(101L, DiaryRules.summaryDueDay(data.copy(settings = DiarySettings(SummaryCycle.TWO))))
        assertEquals(106L, DiaryRules.summaryDueDay(data.copy(settings = DiarySettings(SummaryCycle.WEEKLY))))
        assertNull(DiaryRules.summaryDueDay(data.copy(settings = DiarySettings(SummaryCycle.OFF))))
        assertNull(DiaryRules.summaryDueDay(DiaryData()))
    }

    @Test
    fun savingSummaryRestartsCadenceAndKeepsNewSameDayEntriesForNextReview() {
        val covered = DiaryPlan("covered", "已回顾", day = 102)
        val data = DiaryRules.saveSummary(DiaryData(plans = listOf(covered)), 102, 0, "", emptyMap())
        assertEquals(105L, DiaryRules.summaryDueDay(data))
        val lateIdea = Idea("late", "晚一点想到", recordedAt = 1, day = 102)
        val next = data.copy(plans = listOf(covered, covered.copy(id = "new")), ideas = listOf(lateIdea))
        assertEquals(listOf("new"), DiaryRules.summaryPlans(next, 105).map { it.id })
        assertEquals(listOf("late"), DiaryRules.summaryIdeas(next, 105).map { it.id })
    }

    @Test
    fun carryOnlyCopiesRequestedUnfinishedYesterdayPlansAndPreservesTheirGoal() {
        val carry = DiaryPlan("pending", "未完成", day = 99, goalId = "goal")
        val data = DiaryData(plans = listOf(
            carry, carry.copy(id = "finished", done = true), carry.copy(id = "older", day = 98),
            carry.copy(id = "unselected"),
        ))
        val after = DiaryRules.carryPlans(data, 100, setOf("pending", "finished", "older"))
        assertEquals(data.plans, after.plans.dropLast(1))
        val moved = after.plans.last()
        assertEquals(carry.copy(id = moved.id, day = 100, carriedFrom = carry.id), moved)
        assertNotEquals(carry.id, moved.id)
    }

    @Test
    fun carryCanBeRetriedWithoutDuplicatingPlans() {
        val carry = DiaryPlan("pending", "未完成", day = 99)
        val after = DiaryRules.carryPlans(DiaryData(plans = listOf(carry)), 100, setOf(carry.id))
        assertEquals(after, DiaryRules.carryPlans(after, 100, setOf(carry.id)))
    }

    @Test
    fun reviewingIdeasAppliesAllDecisionsInOneUpdate() {
        val ideas = listOf("keep", "convert", "discard").map { Idea(it, it, 0, 100) }
        val result = DiaryRules.saveSummary(DiaryData(ideas = ideas), 100, 10, "回顾", mapOf(
            "convert" to IdeaReview.PLAN, "discard" to IdeaReview.DISCARD,
        ))
        assertEquals(listOf(IdeaReview.KEEP, IdeaReview.PLAN, IdeaReview.DISCARD), result.ideas.map { it.review })
        assertEquals("convert", result.plans.single().text)
        assertEquals(listOf("keep", "convert"), result.summaries.single().ideas.map { it.text })
    }

    @Test
    fun anOpenedWeekKeepsItsQuestionsAfterChangesInSettingsOrList() {
        val week = 200L
        val items = (1..20).map { Prohibition(id = "$it", text = "$it", firstWeek = week) }
        val opened = DiaryRules.openSurvey(DiaryData(prohibitions = items), week)
        val changed = opened.copy(
            prohibitions = items + Prohibition("new", "新增", week + 1),
            settings = DiarySettings(surveyCount = 8),
        )
        assertEquals(opened.surveys, DiaryRules.openSurvey(changed, week).surveys)
        assertEquals(12, opened.surveys.single().suggestedIds.size)
        assertEquals(8, DiaryRules.openSurvey(changed, week + 1).surveys.last().suggestedIds.size)
    }

    @Test
    fun goalBlockersRankRecentPositiveAnswersForTheAssociatedGoal() {
        val goal = DiaryGoal("goal", "作品集")
        val week = 200L
        val habits = listOf(
            Prohibition("high", "频繁发生", week - 10, goal.id),
            Prohibition("low", "偶尔发生", week - 10, goal.id),
            Prohibition("old", "已很久没发生", week - 10, goal.id),
            Prohibition("other", "未关联", week - 10),
        )
        val recent = mapOf("high" to WeeklyAnswer.HIGH, "low" to WeeklyAnswer.LOW, "other" to WeeklyAnswer.HIGH)
        val surveys = listOf(
            DiarySurvey(week - 6, listOf("old"), listOf("old"),
                answers = mapOf("old" to WeeklyAnswer.HIGH), submitted = true),
            DiarySurvey(week, recent.keys.toList(), recent.keys.toList(), answers = recent, submitted = true),
        )
        val data = DiaryData(goals = listOf(goal), prohibitions = habits, surveys = surveys)
        assertEquals(listOf(GoalBlocker(goal.title, listOf("频繁发生", "偶尔发生"))), DiaryRules.goalBlockers(data, week))
    }

    @Test
    fun weeklyReminderUsesLocalWeekAndSelectedClockTimeAcrossYearBoundary() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val start = 20_087L // 2024-12-30 周一
        val settings = DiarySettings(surveyWeekday = 7, surveyHour = 20, surveyMinute = 15)
        val fire = DiaryDates.surveyAt(settings, DiaryDates.week(start), zone)
        assertEquals(1_736_079_300_000L, fire) // 2025-01-05 20:15 UTC+8
        assertEquals(start + 6, DiaryDates.dayAt(fire, zone))
    }
}
