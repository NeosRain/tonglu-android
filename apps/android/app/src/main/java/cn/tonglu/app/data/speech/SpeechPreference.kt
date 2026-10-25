package cn.tonglu.app.data.speech

import android.content.Context

/**
 * 声音偏好：用户选的朗读声音、口音，**要活过重启**。
 *
 * 用最轻的 `SharedPreferences`（不是加密存储 —— 这里只是「选了哪条声音」，
 * 不含任何密钥），与项目里读进度那类偏好一致。owner 分桶：换账号不串设置。
 *
 * 为什么单开一个文件而不是塞进 EnglishAiStore：那个是**加密**的 AI 凭据存储，
 * 声音偏好跟密钥无关，混在一起会让「AI 没配」和「声音没选」的排查互相干扰。
 */
class SpeechPreference(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("tonglu_speech", Context.MODE_PRIVATE)

    /** 当前选中的 voiceId。空 = 用默认（系统朗读）。 */
    fun voiceId(owner: String, requestedAccent: Accent = accent(owner)): String? {
        val perAccent = key(owner, "voiceId.${requestedAccent.name}")
        return if (prefs.contains(perAccent)) prefs.getString(perAccent, null)
        else if (requestedAccent == accent(owner)) prefs.getString(key(owner, "voiceId"), null)
        else null
    }

    /** 上次选的口音。默认美式（与旧行为一致）。 */
    fun accent(owner: String): Accent {
        val raw = prefs.getString(key(owner, "accent"), null) ?: return Accent.American
        return Accent.entries.firstOrNull { it.name == raw } ?: Accent.American
    }

    /**
     * 保存口音与声线。
     *
     * [voiceId] 传 null 表示「这条口音不点名具体声线」。**但同一个口音下不该把已选声线抹掉**：
     * 单词页的「英音/美音」两个 chip 就是 `save(owner, null, accent)`，
     * 用户点了「英音」只是想换口音，结果把刚在声音设置里选好的英式离线声线清空了 ——
     * 再进声音设置就显示「系统朗读（使用中）」，点发音直接弹「发不出声音」。
     *
     * 规则：**只在口音真的变了**的时候清掉旧声线（旧声线本来就属于别的口音，留着只会串音）；
     * 口音没变就保留。清不掉的极端情况（旧版本留下的、没有声线记录的偏好）由
     * [SpeechService.speak] 的解析兜底 —— 它认得出「该口音已下载的离线声线」。
     */
    fun save(owner: String, voiceId: String?, accent: Accent) {
        val previousAccent = accent(owner)
        val previousVoice = voiceId(owner)
        val next = voiceId ?: if (previousAccent == accent) previousVoice
            else prefs.getString(key(owner, "voiceId.${accent.name}"), null)
        prefs.edit()
            .putString(key(owner, "voiceId.${previousAccent.name}"), previousVoice)
            .putString(key(owner, "voiceId.${accent.name}"), next)
            .putString(key(owner, "voiceId"), next)
            .putString(key(owner, "accent"), accent.name)
            .apply()
    }

    private fun key(owner: String, field: String) = "${owner.take(64)}|$field"
}
