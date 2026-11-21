package cn.tonglu.app.ui.timetable

import android.speech.SpeechRecognizer

internal sealed interface CourseVoiceState {
    data object Idle : CourseVoiceState
    data object Preparing : CourseVoiceState
    data object Listening : CourseVoiceState
    data object Recognizing : CourseVoiceState
    data object Unavailable : CourseVoiceState
    data class Complete(val transcript: String) : CourseVoiceState
    data class Failed(val reason: String) : CourseVoiceState

    val isActive: Boolean get() = this == Preparing || this == Listening || this == Recognizing
}

internal sealed interface CourseVoiceEvent {
    data object Start : CourseVoiceEvent
    data object Ready : CourseVoiceEvent
    data object SpeechEnded : CourseVoiceEvent
    data object Cancel : CourseVoiceEvent
    data object StartupFailed : CourseVoiceEvent
    data object CallbackTimeout : CourseVoiceEvent
    data class Error(val code: Int) : CourseVoiceEvent
    data class Result(val transcript: String) : CourseVoiceEvent
}

internal fun reduceCourseVoice(state: CourseVoiceState, event: CourseVoiceEvent): CourseVoiceState {
    if (!state.isActive && event != CourseVoiceEvent.Start) return state
    return when (event) {
        CourseVoiceEvent.Start ->
            if (state.isActive || state == CourseVoiceState.Unavailable) state else CourseVoiceState.Preparing
        CourseVoiceEvent.Ready -> if (state == CourseVoiceState.Preparing) CourseVoiceState.Listening else state
        CourseVoiceEvent.SpeechEnded -> CourseVoiceState.Recognizing
        CourseVoiceEvent.Cancel -> CourseVoiceState.Failed("已停止听取。")
        CourseVoiceEvent.StartupFailed -> CourseVoiceState.Failed("无法启动语音识别服务。")
        CourseVoiceEvent.CallbackTimeout -> CourseVoiceState.Failed("语音识别服务超过 8 秒没有响应。")
        is CourseVoiceEvent.Error -> CourseVoiceState.Failed(courseVoiceErrorMessage(event.code))
        is CourseVoiceEvent.Result -> if (event.transcript.isBlank()) {
            CourseVoiceState.Failed("没有识别到文字。")
        } else {
            CourseVoiceState.Complete(event.transcript)
        }
    }
}

private fun courseVoiceErrorMessage(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_CLIENT -> "语音识别会话意外中断。"
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音服务网络不可用，请检查连接。"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "语音服务没有录音权限，请在系统设置中允许。"
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听清课程信息。"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "当前语音服务无法识别普通话。"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别服务正忙，请稍后重试。"
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "与语音识别服务的连接已断开。"
    else -> "语音服务暂时不可用（错误 $code）。"
}
