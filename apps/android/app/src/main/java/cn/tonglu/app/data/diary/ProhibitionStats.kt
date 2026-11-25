package cn.tonglu.app.data.diary

import kotlin.math.pow
import kotlin.random.Random

internal object ProhibitionStats {
    const val MIN_QUESTIONS = 8
    const val DEFAULT_QUESTIONS = 12
    const val MAX_QUESTIONS = 20
    private const val HALF_LIFE_WEEKS = 3.0
    private const val PRIOR_SHAPE = 1.0
    private const val PRIOR_RATE = 1.0
    private const val EXPLORATION_PER_WEEK = 0.35
    private const val STABLE_EXPLORATION = 0.5
    private const val GUARANTEED_NEW_WEEKS = 2
    private const val OVERDUE_WEEKS = 12
    private const val STABLE_WEEKS = 6
    private const val MIN_RANK_ANSWERS = 2
    private const val RANK_LIMIT = 10

    data class Metrics(
        val estimate: Double,
        val answerCount: Int,
        val askedCount: Int,
        val lastAskedWeek: Long?,
        val zeroStreak: Int,
        val stable: Boolean,
        val lastPositiveWeek: Long?,
    )

    data class Rank(val position: Int, val item: Prohibition, val stats: Metrics, val previousPosition: Int?)

    fun isActive(item: Prohibition, week: Long): Boolean =
        item.firstWeek <= week && (item.archivedWeek == null || item.archivedWeek > week)

    fun metrics(items: List<Prohibition>, surveys: List<DiarySurvey>, week: Long): Map<String, Metrics> {
        val asked = mutableMapOf<String, MutableList<Long>>()
        val answers = mutableMapOf<String, MutableList<Pair<Long, WeeklyAnswer>>>()
        for (survey in surveys.filter { it.week <= week }.sortedBy { it.week }) {
            for (id in survey.questionIds()) asked.getOrPut(id, ::mutableListOf).add(survey.week)
            if (survey.submitted) {
                survey.answers.forEach { (id, answer) ->
                    answers.getOrPut(id, ::mutableListOf).add(survey.week to answer)
                }
            }
        }
        return items.associate { item ->
            var shape = PRIOR_SHAPE
            var rate = PRIOR_RATE
            var zeroStreak = 0
            var stable = false
            var previousWeek: Long? = null
            var lastPositiveWeek: Long? = null
            val observations = answers[item.id].orEmpty()
            for ((answeredWeek, answer) in observations) {
                val weight = 0.5.pow((week - answeredWeek) / HALF_LIFE_WEEKS)
                shape += weight * answer.estimate
                rate += weight
                if (answer == WeeklyAnswer.NONE) {
                    zeroStreak = if (previousWeek == answeredWeek - 1) zeroStreak + 1 else 1
                    if (zeroStreak >= STABLE_WEEKS) stable = true
                } else {
                    lastPositiveWeek = answeredWeek
                    zeroStreak = 0
                    stable = false
                }
                previousWeek = answeredWeek
            }
            val exposures = asked[item.id].orEmpty()
            item.id to Metrics(shape / rate, observations.size, exposures.size, exposures.lastOrNull(), zeroStreak, stable, lastPositiveWeek)
        }
    }

    fun pickQuestions(items: List<Prohibition>, surveys: List<DiarySurvey>, week: Long, count: Int): List<String> {
        val eligible = items.filter { isActive(it, week) }
        val stats = metrics(eligible, surveys, week - 1)
        val random = Random(week)
        // 按标识抽平局顺序，同周结果不受调用方列表顺序影响。
        val candidates = eligible.sortedBy { it.id }.map { item ->
            val history = stats.getValue(item.id)
            val wait = week - (history.lastAskedWeek ?: (item.firstWeek - 1))
            val exploration = if (history.stable) STABLE_EXPLORATION else 1.0
            Candidate(
                id = item.id,
                mandatory = week - item.firstWeek < GUARANTEED_NEW_WEEKS,
                overdue = wait.takeIf { it >= OVERDUE_WEEKS } ?: 0,
                score = history.estimate + wait * EXPLORATION_PER_WEEK * exploration,
                askedCount = history.askedCount,
                tie = random.nextInt(),
            )
        }
        val limit = count.coerceIn(MIN_QUESTIONS, MAX_QUESTIONS)
        val oldest = candidates.filter { it.overdue > 0 }.maxWithOrNull(compareBy<Candidate> { it.overdue }.thenBy { it.tie })
        // 至少给久未出现的条目一个位置，持续新增条目时也能轮到旧题。
        val suggested = candidates.filterNot { it == oldest }.sortedWith(
            compareByDescending<Candidate> { it.mandatory }
                .thenBy { if (it.mandatory) it.askedCount else 0 }
                .thenByDescending { it.overdue }
                .thenByDescending { it.score }
                .thenBy { it.tie },
        )
        return (listOfNotNull(oldest) + suggested).take(limit).map { it.id }
    }

    fun leaderboard(items: List<Prohibition>, surveys: List<DiarySurvey>, week: Long): List<Rank> {
        val previous = ranked(items, surveys, week - 1).mapIndexed { index, (item, _) -> item.id to index + 1 }.toMap()
        return ranked(items, surveys, week).mapIndexed { index, (item, stats) ->
            Rank(index + 1, item, stats, previous[item.id])
        }
    }

    private fun ranked(items: List<Prohibition>, surveys: List<DiarySurvey>, week: Long): List<Pair<Prohibition, Metrics>> {
        val active = items.filter { isActive(it, week) }
        val stats = metrics(active, surveys, week)
        return active.map { it to stats.getValue(it.id) }.filter { (_, metric) -> metric.answerCount >= MIN_RANK_ANSWERS }
            .sortedWith(compareByDescending<Pair<Prohibition, Metrics>> { it.second.estimate }.thenBy { it.first.id })
            .take(RANK_LIMIT)
    }

    private data class Candidate(
        val id: String,
        val mandatory: Boolean,
        val overdue: Long,
        val score: Double,
        val askedCount: Int,
        val tie: Int,
    )
}
