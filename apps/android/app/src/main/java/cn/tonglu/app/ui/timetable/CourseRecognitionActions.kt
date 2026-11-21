package cn.tonglu.app.ui.timetable

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cn.tonglu.app.data.english.EnglishAiStore
import cn.tonglu.app.data.timetable.CourseRecognitionDraft
import cn.tonglu.app.data.timetable.recognizeCourseDraft
import cn.tonglu.app.ui.AppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField

private const val VOICE_CALLBACK_TIMEOUT_MS = 8_000L

/** Voice is handled by the device's speech service. Nothing is saved here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CourseRecognitionActions(onDraft: (CourseRecognitionDraft) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var manualEntry by remember { mutableStateOf(false) }
    var voiceState by remember(context) {
        mutableStateOf<CourseVoiceState>(
            if (SpeechRecognizer.isRecognitionAvailable(context)) CourseVoiceState.Idle else CourseVoiceState.Unavailable,
        )
    }
    var voiceSession by remember(context) { mutableStateOf<Any?>(null) }

    fun process(raw: String) {
        val input = raw.trim().take(3000)
        source = input
        busy = true
        scope.launch {
            try {
                val draft = recognizeCourseDraft(input, EnglishAiStore(context).load())
                onDraft(draft)
                notice = draft.notice.ifBlank { "已提取 ${draft.fieldCount} 项，请逐项核对后保存。" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.w("CourseRecognition", "Unable to extract course fields (${failure.javaClass.simpleName})")
                if (voiceState is CourseVoiceState.Complete) {
                    voiceState = CourseVoiceState.Failed("未能从语音文字提取课程信息。")
                } else {
                    notice = "识别未完成，请重试或手动填写。"
                }
            } finally {
                busy = false
            }
        }
    }

    fun updateVoice(event: CourseVoiceEvent) {
        val next = reduceCourseVoice(voiceState, event)
        if (next == voiceState) return
        voiceState = next
        when {
            next == CourseVoiceState.Preparing -> voiceSession = Any()
            !next.isActive -> voiceSession = null
        }
        if (next is CourseVoiceState.Complete) process(next.transcript)
    }

    val session = voiceSession
    if (session != null) {
        DisposableEffect(session) {
            // 清理只持有本次创建的实例，重试时不会销毁新会话。
            var service: SpeechRecognizer? = null
            var lastCallbackAt = SystemClock.elapsedRealtime()
            val watchdog = scope.launch {
                var remaining = VOICE_CALLBACK_TIMEOUT_MS
                while (remaining > 0) {
                    delay(remaining)
                    remaining = VOICE_CALLBACK_TIMEOUT_MS - (SystemClock.elapsedRealtime() - lastCallbackAt)
                }
                if (voiceSession === session) updateVoice(CourseVoiceEvent.CallbackTimeout)
            }

            fun onCallback(event: CourseVoiceEvent? = null) {
                if (voiceSession !== session || watchdog.isCancelled) return
                lastCallbackAt = SystemClock.elapsedRealtime()
                if (event != null) updateVoice(event)
            }

            try {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
                service = recognizer
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = onCallback(CourseVoiceEvent.Ready)
                    override fun onBeginningOfSpeech() = onCallback(CourseVoiceEvent.Ready)
                    override fun onRmsChanged(rmsdB: Float) = onCallback()
                    override fun onBufferReceived(buffer: ByteArray?) = onCallback()
                    override fun onEndOfSpeech() = onCallback(CourseVoiceEvent.SpeechEnded)
                    override fun onError(error: Int) = onCallback(CourseVoiceEvent.Error(error))
                    override fun onResults(results: Bundle?) {
                        val transcript = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        onCallback(CourseVoiceEvent.Result(transcript))
                    }
                    override fun onPartialResults(partialResults: Bundle?) = onCallback()
                    override fun onEvent(eventType: Int, params: Bundle?) = onCallback()
                })
                recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                })
            } catch (failure: RuntimeException) {
                Log.w("CourseRecognition", "Unable to start speech recognition", failure)
                onCallback(CourseVoiceEvent.StartupFailed)
            }

            onDispose {
                watchdog.cancel()
                service?.destroy()
            }
        }
    }

    fun checkVoiceService(): Boolean {
        if (SpeechRecognizer.isRecognitionAvailable(context)) return true
        voiceState = CourseVoiceState.Unavailable
        return false
    }

    fun startVoice() {
        if (!busy) updateVoice(CourseVoiceEvent.Start)
    }

    val voicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            voiceState = CourseVoiceState.Failed("需要录音权限才能语音识课。")
        } else if (checkVoiceService()) {
            startVoice()
        }
    }

    fun requestVoice() {
        if (!checkVoiceService()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoice()
        } else {
            voicePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    if (manualEntry) {
        TextButton(onClick = { manualEntry = false }) { Text("语音或文字识别") }
        return
    }

    val state = voiceState
    val draftFeedback = if (busy) "正在提取课程信息…" else notice
    val feedback = when (state) {
        CourseVoiceState.Idle, is CourseVoiceState.Complete ->
            draftFeedback ?: "可说出或粘贴一门课的信息，识别后核对表单再保存。"
        CourseVoiceState.Preparing -> "正在准备语音识别…"
        CourseVoiceState.Listening -> "正在听，请说出课程名、星期、节次、周次和教室。"
        CourseVoiceState.Recognizing -> "正在识别课程信息…"
        CourseVoiceState.Unavailable ->
            listOfNotNull("此设备没有可用的语音识别服务。", draftFeedback).joinToString("\n")
        is CourseVoiceState.Failed -> state.reason
    }
    val voiceAction = when (state) {
        CourseVoiceState.Preparing, CourseVoiceState.Recognizing -> "取消识别"
        CourseVoiceState.Listening -> "停止听取"
        else -> "语音说课"
    }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Button(
            onClick = {
                if (voiceState.isActive) updateVoice(CourseVoiceEvent.Cancel) else requestVoice()
            },
            enabled = !busy && state != CourseVoiceState.Unavailable,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent, contentColor = AppColors.OnAccent),
        ) { Text(voiceAction) }
        Text(
            feedback,
            color = if (state is CourseVoiceState.Failed) AppColors.Danger else AppColors.TextSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (state is CourseVoiceState.Failed || state == CourseVoiceState.Unavailable) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state is CourseVoiceState.Failed) {
                    TextButton(onClick = { requestVoice() }) { Text("重试") }
                }
                TextButton(onClick = { manualEntry = true }, enabled = !busy) { Text("改为手动填写") }
            }
        }
        MiuixTextField(
            source, { source = it.take(3000) },
            label = "也可粘贴一门课的文字",
            modifier = Modifier.fillMaxWidth(),
        )
        if (source.isNotBlank()) {
            Button(
                onClick = {
                    if (!voiceState.isActive && !busy) {
                        if (voiceState != CourseVoiceState.Unavailable) voiceState = CourseVoiceState.Idle
                        process(source)
                    }
                },
                enabled = !busy && !state.isActive,
            ) { Text(if (busy) "识别中…" else "识别文字") }
        }
        Text("语音使用系统识别服务；已配置 AI 时，提取的文字会发往所选服务商，可能产生费用。", color = AppColors.TextSecondary)
    }
}
