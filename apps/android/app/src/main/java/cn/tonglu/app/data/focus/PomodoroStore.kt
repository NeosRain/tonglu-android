package cn.tonglu.app.data.focus

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray

/** Private, owner-scoped settings and one current session per owner. No cloud or account secrets. */
class PomodoroStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("pomodoro-v1", Context.MODE_PRIVATE)

    data class Options(val preset: PomodoroPreset = PomodoroPreset(), val companion: Boolean = false,
                       val reminders: Boolean = true, val sound: Boolean = true, val vibrate: Boolean = true,
                       val expandRest: Boolean = true, val edgeRight: Boolean = true, val edgeY: Float = 0.36f,
                       val reduceMotion: Boolean = false, val alwaysExpanded: Boolean = false,
                       val quickPresets: List<PomodoroPreset> = defaultQuickPresets())

    companion object {
        fun defaultQuickPresets() = listOf(PomodoroPreset(),PomodoroPreset(50,10,2),PomodoroPreset(15,3,4))
        internal fun decodeQuickPresets(array: JSONArray?): List<PomodoroPreset> {
            if(array==null) return defaultQuickPresets()
            require(array.length()==3) { "快捷预设记录损坏，请重试" }
            return (0..2).map { i ->
                val item=array.getJSONObject(i)
                PomodoroPreset(item.getInt("focus"),item.getInt("rest"),item.getInt("rounds")).validated()
            }
        }
        internal fun encodeQuickPresets(presets: List<PomodoroPreset>): JSONArray {
            require(presets.size==3) { "请保留三个快捷预设" }
            return JSONArray().apply { presets.forEach { p ->
                p.validated()
                put(JSONObject().put("focus",p.focusMinutes).put("rest",p.restMinutes).put("rounds",p.rounds))
            } }
        }
    }

    fun options(owner: String): Options {
        val raw = prefs.getString("options:$owner", null) ?: return Options()
        val o = JSONObject(raw)
        return Options(PomodoroPreset(o.optInt("focus",25),o.optInt("rest",5),o.optInt("rounds",4)).validated(),
            o.optBoolean("companion",false),o.optBoolean("reminders",true),o.optBoolean("sound",true),
            o.optBoolean("vibrate",true),o.optBoolean("expandRest",true),o.optBoolean("edgeRight",true),
            o.optDouble("edgeY",0.36).toFloat().takeIf { it.isFinite() && it in 0f..1f } ?: 0.36f,
            o.optBoolean("reduceMotion",false),o.optBoolean("alwaysExpanded",false),decodeQuickPresets(o.optJSONArray("quickPresets")))
    }

    fun saveOptions(owner: String, value: Options) {
        value.preset.validated()
        require(value.edgeY.isFinite() && value.edgeY in 0f..1f)
        val json=JSONObject().put("focus",value.preset.focusMinutes).put("rest",value.preset.restMinutes)
            .put("rounds",value.preset.rounds).put("companion",value.companion).put("reminders",value.reminders)
            .put("sound",value.sound).put("vibrate",value.vibrate).put("expandRest",value.expandRest)
            .put("edgeRight",value.edgeRight).put("edgeY",value.edgeY.toDouble())
            .put("reduceMotion",value.reduceMotion).put("alwaysExpanded",value.alwaysExpanded)
            .put("quickPresets",encodeQuickPresets(value.quickPresets))
        check(prefs.edit().putString("options:$owner",json.toString()).commit()) { "番茄钟设置保存失败" }
    }

    /** Persisted gate: changing windows or restarting the process must not replay this round's line. */
    fun companion(state: PomodoroState, clock: PomodoroClock, automatic: Boolean): String? {
        val key="companion:${state.owner}"
        val old=prefs.getString(key,null)?.let(::JSONObject)
        if(automatic && old?.optString("round")=="${state.id}:${state.round}") return null
        val last=old?.optLong("at",-1) ?: -1
        val cooldown=if(automatic) 15*60_000L else 10_000L
        if(old?.optInt("boot",-1)==clock.boot && last>=0 && clock.elapsed-last in 0 until cooldown) return null
        val mood=if(automatic) cn.tonglu.app.data.ai.AssistantPersona.Mood.Focus else cn.tonglu.app.data.ai.AssistantPersona.Mood.Encourage
        val pool=cn.tonglu.app.data.ai.AssistantPersona.lines(mood).filter { it!=old?.optString("line") }
        val line=pool.randomOrNull() ?: return null
        val next=JSONObject().put("round",if(automatic) "${state.id}:${state.round}" else old?.optString("round").orEmpty())
            .put("at",clock.elapsed).put("boot",clock.boot).put("line",line)
        check(prefs.edit().putString(key,next.toString()).commit()) { "学习提醒记录保存失败" }
        return line
    }

    fun load(owner: String): PomodoroState? {
        val raw=prefs.getString("session:$owner",null) ?: return null
        val o=JSONObject(raw)
        check(o.getInt("schema")==1 && o.getString("owner")==owner) { "番茄钟记录不匹配" }
        val p=PomodoroPreset(o.getInt("focus"),o.getInt("rest"),o.getInt("rounds")).validated()
        val phase=PomodoroPhase.valueOf(o.getString("phase"))
        val round=o.getInt("round"); val completed=o.getInt("completed")
        check(round in 1..p.rounds && completed in 0..p.rounds) { "番茄钟轮次记录损坏" }
        val resume=o.optString("resume").takeIf { it.isNotBlank() }?.let(PomodoroPhase::valueOf)
        val result=PomodoroState(o.getString("id"),owner,p,o.optString("task").takeIf { it.isNotBlank() },
            o.getString("label"),phase,round,completed,o.getLong("sequence"),o.getLong("created"),o.getLong("updated"),
            o.getInt("boot"),o.getLong("startedElapsed"),o.getLong("deadlineElapsed"),o.getLong("remaining"),
            o.getLong("focusMillis"),resume,o.optString("interruption").takeIf { it.isNotBlank() })
        check(result.sequence>=0 && result.focusMillis>=0 && result.remainingAtStart in 0..7_200_000L) { "番茄钟时长记录损坏" }
        check(!result.running || result.deadlineElapsed>=result.phaseStartedElapsed) { "番茄钟截止记录损坏" }
        check(phase !in listOf(PomodoroPhase.Paused,PomodoroPhase.Review) || resume in listOf(
            PomodoroPhase.Preparing,PomodoroPhase.Focus,PomodoroPhase.Rest)) { "番茄钟恢复记录损坏" }
        return result
    }

    fun save(state: PomodoroState) {
        val o=JSONObject().put("schema",1).put("id",state.id).put("owner",state.owner)
            .put("focus",state.preset.focusMinutes).put("rest",state.preset.restMinutes).put("rounds",state.preset.rounds)
            .put("task",state.taskId.orEmpty()).put("label",state.label).put("phase",state.phase.name)
            .put("round",state.round).put("completed",state.completedFocus).put("sequence",state.sequence)
            .put("created",state.createdWall).put("updated",state.updatedWall).put("boot",state.boot)
            .put("startedElapsed",state.phaseStartedElapsed).put("deadlineElapsed",state.deadlineElapsed)
            .put("remaining",state.remainingAtStart).put("focusMillis",state.focusMillis)
            .put("resume",state.resumePhase?.name.orEmpty()).put("interruption",state.interruption.orEmpty())
        check(prefs.edit().putString("session:${state.owner}",o.toString()).commit()) { "番茄钟记录保存失败" }
    }
}
