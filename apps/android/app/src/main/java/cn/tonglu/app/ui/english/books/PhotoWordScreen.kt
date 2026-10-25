package cn.tonglu.app.ui.english.books

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import cn.tonglu.app.data.english.books.OcrCandidate
import cn.tonglu.app.data.english.books.PersonalBookWord
import cn.tonglu.app.data.english.books.PersonalWordBookStore
import cn.tonglu.app.data.english.books.PhotoWordExtractor
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * EF09 拍照识词：拍照（应用内 CameraX）或选图（系统照片选择器）→ 本地 OCR →
 * 候选校对（低置信标出、可编辑、释义必填）→ 选目标词书 → 确认逐项入库。
 *
 * 🔴 照片仅本机识别不上传；识别候选**必须**经用户校对确认，不静默把低置信识别当正确词；
 * 拒绝相机权限时提供手输路径，功能不因权限而死。
 */
@Composable
fun PhotoWordScreen(owner: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember(owner) { PersonalWordBookStore(context, owner) }
    val scope = rememberCoroutineScope()
    var phase by rememberSaveable(owner) { mutableStateOf("source") } // source | camera | proof | done
    var candidates by remember(owner) { mutableStateOf<List<OcrCandidate>>(emptyList()) }
    var recognizing by remember(owner) { mutableStateOf(false) }
    var notice by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var importing by remember(owner) { mutableStateOf(false) }
    var summary by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var books by remember(owner) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var targetBookId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var manualOpen by rememberSaveable(owner) { mutableStateOf(false) }
    /** 校对工作区：key → (surface, meaning)。用户改词后按新 surface 入库；删掉的候选不导入。 */
    val proofState = remember(owner) { mutableStateMapOf<String, Pair<String, String>>() }

    fun reloadBooks() {
        scope.launch {
            try { books = store.snapshot().books.map { it.id to it.name } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (cause: Exception) { notice = cause.message ?: "词书读取失败" }
        }
    }
    LaunchedEffect(owner) { reloadBooks() }

    fun recognize(uri: android.net.Uri) {
        scope.launch {
            recognizing = true; notice = null
            try {
                val found = withContext(Dispatchers.IO) { PhotoWordExtractor.extract(context, uri) }
                candidates = found
                proofState.clear()
                if (found.isEmpty()) notice = "照片里没认出英文词；可以换个角度重拍，或改用手动输入。"
                else phase = "proof"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (cause: Exception) { notice = "识别失败：${cause.message ?: "请重试"}" }
            finally {
                recognizing = false
                // 拍照落在本机 cache 的临时文件识别完即删；相册选图的 Uri 不归我们管
                if (uri.scheme == "file") runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) recognize(uri)
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) phase = "camera" else notice = "未授予相机权限；可以改用「选图」，或手动输入词。"
    }

    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState, selfDrag = true,
    )
    fun back() {
        when {
            manualOpen -> manualOpen = false
            phase == "camera" -> phase = "source"
            phase == "proof" -> { phase = "source"; candidates = emptyList(); summary = null }
            else -> motion.requestClose()
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = { back() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false, onBack = { back() }), color = AppColors.Canvas) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级") }
                    Text(when (phase) {
                        "camera" -> "拍摄含单词的页面"
                        "proof" -> "校对识别候选（${candidates.size}）"
                        else -> "拍照识词"
                    }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
                    if (phase == "proof" && !importing) {
                        TextButton(onClick = {
                            val bookId = targetBookId
                            if (bookId == null) { notice = "先选一本目标词书。"; return@TextButton }
                            val ready = proofState.values.filter { it.first.isNotBlank() && it.second.isNotBlank() }
                            if (ready.isEmpty()) { notice = "至少保留一个词且补上释义。"; return@TextButton }
                            importing = true
                            scope.launch {
                                try {
                                    var added = 0; var duplicate = 0; var failed = 0
                                    for ((surface, meaning) in ready) {
                                        try {
                                            val fresh = store.add(bookId, PersonalBookWord(surface = surface.trim(), meaning = meaning.trim(),
                                                sourcePosition = "拍照识词"))
                                            if (fresh) added++ else duplicate++
                                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                        catch (cause: Exception) { failed++ }
                                    }
                                    summary = "新增 $added · 书内重复 $duplicate · 失败 $failed"
                                    phase = "done"; importing = false; reloadBooks()
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (cause: Exception) { notice = cause.message ?: "导入失败"; importing = false }
                            }
                        }) { Text("确认加入") }
                    }
                }
                notice?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }
                when (phase) {
                    "source" -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("照片只在本机识别（ML Kit 离线模型），不上传服务器；识别结果只是候选，需你逐个校对后才写入词书。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                                    == PackageManager.PERMISSION_GRANTED) phase = "camera"
                                else cameraPermission.launch(Manifest.permission.CAMERA)
                            }, enabled = !recognizing) { Text(if (recognizing) "识别中…" else "拍照") }
                            OutlinedButton(onClick = {
                                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }, enabled = !recognizing) { Text("选图") }
                            OutlinedButton(onClick = { manualOpen = true }) { Text("手动输入") }
                        }
                    }
                    "camera" -> CameraCaptureBody(onCaptured = { uri -> recognize(uri); phase = "source" })
                    "proof" -> ProofBody(candidates, books, targetBookId,
                        proofState, onTarget = { targetBookId = it },
                        onProofChange = { key, surface, meaning -> proofState[key] = surface to meaning },
                        onRemove = { key -> proofState.remove(key) })
                    else -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        summary?.let { Text(it, fontWeight = FontWeight.Medium) }
                        Text("只有「新增」的词写进了词书；重复与失败未写入。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { phase = "proof" }) { Text("返回候选") }
                    }
                }
            }
        }
    }

    if (manualOpen) {
        ManualWordDialog(onDismiss = { manualOpen = false }, onSubmit = { surface, meaning ->
            manualOpen = false
            val bookId = targetBookId
            if (bookId == null) { notice = "先在校对页选一本目标词书，再手动加入。"; return@ManualWordDialog }
            scope.launch {
                try {
                    val fresh = store.add(bookId, PersonalBookWord(surface = surface, meaning = meaning, sourcePosition = "手动输入"))
                    notice = if (fresh) "已加入。" else "该词已在这本书里。"
                    reloadBooks()
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (cause: Exception) { notice = cause.message ?: "加入失败" }
            }
        })
    }
}

