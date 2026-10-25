package cn.tonglu.app.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 壁纸的 CPU 侧处理：解码时先缩、模糊只算一次。
 *
 * 为什么不直接用 `Modifier.blur`：它是逐帧的 RenderEffect，全屏壁纸每帧重新采样，
 * 低端机滚动时会掉帧。这里把「缩小 → 盒式模糊」在 IO 线程做一次，界面只画一张静态
 * ImageBitmap；调模糊滑杆时也只重算一张 420px 的小图，不碰原图。
 */
internal object WallpaperRenderer {

    /**
     * 从私有目录读原图；[maxDimension] 上限保证内存不会跟着用户相册里的大图走。
     * 落盘时已经把长边压到 1920，这里用 2048 是为了**别让 inSampleSize 把 1920 的图
     * 又砍半到 960** —— 户主选的原图已经够小了，再砍半在「模糊=0」时会明显发虚。
     */
    fun decode(file: File, maxDimension: Int = 2048): Bitmap {
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

    /**
     * 生成背景位图：
     * - 模糊≈0：缩到 1024px 的清晰图（做背景已经足够，不拿原图铺满屏）；
     * - 有模糊：缩到 420px 再做两遍可分离盒式模糊，最终由 Image 拉伸铺满。
     */
    fun background(source: Bitmap, blur: Float): Bitmap {
        val safeBlur = blur.coerceIn(0f, 1f)
        val target = if (safeBlur < 0.02f) 1024 else 420
        val scaled = scaleDown(source, target)
        if (safeBlur < 0.02f) return scaled
        val radius = (1f + safeBlur * 9f).roundToInt().coerceIn(1, 10)
        val w = scaled.width
        val h = scaled.height
        var pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(2) { pixels = boxBlur(pixels, w, h, radius) }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== source) scaled.recycle()
        return result
    }

    /** 平均相对亮度，用来决定壁纸上的自适应压暗/提亮层。采样 32×32，避免遍历大图。 */
    fun averageLuminance(bitmap: Bitmap): Float {
        val w = 32
        val h = 32
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        var sum = 0f
        for (pixel in pixels) {
            val r = srgb((pixel shr 16) and 255)
            val g = srgb((pixel shr 8) and 255)
            val b = srgb(pixel and 255)
            sum += 0.2126f * r + 0.7152f * g + 0.0722f * b
        }
        return (sum / pixels.size).coerceIn(0f, 1f)
    }

    private fun scaleDown(source: Bitmap, maxDimension: Int): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxDimension) return source
        val scale = maxDimension.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            source,
            max(1, (source.width * scale).roundToInt()),
            max(1, (source.height * scale).roundToInt()),
            true,
        )
        return scaled
    }

    /** 可分离盒式模糊（先横后竖），滑动窗口 O(w*h)，比逐像素 radius² 快一个量级。 */
    private fun boxBlur(source: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val horizontal = IntArray(source.size)
        val vertical = IntArray(source.size)
        val window = radius * 2 + 1
        for (y in 0 until height) {
            var r = 0
            var g = 0
            var b = 0
            for (x in -radius..radius) {
                val c = source[y * width + x.coerceIn(0, width - 1)]
                r += (c shr 16) and 255
                g += (c shr 8) and 255
                b += c and 255
            }
            for (x in 0 until width) {
                horizontal[y * width + x] = (255 shl 24) or ((r / window) shl 16) or
                    ((g / window) shl 8) or (b / window)
                val out = source[y * width + (x - radius).coerceIn(0, width - 1)]
                val next = source[y * width + (x + radius + 1).coerceIn(0, width - 1)]
                r += ((next shr 16) and 255) - ((out shr 16) and 255)
                g += ((next shr 8) and 255) - ((out shr 8) and 255)
                b += (next and 255) - (out and 255)
            }
        }
        for (x in 0 until width) {
            var r = 0
            var g = 0
            var b = 0
            for (y in -radius..radius) {
                val c = horizontal[y.coerceIn(0, height - 1) * width + x]
                r += (c shr 16) and 255
                g += (c shr 8) and 255
                b += c and 255
            }
            for (y in 0 until height) {
                vertical[y * width + x] = (255 shl 24) or ((r / window) shl 16) or
                    ((g / window) shl 8) or (b / window)
                val out = horizontal[(y - radius).coerceIn(0, height - 1) * width + x]
                val next = horizontal[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                r += ((next shr 16) and 255) - ((out shr 16) and 255)
                g += ((next shr 8) and 255) - ((out shr 8) and 255)
                b += (next and 255) - (out and 255)
            }
        }
        return vertical
    }

    private fun srgb(channel: Int): Float {
        val c = channel / 255f
        return if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
}
