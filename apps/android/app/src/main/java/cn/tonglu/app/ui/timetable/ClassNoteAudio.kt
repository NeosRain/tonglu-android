package cn.tonglu.app.ui

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

internal fun noteDuration(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

/** One player for the whole note window: playing another clip stops the previous one. */
internal class NoteAudioController {
    var selectedPath by mutableStateOf<String?>(null); private set
    var playing by mutableStateOf(false); private set
    var loading by mutableStateOf(false); private set
    var position by mutableIntStateOf(0); private set
    var duration by mutableIntStateOf(0); private set
    var speed by mutableFloatStateOf(1f); private set
    var error by mutableStateOf<String?>(null); private set
    private var player: MediaPlayer? = null
    private var prepared = false
    private var seeking = false
    private fun clearPlayer() {
        val old = player; player = null; prepared = false; seeking = false
        if (old != null) try { old.release() } catch (e: Exception) { android.util.Log.w("NoteAudio", "播放器释放失败", e) }
    }
    fun release() { clearPlayer(); playing = false; loading = false }
    fun pause() {
        val current = player ?: return
        if (!prepared || !playing) return
        try { current.pause(); playing = false; position = current.currentPosition }
        catch (e: Exception) { fail(current, e.localizedMessage) }
    }
    private fun fail(current: MediaPlayer, message: String?) {
        if (player !== current) return
        android.util.Log.w("NoteAudio", "播放失败：$message")
        clearPlayer(); playing = false; loading = false; error = "播放失败：${message ?: "无法读取这段录音"}"
    }
    private fun prepare(file: File, start: Boolean, seek: Int = 0) {
        clearPlayer(); selectedPath = file.absolutePath; playing = false; loading = true; position = seek; duration = 0; error = null
        val current = MediaPlayer(); player = current
        current.setOnErrorListener { mp, what, extra -> fail(mp, "音频错误 $what/$extra"); true }
        current.setOnPreparedListener { mp ->
            if (player === mp) try {
                prepared = true; loading = false; duration = mp.duration.coerceAtLeast(0)
                if (start && speed != 1f) mp.playbackParams = mp.playbackParams.setSpeed(speed)
                if (seek > 0) { seeking = true; mp.seekTo(seek.coerceIn(0, duration)); position = seek.coerceIn(0, duration) }
                if (start) { mp.start(); playing = true } else { if (mp.isPlaying) mp.pause(); playing = false }
            } catch (e: Exception) { fail(mp, e.localizedMessage) }
        }
        current.setOnCompletionListener { mp -> if (player === mp) { playing = false; position = duration } }
        current.setOnSeekCompleteListener { mp -> if (player === mp) { seeking = false; position = mp.currentPosition } }
        try { current.setDataSource(file.absolutePath); current.prepareAsync() }
        catch (e: Exception) { fail(current, e.localizedMessage) }
    }
    fun toggle(file: File) {
        val current = player
        if (selectedPath != file.absolutePath || current == null || !prepared) { prepare(file, true); return }
        try {
            if (current.isPlaying) { current.pause(); playing = false }
            else { if (duration > 0 && position >= duration - 100) current.seekTo(0); current.playbackParams = current.playbackParams.setSpeed(speed); current.start(); playing = true }
            position = current.currentPosition
        } catch (e: Exception) { fail(current, e.localizedMessage) }
    }
    fun seek(file: File, milliseconds: Int) {
        val current = player
        if (selectedPath != file.absolutePath || current == null || !prepared) { prepare(file, false, milliseconds); return }
        try { position = milliseconds.coerceIn(0, duration); seeking = true; current.seekTo(position) }
        catch (e: Exception) { fail(current, e.localizedMessage) }
    }
    fun changeSpeed(value: Float) {
        val current = player
        if (current != null && prepared && playing) try {
            current.playbackParams = current.playbackParams.setSpeed(value)
        } catch (e: Exception) { fail(current, e.localizedMessage); return }
        speed = value
    }
    fun tick() {
        val current = player ?: return
        if (!prepared) return
        try { if (!seeking) position = current.currentPosition; playing = current.isPlaying }
        catch (e: Exception) { fail(current, e.localizedMessage) }
    }
}

@Composable
internal fun NoteAudioCard(file: File, label: String, controller: NoteAudioController, modifier: Modifier = Modifier) {
    var metadataDuration by remember(file.absolutePath, file.lastModified()) { mutableIntStateOf(0) }
    var metadataError by remember(file.absolutePath) { mutableStateOf(false) }
    LaunchedEffect(file.absolutePath) {
        try {
            metadataDuration = withContext(Dispatchers.IO) {
                val reader = MediaMetadataRetriever()
                try { reader.setDataSource(file.absolutePath); reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0 }
                finally { reader.release() }
            }
        } catch (e: Exception) { android.util.Log.w("NoteAudio", "读取录音时长失败", e); metadataError = true }
    }
    val selected = controller.selectedPath == file.absolutePath
    val total = if (selected && controller.duration > 0) controller.duration else metadataDuration
    val position = if (selected) controller.position.coerceIn(0, total.coerceAtLeast(0)) else 0
    var drag by remember(file.absolutePath) { mutableStateOf<Float?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = AppColors.ChipIdle, contentColor = AppColors.TextPrimary) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = !(selected && controller.loading), onClick = { controller.toggle(file) }) {
                    if (selected && controller.loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(if (selected && controller.playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        if (selected && controller.playing) "暂停$label" else "播放$label", tint = AppColors.Accent)
                }
                Column(Modifier.weight(1f)) {
                    Text(label, fontSize = 14.sp)
                    Text("${noteDuration(position.toLong())} / ${noteDuration(total.toLong())}", color = AppColors.TextSecondary, fontSize = 12.sp)
                }
                Box {
                    TextButton(onClick = { speedMenu = true }) { Text("${String.format(Locale.ROOT, "%.2f", controller.speed).trimEnd('0').trimEnd('.')}×", color = AppColors.TextPrimary) }
                    DropdownMenu(speedMenu, onDismissRequest = { speedMenu = false }, containerColor = AppColors.SurfaceOpaque) {
                        listOf(.5f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                            DropdownMenuItem(text = { Text("${speed.toString().removeSuffix(".0")} 倍速") }, onClick = { controller.changeSpeed(speed); speedMenu = false },
                                trailingIcon = { if (speed == controller.speed) Icon(Icons.Outlined.Check, null) })
                        }
                    }
                }
            }
            Slider(value = drag ?: if (total > 0) position.toFloat() / total else 0f,
                onValueChange = { drag = it }, onValueChangeFinished = { drag?.let { controller.seek(file, (it * total).toInt()) }; drag = null },
                enabled = total > 0 && !(selected && controller.loading), modifier = Modifier.fillMaxWidth().height(32.dp).semantics { contentDescription = "$label 播放进度" })
            if (selected && controller.error != null) Text(controller.error!!, color = AppColors.Danger, fontSize = 12.sp)
            else if (metadataError && !selected) Text("时长读取失败，可点击播放重试", color = AppColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

/** Finalizes recordings before navigation/background; a completed but unsaved clip survives recreation. */
internal class NoteRecorder(private val context: android.content.Context, private val recoveryFile: File) {
    var active by mutableStateOf(false); private set
    var paused by mutableStateOf(false); private set
    var elapsed by mutableLongStateOf(0L); private set
    var pending by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var native: android.media.MediaRecorder? = null
    private var file: File? = null
    private var target: String? = null
    private var accumulated = 0L
    private var started = 0L
    private var recoveryFailed = false
    init {
        if (recoveryFile.exists() || File(recoveryFile.path + ".bak").exists()) try {
            val row = android.util.AtomicFile(recoveryFile).openRead().bufferedReader().use { org.json.JSONObject(it.readText()) }
            val candidate = File(recoveryFile.parentFile, row.getString("file"))
            require(candidate.name == row.getString("file") && candidate.canonicalFile.parentFile == recoveryFile.parentFile?.canonicalFile) { "录音恢复路径无效" }
            require(candidate.isFile && candidate.length() > 0) { "待保存的录音文件不存在" }
            file = candidate; target = row.getString("note"); pending = true; elapsed = row.optLong("elapsed")
        } catch (e: Exception) { recoveryFailed = true; error = "读取录音恢复信息失败：${e.localizedMessage}" }
    }
    fun start(output: File, noteId: String) {
        if (active || pending || recoveryFailed) { error = error ?: "请先保存当前录音"; return }
        val recorder = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(context)
            else @Suppress("DEPRECATION") android.media.MediaRecorder()
        try {
            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(output.absolutePath)
            recorder.setOnErrorListener { mr, what, extra ->
                if (native === mr) { native = null; active = false; paused = false; mr.release(); output.delete(); file = null; error = "录音中断：$what/$extra" }
            }
            recorder.prepare(); recorder.start()
            native = recorder; file = output; target = noteId; active = true; paused = false; pending = false; error = null
            accumulated = 0; elapsed = 0; started = android.os.SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            recorder.release(); output.delete(); error = "录音失败：${e.localizedMessage}"; android.util.Log.w("NoteRecorder", "启动录音失败", e)
        }
    }
    fun tick() { if (active) elapsed = accumulated + if (paused) 0 else android.os.SystemClock.elapsedRealtime() - started }
    fun togglePause() {
        val recorder = native ?: return
        try {
            if (paused) { recorder.resume(); started = android.os.SystemClock.elapsedRealtime(); paused = false }
            else { recorder.pause(); accumulated += android.os.SystemClock.elapsedRealtime() - started; paused = true; elapsed = accumulated }
        } catch (e: Exception) { error = "暂停/继续失败：${e.localizedMessage}"; android.util.Log.w("NoteRecorder", "暂停或继续录音失败", e) }
    }
    fun finish(save: (File, String) -> Boolean): Boolean {
        val recorder = native
        if (recorder != null) {
            tick(); native = null; active = false; paused = false
            try { recorder.stop(); pending = true }
            catch (e: Exception) { file?.delete(); file = null; error = "录音太短或无法完成：${e.localizedMessage}"; android.util.Log.w("NoteRecorder", "完成录音失败", e); return false }
            finally { recorder.release() }
        }
        if (!pending) return true
        val output = file ?: return false
        val noteId = target ?: return false
        return try {
            val atomic = android.util.AtomicFile(recoveryFile); val stream = atomic.startWrite()
            try {
                stream.write(org.json.JSONObject().put("file", output.name).put("note", noteId).put("elapsed", elapsed).toString().toByteArray())
                atomic.finishWrite(stream)
            } catch (e: Exception) { atomic.failWrite(stream); throw e }
            if (!save(output, noteId)) { error = "录音已保留，保存失败，请重试"; false }
            else {
                atomic.delete(); check(!recoveryFile.exists()) { "无法清理录音恢复信息" }
                pending = false; file = null; target = null; error = null; true
            }
        } catch (e: Exception) { error = "录音已保留，保存失败：${e.localizedMessage}"; android.util.Log.w("NoteRecorder", "保存录音失败", e); false }
    }
}

@Composable
internal fun NoteRecordingPanel(recorder: NoteRecorder, finish: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (recorder.active || recorder.pending) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (recorder.pending) "录音待保存" else if (recorder.paused) "已暂停 ${noteDuration(recorder.elapsed)}" else "正在录音 ${noteDuration(recorder.elapsed)}",
                        color = AppColors.Danger, fontSize = 14.sp)
                    Text("离开页面或切到后台会保存本段录音", color = AppColors.TextSecondary, fontSize = 10.sp)
                }
                if (recorder.active) TextButton(onClick = { recorder.togglePause() }) { Text(if (recorder.paused) "继续" else "暂停") }
                TextButton(onClick = finish) { Text(if (recorder.pending) "重试保存" else "结束录音", color = AppColors.Danger) }
            }
        }
        recorder.error?.let { Text(it, color = AppColors.Danger, fontSize = 12.sp) }
    }
}