/** 校对工作区由 PhotoWordScreen 组合内持有（remember(owner)），不跨会话共享。 */

@Composable
private fun CameraCaptureBody(onCaptured: (android.net.Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var flashError by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { ctx ->
            val previewView = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                } catch (cause: Exception) { flashError = cause.message ?: "相机启动失败" }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }, modifier = Modifier.fillMaxSize())
        flashError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.align(Alignment.Center).padding(24.dp))
        }
        Button(
            onClick = {
                val dir = File(context.cacheDir, "photo-words").apply { mkdirs() }
                val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
                val options = ImageCapture.OutputFileOptions.Builder(file).build()
                imageCapture.takePicture(options, ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            onCaptured(android.net.Uri.fromFile(file))
                        }
                        override fun onError(exception: ImageCaptureException) {
                            flashError = "拍摄失败：${exception.message ?: "请重试"}"
                        }
                    })
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
        ) { Text("拍摄") }
    }
}

@Composable
private fun ProofBody(candidates: List<OcrCandidate>, books: List<Pair<String, String>>, targetBookId: String?,
    proof: Map<String, Pair<String, String>>, onTarget: (String?) -> Unit,
    onProofChange: (String, String, String) -> Unit, onRemove: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("目标词书", fontWeight = FontWeight.Medium)
            if (books.isEmpty()) Text("还没有词书：先回「个人词书」新建一本。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                books.forEach { (id, name) ->
                    FilterChip(selected = targetBookId == id, onClick = { onTarget(if (targetBookId == id) null else id) },
                        label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                }
            }
        }
        items(candidates, key = { it.key }) { candidate ->
            val working = proof[candidate.key] ?: (candidate.surface to "")
            var surface by remember(candidate.key) { mutableStateOf(working.first) }
            var meaning by remember(candidate.key) { mutableStateOf(working.second) }
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("置信 ${(candidate.confidence * 100).toInt()}%", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (candidate.lowConfidence) AssistChip(onClick = {}, label = { Text("低置信，请核对") })
                        Text(candidate.lineText.take(40), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = { onRemove(candidate.key) }) { Text("不导入") }
                    }
                    OutlinedTextField(value = surface, onValueChange = { surface = it; onProofChange(candidate.key, it, meaning) },
                        label = { Text("词") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = meaning, onValueChange = { meaning = it; onProofChange(candidate.key, surface, it) },
                        label = { Text("释义（加入必填）") }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ManualWordDialog(onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var surface by remember { mutableStateOf("") }
    var meaning by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("手动输入词") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = surface, onValueChange = { surface = it }, label = { Text("词") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = meaning, onValueChange = { meaning = it }, label = { Text("释义") },
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(surface.trim(), meaning.trim()) },
            enabled = surface.isNotBlank() && meaning.isNotBlank()) { Text("加入所选词书") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
