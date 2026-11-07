package cn.tonglu.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.os.Build
import android.net.Uri
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import androidx.compose.material.icons.outlined.AttachFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import cn.tonglu.app.ui.glass.GlassActionPanel
import cn.tonglu.app.ui.glass.GlassPanelStyle
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.layout.onSizeChanged
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

private data class ClassNote(val id: String, val time: Long, val text: String, val image: String?, val audio: String?,
    val extraImages: List<String> = emptyList(), val extraAudio: List<String> = emptyList()) {
    val images get() = listOfNotNull(image) + extraImages
    val recordings get() = listOfNotNull(audio) + extraAudio
}
private fun JSONObject.mediaNames(key: String): List<String> = optJSONArray(key)?.let { array ->
    (0 until array.length()).map { array.getString(it) }
}.orEmpty()

private fun scaledPhoto(file: File, maxSide: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Same subject/teacher can occupy several timetable cells with different imported IDs. */
internal fun classNoteCourseKey(course: CourseDto): String =
    "${course.name.trim().lowercase(Locale.ROOT)}\u0000${course.teacher.orEmpty().trim().lowercase(Locale.ROOT)}"

private fun noteHash(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }

private class ClassNoteStore(context: Context, course: CourseDto, relatedCourses: List<CourseDto>) {
    private val root = File(context.filesDir, "class-notes").apply { mkdirs() }
    private val prefix = noteHash("course:${classNoteCourseKey(course)}")
    // Read old per-cell files in place. Keeping originals avoids losing media or partial migration data.
    private val legacyPrefixes = (relatedCourses.filter { classNoteCourseKey(it) == classNoteCourseKey(course) } + course)
        .map { noteHash(it.id) }.toSet()
    private val readablePrefixes = legacyPrefixes + prefix
    private val key = "$prefix-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}"
    private val file = File(root, "$key.json")
    private val draftFile = File(root, "$key.draft")
    private val resumeFile = File(root, "$prefix.resume")
    fun takeResumeId(): String? = if (resumeFile.exists()) resumeFile.readText().also { check(resumeFile.delete()) } else null
    fun checkpoint(id: String?) {
        if (id == null) { check(!resumeFile.exists() || resumeFile.delete()); return }
        val atomic = android.util.AtomicFile(resumeFile)
        val output = atomic.startWrite()
        try { output.write(id.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }
    private val legacyDraftFiles = legacyPrefixes.map { File(root, "$it-${SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())}.draft") }
    fun media(ext: String) = File(root, "$key-${UUID.randomUUID()}.$ext")
    fun mediaFile(name: String): File = File(root, name)
    fun readDraft(): String = if (draftFile.exists()) draftFile.readText() else
        legacyDraftFiles.firstOrNull { it.exists() }?.readText().orEmpty()
    fun saveDraft(value: String) {
        if (value.isBlank()) {
            check(!draftFile.exists() || draftFile.delete())
            legacyDraftFiles.forEach { check(!it.exists() || it.delete()) }
        } else draftFile.writeText(value)
    }
    private fun read(file: File): List<ClassNote> {
        if (!file.exists()) return emptyList()
        val rows = JSONArray(file.readText())
        return (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            ClassNote(it.getString("id"), it.getLong("time"), it.optString("text"),
                it.optString("image").takeIf(String::isNotBlank), it.optString("audio").takeIf(String::isNotBlank), it.mediaNames("extraImages"), it.mediaNames("extraAudio"))
        } }
    }
    fun readAll(): List<ClassNote> = root.listFiles().orEmpty()
        .filter { candidate -> candidate.extension == "json" && readablePrefixes.any { candidate.name.startsWith("$it-") } }
        .flatMap(::read).distinctBy { it.id }.sortedByDescending { it.time }
    fun append(item: ClassNote) {
        val array = JSONArray()
        (listOf(item) + read(file)).forEach { row -> array.put(JSONObject().put("id", row.id).put("time", row.time)
            .put("text", row.text).put("image", row.image ?: "").put("audio", row.audio ?: "").put("extraImages", JSONArray(row.extraImages)).put("extraAudio", JSONArray(row.extraAudio))) }
        val pending = File(root, "$key.pending")
        pending.writeText(array.toString())
        if (!pending.renameTo(file)) {
            pending.copyTo(file, overwrite = true)
            check(pending.delete())
        }
    }
    fun replaceText(id: String, value: String) = update(id) { it.copy(text = value) }
    fun attach(id: String, name: String, image: Boolean) = update(id) {
        if (image) {
            if (it.image == null) it.copy(image = name) else it.copy(extraImages = it.extraImages + name)
        } else {
            if (it.audio == null) it.copy(audio = name) else it.copy(extraAudio = it.extraAudio + name)
        }
    }
    private fun update(id: String, transform: (ClassNote) -> ClassNote) {
        val target = root.listFiles().orEmpty().firstOrNull { candidate ->
            candidate.extension == "json" && readablePrefixes.any { candidate.name.startsWith("$it-") } &&
                read(candidate).any { it.id == id }
        } ?: error("找不到原笔记，请返回后重试")
        val rows = read(target).map { if (it.id == id) transform(it) else it }
        val array = JSONArray()
        rows.forEach { row -> array.put(JSONObject().put("id", row.id).put("time", row.time)
            .put("text", row.text).put("image", row.image ?: "").put("audio", row.audio ?: "").put("extraImages", JSONArray(row.extraImages)).put("extraAudio", JSONArray(row.extraAudio))) }
        val pending = File(root, "${target.name}.pending")
        pending.writeText(array.toString())
        if (!pending.renameTo(target)) {
            pending.copyTo(target, overwrite = true)
            check(pending.delete())
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ClassNotesDialog(course: CourseDto, relatedCourses: List<CourseDto> = listOf(course), onDismiss: () -> Unit) {
    val context = LocalContext.current
    val relatedIds = relatedCourses.map { it.id }.sorted()
    val store = remember(classNoteCourseKey(course), relatedIds) { ClassNoteStore(context, course, relatedCourses) }
    var error by remember { mutableStateOf<String?>(null) }
    var notes by remember(store) { mutableStateOf(runCatching { store.readAll() }.getOrElse { error = "读取笔记失败，请勿继续写入：${it.localizedMessage}"; emptyList() }) }
    val resumedId = remember(store) {
        runCatching { store.takeResumeId() }.onFailure { error = "恢复编辑位置失败：${it.localizedMessage}" }.getOrNull()
            ?.takeIf { id -> notes.any { it.id == id } }
    }
    var draft by remember(store) { mutableStateOf(runCatching { store.readDraft().ifBlank { notes.firstOrNull { it.id == resumedId }?.text.orEmpty() } }.getOrElse { error = "读取草稿失败：${it.localizedMessage}"; "" }) }
    var query by remember { mutableStateOf("") }
    var mediaFilter by remember { mutableIntStateOf(0) }
    var attachmentMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    val visibleNotes = notes.filter { note ->
        (query.isBlank() || note.text.contains(query.trim(), ignoreCase = true)) && when (mediaFilter) {
            1 -> note.images.isNotEmpty()
            2 -> note.recordings.isNotEmpty()
            3 -> note.text.isNotBlank()
            else -> true
        }
    }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var enlargedPhoto by remember { mutableStateOf<String?>(null) }
    var editingNoteId by remember(store) {
        mutableStateOf(resumedId ?: notes.firstOrNull { draft.isNotBlank() && it.text.trim() == draft.trim() }?.id)
    }
    var editorOpen by remember(store) { mutableStateOf(draft.isNotBlank() || resumedId != null) }
    var morphNoteId by remember(store) { mutableStateOf(editingNoteId) }
    val gridState = rememberLazyStaggeredGridState()
    var headerHeight by remember { mutableIntStateOf(0) }
    var listHeaderHeight by remember { mutableIntStateOf(0) }
    var footerHeight by remember { mutableIntStateOf(0) }
    val headerInset = with(LocalDensity.current) { headerHeight.toDp() }
    val listHeaderInset = with(LocalDensity.current) { listHeaderHeight.toDp() }
    val footerInset = with(LocalDensity.current) { footerHeight.toDp() }
    val backdrop = rememberLayerBackdrop()
    fun append(note: ClassNote): Boolean {
        if (error?.startsWith("读取笔记失败") == true) return false
        return runCatching { store.append(note); notes = store.readAll(); error = null }
            .onFailure { error = "保存失败：${it.localizedMessage}" }.isSuccess
    }
    fun saveDraft(): Boolean {
        val content = draft.trim()
        if ((content.isNotEmpty() || editingNoteId != null) && error?.startsWith("读取笔记失败") != true) {
            if (notes.any { it.id == editingNoteId && it.text == content }) {
                return runCatching { store.saveDraft(""); error = null }
                    .onFailure { error = "清理草稿失败：${it.localizedMessage}" }.isSuccess
            }
            val targetId = editingNoteId ?: UUID.randomUUID().toString()
            val saved = editingNoteId?.let { id ->
                runCatching { store.replaceText(id, content); notes = store.readAll(); error = null }
                    .onFailure { error = "更新失败：${it.localizedMessage}" }.isSuccess
            } ?: append(ClassNote(targetId, System.currentTimeMillis(), content, null, null))
            if (saved) {
                editingNoteId = targetId
                runCatching { store.saveDraft("") }.onFailure { error = "清理草稿失败：${it.localizedMessage}" }
            }
            return saved
        }
        return content.isEmpty()
    }
    var recordingTarget by remember { mutableStateOf<String?>(null) }
    var cameraTarget by remember { mutableStateOf<String?>(null) }
    fun mediaTarget(): String? {
        if (!editorOpen) return null
        if (!saveDraft()) throw IllegalStateException(error ?: "请先保存当前笔记")
        if (editingNoteId == null) {
            val id = UUID.randomUUID().toString()
            check(append(ClassNote(id, System.currentTimeMillis(), draft.trim(), null, null))) { error ?: "创建笔记失败" }
            editingNoteId = id
        }
        return editingNoteId
    }
    fun saveMedia(file: File, image: Boolean, target: String?): Boolean {
        if (target == null) return append(ClassNote(UUID.randomUUID().toString(), System.currentTimeMillis(), "",
            if (image) file.name else null, if (image) null else file.name))
        return runCatching { store.attach(target, file.name, image); notes = store.readAll(); error = null }
            .onFailure { error = "附件保存失败：${it.localizedMessage}" }.isSuccess
    }
    fun importAttachment(uri: Uri?, image: Boolean) {
        if (uri == null || importing || !saveDraft()) return
        val target = runCatching { mediaTarget() }.getOrElse { error = "导入失败：${it.localizedMessage}"; return }
        importing = true
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val target = store.media(if (image) "image" else "audio")
                    try {
                        checkNotNull(context.contentResolver.openInputStream(uri)) { "无法读取所选文件" }.use { input ->
                            target.outputStream().use { input.copyTo(it) }
                        }
                        if (image) {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(target.path, bounds)
                            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "所选文件不是可读取的图片" }
                        } else {
                            val metadata = MediaMetadataRetriever()
                            try {
                                metadata.setDataSource(target.path)
                                require(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes") { "所选文件没有音轨" }
                            } finally { metadata.release() }
                        }
                        target
                    } catch (failure: Exception) { target.delete(); throw failure }
                }
                if (!saveMedia(file, image, target)) file.delete()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = "导入失败：${failure.localizedMessage}" }
            finally { importing = false }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importAttachment(it, true) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { importAttachment(it, false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val file = pendingPhoto
        pendingPhoto = null
        if (captured && file != null && file.length() > 0) {
            if (!saveMedia(file, true, cameraTarget)) file.delete()
        } else file?.delete()
    }
    fun openCamera() {
        runCatching {
            cameraTarget = mediaTarget()
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
            recordingTarget = mediaTarget()
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
    fun stopRecording(): Boolean {
        val mr = recorder ?: return true
        recorder = null
        var stopped = false
        val saved = runCatching {
            mr.stop(); stopped = true
            audioFile?.let { check(saveMedia(it, false, recordingTarget)) { error ?: "录音保存失败" } }
        }.onFailure {
            if (!stopped) audioFile?.delete()
            error = "录音太短或保存失败：${it.localizedMessage}"
        }
        runCatching { mr.release() }.onFailure { android.util.Log.w("TaimiNotes", "Recorder release failed", it) }
        audioFile = null
        return saved.isSuccess
    }
    val memoryHandler = rememberUpdatedState<(Boolean) -> Boolean> { kill ->
        if (!kill) true
        else {
            val recordingSaved = stopRecording()
            player?.release(); player = null
            val textSaved = saveDraft()
            val checkpointSaved = runCatching { store.checkpoint(if (editorOpen) editingNoteId else null) }
                .onFailure { error = "保存编辑位置失败：${it.localizedMessage}" }.isSuccess
            recordingSaved && textSaved && checkpointSaved && !importing &&
                error?.startsWith("读取笔记失败") != true
        }
    }
    DisposableEffect(store) {
        val registration = cn.tonglu.app.data.memory.FairMemory.register { memoryHandler.value(it) }
        onDispose { registration.close() }
    }
    DisposableEffect(Unit) { onDispose { recorder?.let { runCatching { it.stop(); it.release() } }; player?.release() } }
    LaunchedEffect(draft) {
        delay(700)
        if (draft.isNotBlank()) runCatching { store.saveDraft(draft); saveDraft() }
            .onFailure { error = "自动保存失败：${it.localizedMessage}" }
    }
    Dialog(onDismissRequest = { if (recorder != null) stopRecording(); if (saveDraft()) { if (editorOpen) { editorOpen = false; editingNoteId = null; draft = "" } else onDismiss() } },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogView = LocalView.current
        val imeShowing = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        DisposableEffect(dialogView, AppTheme.darkState) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            val controller = window?.let { WindowCompat.getInsetsController(it, dialogView) }
            val oldStatus = controller?.isAppearanceLightStatusBars
            val oldNavigation = controller?.isAppearanceLightNavigationBars
            controller?.isAppearanceLightStatusBars = !AppTheme.darkState
            controller?.isAppearanceLightNavigationBars = !AppTheme.darkState
            onDispose {
                if (oldStatus != null) controller.isAppearanceLightStatusBars = oldStatus
                if (oldNavigation != null) controller.isAppearanceLightNavigationBars = oldNavigation
            }
        }
        Surface(Modifier.fillMaxSize(), color = AppColors.CanvasOpaque, contentColor = AppColors.TextPrimary) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                SharedTransitionLayout(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                AnimatedContent(targetState = editorOpen,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
                    modifier = Modifier.fillMaxSize(), label = "class-note-editor") { editing ->
                    if (editing) {
                        Column(Modifier.fillMaxSize().sharedBounds(
                            rememberSharedContentState(key = "note-${morphNoteId ?: "new"}"),
                            animatedVisibilityScope = this@AnimatedContent,
                            boundsTransform = { _, _ -> tween(380) })
                            .background(AppColors.CanvasOpaque)
                            // Reserve the floating controls in the IME scroll viewport so the caret cannot hide behind them.
                            .padding(bottom = if (imeShowing) footerInset + 24.dp else 0.dp)
                            .padding(horizontal = 24.dp).verticalScroll(rememberScrollState())
                            .padding(top = headerInset + 16.dp, bottom = if (imeShowing) 16.dp else footerInset + 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val current = notes.firstOrNull { it.id == (if (editorOpen) editingNoteId else morphNoteId) }
                            val shownDraft = if (editorOpen) draft else current?.text.orEmpty()
                            current?.let { Text(SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()).format(Date(it.time)),
                                color = AppColors.TextSecondary, fontSize = 12.sp) }
                            BasicTextField(value = shownDraft, onValueChange = { draft = it }, readOnly = !editorOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                                textStyle = TextStyle(color = AppColors.TextPrimary, fontSize = 20.sp, lineHeight = 32.sp),
                                cursorBrush = SolidColor(AppColors.Accent),
                                decorationBox = { field -> Box { if (shownDraft.isEmpty()) Text("开始写这篇笔记…", color = AppColors.TextMuted, fontSize = 20.sp); field() } })
                            current?.images?.forEach { name ->
                                val bitmap = remember(name) { scaledPhoto(store.mediaFile(name), 1000) }
                                if (bitmap != null) Image(bitmap.asImageBitmap(), "笔记图片",
                                    Modifier.fillMaxWidth().heightIn(max = 360.dp).aspectRatio(bitmap.width.toFloat() / bitmap.height).clickable { enlargedPhoto = name }, contentScale = ContentScale.Fit)
                            }
                            current?.recordings?.forEachIndexed { index, name ->
                                TextButton(onClick = {
                                    runCatching {
                                        player?.release()
                                        player = MediaPlayer().apply { setDataSource(store.mediaFile(name).absolutePath); prepare(); start() }
                                    }.onFailure { error = "播放失败：${it.localizedMessage}" }
                                }) { Text("▶ 录音 ${index + 1}", color = AppColors.Accent) }
                            }
                        }
                    } else {
                LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(2), state = gridState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    contentPadding = PaddingValues(top = listHeaderInset + 12.dp, bottom = footerInset + 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalItemSpacing = 12.dp) {
                    items(visibleNotes, key = { it.id }) { note ->
                        Surface(shape = RoundedCornerShape(20.dp), color = AppColors.SurfaceOpaque,
                            modifier = Modifier.sharedBounds(
                                rememberSharedContentState(key = "note-${note.id}"),
                                animatedVisibilityScope = this@AnimatedContent,
                                boundsTransform = { _, _ -> tween(380) }).clickable {
                                if (draft.isNotBlank() && editingNoteId != note.id && !saveDraft()) return@clickable
                                morphNoteId = note.id
                                editingNoteId = note.id
                                draft = note.text
                                editorOpen = true
                            },
                            contentColor = AppColors.TextPrimary) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Text(SimpleDateFormat("MM月dd日 HH:mm", Locale.getDefault()).format(Date(note.time)),
                                fontSize = 11.sp, color = AppColors.TextSecondary)
                            if (note.text.isNotEmpty()) Text(note.text, fontSize = 16.sp, lineHeight = 24.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                            note.images.take(1).forEach { name ->
                                val bitmap = remember(name) { scaledPhoto(store.mediaFile(name), 700) }
                                if (bitmap != null) Image(bitmap.asImageBitmap(), "课堂照片",
                                    Modifier.fillMaxWidth().height(140.dp).clickable { enlargedPhoto = name },
                                    contentScale = ContentScale.Crop)
                            }
                            note.recordings.take(1).forEach { name -> TextButton(onClick = {
                                runCatching {
                                    player?.release()
                                    player = MediaPlayer().apply { setDataSource(store.mediaFile(name).absolutePath); prepare(); start() }
                                }.onFailure { error = "播放失败：${it.localizedMessage}" }
                            }) { Text("▶ 播放录音", color = AppColors.Accent) } }
                        } }
                    }
                }
                    }
                }
                }
                GlassActionPanel(backdrop, modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { headerHeight = it.height; if (!editorOpen) listHeaderHeight = it.height }, style = GlassPanelStyle.Solid.copy(surfaceAlpha = 0.72f, blurDp = 24f)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick = { if (recorder != null) stopRecording(); if (saveDraft()) { if (editorOpen) { editorOpen = false; editingNoteId = null; draft = "" } else onDismiss() } }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                    Text(course.name, color = AppColors.TextSecondary, fontSize = 14.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                if (!editorOpen) Text("课堂笔记", color = AppColors.TextPrimary, fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                if (!editorOpen) {
                    TextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("搜索笔记") }, shape = RoundedCornerShape(20.dp),
                        colors = TextFieldDefaults.colors(focusedContainerColor = AppColors.ChipIdle, unfocusedContainerColor = AppColors.ChipIdle,
                            focusedTextColor = AppColors.TextPrimary, unfocusedTextColor = AppColors.TextPrimary,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("全部", "图片", "录音", "文字").forEachIndexed { index, label ->
                            FilterChip(selected = mediaFilter == index, onClick = { mediaFilter = index }, label = { Text(label) },
                                shape = RoundedCornerShape(20.dp), border = null,
                                colors = FilterChipDefaults.filterChipColors(containerColor = AppColors.ChipIdle,
                                    labelColor = AppColors.TextSecondary, selectedContainerColor = AppColors.Accent, selectedLabelColor = AppColors.OnAccent))
                        }
                    }
                    if (visibleNotes.isEmpty()) Text(if (notes.isEmpty()) "还没有笔记，点击下方＋开始记录" else "没有匹配的笔记",
                        color = AppColors.TextSecondary, fontSize = 13.sp)
                }
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = AppColors.Danger, fontSize = 13.sp) }
                }
                }
                GlassActionPanel(backdrop,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 300.dp)
                        .onSizeChanged { footerHeight = it.height },
                    shape = RoundedCornerShape(32.dp), style = GlassPanelStyle.Solid.copy(surfaceAlpha = 0.72f, blurDp = 24f)) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(enabled = !importing, onClick = { attachmentMenu = true }) {
                                Icon(Icons.Outlined.AttachFile, "添加附件")
                            }
                            DropdownMenu(expanded = attachmentMenu, onDismissRequest = { attachmentMenu = false }, containerColor = AppColors.SurfaceOpaque) {
                                DropdownMenuItem(text = { Text("导入图片") }, onClick = { attachmentMenu = false; imagePicker.launch(arrayOf("image/*")) })
                                DropdownMenuItem(text = { Text("导入音频") }, onClick = { attachmentMenu = false; audioPicker.launch(arrayOf("audio/*")) })
                                DropdownMenuItem(text = { Text("拍照") }, onClick = {
                                    attachmentMenu = false
                                    if (saveDraft()) {
                                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
                                        else cameraPermission.launch(Manifest.permission.CAMERA)
                                    }
                                })
                            }
                        }
                        IconButton(onClick = {
                            if (recorder != null) stopRecording()
                            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }) { Icon(if (recorder == null) Icons.Outlined.Mic else Icons.Outlined.Stop,
                            if (recorder == null) "开始录音" else "结束录音", tint = AppColors.Danger) }
                        IconButton(enabled = !importing && recorder == null, onClick = { if (saveDraft()) { morphNoteId = null; editingNoteId = null; draft = ""; editorOpen = true } }) {
                            Icon(Icons.Outlined.Add, "新建笔记")
                        }
                        if (editorOpen) IconButton(onClick = { saveDraft() }) {
                            Icon(Icons.Outlined.Check, "保存笔记", tint = AppColors.Accent)
                        }
                    }
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
