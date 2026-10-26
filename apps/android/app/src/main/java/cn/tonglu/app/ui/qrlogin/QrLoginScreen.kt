package cn.tonglu.app.ui.qrlogin

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import cn.tonglu.app.data.AppViewModel
import cn.tonglu.app.data.QrTicketDto
import cn.tonglu.app.notify.DeadlineReminder
import cn.tonglu.app.ui.AppColors
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private const val TAG = "TongluQrLogin"

/**
 * PC 扫码登录（docs：网页发起 → App 扫码 → 本页确认 → 网页领取登录态）。
 *
 * 设计口径：
 *  - 相机只做取景解码，不拍不存；解码用工程里已有的 zxing core（不引 mlkit）。
 *  - token 容忍整串 URI 与裸 token：切给后端做归一化，别在端上写第二份解析规则。
 *  - 确认页展示**发起方 UA** 与剩余秒数 —— 用户要能回答「这是我在用的那台电脑吗」；
 *    这一步是扫码登录唯一的人工防线，绝不能做成「扫了就过」。
 *  - 时间显示不引 java.time（工程没开 desugaring），复用 DeadlineReminder 的手写解析。
 */
@Composable
internal fun QrLoginScreen(
    viewModel: AppViewModel,
    onClose: () -> Unit,
) {
    var phase by remember { mutableStateOf<Phase>(Phase.Scan) }
    val scope = rememberCoroutineScope()

    when (val p = phase) {
        is Phase.Scan -> ScanPhase(
            onDecoded = { raw ->
                phase = Phase.Loading(raw)
                scope.launch {
                    viewModel.qrLoginTicket(raw).fold(
                        onSuccess = { ticket ->
                            phase = if (ticket.status == "PENDING") Phase.Confirm(raw, ticket)
                            else Phase.Error("这个码已被使用或已过期，请在网页上刷新二维码后重新扫", retryable = true)
                        },
                        onFailure = { e ->
                            phase = Phase.Error(friendly(e), retryable = true)
                        },
                    )
                }
            },
            onClose = onClose,
        )
        is Phase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        is Phase.Confirm -> ConfirmPhase(
            ticket = p.ticket,
            onConfirm = {
                scope.launch {
                    viewModel.qrLoginConfirm(p.raw).fold(
                        onSuccess = { phase = Phase.Success },
                        onFailure = { e -> phase = Phase.Error(friendly(e), retryable = true) },
                    )
                }
            },
            onCancel = onClose,
        )
        is Phase.Error -> ErrorPhase(p.message, retryable = p.retryable, onRetry = { phase = Phase.Scan }, onClose = onClose)
        is Phase.Success -> SuccessPhase(onClose = onClose)
    }
}

private sealed interface Phase {
    data object Scan : Phase
    data class Loading(val raw: String) : Phase
    data class Confirm(val raw: String, val ticket: QrTicketDto) : Phase
    data class Error(val message: String, val retryable: Boolean) : Phase
    data object Success : Phase
}

private fun friendly(e: Throwable): String = when {
    e is retrofit2.HttpException && e.code() == 400 -> "二维码无效或已过期，请在网页上刷新后重新扫"
    e is retrofit2.HttpException && e.code() == 401 -> "登录状态已失效，请重新登录后再试"
    else -> "网络异常，请稍后重试"
}

// ── 扫码 ─────────────────────────────────────────────────────────────────

@Composable
private fun ScanPhase(onDecoded: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Column(Modifier.fillMaxSize().background(AppColors.Canvas)) {
        Spacer(Modifier.height(46.dp))
        Text(
            "扫一扫登录电脑",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(
            "对准网页登录页上的二维码；扫完请在手机上确认",
            color = AppColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (granted) {
                CameraQrView(onDecoded = onDecoded)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("需要相机权限才能扫码", color = AppColors.TextMuted)
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("授权相机") }
                }
            }
        }
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        ) { Text("取消") }
    }
}

