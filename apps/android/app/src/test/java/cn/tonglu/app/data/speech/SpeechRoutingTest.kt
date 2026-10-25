package cn.tonglu.app.data.speech

import org.junit.Assert.*
import org.junit.Test

class SpeechRoutingTest {
    @Test fun `英式请求遇到美式离线声线必须回系统`() {
        assertTrue(needsBritishSystem(Accent.British, SoundSource.BuiltinVoice, Accent.American))
        assertTrue(needsBritishSystem(Accent.British, SoundSource.BuiltinVoice, Accent.Chinese))
        assertFalse(needsBritishSystem(Accent.British, SoundSource.SystemTts, Accent.American))
        assertFalse(needsBritishSystem(Accent.American, SoundSource.BuiltinVoice, Accent.American))
    }
    @Test fun `英美使用明确地区语言标签`() {
        assertEquals("en-GB", speechLocale(Accent.British))
        assertEquals("en-US", speechLocale(Accent.American))
    }
}
