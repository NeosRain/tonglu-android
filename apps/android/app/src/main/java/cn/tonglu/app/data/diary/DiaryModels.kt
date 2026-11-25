package cn.tonglu.app.data.diary

import androidx.annotation.Keep
import java.util.UUID

@Keep
internal data class Idea(
    val id: String = diaryId(),
    val text: String,
    val recordedAt: Long,
    val day: Long,
    val tags: List<String> = emptyList(),
    val review: IdeaReview? = null,
    val goalId: String? = null,
    val formerPlan: FormerDiaryPlan? = null,
)

@Keep
internal data class FormerDiaryPlan(val done: Boolean, val carriedFrom: String?, val recordedAt: Long?)

@Keep
internal enum class IdeaReview(val label: String) {
    KEEP("保留"), PLAN("转成计划"), DISCARD("丢弃"),
}

@Keep
internal data class DiaryGoal(val id: String = diaryId(), val title: String, val deadline: Long? = null)

@Keep
internal data class DiaryPlan(
    val id: String = diaryId(),
    val text: String,
    val day: Long,
    val goalId: String? = null,
    val done: Boolean = false,
    val carriedFrom: String? = null,
    val recordedAt: Long? = null,
    val tags: List<String>? = null,
    val ideaReview: IdeaReview? = null,
)

@Keep
internal enum class DiaryEntryKind(val label: String) {
    IDEA("💡 灵感"), PLAN("✅ 今天要做"),
}

@Keep
internal data class SummaryIdea(val text: String, val review: IdeaReview)
@Keep
internal data class GoalBlocker(val goal: String, val habits: List<String>)

@Keep
internal data class DiarySummary(
    val id: String = diaryId(),
    val fromDay: Long,
    val throughDay: Long,
    val recordedAt: Long,
    val text: String,
    val plans: List<DiaryPlan>,
    val ideas: List<SummaryIdea>,
    val blockers: List<GoalBlocker>,
)

@Keep
internal data class Prohibition(
    val id: String = diaryId(),
    val text: String,
    val firstWeek: Long,
    val goalId: String? = null,
    val archivedWeek: Long? = null,
)

@Keep
internal enum class WeeklyAnswer(val label: String, val estimate: Double) {
    NONE("没做", 0.0), LOW("1–2 次", 1.5), MEDIUM("3–5 次", 4.0), HIGH("几乎每天", 6.5),
}

@Keep
internal data class DiarySurvey(
    val week: Long,
    val suggestedIds: List<String>,
    val allIds: List<String>,
    val expanded: Boolean = false,
    val answers: Map<String, WeeklyAnswer> = emptyMap(),
    val submitted: Boolean = false,
) {
    fun questionIds(): List<String> {
        if (!expanded) return suggestedIds
        val suggested = suggestedIds.toHashSet()
        return suggestedIds + allIds.filterNot(suggested::contains)
    }
}

@Keep
internal enum class SummaryCycle(val days: Int, val label: String) {
    TWO(2, "每 2 天"), THREE(3, "每 3 天"), WEEKLY(7, "每周"), OFF(0, "关闭"),
}

@Keep
internal data class DiarySettings(
    val summaryCycle: SummaryCycle = SummaryCycle.THREE,
    val surveyWeekday: Int = 7,
    val surveyHour: Int = 20,
    val surveyMinute: Int = 0,
    val surveyCount: Int = ProhibitionStats.DEFAULT_QUESTIONS,
    val showHomeCard: Boolean = true,
    val writeKind: DiaryEntryKind? = null,
)

@Keep
internal data class DiaryData(
    val ideas: List<Idea> = emptyList(),
    val plans: List<DiaryPlan> = emptyList(),
    val goals: List<DiaryGoal> = emptyList(),
    val summaries: List<DiarySummary> = emptyList(),
    val prohibitions: List<Prohibition> = emptyList(),
    val surveys: List<DiarySurvey> = emptyList(),
    val settings: DiarySettings = DiarySettings(),
    val example: Boolean = false,
)

internal fun diaryId(): String = UUID.randomUUID().toString()
