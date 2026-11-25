package cn.tonglu.app.data.diary

import org.junit.Assert.*
import org.junit.Test

class ProhibitionStatsTest {
    private val week = 200L
    private val item = Prohibition("habit", "刷短视频", week)

    private fun response(at: Long, vararg values: Pair<String, WeeklyAnswer>) =
        DiarySurvey(at, values.map { it.first }, values.map { it.first }, answers = values.toMap(), submitted = true)

    @Test
    fun posteriorStartsAtOneAndShrinksOneExtremeAnswerTowardItsPrior() {
        assertEquals(1.0, ProhibitionStats.metrics(listOf(item), emptyList(), week).getValue(item.id).estimate, 1e-9)
        val answers = listOf(response(week, item.id to WeeklyAnswer.HIGH))
        val mean = ProhibitionStats.metrics(listOf(item), answers, week).getValue(item.id).estimate
        assertEquals((1.0 + 6.5) / (1.0 + 1.0), mean, 1e-9)
    }

    @Test
    fun threeWeeksHalvesTheWeightAndFutureAnswersDoNotLeakIntoPastStats() {
        val answers = listOf(response(week, item.id to WeeklyAnswer.HIGH), response(week + 4, item.id to WeeklyAnswer.NONE))
        val mean = ProhibitionStats.metrics(listOf(item), answers, week + 3).getValue(item.id).estimate
        assertEquals((1.0 + 6.5 * 0.5) / (1.0 + 0.5), mean, 1e-9)
    }

    @Test
    fun aSkippedQuestionUpdatesExposureWithoutCountingAsZeroOrAnAnswer() {
        val survey = DiarySurvey(week, listOf(item.id), listOf(item.id), submitted = true)
        val stats = ProhibitionStats.metrics(listOf(item), listOf(survey), week).getValue(item.id)
        assertEquals(1, stats.askedCount)
        assertEquals(week, stats.lastAskedWeek)
        assertEquals(0, stats.answerCount)
        assertEquals(0, stats.zeroStreak)
    }

    @Test
    fun sameWeekQuestionsAreStableEvenIfInputOrderChanges() {
        val habits = (1..30).map { item.copy(id = "$it") }
        val first = ProhibitionStats.pickQuestions(habits, emptyList(), week, 12)
        assertEquals(first, ProhibitionStats.pickQuestions(habits.reversed(), emptyList(), week, 12))
    }

    @Test
    fun aNewItemIsAskedInBothFirstWeeksWhenThereIsCapacity() {
        val others = (1..18).map { item.copy(id = "$it", firstWeek = week - 3) }
        val previous = response(week - 1, *others.map { it.id to WeeklyAnswer.HIGH }.toTypedArray())
        val first = ProhibitionStats.pickQuestions(others + item, listOf(previous), week, 8)
        val used = DiarySurvey(week, first, (others + item).map { it.id })
        val second = ProhibitionStats.pickQuestions(others + item, listOf(previous, used), week + 1, 8)
        assertTrue(item.id in first)
        assertTrue(item.id in second)
    }

    @Test
    fun aLargeListKeepsTheWeeklyBudgetAndEveryItemIsAsked() {
        val habits = (1..137).map { item.copy(id = "$it") }
        val asked = mutableSetOf<String>()
        val surveys = mutableListOf<DiarySurvey>()
        repeat(36) { index ->
            val at = week + index
            val ids = ProhibitionStats.pickQuestions(habits, surveys, at, 12)
            assertEquals(12, ids.size)
            assertEquals(12, ids.toSet().size)
            asked.addAll(ids)
            surveys.add(response(at, *ids.map { it to if (it.toInt() % 3 == 0) WeeklyAnswer.HIGH else WeeklyAnswer.NONE }.toTypedArray()))
        }
        assertEquals(habits.map { it.id }.toSet(), asked)
    }

    @Test
    fun rankingRequiresTwoAnswersAndReportsBothDirectionsAndNewEntries() {
        val habits = listOf("a", "b", "c", "d").map { item.copy(id = it) }
        val observations = listOf(
            response(week, "a" to WeeklyAnswer.LOW, "b" to WeeklyAnswer.HIGH),
            response(week + 1, "a" to WeeklyAnswer.LOW, "b" to WeeklyAnswer.NONE, "c" to WeeklyAnswer.NONE),
            response(week + 2, "a" to WeeklyAnswer.HIGH, "b" to WeeklyAnswer.NONE, "c" to WeeklyAnswer.LOW, "d" to WeeklyAnswer.HIGH),
        )
        val rank = ProhibitionStats.leaderboard(habits, observations, week + 2)
        assertEquals(listOf("a", "b", "c"), rank.map { it.item.id })
        assertEquals(listOf(1, 2, 3), rank.map { it.position })
        assertEquals(listOf(2, 1, null), rank.map { it.previousPosition })
    }

    @Test
    fun rankingReturnsOnlyTheFirstTen() {
        val habits = (1..15).map { item.copy(id = "$it") }
        val observations = (week..week + 1).map { at ->
            response(at, *habits.map { it.id to WeeklyAnswer.MEDIUM }.toTypedArray())
        }
        assertEquals((1..10).toList(), ProhibitionStats.leaderboard(habits, observations, week + 1).map { it.position })
    }

    @Test
    fun sixConsecutiveZeroWeeksStabilizeAnItemUntilItsNextPositiveAnswer() {
        val zeros = (week..week + 5).map { response(it, item.id to WeeklyAnswer.NONE) }
        fun at(at: Long, responses: List<DiarySurvey> = zeros) =
            ProhibitionStats.metrics(listOf(item), responses, at).getValue(item.id)
        assertFalse(at(week + 4).stable)
        assertEquals(6, at(week + 5).zeroStreak)
        assertTrue(at(week + 5).stable)
        assertTrue(at(week + 6).stable)
        val after = at(week + 7, zeros + response(week + 7, item.id to WeeklyAnswer.LOW))
        assertFalse(after.stable)
        assertEquals(0, after.zeroStreak)
    }

    @Test
    fun aMissingWeekBreaksTheRunTowardStability() {
        val observations = (week..week + 6).filter { it != week + 3 }.map { response(it, item.id to WeeklyAnswer.NONE) }
        val stats = ProhibitionStats.metrics(listOf(item), observations, week + 6).getValue(item.id)
        assertEquals(3, stats.zeroStreak)
        assertFalse(stats.stable)
    }
}
