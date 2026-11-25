package cn.tonglu.app.ui.books

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.tonglu.app.data.books.normalizeBookIsbn
import cn.tonglu.app.ui.AppColors
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutionException

@Composable
internal fun BookIsbnScanner(onBack: () -> Unit, onIsbn: (String) -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    BooksPage("扫 ISBN", onBack) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (granted) {
                Text("将书背面的 ISBN 条码放入取景框", color = AppColors.TextSecondary)
                BookBarcodePreview(onIsbn)
            } else {
                Text("开启相机权限后可扫描 ISBN", color = AppColors.TextSecondary)
                TextButton(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text("授权相机") }
            }
            TextButton(onClick = onBack) { Text("手动输入 ISBN") }
        }
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun BookBarcodePreview(onIsbn: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val finish by rememberUpdatedState(onIsbn)
    var error by remember { mutableStateOf<String?>(null) }
    val view = remember(context) {
        PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(300.dp))
    BookErrorText(error)

    DisposableEffect(context, lifecycle, view) {
        val executor = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_EAN_13).build(),
        )
        var provider: ProcessCameraProvider? = null
        // CameraX 和 Task 的回调都在主线程交接，扫码页退出后不再提交结果。
        var disposed = false
        var processing = false
        var done = false

        fun fail(cause: Throwable) {
            Log.w("TongluBooks", "ISBN 扫码失败（${cause.javaClass.simpleName}）")
            if (!disposed) {
                done = true
                error = "相机暂不可用，请返回手动输入 ISBN"
            }
        }

        analysis.setAnalyzer(executor) { frame ->
            val image = frame.image
            if (disposed || done || image == null) {
                frame.close()
            } else {
                processing = true
                try {
                    scanner.process(InputImage.fromMediaImage(image, frame.imageInfo.rotationDegrees))
                        .addOnCompleteListener(executor) { task ->
                            frame.close()
                            processing = false
                            if (disposed) {
                                scanner.close()
                            } else if (!task.isSuccessful) {
                                fail(task.exception ?: IllegalStateException("条码识别任务已取消"))
                            } else {
                                val isbn = task.result.firstNotNullOfOrNull { it.rawValue?.let(::normalizeBookIsbn) }
                                if (isbn != null) {
                                    done = true
                                    finish(isbn)
                                }
                            }
                        }
                } catch (cause: RuntimeException) {
                    frame.close()
                    processing = false
                    fail(cause)
                }
            }
        }
        future.addListener({
            if (!disposed) {
                try {
                    provider = future.get().also {
                        it.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }
                } catch (cause: ExecutionException) {
                    fail(cause.cause ?: cause)
                } catch (cause: RuntimeException) {
                    fail(cause)
                }
            }
        }, executor)
        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            if (!processing) scanner.close()
        }
    }
}
