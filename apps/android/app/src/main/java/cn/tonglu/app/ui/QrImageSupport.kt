package cn.tonglu.app.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.detector.Detector
import com.google.zxing.qrcode.decoder.Decoder
import java.io.File

/** 保留原二维码模块，只补齐白色静区；不记录或改写登录数据。 */
internal fun normalizeQr(source: Bitmap): Bitmap? = runCatching {
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(source.width, source.height, pixels)))
    val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    val bits = Detector(image.blackMatrix).detect(hints).bits
    Decoder().decode(bits.clone(), hints)
    val modules = bits.width + 8
    val scale = (640 / modules).coerceIn(2, 16)
    val size = modules * scale
    val output = IntArray(size * size) { android.graphics.Color.WHITE }
    for (y in 0 until bits.height) for (x in 0 until bits.width) if (bits[x, y]) {
        for (dy in 0 until scale) for (dx in 0 until scale) output[((y + 4) * scale + dy) * size + (x + 4) * scale + dx] = android.graphics.Color.BLACK
    }
    Bitmap.createBitmap(output, size, size, Bitmap.Config.ARGB_8888)
}.getOrNull()

private fun saveQr(context: Context, bitmap: Bitmap) {
    check(!bitmap.isRecycled)
    val resolver = context.contentResolver
    val name = "campus_qr_${System.currentTimeMillis()}.png"
    val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "CampusQR")
    var legacyFile: File? = null
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= 29) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CampusQR")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        } else {
            check(folder.isDirectory || folder.mkdirs())
            legacyFile = File(folder, name)
            put(MediaStore.Images.Media.DATA, legacyFile!!.absolutePath)
        }
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建图片")
    try {
        resolver.openOutputStream(uri)?.use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "图片写入失败" }
            it.flush()
        } ?: error("无法写入图片")
        if (Build.VERSION.SDK_INT >= 29) {
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) > 0) { "无法发布图片" }
        } else android.media.MediaScannerConnection.scanFile(context, arrayOf(legacyFile!!.absolutePath), arrayOf("image/png"), null)
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        legacyFile?.delete()
        throw e
    }
}

/** 两个导入页面共用权限恢复与保存流程，IO 不阻塞界面。 */
@Composable
internal fun rememberQrGallerySaver(onStatus: (String) -> Unit): (Bitmap?) -> Unit {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val status by rememberUpdatedState(onStatus)
    var pending by remember { mutableStateOf<Bitmap?>(null) }
    var saving by remember { mutableStateOf(false) }
    val save: (Bitmap) -> Unit = { bitmap ->
        if (!saving) {
            saving = true
            scope.launch {
                val success = withContext(Dispatchers.IO) { runCatching { saveQr(context, bitmap) }.isSuccess }
                saving = false
                status(if (success) "二维码已保存到相册，请及时用企业微信扫码；过期后需要重新加载" else "保存失败，请检查存储空间和相册权限后重试")
            }
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val image = pending; pending = null
        if (allowed && image != null) save(image) else status("未获得相册权限，无法保存二维码")
    }
    return { image ->
        when {
            image == null -> status("尚未识别到有效二维码，请稍等或重新加载")
            saving -> Unit
            Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED -> {
                pending = image; launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            else -> save(image)
        }
    }
}

