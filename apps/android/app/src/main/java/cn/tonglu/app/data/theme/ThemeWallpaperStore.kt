package cn.tonglu.app.data.theme

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

/**
 * 主题壁纸的本机存储。
 *
 * 与 `StartupAppearanceStore` 同一套思路：用户只选一次，图片解码后**缩小**再压成 JPEG，
 * 存进应用私有目录（`filesDir/theme_wallpaper`），SharedPreferences 只记文件名。
 * 不上传、不导出、不申请相册全盘权限；换图/移除时删旧文件。
 *
 * 🔴 文件名必须过白名单正则再拼路径：SharedPreferences 被改坏时不能拿它去删任意文件。
 */
internal class ThemeWallpaperStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val folder = File(context.filesDir, "theme_wallpaper")

    /** 读取当前壁纸文件；记录损坏/文件缺失时顺手清掉坏记录，返回 null。 */
    fun read(): File? {
        val name = prefs.getString(KEY_FILE, null) ?: return null
        if (!NAME_PATTERN.matches(name)) {
            prefs.edit().remove(KEY_FILE).apply()
            return null
        }
        val file = File(folder, name)
        if (!file.isFile) {
            prefs.edit().remove(KEY_FILE).apply()
            return null
        }
        return file
    }

    /** 保存一张新壁纸；成功后才写记录，失败不留半张图。 */
    fun save(uri: Uri): File {
        if (!folder.isDirectory && !folder.mkdirs()) throw IOException("无法创建壁纸目录")
        val old = read()
        val bitmap = decode(uri)
        val target = File(folder, "${UUID.randomUUID()}.jpg")
        try {
            FileOutputStream(target).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    throw IOException("图片压缩失败")
                }
                output.fd.sync()
            }
            if (!prefs.edit().putString(KEY_FILE, target.name).commit()) {
                throw IOException("壁纸记录未保存")
            }
        } catch (error: Exception) {
            if (target.exists() && !target.delete()) Log.w(TAG, "未完成壁纸清理失败")
            throw error
        } finally {
            bitmap.recycle()
        }
        if (old != null && old != target && !old.delete()) Log.w(TAG, "旧壁纸清理失败")
        return target
    }

    /** 移除壁纸记录并删文件。返回可展示的清理提示（没有就 null）。 */
    fun remove(): String? {
        val old = read()
        if (!prefs.edit().remove(KEY_FILE).commit()) throw IOException("壁纸设置未移除")
        return if (old != null && old.exists() && !old.delete()) "壁纸已移除，但旧图片清理失败" else null
    }

    /** 从私有目录读回壁纸并降到 [maxDimension] 以内，给背景渲染用。 */
    fun decodeStored(file: File, maxDimension: Int = 1280): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("壁纸文件无法解码")
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (max(bounds.outWidth, bounds.outHeight) / inSampleSize > maxDimension) inSampleSize *= 2
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IOException("壁纸文件解码失败")
    }

    /** 相册 Uri → 方向已校正、尺寸受限的 Bitmap。 */
    private fun decode(uri: Uri): Bitmap {
        val mime = context.contentResolver.getType(uri)
        if (mime == "image/gif") throw IOException("壁纸使用静态图片，请选择照片或 PNG")
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                if (info.size.width.toLong() * info.size.height > 80_000_000L) throw IOException("图片尺寸过大")
                val scale = (1920f / max(info.size.width, info.size.height)).coerceAtMost(1f)
                decoder.setTargetSize(
                    max(1, (info.size.width * scale).toInt()),
                    max(1, (info.size.height * scale).toInt()),
                )
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: throw IOException("无法读取所选图片")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth.toLong() * bounds.outHeight > 80_000_000L
        ) {
            throw IOException("图片格式或尺寸不支持")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (max(bounds.outWidth, bounds.outHeight) / inSampleSize > 1920) inSampleSize *= 2
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: throw IOException("图片解码失败")
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

    internal companion object {
        const val PREFS = "tonglu_settings"
        const val KEY_FILE = "theme_wallpaper_file"
        private const val TAG = "TongluTheme"
        private val NAME_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg")
    }
}
