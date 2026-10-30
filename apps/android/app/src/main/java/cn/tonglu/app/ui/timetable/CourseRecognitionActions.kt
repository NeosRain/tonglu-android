package cn.tonglu.app.ui.timetable

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.english.EnglishAiStore
import cn.tonglu.app.data.timetable.CourseRecognitionDraft
import cn.tonglu.app.data.timetable.readCoursePhoto
import cn.tonglu.app.data.timetable.recognizeCourseDraft
import cn.tonglu.app.ui.AppColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TextField as MiuixTextField

/** Voice is handled by the device's speech service; photos are OCR'd locally. Nothing is saved here. */
@Composable
fun CourseRecognitionActions(onDraft: (CourseRecognitionDraft) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("可说出或粘贴一门课，也可选课表截图。识别后核对表单再保存。") }
    var busy by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    DisposableEffect(recognizer) { onDispose { recognizer?.destroy() } }

    fun process(raw: String) {
        val input = raw.trim().take(3000)
        source = input
        if (input.isBlank()) {
            notice = "没有识别到文字，请重试或手动输入。"
            return
        }
        scope.launch {
            busy = true
            try {
                val draft = recognizeCourseDraft(input, EnglishAiStore(context).load())
                onDraft(draft)
                notice = draft.notice.ifBlank { "已提取 ${draft.fieldCount} 项，请逐项核对后保存。" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = "识别未完成，请重试或手动填写。"
            } finally {
                busy = false
            }
        }
    }

    fun startVoice() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            notice = "手机没有可用的语音识别服务。可粘贴课程信息或识别课表截图。"
            return
        }
        runCatching {
            recognizer?.destroy()
            val service = SpeechRecognizer.createSpeechRecognizer(context)
            recognizer = service
            service.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { notice = "正在听，请说出课程名、星期、节次、周次和教室。" }
                override fun onBeginningOfSpeech() { notice = "正在识别课程信息…" }
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() { notice = "正在整理识别结果…" }
                override fun onError(error: Int) {
                    listening = false
                    recognizer = null
                    notice = when (error) {
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音服务网络不可用，请检查连接，或用文字、截图加课。"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "语音服务没有录音权限，请在系统设置中允许。"
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有听清课程信息，请重试或直接输入文字。"
                        else -> "语音服务暂时不可用（错误 $error），可用文字或截图加课。"
                    }
                }
                override fun onResults(results: Bundle?) {
                    listening = false
                    recognizer = null
                    process(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
                }
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            listening = true
            service.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        }.onFailure { listening = false; recognizer = null; notice = "无法启动语音服务，可用文字或截图加课。" }
    }
    val voicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else notice = "需要录音权限才能语音识课；也可以输入文字或识别截图。"
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val recognized = withContext(Dispatchers.IO) { readCoursePhoto(context, uri) }
                busy = false
                process(recognized)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = "图片文字识别失败，请换一张清晰截图或手动填写。"
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (listening) { recognizer?.cancel(); recognizer = null; listening = false; notice = "已停止听取。" }
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice()
                    else voicePermission.launch(Manifest.permission.RECORD_AUDIO)
                }, enabled = !busy, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
            ) { Text(if (listening) "停止听取" else "语音说课") }
            Button(
                onClick = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy && !listening, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
            ) { Text("识别截图") }
        }
        MiuixTextField(
            source, { source = it.take(3000) },
            label = "也可粘贴一门课的文字",
            modifier = Modifier.fillMaxWidth(),
        )
        if (source.isNotBlank()) {
            Button(onClick = { process(source) }, enabled = !busy) { Text(if (busy) "识别中…" else "识别文字") }
        }
        Text(notice, color = AppColors.TextSecondary)
        Text("语音使用系统识别服务；照片在本机识字。已配置 AI 时，提取的文字会发往所选服务商，可能产生费用。", color = AppColors.TextSecondary)
    }
}
