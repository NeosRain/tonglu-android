package cn.tonglu.app.data.diary

internal sealed interface DiaryEntry {
    val id: String
    val text: String
    val day: Long
    val recordedAt: Long?
    val tags: List<String>
    val goalId: String?
    val key: String

    data class Inspiration(val idea: Idea) : DiaryEntry {
        override val id get() = idea.id
        override val text get() = idea.text
        override val day get() = idea.day
        override val recordedAt get() = idea.recordedAt
        override val tags get() = idea.tags
        override val goalId get() = idea.goalId
        override val key get() = "idea:$id"
    }

    data class Plan(val plan: DiaryPlan) : DiaryEntry {
        override val id get() = plan.id
        override val text get() = plan.text
        override val day get() = plan.day
        override val recordedAt get() = plan.recordedAt
        override val tags get() = plan.tags.orEmpty()
        override val goalId get() = plan.goalId
        override val key get() = "plan:$id"
    }
}

internal data class DiaryEntryGroup(val day: Long, val entries: List<DiaryEntry>)
internal data class DiaryDeletion(val entry: DiaryEntry, val index: Int)

internal object DiaryEntries {
    fun add(data: DiaryData, kind: DiaryEntryKind, text: String, now: Long): DiaryData {
        val day = DiaryDates.dayAt(now)
        return when (kind) {
            DiaryEntryKind.IDEA -> data.copy(ideas = data.ideas + Idea(text = text, recordedAt = now, day = day))
            DiaryEntryKind.PLAN -> data.copy(plans = data.plans + DiaryPlan(text = text, day = day, recordedAt = now))
        }
    }

    fun groups(data: DiaryData, today: Long, search: String): List<DiaryEntryGroup> {
        val query = search.trim()
        val goals = data.goals.associateBy { it.id }
        // 同一毫秒及没有时间的旧计划，沿用追加顺序的倒序作为稳定排序。
        val entries = data.plans.asReversed().map(DiaryEntry::Plan) +
            data.ideas.asReversed().filter { it.review != IdeaReview.DISCARD }.map(DiaryEntry::Inspiration)
        return entries.filter { entry ->
            query.isEmpty() || entry.text.contains(query, ignoreCase = true) ||
                entry.tags.any { it.contains(query, ignoreCase = true) } ||
                goals[entry.goalId]?.title.orEmpty().contains(query, ignoreCase = true)
        }.groupBy { it.day }.toSortedMap(reverseOrder()).map { (day, items) ->
            val order = compareByDescending<DiaryEntry> { day == today && it is DiaryEntry.Plan }
                .thenByDescending { it.recordedAt }
            DiaryEntryGroup(day, items.sortedWith(order))
        }
    }

    fun edit(
        data: DiaryData,
        entry: DiaryEntry,
        text: String = entry.text,
        tags: List<String> = entry.tags,
        goalId: String? = entry.goalId,
    ): DiaryData = when (entry) {
        is DiaryEntry.Inspiration -> data.copy(ideas = data.ideas.map {
            if (it.id == entry.id) it.copy(text = text, tags = tags, goalId = goalId) else it
        })
        is DiaryEntry.Plan -> data.copy(plans = data.plans.map {
            if (it.id == entry.id) it.copy(text = text, tags = tags.takeIf(List<String>::isNotEmpty), goalId = goalId) else it
        })
    }

    fun convert(data: DiaryData, entry: DiaryEntry, now: Long): DiaryData = when (entry) {
        is DiaryEntry.Inspiration -> {
            val idea = entry.idea
            val former = idea.formerPlan
            data.copy(
                ideas = data.ideas.filterNot { it.id == idea.id },
                plans = data.plans + DiaryPlan(
                    id = idea.id, text = idea.text, day = idea.day, goalId = idea.goalId,
                    done = former?.done ?: false, carriedFrom = former?.carriedFrom,
                    recordedAt = if (former == null) idea.recordedAt else former.recordedAt,
                    tags = idea.tags.takeIf(List<String>::isNotEmpty), ideaReview = idea.review,
                ),
            )
        }
        is DiaryEntry.Plan -> {
            val plan = entry.plan
            val former = FormerDiaryPlan(plan.done, plan.carriedFrom, plan.recordedAt)
            data.copy(
                plans = data.plans.filterNot { it.id == plan.id },
                ideas = data.ideas + Idea(
                    id = plan.id, text = plan.text, recordedAt = plan.recordedAt ?: now, day = plan.day,
                    tags = plan.tags.orEmpty(), review = plan.ideaReview, goalId = plan.goalId,
                    formerPlan = former.takeIf { it.done || it.carriedFrom != null || it.recordedAt == null },
                ),
            )
        }
    }

    fun delete(data: DiaryData, entry: DiaryEntry): DiaryData = when (entry) {
        is DiaryEntry.Inspiration -> data.copy(ideas = data.ideas.filterNot { it.id == entry.id })
        is DiaryEntry.Plan -> data.copy(plans = data.plans.filterNot { it.id == entry.id })
    }

    fun deletion(data: DiaryData, entry: DiaryEntry): DiaryDeletion = DiaryDeletion(entry, when (entry) {
        is DiaryEntry.Inspiration -> data.ideas.indexOfFirst { it.id == entry.id }
        is DiaryEntry.Plan -> data.plans.indexOfFirst { it.id == entry.id }
    })

    fun restore(data: DiaryData, deletion: DiaryDeletion): DiaryData {
        val entry = deletion.entry
        if (data.ideas.any { it.id == entry.id } || data.plans.any { it.id == entry.id }) return data
        return when (entry) {
            is DiaryEntry.Inspiration -> data.copy(ideas = data.ideas.toMutableList().apply {
                add(deletion.index.coerceIn(0, size), entry.idea)
            })
            is DiaryEntry.Plan -> data.copy(plans = data.plans.toMutableList().apply {
                add(deletion.index.coerceIn(0, size), entry.plan)
            })
        }
    }
}
