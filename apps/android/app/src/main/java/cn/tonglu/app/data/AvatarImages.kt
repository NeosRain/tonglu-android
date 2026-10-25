package cn.tonglu.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Decode with sampling on IO; upload only a resized JPEG without location/EXIF metadata. */
internal suspend fun decodeAvatar(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (true) {
            val read = input.read(block); if (read < 0) break
            require(out.size() + read <= 12 * 1024 * 1024) { "原图过大，请选择 12MB 以内图片" }
            out.write(block, 0, read)
        }
        out.toByteArray()
    } ?: error("无法读取图片")
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    require(options.outWidth > 0 && options.outHeight > 0) { "图片格式无法读取" }
    require(options.outWidth.toLong() * options.outHeight <= 100_000_000L) { "原图分辨率过大" }
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2
    options.inJustDecodeBounds = false
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("图片无法解码")
    var bitmap = decoded
    try {
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }
                8 -> setRotate(270f)
            }
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
        bitmap
    } catch (error: Throwable) { bitmap.recycle(); throw error }
}

internal suspend fun avatarPart(context: Context, uri: Uri): MultipartBody.Part = withContext(Dispatchers.IO) {
    var bitmap = decodeAvatar(context, uri)
    try {
        val side = minOf(bitmap.width, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        if (crop !== bitmap) { bitmap.recycle(); bitmap = crop }
        val resized = Bitmap.createScaledBitmap(bitmap, minOf(side, 512), minOf(side, 512), true)
        if (resized !== bitmap) { bitmap.recycle(); bitmap = resized }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        require(out.size() <= 256 * 1024) { "图片压缩失败，请换一张" }
        MultipartBody.Part.createFormData("avatar", "avatar.jpg", out.toByteArray().toRequestBody("image/jpeg".toMediaType()))
    } finally { bitmap.recycle() }
}

internal fun avatarAddress(path: String?): String? = path?.let {
    if (it.startsWith("/api/v1/media/avatars/")) cn.tonglu.app.BuildConfig.API_BASE_URL.toHttpUrl().resolve(it)?.toString() else null
}
