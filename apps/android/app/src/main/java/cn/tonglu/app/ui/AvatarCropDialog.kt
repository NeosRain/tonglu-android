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
internal fun AvatarCropDialog(uri: Uri, onClose: () -> Unit, onConfirm: (Uri) -> Unit, cover: Boolean = false, uniqueFile: Boolean = false) {
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
    val aspect = if (cover) 3 else 1
    fun cropHeight() = (minOf(bitmap!!.width.toFloat() / aspect, bitmap!!.height.toFloat()) / zoom).toInt().coerceAtLeast(1)
    fun cropWidth() = (cropHeight() * aspect).coerceAtMost(bitmap!!.width)
    fun constrain() {
        val b = bitmap ?: return
        val halfWidth = cropWidth() / 2f
        val halfHeight = cropHeight() / 2f
        center = Offset(center.x.coerceIn(halfWidth, b.width - halfWidth), center.y.coerceIn(halfHeight, b.height - halfHeight))
    }
    Dialog(onDismissRequest = { if (!saving) onClose() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (cover) "裁剪背景图（3:1）" else "裁剪头像", style = MaterialTheme.typography.titleLarge)
                Text(if (cover) "拖动调整位置，双指缩放。" else "拖动调整位置，双指缩放；圆框内为头像预览。", style = MaterialTheme.typography.bodySmall)
                val b = bitmap
                if (b != null) Canvas(Modifier.fillMaxWidth().aspectRatio(aspect.toFloat()).pointerInput(b) {
                    detectTransformGestures { _, pan, scale, _ ->
                        zoom = (zoom * scale).coerceIn(1f, 5f)
                        val unit = cropWidth().toFloat() / size.width
                        center -= pan * unit
                        constrain()
                    }
                }) {
                    val width = cropWidth()
                    val height = cropHeight()
                    val origin = IntOffset((center.x - width / 2f).toInt().coerceIn(0, b.width - width),
                        (center.y - height / 2f).toInt().coerceIn(0, b.height - height))
                    drawImage(b.asImageBitmap(), srcOffset = origin, srcSize = IntSize(width, height),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    if (!cover) drawCircle(Color.White, radius = size.width / 2f - 2.dp.toPx(), style = Stroke(2.dp.toPx()))
                } else if (error == null) CircularProgressIndicator()
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose, enabled = !saving) { Text("取消") }
                    Button(enabled = b != null && !saving, onClick = {
                        saving = true
                        val width = cropWidth()
                        val height = cropHeight()
                        val x = (center.x - width / 2f).toInt().coerceIn(0, b!!.width - width)
                        val y = (center.y - height / 2f).toInt().coerceIn(0, b.height - height)
                        scope.launch {
                            try {
                                val file = withContext(Dispatchers.IO) {
                                    val crop = android.graphics.Bitmap.createBitmap(b, x, y, width, height)
                                    val outputHeight = if (cover) 400 else minOf(height, 512)
                                    val scaled = android.graphics.Bitmap.createScaledBitmap(crop, outputHeight * aspect, outputHeight, true)
                                    try {
                                        val target = if (uniqueFile) File.createTempFile("profile-crop-", ".jpg", context.cacheDir)
                                            else File(context.cacheDir, "avatar-crop.jpg")
                                        target.also { f -> f.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) } }
                                    } finally { if (scaled !== b) scaled.recycle(); if (crop !== scaled && crop !== b) crop.recycle() }
                                }
                                onConfirm(Uri.fromFile(file))
                            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; error = e.message }
                            finally { saving = false }
                        }
                    }) { Text(if (saving) "处理中…" else if (cover) "使用背景图" else "使用头像") }
                }
            }
        }
    }
}
