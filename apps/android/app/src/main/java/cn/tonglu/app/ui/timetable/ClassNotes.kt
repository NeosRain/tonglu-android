package cn.tonglu.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import cn.tonglu.app.data.CourseDto
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private data class ClassNote(val id: String, val time: Long, val text: String, val image: String?, val audio: String?)

private fun scaledPhoto(file: File, maxSide: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private class ClassNoteStore(context: Context, course: CourseDto) {
    private val root = File(context.filesDir, "class-notes").apply { mkdirs() }
    private val prefix = java.security.MessageDigest.getInstance("SHA-256")
        .digest(course.id.toString().toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    private val key = "$prefix-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}"
    private val file = File(root, "$key.json")
    private val draftFile = File(root, "$key.draft")
    fun media(ext: String) = File(root, "$key-${UUID.randomUUID()}.$ext")
    fun mediaFile(name: String): File = File(root, name)
    fun readDraft(): String = if (draftFile.exists()) draftFile.readText() else ""
    fun saveDraft(value: String) { if (value.isBlank()) draftFile.delete() else draftFile.writeText(value) }
    private fun read(file: File): List<ClassNote> {
        if (!file.exists()) return emptyList()
        val rows = JSONArray(file.readText())
        return (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            ClassNote(it.getString("id"), it.getLong("time"), it.optString("text"),
                it.optString("image").takeIf(String::isNotBlank), it.optString("audio").takeIf(String::isNotBlank))
        } }
    }
    fun readAll(): List<ClassNote> = root.listFiles().orEmpty()
        .filter { it.name.startsWith("$prefix-") && it.extension == "json" }
        .flatMap(::read).sortedByDescending { it.time }
    fun append(item: ClassNote) {
        val array = JSONArray()
        (listOf(item) + read(file)).forEach { row -> array.put(JSONObject().put("id", row.id).put("time", row.time)
            .put("text", row.text).put("image", row.image ?: "").put("audio", row.audio ?: "")) }
        val pending = File(root, "$key.pending")
        pending.writeText(array.toString())
        if (!pending.renameTo(file)) {
            pending.copyTo(file, overwrite = true)
            check(pending.delete())
        }
    }
}

@Composable
internal fun ClassNotesDialog(course: CourseDto, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(course.id) { ClassNoteStore(context, course) }
    var error by remember { mutableStateOf<String?>(null) }
    var notes by remember(store) { mutableStateOf(runCatching { store.readAll() }.getOrElse { error = "读取笔记失败，请勿继续写入：${it.localizedMessage}"; emptyList() }) }
    var draft by remember(store) { mutableStateOf(runCatching { store.readDraft() }.getOrElse { error = "读取草稿失败：${it.localizedMessage}"; "" }) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var enlargedPhoto by remember { mutableStateOf<String?>(null) }
    fun append(note: ClassNote): Boolean {
        if (error?.startsWith("读取笔记失败") == true) return false
        return runCatching { store.append(note); notes = store.readAll(); error = null }
            .onFailure { error = "保存失败：${it.localizedMessage}" }.isSuccess
    }
    fun saveDraft() {
        val content = draft.trim()
        if (content.isNotEmpty() && error?.startsWith("读取笔记失败") != true) {
            if (append(ClassNote(UUID.randomUUID().toString(), System.currentTimeMillis(), content, null, null))) {
                draft = ""
                runCatching { store.saveDraft("") }.onFailure { error = "清理草稿失败：${it.localizedMessage}" }
            }
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val file = pendingPhoto
        pendingPhoto = null
        if (captured && file != null && file.length() > 0) {
            if (!append(ClassNote(UUID.randomUUID().toString(), System.currentTimeMillis(), "", file.name, null))) file.delete()
        } else file?.delete()
    }
    fun openCamera() {
        runCatching {
            val file = store.media("jpg")
            pendingPhoto = file
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.updates", file))
        }.onFailure { pendingPhoto?.delete(); pendingPhoto = null; error = "无法打开相机：${it.localizedMessage}" }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openCamera()
        else error = "需要相机权限才能拍照"
    }
    fun startRecording() {
        runCatching {
            val file = store.media("m4a")
            val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setOutputFile(file.absolutePath)
            mr.prepare(); mr.start(); audioFile = file; recorder = mr
        }.onFailure { error = "录音失败：${it.localizedMessage}" }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else error = "需要麦克风权限才能录音"
    }
    fun stopRecording() {
        val mr = recorder ?: return
        recorder = null
        runCatching {
            mr.stop(); mr.release()
            audioFile?.let { append(ClassNote(UUID.randomUUID().toString(), System.currentTimeMillis(), "", null, it.name)) }
        }.onFailure { mr.release(); audioFile?.delete(); error = "录音太短或保存失败" }
        audioFile = null
    }
    DisposableEffect(Unit) { onDispose { recorder?.let { runCatching { it.stop(); it.release() } }; player?.release() } }
    LaunchedEffect(draft) { delay(700); runCatching { store.saveDraft(draft) }.onFailure { error = "草稿保存失败：${it.localizedMessage}" } }
    Dialog(onDismissRequest = { if (recorder != null) stopRecording(); saveDraft(); onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = AppColors.CanvasOpaque, contentColor = AppColors.TextPrimary) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick = { if (recorder != null) stopRecording(); saveDraft(); onDismiss() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                    Text(course.name, color = AppColors.TextSecondary, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                Text("课堂笔记", color = AppColors.TextPrimary, fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp))
                Text("按时间记录 · 退出自动保存", color = AppColors.TextSecondary,
                    fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 18.dp))
                error?.let { Text(it, color = AppColors.Danger, fontSize = 13.sp) }
                LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(2),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalItemSpacing = 12.dp) {
                    items(notes, key = { it.id }) { note ->
                        Surface(shape = RoundedCornerShape(20.dp), color = AppColors.SurfaceOpaque,
                            contentColor = AppColors.TextPrimary) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()).format(Date(note.time)),
                                fontSize = 11.sp, color = AppColors.TextSecondary)
                            if (note.text.isNotEmpty()) Text(note.text, fontSize = 16.sp, lineHeight = 24.sp)
                            note.image?.let { name ->
                                val bitmap = remember(name) { scaledPhoto(store.mediaFile(name), 700) }
                                if (bitmap != null) Image(bitmap.asImageBitmap(), "课堂照片",
                                    Modifier.fillMaxWidth().height(140.dp).clickable { enlargedPhoto = name },
                                    contentScale = ContentScale.Crop)
                            }
                            note.audio?.let { name -> TextButton(onClick = {
                                runCatching {
                                    player?.release()
                                    player = MediaPlayer().apply { setDataSource(store.mediaFile(name).absolutePath); prepare(); start() }
                                }.onFailure { error = "播放失败：${it.localizedMessage}" }
                            }) { Text("▶ 播放录音", color = AppColors.Accent) } }
                        } }
                    }
                }
                OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    placeholder = { Text("记下课堂重点…") }, maxLines = 4,
                    shape = RoundedCornerShape(18.dp))
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick = {
                        saveDraft()
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
                            openCamera()
                        else cameraPermission.launch(Manifest.permission.CAMERA)
                    }) { Icon(Icons.Outlined.CameraAlt, "拍照", tint = AppColors.Accent) }
                    IconButton(onClick = {
                        if (recorder != null) stopRecording()
                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Icon(if (recorder == null) Icons.Outlined.Mic else Icons.Outlined.Stop,
                        if (recorder == null) "开始录音" else "结束录音",
                        tint = if (recorder == null) AppColors.Accent else AppColors.Danger) }
                    Button(onClick = { saveDraft() }, colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent,
                        contentColor = AppColors.OnAccent)) { Text("保存") }
                }
            }
        }
    }
    enlargedPhoto?.let { name ->
        Dialog(onDismissRequest = { enlargedPhoto = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val bitmap = remember(name) { scaledPhoto(store.mediaFile(name), 2200) }
            Surface(Modifier.fillMaxSize().clickable { enlargedPhoto = null }, color = AppColors.CanvasOpaque) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), "查看课堂照片", Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit)
            }
        }
    }
}
