package cn.tonglu.app.data.diary

import org.junit.Assert.*
import org.junit.Test

class DiaryEntriesTest {
    @Test
    fun savingKeepsTodaysPlansFirstAndNewestWithinEachType() {
        val today = 20_000L
        val morning = DiaryDates.atTime(today, 10, 0)
        var data = DiaryData(plans = listOf(DiaryPlan("legacy", "旧计划", today)))
        data = DiaryEntries.add(data, DiaryEntryKind.IDEA, "早上的灵感", morning)
        data = DiaryEntries.add(data, DiaryEntryKind.PLAN, "先写的计划", morning)
        data = DiaryEntries.add(data, DiaryEntryKind.PLAN, "后写的计划", morning)
        data = DiaryEntries.add(data, DiaryEntryKind.IDEA, "最新的灵感", morning + 60_000)

        assertEquals(
            listOf("后写的计划", "先写的计划", "旧计划", "最新的灵感", "早上的灵感"),
            DiaryEntries.groups(data, today, "").single().entries.map { it.text },
        )
    }

    @Test
    fun convertingAnIdeaAndBackRetainsItsDateTagsGoalAndReview() {
        val idea = Idea("idea", "试一种版式", 1234, 8, listOf("作品集"), IdeaReview.KEEP, "goal")
        val original = DiaryData(ideas = listOf(idea))
        val converted = DiaryEntries.convert(original, DiaryEntry.Inspiration(idea), 5678)
        val plan = converted.plans.single()
        assertTrue(converted.ideas.isEmpty())
        assertEquals(
            DiaryPlan("idea", "试一种版式", 8, "goal", recordedAt = 1234, tags = listOf("作品集"), ideaReview = IdeaReview.KEEP),
            plan,
        )

        val back = DiaryEntries.convert(converted, DiaryEntry.Plan(plan), 5678)
        assertTrue(back.plans.isEmpty())
        assertEquals(original, back)
    }

    @Test
    fun convertingACarriedPlanAndBackPreservesCompletionAndLineage() {
        val plan = DiaryPlan("plan", "整理作品集", 8, "goal", done = true, carriedFrom = "yesterday", tags = listOf("作品集"))
        val original = DiaryData(plans = listOf(plan))
        val converted = DiaryEntries.convert(original, DiaryEntry.Plan(plan), 5678)

        assertTrue(converted.plans.isEmpty())
        assertEquals(original, DiaryEntries.convert(converted, DiaryEntry.Inspiration(converted.ideas.single()), 5678))
    }

    @Test
    fun groupsHistoryByLocalDayWithNewestDatesAndEntriesFirst() {
        val today = 20_454L // 2026-01-01
        val older = DiaryPlan("older", "旧计划", today - 2)
        val yesterdayPlan = DiaryPlan("y-plan", "昨天上午", today - 1, recordedAt = DiaryDates.atTime(today - 1, 9, 0))
        val yesterdayIdea = Idea("y-idea", "昨天下午", DiaryDates.atTime(today - 1, 15, 0), today - 1)
        val todayIdea = Idea("today", "今天", DiaryDates.atTime(today, 9, 0), today)
        val data = DiaryData(plans = listOf(older, yesterdayPlan), ideas = listOf(yesterdayIdea, todayIdea))

        val groups = DiaryEntries.groups(data, today, "")
        assertEquals(listOf(today, today - 1, today - 2), groups.map { it.day })
        assertEquals(listOf("y-idea", "y-plan"), groups[1].entries.map { it.id })
        assertEquals(listOf("今天", "昨天", "2025年12月30日"), groups.map { DiaryDates.groupLabel(it.day, today) })
    }

    @Test
    fun searchMatchesMetadataWithoutResurfacingDiscardedIdeas() {
        val matched = Idea("match", "写说明", 10, 8, listOf("Draft"), goalId = "goal")
        val discarded = matched.copy(id = "discarded", review = IdeaReview.DISCARD)
        val other = Idea("other", "午餐", 20, 8)
        val data = DiaryData(ideas = listOf(matched, discarded, other), goals = listOf(DiaryGoal("goal", "作品集")))

        for (query in listOf("写说明", " draft ", "作品集")) {
            assertEquals(listOf("match"), DiaryEntries.groups(data, 8, query).single().entries.map { it.id })
        }
    }

    @Test
    fun undoRestoresADeletedLegacyPlanAtItsPreviousPosition() {
        val plans = (1..3).map { DiaryPlan(it.toString(), "计划 $it", 8, done = it == 2) }
        val original = DiaryData(plans = plans)
        val entry = DiaryEntry.Plan(plans[1])
        val deletion = DiaryEntries.deletion(original, entry)
        val removed = DiaryEntries.delete(original, entry)

        assertEquals(listOf(plans[0], plans[2]), removed.plans)
        assertEquals(original, DiaryEntries.restore(removed, deletion))
    }
}
