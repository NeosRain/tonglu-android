package cn.tonglu.app.data.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineVoiceSupportTest {
    @Test
    fun `arm64 设备可以使用包内的 arm64 离线库`() {
        assertTrue(supportsOfflineVoice(listOf("arm64-v8a"), setOf("arm64-v8a")))
    }

    @Test
    fun `x86_64 设备不能靠次选的转译 ABI 使用 arm64 离线库`() {
        assertFalse(supportsOfflineVoice(listOf("x86_64", "arm64-v8a"), setOf("arm64-v8a")))
    }

    @Test
    fun `设备不支持时已安装的离线声线也不可选`() {
        val model = TtsModelStore.fallback.first { it.id == "en-ljs" }
        val voice = SpeechCatalog.expand(model, installed = true, supported = false).single()

        assertTrue(voice.installed)
        assertFalse(voice.available)
        assertEquals(OFFLINE_VOICE_UNSUPPORTED, voice.unavailableReason)
    }

    @Test
    fun `恢复已选离线声线不调用引擎工厂`() {
        val model = TtsModelStore.fallback.first { it.id == "en-ljs" }
        val voice = SpeechCatalog.expand(model, installed = true).single()
        var engineCreations = 0
        val service = SpeechService(
            contextProvider = { error("恢复选择不需要 Android Context") },
            createOfflineEngine = { engineCreations++; error("恢复选择时不应创建引擎") },
        )
        val decision = resolveSpokenVoice(
            accent = voice.accent,
            requested = voice,
            system = SpeechCatalog.systemVoice(),
            builtin = listOf(voice),
            systemCanSpeakAccent = true,
        )

        service.restoreVoice(decision.voice)

        assertEquals(voice, service.current)
        assertEquals(0, engineCreations)
    }
}
