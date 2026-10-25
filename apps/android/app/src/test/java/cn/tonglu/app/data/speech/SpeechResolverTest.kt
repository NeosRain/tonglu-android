package cn.tonglu.app.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这次朗读该用哪条声音」的判据表（2026-10-15 英式 bug 的回归防线）。
 *
 * 用户手机上的真实现象：英式离线声线**已经下载好**，但系统朗读没有 en-GB 语音，
 * 于是整条朗读退回系统 → 弹「发不出声音：系统没有可用的英式声音」。
 * 把 [resolveSpokenVoice] 里「系统发不出这个口音就用已下载的离线声线」那一段删掉，
 * [英式请求系统发不出时必须用已下载的英式离线声线] 与
 * [系统发不出美式时才轮到离线声线] 必须变红。
 */
class SpeechResolverTest {

    private fun builtin(accent: Accent, id: String, available: Boolean = true) = SpeechVoice(
        id = id, name = "离线声线 $id", source = SoundSource.BuiltinVoice,
        accent = accent, gender = VoiceGender.Unspecified, available = available,
        modelId = id.substringAfter("builtin-").substringBefore("#"),
    )

    private fun system(accent: Accent, canSpeak: Boolean) = SpeechCatalog.systemVoice(canSpeak, accent)

    @Test
    fun `英式请求系统发不出时必须用已下载的英式离线声线`() {
        val british = builtin(Accent.British, "builtin-en-vctk-british#101")
        val decision = resolveSpokenVoice(
            accent = Accent.British,
            requested = null,
            system = system(Accent.British, canSpeak = false),
            builtin = listOf(builtin(Accent.American, "builtin-en-ljs"), british),
            systemCanSpeakAccent = false,
        )
        assertEquals("英式只能由英式那条声线来读", british, decision.voice)
        assertNotNull("换了声音就必须说明原因（界面行内显示）", decision.note)
        assertTrue("原因要写清是系统没有英式语音", decision.note!!.contains("英式"))
    }

    @Test
    fun `系统发得出这个口音时不偷偷换成离线声线`() {
        val decision = resolveSpokenVoice(
            accent = Accent.American,
            requested = null,
            system = system(Accent.American, canSpeak = true),
            builtin = listOf(builtin(Accent.American, "builtin-en-ljs")),
            systemCanSpeakAccent = true,
        )
        assertEquals(SoundSource.SystemTts, decision.voice.source)
        assertNull("默认系统朗读时说「改用了别的声线」就是假话", decision.note)
    }

    @Test
    fun `系统发不出美式时才轮到离线声线`() {
        val american = builtin(Accent.American, "builtin-en-ljs")
        val decision = resolveSpokenVoice(
            accent = Accent.American,
            requested = null,
            system = system(Accent.American, canSpeak = false),
            builtin = listOf(american),
            systemCanSpeakAccent = false,
        )
        assertEquals(american, decision.voice)
        assertNotNull(decision.note)
    }

    @Test
    fun `点名的那条声线优先`() {
        val british = builtin(Accent.British, "builtin-en-vctk-british#43")
        val decision = resolveSpokenVoice(
            accent = Accent.British,
            requested = british,
            system = system(Accent.British, canSpeak = false),
            builtin = listOf(british),
            systemCanSpeakAccent = false,
        )
        assertEquals("试听/偏好点名的那条必须真的被用上", british, decision.voice)
        assertNull(decision.note)
    }

    @Test
    fun `点名那条不可用时退回同口音已下载的声线`() {
        val stale = builtin(Accent.British, "builtin-en-vctk-british#101", available = false)
        val other = builtin(Accent.British, "builtin-en-vctk-british#43")
        val decision = resolveSpokenVoice(
            accent = Accent.British,
            requested = stale,
            system = system(Accent.British, canSpeak = false),
            builtin = listOf(stale, other),
            systemCanSpeakAccent = false,
        )
        assertEquals("模型被删了也要在同口音里找一条能用的", other, decision.voice)
    }

    @Test
    fun `口音不符的声线不许顶替`() {
        // 点「英音」却拿美式离线声线来读 = 静默顶替（47 §3 明令禁止）。
        val decision = resolveSpokenVoice(
            accent = Accent.British,
            requested = builtin(Accent.American, "builtin-en-ljs"),
            system = system(Accent.British, canSpeak = false),
            builtin = listOf(builtin(Accent.American, "builtin-en-ljs")),
            systemCanSpeakAccent = false,
        )
        assertEquals(SoundSource.SystemTts, decision.voice.source)
        assertFalse("这条系统朗读本身是不可用的 —— 调用方据此如实失败", decision.voice.available)
    }

    @Test
    fun `一个都发不出来时返回系统那条且保持不可用`() {
        val decision = resolveSpokenVoice(
            accent = Accent.British,
            requested = null,
            system = system(Accent.British, canSpeak = false),
            builtin = emptyList(),
            systemCanSpeakAccent = false,
        )
        assertEquals(SoundSource.SystemTts, decision.voice.source)
        assertFalse(decision.voice.available)
        assertTrue("不可用必须写明原因", decision.voice.unavailableReason.isNotBlank())
    }

    @Test
    fun `换口音时只有口音真的变了才清掉已存声线`() {
        assertEquals(
            "同一个口音再点一次只是重申口音，不该抹掉刚选好的声线",
            "builtin-en-vctk-british#101",
            keepVoiceOnAccentSave("builtin-en-vctk-british#101", Accent.British, Accent.British),
        )
        assertNull(
            "换了口音就该清掉旧口音的声线（留着会串音）",
            keepVoiceOnAccentSave("builtin-en-vctk-british#101", Accent.British, Accent.American),
        )
        assertNull(keepVoiceOnAccentSave(null, Accent.American, Accent.British))
    }
}
