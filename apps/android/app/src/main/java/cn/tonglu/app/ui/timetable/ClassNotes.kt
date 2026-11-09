package cn.tonglu.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.material.icons.outlined.Edit
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
import cn.tonglu.app.ui.glass.GlassTokens
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import cn.tonglu.app.data.notes.ClassNoteExport
import cn.tonglu.app.data.notes.ClassNoteExportData
import cn.tonglu.app.data.notes.ClassNoteExportFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private data class ClassNote(val id: String, val time: Long, val text: String, val image: String?, val audio: String?,
    val extraImages: List<String> = emptyList(), val extraAudio: List<String> = emptyList(),
    val html: String? = null, val markdown: String? = null) {
    val images get() = listOfNotNull(image) + extraImages
    val recordings get() = listOfNotNull(audio) + extraAudio
    val document get() = NoteDocument(text, html, markdown)
}
private fun ClassNote.toJson() = JSONObject().put("id", id).put("time", time).put("text", text)
    .put("image", image ?: "").put("audio", audio ?: "").put("extraImages", JSONArray(extraImages))
    .put("extraAudio", JSONArray(extraAudio)).putOpt("html", html).putOpt("markdown", markdown)
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
    fun recordingRecoveryFile() = File(root, "$prefix.recording-ready")
    fun mediaFile(name: String): File = File(root, name)
    fun exportMedia(name: String): File = mediaFile(name).also {
        require(name == it.name && !name.contains('\\') && it.canonicalFile.parentFile == root.canonicalFile) { "附件路径不属于此笔记目录" }
    }
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
                it.optString("image").takeIf(String::isNotBlank), it.optString("audio").takeIf(String::isNotBlank), it.mediaNames("extraImages"), it.mediaNames("extraAudio"),
                it.optString("html", "").takeIf(String::isNotBlank), it.optString("markdown", "").takeIf(String::isNotBlank))
        } }
    }
    fun readAll(): List<ClassNote> = root.listFiles().orEmpty()
        .filter { candidate -> candidate.extension == "json" && readablePrefixes.any { candidate.name.startsWith("$it-") } }
        .flatMap(::read).distinctBy { it.id }.sortedByDescending { it.time }
    fun append(item: ClassNote) {
        val array = JSONArray()
        (listOf(item) + read(file)).forEach { row -> array.put(row.toJson()) }
        val pending = File(root, "$key.pending")
        pending.writeText(array.toString())
        if (!pending.renameTo(file)) {
            pending.copyTo(file, overwrite = true)
            check(pending.delete())
        }
    }
    fun replaceDocument(id: String, value: NoteDocument) = update(id) { it.copy(text = value.text, html = value.html, markdown = value.markdown) }
    fun attach(id: String, name: String, image: Boolean) = update(id) {
        if (image) {
            if (name in it.images) it else if (it.image == null) it.copy(image = name) else it.copy(extraImages = it.extraImages + name)
        } else {
            if (name in it.recordings) it else if (it.audio == null) it.copy(audio = name) else it.copy(extraAudio = it.extraAudio + name)
        }
    }
    private fun update(id: String, transform: (ClassNote) -> ClassNote) {
        val target = root.listFiles().orEmpty().firstOrNull { candidate ->
            candidate.extension == "json" && readablePrefixes.any { candidate.name.startsWith("$it-") } &&
                read(candidate).any { it.id == id }
        } ?: error("找不到原笔记，请返回后重试")
        val rows = read(target).map { if (it.id == id) transform(it) else it }
        val array = JSONArray()
        rows.forEach { row -> array.put(row.toJson()) }
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
    var exportMenu by remember { mutableStateOf(false) }
    var exportPicker by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportNotice by remember { mutableStateOf<String?>(null) }
    var pendingExportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportWarnings by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val visibleNotes = notes.filter { note ->
        (query.isBlank() || note.text.contains(query.trim(), ignoreCase = true)) && when (mediaFilter) {
            1 -> note.images.isNotEmpty()
            2 -> note.recordings.isNotEmpty()
            3 -> note.text.isNotBlank()
            else -> true
        }
    }
    val recorder = remember(store) { NoteRecorder(context, store.recordingRecoveryFile()) }
    val audioPlayer = remember(store) { NoteAudioController() }
    var pendingPhoto by remember { mutableStateOf<File?>(null) }
    var enlargedPhoto by remember { mutableStateOf<String?>(null) }
    var editingNoteId by remember(store) {
        mutableStateOf(resumedId ?: notes.firstOrNull { draft.isNotBlank() && it.text.trim() == draft.trim() }?.id)
    }
    var editorOpen by remember(store) { mutableStateOf(draft.isNotBlank() || resumedId != null) }
    var morphNoteId by remember(store) { mutableStateOf(editingNoteId) }
    var editorSession by remember(store) { mutableStateOf(NoteEditorSession(
        notes.firstOrNull { it.id == editingNoteId && it.text == draft }?.document ?: NoteDocument(draft))) }
    var writing by remember(store) { mutableStateOf(true) }
    var documentRevision by remember(store) { mutableIntStateOf(0) }
    var keyboardRequest by remember(store) { mutableIntStateOf(0) }
    var savedNotice by remember(store) { mutableStateOf<String?>(null) }
    var editorLicense by remember { mutableStateOf(false) }
    val gridState = rememberLazyStaggeredGridState()
    var headerHeight by remember { mutableIntStateOf(0) }
    var listHeaderHeight by remember { mutableIntStateOf(0) }
    var footerHeight by remember { mutableIntStateOf(0) }
    val headerInset = with(LocalDensity.current) { headerHeight.toDp() }
    val listHeaderInset = with(LocalDensity.current) { listHeaderHeight.toDp() }
    val footerInset = with(LocalDensity.current) { footerHeight.toDp() }
    val backdrop = rememberLayerBackdrop()
    val editorFocus = remember { FocusRequester() }
    fun append(note: ClassNote): Boolean {
        if (error?.startsWith("读取笔记失败") == true) return false
        return runCatching { store.append(note); notes = store.readAll(); error = null }
            .onFailure { error = "保存失败：${it.localizedMessage}" }.isSuccess
    }
    fun saveDraft(): Boolean {
        val document = if (editorOpen) editorSession.document() else NoteDocument(draft)
        val content = document.text
        draft = content
        val existing = notes.any { it.id == editingNoteId }
        if ((content.isNotBlank() || existing) && error?.startsWith("读取笔记失败") != true) {
            if (notes.any { it.id == editingNoteId && it.document == document }) {
                return runCatching { store.saveDraft(""); error = null }
                    .onFailure { error = "清理草稿失败：${it.localizedMessage}" }.isSuccess
            }
            val targetId = editingNoteId ?: UUID.randomUUID().toString()
            val saved = if (existing) {
                runCatching { store.replaceDocument(targetId, document); notes = store.readAll(); error = null }
                    .onFailure { error = "更新失败：${it.localizedMessage}" }.isSuccess
            } else append(ClassNote(targetId, System.currentTimeMillis(), content, null, null, html = document.html, markdown = document.markdown))
            if (saved) {
                editingNoteId = targetId
                morphNoteId = targetId
                runCatching { store.saveDraft("") }.onFailure { error = "清理草稿失败：${it.localizedMessage}" }
            }
            return saved
        }
        return content.isBlank()
    }
    var cameraTarget by remember { mutableStateOf<String?>(null) }
    fun mediaTarget(): String? {
        if (!editorOpen) return null
        if (!saveDraft()) throw IllegalStateException(error ?: "请先保存当前笔记")
        if (editingNoteId == null) {
            // Reserve an identity, not a row. Cancelling a camera/import or a failed recording
            // must not leave a blank note; typing during a recording still uses this identity.
            editingNoteId = UUID.randomUUID().toString()
        }
        return editingNoteId
    }
    fun saveMedia(file: File, image: Boolean, target: String?): Boolean {
        if (target == null || notes.none { it.id == target }) {
            val id = target ?: UUID.randomUUID().toString()
            val saved = append(ClassNote(id, System.currentTimeMillis(), "",
                if (image) file.name else null, if (image) null else file.name))
            if (saved && editorOpen && editingNoteId == id) morphNoteId = id
            return saved
        }
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
            val target = mediaTarget() ?: UUID.randomUUID().toString()
            audioPlayer.pause()
            recorder.start(store.media("m4a"), target)
        }.onFailure { error = "录音失败：${it.localizedMessage}" }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else error = "需要麦克风权限才能录音"
    }
    fun stopRecording(): Boolean = recorder.finish { file, id -> saveMedia(file, false, id) }
    fun beginNote(note: ClassNote?) {
        if (!stopRecording() || !saveDraft()) return
        runCatching { NoteEditorSession(note?.document ?: NoteDocument("")) }
            .onSuccess { session ->
                editorSession = session; draft = note?.text.orEmpty(); editingNoteId = note?.id
                morphNoteId = note?.id; writing = true; savedNotice = null; keyboardRequest = 0; editorOpen = true
            }.onFailure { error = "无法打开笔记格式：${it.localizedMessage}" }
    }
    LaunchedEffect(editorSession) {
        snapshotFlow { editorSession.state.annotatedString }.map { editorSession.document() }
            .distinctUntilChanged().drop(1).collect { document ->
                draft = document.text; documentRevision++; savedNotice = null
            }
    }
    LaunchedEffect(recorder.active, recorder.paused) {
        while (recorder.active && !recorder.paused) { recorder.tick(); delay(200) }
    }
    LaunchedEffect(audioPlayer.playing) {
        while (audioPlayer.playing) { audioPlayer.tick(); delay(200) }
    }
    val memoryHandler = rememberUpdatedState<(Boolean) -> Boolean> { kill ->
        if (!kill) true
        else {
            val recordingSaved = stopRecording()
            audioPlayer.release()
            val textSaved = saveDraft()
            val checkpointSaved = runCatching { store.checkpoint(if (editorOpen) editingNoteId else null) }
                .onFailure { error = "保存编辑位置失败：${it.localizedMessage}" }.isSuccess
            recordingSaved && textSaved && checkpointSaved && !importing &&
                error?.startsWith("读取笔记失败") != true
        }
    }
    DisposableEffect(store) {
        val registration = cn.tonglu.app.data.memory.FairMemory.register { memoryHandler.value(it) }
        onDispose { memoryHandler.value(true); audioPlayer.release(); registration.close() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val saveOnStop = rememberUpdatedState { stopRecording(); saveDraft(); audioPlayer.pause() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) saveOnStop.value() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(draft, documentRevision, editorOpen) {
        delay(700)
        if (editorOpen && (draft.isNotBlank() || notes.any { it.id == editingNoteId })) runCatching { store.saveDraft(draft); saveDraft() }
            .onFailure { error = "自动保存失败：${it.localizedMessage}" }
    }
    val outerBackEnabled = !editorOpen && !recorder.active && !recorder.pending && !importing && draft.isBlank() && enlargedPhoto == null
    val outerMotion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onDismiss() },
        predictive = OverlayMotion.enabledState, selfDrag = true, backEnabled = outerBackEnabled,
    )
    fun requestBack() {
        if (!stopRecording()) return
        if (!saveDraft()) return
        if (editorOpen) { editorOpen = false; editingNoteId = null; draft = "" }
        else outerMotion.requestClose()
    }
    val saveExport = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val path = pendingExportPath
        val uri = result.data?.data.takeIf { result.resultCode == android.app.Activity.RESULT_OK }
        if (path != null) {
            val cached = File(path)
            if (uri == null) {
                if (cached.exists() && !cached.delete()) android.util.Log.w("ClassNotes", "取消导出后缓存清理失败")
                pendingExportPath = null
            } else scope.launch {
                exporting = true
                try {
                    withContext(Dispatchers.IO) {
                        require(cached.canonicalFile.parentFile == File(context.cacheDir, "note-exports").canonicalFile) { "导出缓存路径无效" }
                        cached.inputStream().use { input ->
                            requireNotNull(context.contentResolver.openOutputStream(uri, "w")) { "无法写入所选位置" }.use { output -> input.copyTo(output) }
                        }
                    }
                    if (pendingExportWarnings.isEmpty()) android.widget.Toast.makeText(context, "笔记已导出", android.widget.Toast.LENGTH_SHORT).show()
                    else exportNotice = "文件已导出。\n" + pendingExportWarnings.joinToString("\n")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (cause: Exception) { error = "导出未完成，请重试：${cause.localizedMessage}" }
                finally {
                    if (cached.exists() && !cached.delete()) android.util.Log.w("ClassNotes", "导出缓存清理失败")
                    pendingExportPath = null; exporting = false
                }
            }
        } else if (uri != null) error = "导出内容未能恢复，请重新导出。笔记正文仍保存在本机。"
    }
    fun prepareExport(format: ClassNoteExportFormat) {
        exportPicker = false
        if (exporting || importing || !stopRecording() || !saveDraft()) return
        val note = notes.firstOrNull { it.id == editingNoteId } ?: return
        val warnings = mutableListOf<String>()
        fun media(names: List<String>, label: String) = names.mapIndexedNotNull { index, name ->
            try { store.exportMedia(name) }
            catch (cause: Exception) {
                android.util.Log.w("ClassNotes", "导出附件路径无效", cause)
                warnings += "$label${index + 1}路径无效，未导出"; null
            }
        }
        val snapshot = ClassNoteExportData(note.id, course.name, note.text, note.time,
            media(note.images, "图片"), media(note.recordings, "录音"), warnings, note.html, note.markdown)
        exporting = true
        scope.launch {
            var file: File? = null
            try {
                val prepared = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "note-exports").apply { check(exists() || mkdirs()) { "无法准备导出目录" } }
                    val target = File(dir, "${UUID.randomUUID()}.${format.extension}"); file = target
                    val result = target.outputStream().use { ClassNoteExport.write(snapshot, format, it) }
                    target to result
                }
                pendingExportPath = prepared.first.path; pendingExportWarnings = ArrayList(prepared.second)
                saveExport.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(format.mime).putExtra(Intent.EXTRA_TITLE, ClassNoteExport.filename(snapshot, format)))
                file = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (cause: Exception) { error = "无法导出笔记：${cause.localizedMessage}" }
            finally {
                file?.let { if (it.exists() && !it.delete()) android.util.Log.w("ClassNotes", "准备导出失败后缓存清理失败") }
                exporting = false
            }
        }
    }
    Dialog(onDismissRequest = { requestBack() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val dialogView = LocalView.current
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        LaunchedEffect(editorOpen, writing, keyboardRequest) {
            if (editorOpen && writing && (morphNoteId == null || keyboardRequest > 0)) {
                withFrameNanos { }; editorFocus.requestFocus(); keyboard?.show()
            } else if (!editorOpen || !writing) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
        }
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
        Surface(Modifier.fillMaxSize().overlayMotion(outerMotion, fade = false,
            onBack = {
                if (imeShowing) { focusManager.clearFocus(); keyboard?.hide() }
                else requestBack()
            }, backEnabled = !importing && enlargedPhoto == null,
            enter = OverlayEnter.Right), color = AppColors.CanvasOpaque, contentColor = AppColors.TextPrimary) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                val editorSidePadding = maxOf(24.dp, (maxWidth - 800.dp) / 2)
                val noteColumns = if (maxWidth >= 1100.dp) 4 else if (maxWidth >= 700.dp) 3 else 2
                SharedTransitionLayout(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                AnimatedContent(targetState = editorOpen,
                    // Only the selected card bounds crossfade. Fading both whole pages as well
                    // made the entire grid ghost through the expanding document.
                    transitionSpec = {
                        if (morphNoteId == null) {
                            if (targetState) slideInHorizontally(tween(320)) { it } togetherWith ExitTransition.None
                            else EnterTransition.None togetherWith slideOutHorizontally(tween(280)) { it }
                        } else EnterTransition.None togetherWith ExitTransition.None
                    },
                    modifier = Modifier.fillMaxSize(), label = "class-note-editor") { editing ->
                    if (editing) {
                        Column(Modifier.fillMaxSize().sharedBounds(
                            rememberSharedContentState(key = "note-${morphNoteId ?: "new"}"),
                            animatedVisibilityScope = this@AnimatedContent,
                            enter = fadeIn(tween(90)), exit = fadeOut(tween(90)),
                            boundsTransform = { _, _ -> tween(380) })
                            .background(AppColors.CanvasOpaque)
                            // Reserve the floating controls in the IME scroll viewport so the caret cannot hide behind them.
                            .padding(bottom = if (imeShowing) footerInset + 24.dp else 0.dp)
                            .padding(horizontal = editorSidePadding).verticalScroll(rememberScrollState())
                            .padding(top = headerInset + 16.dp, bottom = if (imeShowing) 16.dp else footerInset + 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val current = notes.firstOrNull { it.id == (if (editorOpen) editingNoteId else morphNoteId) }
                            current?.let { Text(SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()).format(Date(it.time)),
                                color = AppColors.TextSecondary, fontSize = 12.sp) }
                            if (writing && editorOpen) NoteTextEditor(editorSession,
                                Modifier.fillMaxWidth().heightIn(min = 160.dp).focusRequester(editorFocus))
                            else NoteTextView(editorSession, Modifier.fillMaxWidth().heightIn(min = 160.dp)
                                .clickable(enabled = editorOpen) { writing = true; keyboardRequest++ })
                            current?.images?.forEach { name ->
                                val bitmap = remember(name) { scaledPhoto(store.mediaFile(name), 1000) }
                                  if (bitmap != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                      Image(bitmap.asImageBitmap(), "笔记图片",
                                          Modifier.heightIn(max = 360.dp).widthIn(max = 800.dp)
                                              .aspectRatio(bitmap.width.toFloat() / bitmap.height, matchHeightConstraintsFirst = true)
                                              .clickable { enlargedPhoto = name }, contentScale = ContentScale.Fit)
                                  }
                            }
                            current?.recordings?.forEachIndexed { index, name ->
                                NoteAudioCard(store.mediaFile(name), "录音 ${index + 1}", audioPlayer)
                            }
                        }
                    } else {
                LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(noteColumns), state = gridState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
                    contentPadding = PaddingValues(top = listHeaderInset + 12.dp, bottom = footerInset + 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalItemSpacing = 12.dp) {
                    items(visibleNotes, key = { it.id }) { note ->
                        Surface(shape = RoundedCornerShape(20.dp), color = AppColors.SurfaceOpaque,
                            modifier = Modifier.sharedBounds(
                                rememberSharedContentState(key = "note-${note.id}"),
                                animatedVisibilityScope = this@AnimatedContent,
                                enter = fadeIn(tween(90)), exit = fadeOut(tween(90)),
                                boundsTransform = { _, _ -> tween(380) }).clickable {
                                beginNote(note)
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
                            note.recordings.take(1).forEach { name -> NoteAudioCard(store.mediaFile(name), "录音", audioPlayer) }
                            if (note.recordings.size > 1) Text("另有 ${note.recordings.size - 1} 段录音，打开笔记查看", color = AppColors.TextSecondary, fontSize = 12.sp)
                        } }
                    }
                }
                    }
                }
                }
                GlassActionPanel(backdrop, modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { headerHeight = it.height; if (!editorOpen) listHeaderHeight = it.height },
                    shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                    style = GlassPanelStyle.Default.copy(highlightAlpha = 0f, innerShadowAlpha = 0f)) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(if (editorOpen) "笔记" else course.name,
                            color = if (editorOpen) AppColors.TextPrimary else AppColors.TextSecondary,
                            fontSize = if (editorOpen) 18.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        AnimatedVisibility(editorOpen && savedNotice != null) { Text(savedNotice.orEmpty(), color = AppColors.TextSecondary, fontSize = 11.sp) }
                    }
                    if (editorOpen) Box {
                        IconButton(enabled = !exporting, onClick = { exportMenu = true; focusManager.clearFocus(); keyboard?.hide() }) {
                            Icon(Icons.Outlined.MoreVert, "更多笔记操作")
                        }
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }, containerColor = AppColors.SurfaceOpaque) {
                            DropdownMenuItem(text = { Text("导出笔记") }, onClick = { exportMenu = false; exportPicker = true },
                                enabled = draft.isNotBlank() || notes.any { it.id == editingNoteId })
                            DropdownMenuItem(text = { Text("开源许可") }, onClick = { exportMenu = false; editorLicense = true })
                        }
                    }
                }
                if (!editorOpen) Text("课堂笔记", color = AppColors.TextPrimary, fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                if (!editorOpen) {
                    GlassActionPanel(backdrop, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                        style = GlassPanelStyle.Default.copy(surfaceAlpha = GlassTokens.SearchSurfaceAlpha)) {
                    TextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("搜索笔记") }, shape = RoundedCornerShape(20.dp),
                        colors = TextFieldDefaults.colors(focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedTextColor = AppColors.TextPrimary, unfocusedTextColor = AppColors.TextPrimary,
                            cursorColor = GlassTokens.AccentOnGlass,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                    }
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
                if (importing || exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = AppColors.Danger, fontSize = 13.sp) }
                }
                }
                val footerWidth = if (editorOpen) Modifier.widthIn(max = 520.dp).fillMaxWidth() else Modifier.widthIn(max = 340.dp)
                GlassActionPanel(backdrop,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).then(footerWidth)
                        .onSizeChanged { footerHeight = it.height },
                    shape = RoundedCornerShape(28.dp), style = GlassPanelStyle.Default) {
                    Column(Modifier.animateContentSize(tween(180))) {
                        NoteRecordingPanel(recorder, finish = { stopRecording() })
                        AnimatedVisibility(editorOpen && writing) {
                            NoteFormattingToolbar(editorSession, Modifier.padding(horizontal = 8.dp)) { writing = true; keyboardRequest++ }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                TextButton(enabled = !importing, onClick = { attachmentMenu = true }) {
                                    Icon(Icons.Outlined.AttachFile, "添加附件"); Spacer(Modifier.width(4.dp)); Text("附件", color = AppColors.TextPrimary)
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
                            TextButton(onClick = {
                                if (recorder.active || recorder.pending) stopRecording()
                                else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }) {
                                Icon(if (recorder.active || recorder.pending) Icons.Outlined.Stop else Icons.Outlined.Mic,
                                    if (recorder.active || recorder.pending) "结束并保存录音" else "开始录音", tint = AppColors.Danger)
                                Spacer(Modifier.width(4.dp)); Text(if (recorder.active) "结束" else if (recorder.pending) "重试保存" else "录音", color = AppColors.TextPrimary)
                            }
                            if (!editorOpen) TextButton(enabled = !importing, onClick = { beginNote(null) }) {
                                Icon(Icons.Outlined.Add, "新建笔记"); Spacer(Modifier.width(4.dp)); Text("新建", color = AppColors.TextPrimary)
                            }
                            else if (!writing) TextButton(onClick = { writing = true; keyboardRequest++ }) {
                                Icon(Icons.Outlined.Edit, "继续编辑"); Spacer(Modifier.width(4.dp)); Text("编辑", color = GlassTokens.AccentOnGlass)
                            }
                            else FilledTonalButton(enabled = !importing && !exporting, onClick = {
                                if (stopRecording() && saveDraft()) {
                                    focusManager.clearFocus(); keyboard?.hide()
                                    if (notes.none { it.id == editingNoteId }) { editorOpen = false; draft = ""; editingNoteId = null }
                                    else { writing = false; savedNotice = "已保存" }
                                }
                            }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = GlassTokens.AccentOnGlass.copy(alpha = .14f), contentColor = GlassTokens.AccentOnGlass)) {
                                Icon(Icons.Outlined.Check, "完成并保存"); Spacer(Modifier.width(4.dp)); Text("完成")
                            }
                        }
                    }
                }
            }
        }
    }
    if (editorLicense) AlertDialog(onDismissRequest = { editorLicense = false }, containerColor = AppColors.SurfaceOpaque,
        title = { Text("开源许可") }, text = {
            val license = remember { context.assets.open("licenses/compose-rich-editor.txt").bufferedReader().use { it.readText() } }
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Compose Rich Editor 1.1.0 · Mohamed Rejeb · Apache-2.0", fontSize = 13.sp)
                Text(license, fontSize = 11.sp)
            }
        }, confirmButton = { TextButton(onClick = { editorLicense = false }) { Text("关闭") } })
    if (exportPicker) AlertDialog(containerColor = AppColors.SurfaceOpaque, onDismissRequest = { exportPicker = false },
        title = { Text("导出笔记") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("TXT 和 Markdown 保存文字；Word、PDF 包含图片；录音请选完整资料包。", style = MaterialTheme.typography.bodySmall)
            ClassNoteExportFormat.entries.forEach { format ->
                TextButton(onClick = { prepareExport(format) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(format.label, fontWeight = FontWeight.SemiBold)
                        Text(format.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = { exportPicker = false }) { Text("取消") } })
    exportNotice?.let { notice -> AlertDialog(containerColor = AppColors.SurfaceOpaque, onDismissRequest = { exportNotice = null },
        title = { Text("导出结果") }, text = { Text(notice) }, confirmButton = { TextButton(onClick = { exportNotice = null }) { Text("知道了") } }) }
    enlargedPhoto?.let { name ->
        val photoMotion = rememberOverlayMotion(
            onClose = { enlargedPhoto = null }, predictive = OverlayMotion.enabledState, selfDrag = true,
        )
        Dialog(onDismissRequest = { photoMotion.requestClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            val bitmap = remember(name) { scaledPhoto(store.mediaFile(name), 2200) }
            Surface(Modifier.fillMaxSize().overlayMotion(photoMotion, fade = false, enter = OverlayEnter.Right).clickable { photoMotion.requestClose() }, color = AppColors.CanvasOpaque) {
                if (bitmap != null) Image(bitmap.asImageBitmap(), "查看课堂照片", Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit)
            }
        }
    }
}
