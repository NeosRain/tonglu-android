package cn.tonglu.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** 仅在工作线程调用；预览与上传共用同一份已纠正方向、去除元数据的图片。 */
data class PreparedProofPhoto(val jpeg: ByteArray, val preview: Bitmap)

object ProofPhoto {
    fun prepare(context: Context, uri: Uri): PreparedProofPhoto {
        val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = stream.read(buffer)
                if (size < 0) break
                require(output.size() + size <= 12 * 1024 * 1024) { "原图超过 12MB，请选择较小的照片" }
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        } ?: error("照片无法读取，请重新选择")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "请选择有效图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1800) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("照片无法解码")
        val orientation = runCatching { ByteArrayInputStream(raw).use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        val upright = try { if (matrix.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true) }
        catch (error: Throwable) { decoded.recycle(); throw error }
        if (upright !== decoded) decoded.recycle()
        try {
            val jpeg = ByteArrayOutputStream().use { output ->
                check(upright.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "照片处理失败" }
                output.toByteArray().also { require(it.size <= 5 * 1024 * 1024) { "照片过大，请缩小后重试" } }
            }
            // 预览使用最终 JPEG，让用户看到实际提交的图片，避免压缩前后观感不一致。
            val preview = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("照片预览失败")
            return PreparedProofPhoto(jpeg, preview)
        } finally { upright.recycle() }
    }
}
