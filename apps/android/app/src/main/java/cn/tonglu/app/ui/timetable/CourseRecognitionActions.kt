package cn.tonglu.app.ui.timetable

import android.content.Intent
import android.speech.RecognizerIntent
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

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val heard = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            process(heard)
        } else notice = "语音识别已取消；也可以直接在下面输入课程信息。"
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
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "说出课程名、星期、节次、周次和教室")
                    }
                    try { voice.launch(intent) }
                    catch (_: Exception) { notice = "手机没有可用的语音识别服务，请输入文字或选图。" }
                }, enabled = !busy, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
            ) { Text("语音说课") }
            Button(
                onClick = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !busy, modifier = Modifier.weight(1f),
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
