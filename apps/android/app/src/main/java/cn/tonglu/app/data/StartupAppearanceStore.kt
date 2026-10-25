package cn.tonglu.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.math.max

internal data class StartupAppearance(val enabled: Boolean, val customFile: File?)
internal data class StartupArtwork(val sharp: Bitmap, val frosted: Bitmap)

internal fun startupOwnerBucket(owner: String): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

/** 只在本机保存，按身份分桶。图片不可上传，也不读取/导出校园Cookie。所有图片IO调用放后台。 */
internal class StartupAppearanceStore(private val context: Context, owner: String) {
    private val bucket = startupOwnerBucket(owner)
    private val prefs = context.getSharedPreferences("tonglu_startup", Context.MODE_PRIVATE)
    private val folder = File(context.filesDir, "startup_art")

    fun read(): StartupAppearance {
        val name = prefs.getString("image_$bucket", null)
        val file = name?.let {
            if (!Regex("${bucket}_[a-f0-9-]{36}\\.jpg").matches(it))
                throw IOException("启动背景记录损坏，请恢复默认背景")
            File(folder, it).also { image -> if (!image.isFile) throw IOException("启动背景文件缺失，请恢复默认背景") }
        }
        return StartupAppearance(prefs.getBoolean("enabled_$bucket", true), file)
    }

    fun setEnabled(enabled: Boolean) {
        if (!prefs.edit().putBoolean("enabled_$bucket", enabled).commit()) throw IOException("启动动画设置未保存")
    }

    fun saveBackground(uri: Uri): String? {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("无法创建启动背景目录")
        val old = read().customFile
        val bitmap = decode(uri)
        val target = File(folder, "${bucket}_${UUID.randomUUID()}.jpg")
        try {
            FileOutputStream(target).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)) throw IOException("图片压缩失败")
                output.fd.sync()
            }
            if (!prefs.edit().putString("image_$bucket", target.name).commit()) throw IOException("背景记录未保存")
        } catch (error: Exception) {
            if (target.exists() && !target.delete()) Log.w("TongluStartup", "未完成背景文件清理失败")
            throw error
        } finally { bitmap.recycle() }
        return if (old != null && !old.delete()) "背景已更换，但旧图片清理失败" else null
    }

    fun restoreDefault(): String? {
        // 即使记录损坏，也能恢复，且不使用损坏路径执行删除。
        val name = prefs.getString("image_$bucket", null)
        val old = name?.takeIf { Regex("${bucket}_[a-f0-9-]{36}\\.jpg").matches(it) }?.let { File(folder, it) }
        if (!prefs.edit().remove("image_$bucket").commit()) throw IOException("默认背景未恢复")
        return if (old != null && old.exists() && !old.delete()) "默认背景已恢复，但旧图片清理失败" else null
    }

    /**
     * 取启动外观位图。**默认外观没有图片**：返回 null，由 [cn.tonglu.app.ui.StartupScreen]
     * 用代码绘制「液态玻璃方块」（2026-10-15 用户：「不要图片要玻璃方块留下」）。
     *
     * 此前默认会解 `R.drawable.startup_miku`（一张第三方初音玻璃拼贴图，`raw/startup_miku_source.json`
     * 自述「分发许可未核验、仅用于本机调试」）——既带来许可风险，用户看到的「方块玻璃」其实也主要
     * 来自那张图自带的格纹，而不是代码画的格。现在默认路径不依赖任何图片资源。
     *
     * 只有用户在外观页自选了照片时才解码（那些照片只存本机、按身份分桶）。
     */
    fun artwork(config: StartupAppearance = read()): StartupArtwork? {
        val custom = config.customFile ?: return null
        val sharp = BitmapFactory.decodeFile(custom.absolutePath)
            ?: throw IOException("自定义启动图片无法解码，请恢复默认背景")
        // 小图上两次盒式卷积生成磨砂采样，单画布复用；不为每块创建模糊RenderEffect。
        val small = Bitmap.createScaledBitmap(sharp, max(1, sharp.width / 8), max(1, sharp.height / 8), true)
        val w = small.width; val h = small.height
        var pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(2) {
            val output = IntArray(pixels.size)
            for (y in 0 until h) for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var n = 0
                for (dy in -2..2) for (dx in -2..2) {
                    val c = pixels[(y + dy).coerceIn(0, h - 1) * w + (x + dx).coerceIn(0, w - 1)]
                    r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++
                }
                output[y * w + x] = (255 shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
            pixels = output
        }
        val frost = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        if (small !== sharp) small.recycle()
        return StartupArtwork(sharp, frost)
    }

    private fun decode(uri: Uri): Bitmap {
        val mime = context.contentResolver.getType(uri)
        if (mime == "image/gif") throw IOException("启动背景使用静态图片，请选择照片或PNG")
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                if (info.size.width.toLong() * info.size.height > 80_000_000L) throw IOException("图片尺寸过大")
                val scale = (1600f / max(info.size.width, info.size.height)).coerceAtMost(1f)
                decoder.setTargetSize(max(1, (info.size.width * scale).toInt()), max(1, (info.size.height * scale).toInt()))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); Unit }
            ?: throw IOException("无法读取所选图片")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 80_000_000L)
            throw IOException("图片格式或尺寸不支持")
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (max(bounds.outWidth, bounds.outHeight) / inSampleSize > 1600) inSampleSize *= 2
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("图片解码失败")
        if (mime != "image/jpeg") return decoded
        try {
            val orientation = context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: throw IOException("无法读取照片方向")
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
                else -> return decoded
            }
            val corrected = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (corrected !== decoded) decoded.recycle()
            return corrected
        } catch (error: Exception) {
            decoded.recycle()
            throw error
        }
    }
}
