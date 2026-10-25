package cn.tonglu.app.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import cn.tonglu.app.data.decodeAvatar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A bounded preview bitmap; gestures choose the source square, only confirmation uploads. */
@Composable
internal fun AvatarCropDialog(uri: Uri, onClose: () -> Unit, onConfirm: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var center by remember { mutableStateOf(Offset.Zero) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(uri) {
        try { bitmap = decodeAvatar(context, uri); center = Offset(bitmap!!.width / 2f, bitmap!!.height / 2f) }
        catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message ?: "无法读取图片" }
    }
    DisposableEffect(Unit) { onDispose { bitmap?.recycle() } }
    fun cropSide() = minOf(bitmap!!.width, bitmap!!.height).toFloat() / zoom
    fun constrain() {
        val b = bitmap ?: return
        val half = cropSide() / 2f
        center = Offset(center.x.coerceIn(half, b.width - half), center.y.coerceIn(half, b.height - half))
    }
    Dialog(onDismissRequest = { if (!saving) onClose() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("裁剪头像", style = MaterialTheme.typography.titleLarge)
                Text("拖动调整位置，双指缩放；圆框内为头像预览。", style = MaterialTheme.typography.bodySmall)
                val b = bitmap
                if (b != null) Canvas(Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(b) {
                    detectTransformGestures { _, pan, scale, _ ->
                        zoom = (zoom * scale).coerceIn(1f, 5f)
                        val unit = cropSide() / size.width
                        center -= pan * unit
                        constrain()
                    }
                }) {
                    val side = cropSide().toInt().coerceAtLeast(1)
                    val origin = IntOffset((center.x - side / 2f).toInt().coerceIn(0, b.width - side),
                        (center.y - side / 2f).toInt().coerceIn(0, b.height - side))
                    drawImage(b.asImageBitmap(), srcOffset = origin, srcSize = IntSize(side, side),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    drawCircle(Color.White, radius = size.width / 2f - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
                } else if (error == null) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose, enabled = !saving) { Text("取消") }
                    Button(enabled = b != null && !saving, onClick = {
                        saving = true
                        val side = cropSide().toInt().coerceAtLeast(1)
                        val x = (center.x - side / 2f).toInt().coerceIn(0, b!!.width - side)
                        val y = (center.y - side / 2f).toInt().coerceIn(0, b.height - side)
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) {
                                    val crop = android.graphics.Bitmap.createBitmap(b, x, y, side, side)
                                    val scaled = android.graphics.Bitmap.createScaledBitmap(crop, minOf(side, 512), minOf(side, 512), true)
                                    try {
                                        File(context.cacheDir, "avatar-crop.jpg").also { f -> f.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } }
                                    } finally { if (scaled !== b) scaled.recycle(); if (crop !== scaled && crop !== b) crop.recycle() }
                                }
                                onConfirm(Uri.fromFile(file))
                            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message }
                            finally { saving = false }
                        }
                    }) { Text(if (saving) "处理中…" else "使用头像") }
                }
            }
        }
    }
}
