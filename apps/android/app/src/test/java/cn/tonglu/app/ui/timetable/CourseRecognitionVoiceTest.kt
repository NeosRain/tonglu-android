package cn.tonglu.app.ui.timetable

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CourseRecognitionVoiceTest {
    @Test fun `准备好后开始听取且说完后进入识别`() {
        val preparing = reduceCourseVoice(CourseVoiceState.Idle, CourseVoiceEvent.Start)
        assertEquals(CourseVoiceState.Preparing, preparing)
        val listening = reduceCourseVoice(preparing, CourseVoiceEvent.Ready)
        assertEquals(CourseVoiceState.Listening, listening)
        assertEquals(CourseVoiceState.Recognizing, reduceCourseVoice(listening, CourseVoiceEvent.SpeechEnded))
    }

    @Test fun `手动停止听取后提示原因并可重试`() {
        assertFailure(CourseVoiceState.Listening, CourseVoiceEvent.Cancel, "已停止听取。")
    }

    @Test fun `服务报告客户端错误时提示会话中断并可重试`() {
        assertFailure(
            CourseVoiceState.Listening,
            CourseVoiceEvent.Error(SpeechRecognizer.ERROR_CLIENT),
            "语音识别会话意外中断。",
        )
    }

    @Test fun `服务网络错误时提示检查连接并可重试`() {
        assertFailure(
            CourseVoiceState.Recognizing,
            CourseVoiceEvent.Error(SpeechRecognizer.ERROR_NETWORK),
            "语音服务网络不可用，请检查连接。",
        )
    }

    @Test fun `启动失败后提示无法启动并可重试`() {
        assertFailure(CourseVoiceState.Preparing, CourseVoiceEvent.StartupFailed, "无法启动语音识别服务。")
    }

    @Test fun `任一会话阶段无回调超时后都可重试`() {
        for (active in listOf(CourseVoiceState.Preparing, CourseVoiceState.Listening, CourseVoiceState.Recognizing)) {
            assertFailure(active, CourseVoiceEvent.CallbackTimeout, "语音识别服务超过 8 秒没有响应。")
        }
    }

    @Test fun `正常结果保留识别文字并允许再开始`() {
        val transcript = "高等数学，星期三，第 1 到 2 节"
        val complete = reduceCourseVoice(CourseVoiceState.Recognizing, CourseVoiceEvent.Result(transcript))
        assertEquals(CourseVoiceState.Complete(transcript), complete)
        assertCanRestart(complete)
    }

    @Test fun `空白结果提示没有文字并可重试`() {
        assertFailure(CourseVoiceState.Recognizing, CourseVoiceEvent.Result("  "), "没有识别到文字。")
    }

    @Test fun `迟到的识别结果不会覆盖已停止的状态`() {
        val failure = reduceCourseVoice(CourseVoiceState.Listening, CourseVoiceEvent.Cancel)
        assertEquals(failure, reduceCourseVoice(failure, CourseVoiceEvent.Result("高等数学")))
    }

    @Test fun `没有语音服务时不能进入准备状态`() {
        val unavailable = reduceCourseVoice(CourseVoiceState.Unavailable, CourseVoiceEvent.Start)
        assertEquals(CourseVoiceState.Unavailable, unavailable)
        assertFalse(unavailable.isActive)
    }

    private fun assertFailure(active: CourseVoiceState, event: CourseVoiceEvent, reason: String) {
        val failure = reduceCourseVoice(active, event)
        assertEquals(CourseVoiceState.Failed(reason), failure)
        assertCanRestart(failure)
    }

    private fun assertCanRestart(state: CourseVoiceState) {
        assertFalse(state.isActive)
        assertEquals(CourseVoiceState.Preparing, reduceCourseVoice(state, CourseVoiceEvent.Start))
    }
}