@Composable
private fun CameraQrView(onDecoded: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var bindError by remember { mutableStateOf<String?>(null) }
    // 解码在单线程池跑；decodeWithState 不是线程安全的，一个池一个 reader 正好
    val executor = remember { Executors.newSingleThreadExecutor() }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
            ))
        }
    }
    // 命中一次就停：onDecoded 会切走界面，这里只防同一帧/连帧重复回调
    var done by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bindError != null) {
            Text(bindError!!, color = AppColors.TextMuted)
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        try {
                            val provider = future.get()
                            val preview = Preview.Builder().build().also {
                                it.surfaceProvider = previewView.surfaceProvider
                            }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysis.setAnalyzer(executor) { proxy ->
                                // 🔴 分析线程上抛异常会连带**整个进程**被 force finish（S135 就是这么炸的）。
                                // 解码失败只该丢这一帧 —— 但要留痕，不许静默吞。
                                val raw = try {
                                    decodeQr(reader, proxy)
                                } catch (e: Exception) {
                                    Log.w(TAG, "扫码解码丢帧", e)
                                    null
                                } finally {
                                    proxy.close()
                                }
                                if (raw != null && !done) {
                                    done = true
                                    ContextCompat.getMainExecutor(ctx).execute { onDecoded(raw) }
                                }
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        } catch (e: Exception) {
                            bindError = "相机不可用：${e.message ?: "初始化失败"}"
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
            )
            // 取景框提示条
            Text(
                "将二维码放入框内",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 把相机给的 Y 平面**转正**，返回 (新字节, 新宽, 新高)。
 *
 * 🔴 绝不能走 zxing 的 `LuminanceSource.rotateCounterClockwise()`：
 *    `PlanarYUVLuminanceSource` 没有实现旋转，基类直接抛
 *    `UnsupportedOperationException: This luminance source does not support rotation by 90 degrees.`
 *    而它在 CameraX 的分析线程上抛，没人接 → 进程被 force finish。
 *    用户看到的是「一进扫码页就被弹回桌面」，很容易误判成「相机不可用」。
 *    （走查 S135 抓到：原 QrLoginScreen.kt:267）
 */
internal fun uprightYPlane(src: ByteArray, width: Int, height: Int, degrees: Int): Triple<ByteArray, Int, Int> {
    val d = ((degrees % 360) + 360) % 360
    if (d == 0) return Triple(src, width, height)
    val out = ByteArray(src.size)
    when (d) {
        // 逆时针 90：源 (x,y) → 目标 (y, width-1-x)，目标尺寸 height × width
        90 -> for (y in 0 until height) for (x in 0 until width) out[(width - 1 - x) * height + y] = src[y * width + x]
        // 顺时针 90（= 逆时针 270）：源 (x,y) → 目标 (height-1-y, x)
        270 -> for (y in 0 until height) for (x in 0 until width) out[x * height + (height - 1 - y)] = src[y * width + x]
        // 180：首尾对调
        180 -> for (i in src.indices) out[src.size - 1 - i] = src[i]
        else -> return Triple(src, width, height)
    }
    return if (d == 180) Triple(out, width, height) else Triple(out, height, width)
}

/**
 * 剥掉 Y 平面的行填充：`rowStride` 常大于 `width`，把整段缓冲直接当 width×height
 * 用会逐行错位（图像越往下越歪），所以按行拷贝。
 */
internal fun stripRowPadding(buffer: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int): ByteArray {
    val out = ByteArray(width * height)
    if (rowStride <= width && buffer.remaining() >= width * height) {
        buffer.rewind()
        buffer.get(out, 0, width * height)
        return out
    }
    val row = ByteArray(rowStride)
    buffer.rewind()
    for (r in 0 until height) {
        val n = minOf(rowStride, buffer.remaining())
        if (n <= 0) break
        buffer.get(row, 0, n)
        System.arraycopy(row, 0, out, r * width, minOf(width, n))
    }
    return out
}

/** YUV → zxing 亮度源解码；非 QR / 无结果返回 null（帧率远高于人扫码速度，静默丢弃即可） */
private fun decodeQr(reader: MultiFormatReader, image: ImageProxy): String? {
    if (image.format != android.graphics.ImageFormat.YUV_420_888) return null
    val w = image.width
    val h = image.height
    val y = stripRowPadding(image.planes[0].buffer, w, h, image.planes[0].rowStride)
    val (up, uw, uh) = uprightYPlane(y, w, h, image.imageInfo.rotationDegrees)
    val source = PlanarYUVLuminanceSource(up, uw, uh, 0, 0, uw, uh, false)
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: NotFoundException) {
        null
    } finally {
        reader.reset()
    }
}

// ── 确认 ─────────────────────────────────────────────────────────────────

/** 从 UA 里挑一句人能看懂的设备描述；解析不出就原样截短，绝不自己编。 */
internal fun uaSummary(ua: String?): String {
    if (ua.isNullOrBlank()) return "未知设备（网页未提供 UA）"
    // 🔴 iPhone/iPad 的 UA 里带着「like Mac OS X」—— Mac 的判断必须排在苹果移动设备**后面**，
    // 否则 iPhone 会被认成 Mac 电脑（单测 QrLoginUaSummaryTest 钉住这个顺序）。
    val os = when {
        ua.contains("Windows", true) -> "Windows 电脑"
        ua.contains("iPhone", true) || ua.contains("iPad", true) -> "苹果设备"
        ua.contains("Mac OS", true) || ua.contains("Macintosh", true) -> "Mac 电脑"
        ua.contains("Android", true) -> "安卓设备"
        ua.contains("Linux", true) -> "Linux 设备"
        else -> "未知系统"
    }
    val browser = when {
        ua.contains("Edg/", true) -> "Edge"
        ua.contains("OPR/", true) || ua.contains("Opera", true) -> "Opera"
        ua.contains("Firefox", true) -> "Firefox"
        ua.contains("Chrome/", true) -> "Chrome"
        ua.contains("Safari/", true) -> "Safari"
        else -> null
    }
    return if (browser == null) os else "$os · $browser"
}

@Composable
private fun ConfirmPhase(ticket: QrTicketDto, onConfirm: () -> Unit, onCancel: () -> Unit) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ticket) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val expiresMs = ticket.expiresAt?.let { DeadlineReminder.isoToEpochMillis(it) }
    val remainSec = expiresMs?.let { ((it - nowMs) / 1000).coerceAtLeast(0) }
    val expired = remainSec != null && remainSec <= 0L

    Column(
        Modifier.fillMaxSize().background(AppColors.Canvas).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        Text("确认登录电脑？", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "确认后，这台设备将登录你的苔米厸账号",
            color = AppColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp),
        )

        Column(
            Modifier.fillMaxWidth().padding(top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoRow("发起方", uaSummary(ticket.userAgent))
            InfoRow(
                "有效期",
                when {
                    remainSec == null -> "3 分钟内有效"
                    expired -> "已过期"
                    else -> "剩余 ${remainSec / 60} 分 ${remainSec % 60} 秒"
                },
            )
        }

        if (expired) {
            Spacer(Modifier.height(20.dp))
            Text("二维码已过期，请回到电脑上刷新后重新扫", color = AppColors.Danger, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("知道了") }
        } else {
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Accent),
            ) { Text("确认登录") }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("取消") }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(
        Modifier.fillMaxWidth().background(AppColors.Surface, RoundedCornerShape(14.dp)).padding(14.dp),
    ) {
        Text(label, color = AppColors.TextMuted, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

// ── 结果 ─────────────────────────────────────────────────────────────────

@Composable
private fun ErrorPhase(message: String, retryable: Boolean, onRetry: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(AppColors.Canvas).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("扫不了这个码", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(message, color = AppColors.TextMuted, modifier = Modifier.padding(top = 10.dp))
        Spacer(Modifier.height(24.dp))
        if (retryable) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("重新扫码") }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("关闭") }
    }
}

@Composable
private fun SuccessPhase(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(AppColors.Canvas).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("✓", style = MaterialTheme.typography.displayMedium, color = AppColors.Accent)
        Text("电脑已登录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "回到电脑上看一眼就生效了",
            color = AppColors.TextMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("完成") }
    }
}
