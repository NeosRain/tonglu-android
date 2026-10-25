package cn.tonglu.app.data.community

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 有界读取，静图降采样/压缩，GIF保留原字节交服务端实测帧数和时长。 */
internal suspend fun readEmojiFile(context: Context, uri: Uri): Pair<ByteArray, String> = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= 12 * 1024 * 1024) { "原图片超过12MB，请先缩小图片" }
            out.write(buffer, 0, count)
        }
        out.toByteArray()
    } ?: error("无法读取这张图片，请重新选择")
    val mime = sniffEmojiMime(bytes)
    if (mime == "image/gif") {
        validateEmojiUpload(bytes.size, mime)
        return@withContext bytes to mime
    }
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    require(options.outWidth > 0 && options.outHeight > 0) { "图片无法解码" }
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2
    options.inJustDecodeBounds = false
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("图片无法解码")
    try {
        val out = ByteArrayOutputStream()
        val alpha = bitmap.hasAlpha()
        check(bitmap.compress(if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 85, out)) { "图片压缩失败" }
        val compressed = out.toByteArray()
        val resultMime = if (alpha) "image/png" else "image/jpeg"
        validateEmojiUpload(compressed.size, resultMime)
        compressed to resultMime
    } finally { bitmap.recycle() }
}

internal fun sniffEmojiMime(data: ByteArray): String = when {
    data.size >= 6 && String(data, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
    data.size >= 8 && data.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
    data.size >= 3 && data[0] == 0xff.toByte() && data[1] == 0xd8.toByte() && data[2] == 0xff.toByte() -> "image/jpeg"
    data.size >= 12 && String(data, 0, 4, Charsets.US_ASCII) == "RIFF" && String(data, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
    else -> error("只支持有效的 JPG、PNG、WebP 或 GIF 文件")
}
