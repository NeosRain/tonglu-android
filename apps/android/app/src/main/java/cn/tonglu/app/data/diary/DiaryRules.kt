package cn.tonglu.app.data.diary

internal object DiaryRules {
    const val SUMMARY_HOUR = 20
    private const val BLOCKER_LOOKBACK_WEEKS = 6

    private fun firstActivityDay(data: DiaryData): Long? =
        listOfNotNull(data.ideas.minOfOrNull { it.day }, data.plans.minOfOrNull { it.day }).minOrNull()

    private fun lastSummaryDay(data: DiaryData) = data.summaries.maxOfOrNull { it.throughDay }

    fun summaryStart(data: DiaryData, today: Long): Long {
        val last = lastSummaryDay(data) ?: return firstActivityDay(data)?.coerceAtMost(today) ?: today
        val remaining = data.ideas.any { it.day == last && it.review == null } ||
            unsummarizedPlans(data, last).any { it.day == last }
        return (if (remaining) last else last + 1).coerceAtMost(today)
    }

    fun summaryDueDay(data: DiaryData): Long? {
        val cycle = data.settings.summaryCycle.days
        if (cycle == 0) return null
        val start = lastSummaryDay(data)?.plus(1)
            ?: firstActivityDay(data) ?: return null
        return start + cycle - 1
    }

    fun carryablePlans(data: DiaryData, today: Long): List<DiaryPlan> {
        val carriedIds = data.plans.mapNotNull { it.carriedFrom }.toHashSet()
        return data.plans.filter { it.day == today - 1 && !it.done && it.id !in carriedIds }
    }

    fun carryPlans(data: DiaryData, today: Long, planIds: Set<String>): DiaryData {
        val additions = carryablePlans(data, today).filter { it.id in planIds }.map {
            it.copy(id = diaryId(), day = today, carriedFrom = it.id)
        }
        return data.copy(plans = data.plans + additions)
    }

    private fun unsummarizedPlans(data: DiaryData, last: Long?): List<DiaryPlan> {
        if (last == null) return data.plans
        val covered = data.summaries.filter { it.throughDay == last }
            .flatMap { it.plans }.associateBy { it.id }
        return data.plans.filter { it.day > last || it.day == last && covered[it.id] != it }
    }

    fun summaryPlans(data: DiaryData, today: Long): List<DiaryPlan> =
        unsummarizedPlans(data, lastSummaryDay(data)).filter { it.day <= today }

    fun summaryIdeas(data: DiaryData, today: Long): List<Idea> =
        data.ideas.filter { it.day in summaryStart(data, today)..today && it.review == null }

    fun goalBlockers(data: DiaryData, week: Long): List<GoalBlocker> {
        val associated = data.prohibitions.filter { it.goalId != null && ProhibitionStats.isActive(it, week) }
        val stats = ProhibitionStats.metrics(associated, data.surveys, week)
        return data.goals.mapNotNull { goal ->
            val habits = associated.filter {
                val metric = stats.getValue(it.id)
                it.goalId == goal.id && !metric.stable &&
                    metric.lastPositiveWeek?.let { answered -> week - answered < BLOCKER_LOOKBACK_WEEKS } == true
            }
                .sortedByDescending { stats.getValue(it.id).estimate }
                .take(3).map { it.text }
            habits.takeIf { it.isNotEmpty() }?.let { GoalBlocker(goal.title, it) }
        }
    }

    fun saveSummary(data: DiaryData, today: Long, now: Long, text: String, decisions: Map<String, IdeaReview>): DiaryData {
        val ideas = summaryIdeas(data, today)
        val reviewed = ideas.associate { it.id to (decisions[it.id] ?: IdeaReview.KEEP) }
        val summary = DiarySummary(
            fromDay = summaryStart(data, today),
            throughDay = today,
            recordedAt = now,
            text = text.trim(),
            plans = summaryPlans(data, today),
            ideas = ideas.mapNotNull { idea ->
                val review = reviewed.getValue(idea.id)
                if (review == IdeaReview.DISCARD) null else SummaryIdea(idea.text, review)
            },
            blockers = goalBlockers(data, DiaryDates.week(today)),
        )
        return data.copy(
            ideas = data.ideas.map { if (it.id in reviewed) it.copy(review = reviewed.getValue(it.id)) else it },
            plans = data.plans + ideas.filter { reviewed[it.id] == IdeaReview.PLAN }.map { DiaryPlan(text = it.text, day = today) },
            summaries = data.summaries + summary,
        )
    }

    fun openSurvey(data: DiaryData, week: Long): DiaryData {
        if (data.surveys.any { it.week == week }) return data
        val eligible = data.prohibitions.filter { ProhibitionStats.isActive(it, week) }
        if (eligible.isEmpty()) return data
        val survey = DiarySurvey(
            week = week,
            suggestedIds = ProhibitionStats.pickQuestions(eligible, data.surveys, week, data.settings.surveyCount),
            allIds = eligible.map { it.id },
        )
        return data.copy(surveys = data.surveys + survey)
    }
}
