package cn.tonglu.app.data.ai

import androidx.annotation.DrawableRes
import cn.tonglu.app.R

/** Public identity only. Provider/model labels and machine moderation protocols stay independent. */
object AssistantPersona {
    const val NAME = "番茄酱"
    const val IDENTITY = "你是「苔米厸」里的学习助手「番茄酱」。温和清楚，先回答问题。" +
        "专业释义、翻译、语法分析和结构化输出直接完成任务，不加招呼、角色表演或签名。" +
        "严格遵循任务的JSON/字段/纯译文约束；不编造资料、进度、计时轮次，不宣称看见用户行为。"

    enum class Mood { Idle, Focus, Encourage, Rest, Paused, Complete }

    @DrawableRes
    fun avatar(mood: Mood = Mood.Idle): Int = when (mood) {
        Mood.Idle -> R.drawable.tomato_idle
        Mood.Focus -> R.drawable.tomato_focus
        Mood.Encourage -> R.drawable.tomato_encourage
        Mood.Rest -> R.drawable.tomato_rest
        Mood.Paused -> R.drawable.tomato_paused
        Mood.Complete -> R.drawable.tomato_complete
    }

    fun lines(mood: Mood): List<String> = when (mood) {
        Mood.Idle -> listOf("番茄酱就位，挑一小段开始吧。", "先做眼前这一步，进度会慢慢攒起来。")
        Mood.Focus -> listOf("这一小段拿下，今天又多一块拼图。", "番茄酱在线陪读，进度条慢慢推。", "不急，给这段内容一点时间。")
        Mood.Encourage -> listOf("卡住也正常，换个角度再试试。", "先把一个小问题讲清楚，就很有收获。")
        Mood.Rest -> listOf("大脑缓存清理时间到，喝口水吧。", "眼睛也要放个小假，看看远处。")
        Mood.Paused -> listOf("已暂停，准备好了再继续。", "留个书签，回来接着走。")
        Mood.Complete -> listOf("这份进度，稳稳收下。", "专注结束，给今天的自己点个赞。")
    }
}

/** One instance per timer session. nowMs must come from a monotonic clock. */
class CompanionCopyGate {
    private var lastAt: Long? = null
    private var lastLine: String? = null
    private val deliveredRounds = mutableSetOf<Int>()

    fun next(mood: AssistantPersona.Mood, nowMs: Long, round: Int,
        enabled: Boolean, quiet: Boolean, automatic: Boolean = true): String? {
        if (!enabled || quiet || mood == AssistantPersona.Mood.Paused) return null
        if (automatic && (mood != AssistantPersona.Mood.Focus || round in deliveredRounds)) return null
        val cooldown = if (automatic) 15 * 60_000L else 10_000L
        if (lastAt?.let { nowMs - it < cooldown } == true) return null
        val pool = AssistantPersona.lines(mood)
        val line = pool.firstOrNull { it != lastLine } ?: return null
        lastAt = nowMs
        lastLine = line
        if (automatic) deliveredRounds += round
        return line
    }
}
