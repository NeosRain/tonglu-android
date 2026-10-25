package cn.tonglu.app.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import cn.tonglu.app.data.theme.ThemeWallpaperStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * 主题壁纸的运行时状态（Compose 可观察）+ 只算一次的模糊结果。
 *
 * 生命周期：
 * 1. [load] 在 Activity `setContent` 之前读记录，决定 [ThemeCanvas.wallpaperActive]；
 * 2. 原图在 IO 线程解码，缩到 1280；背景图按模糊档位缩到 1024/420 再模糊；
 * 3. 调模糊只重算背景图，原图缓存在 [sourceBitmap]；调暗化只改一个浮点，不重算位图；
 * 4. 换图/移除会先 invalidate 旧的渲染 token，避免慢 IO 回来覆盖新图。
 */
internal object ThemeWallpaper {

    private const val PREFS = "tonglu_settings"
    private const val KEY_BLUR = "theme_wallpaper_blur"
    private const val KEY_DIM = "theme_wallpaper_dim"
    private const val KEY_GLASS = "theme_wallpaper_glass"
    private const val DEFAULT_BLUR = 0.35f
    private const val DEFAULT_DIM = 0.18f
    private const val DEFAULT_GLASS = 0.55f

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var renderToken = 0
    private var sourceBitmap: Bitmap? = null

    /** 当前持久化的文件名（仅用于 UI 判断是否有壁纸）。 */
    var fileName by mutableStateOf<String?>(null)
        private set

    /** 原图缩略版本，给设置页缩略图用（比模糊图更清楚）。 */
    var sourceImage by mutableStateOf<ImageBitmap?>(null)
        private set

    /** 已模糊/缩放好的背景图。 */
    var image by mutableStateOf<ImageBitmap?>(null)
        private set

    /** 平均亮度（0..1），壁纸压暗层按它自适应。 */
    var luminance by mutableFloatStateOf(0.5f)
        private set

    var blur by mutableFloatStateOf(DEFAULT_BLUR)
        private set

    var dim by mutableFloatStateOf(DEFAULT_DIM)
        private set

    /** 「卡片透光」档位 0..1；0=近实底，1=最透。渲染落在 [ThemeCanvas.cardGlass]。 */
    var glass by mutableFloatStateOf(DEFAULT_GLASS)
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)

    val active: Boolean get() = fileName != null

    /** 启动时读一次；记录损坏就按「没有壁纸」处理，绝不影响主界面启动。 */
    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        blur = runCatching { prefs.getFloat(KEY_BLUR, DEFAULT_BLUR) }
            .getOrDefault(DEFAULT_BLUR).coerceIn(0f, 1f)
        dim = runCatching { prefs.getFloat(KEY_DIM, DEFAULT_DIM) }
            .getOrDefault(DEFAULT_DIM).coerceIn(0f, 1f)
        val glass = runCatching { prefs.getFloat(KEY_GLASS, DEFAULT_GLASS) }
            .getOrDefault(DEFAULT_GLASS).coerceIn(0f, 1f)
        this.glass = glass
        ThemeCanvas.applyCardGlass(glass)
        val file = runCatching { ThemeWallpaperStore(context).read() }.getOrNull()
        fileName = file?.name
        ThemeCanvas.setActive(file != null)
        if (file != null) render(context, file, debounce = 0) else clearBitmaps()
    }

    fun setBlur(context: Context, value: Float) {
        val safe = value.coerceIn(0f, 1f)
        if (abs(safe - blur) < 0.001f) return
        blur = safe
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_BLUR, safe).apply()
        // Keep the user's last drag even while a bitmap is rendering. renderLocked coalesces it.
        if (busy) return
        val file = currentFile(context) ?: return
        render(context, file, debounce = 180)
    }

    fun setDim(context: Context, value: Float) {
        val safe = value.coerceIn(0f, 1f)
        if (abs(safe - dim) < 0.001f) return
        dim = safe
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_DIM, safe).apply()
    }

    /** 拖「卡片透光」滑杆：只改一个浮点并持久化，不重算位图，实时生效。 */
    fun setGlass(context: Context, value: Float) {
        val safe = value.coerceIn(0f, 1f)
        if (abs(safe - glass) < 0.001f) return
        glass = safe
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putFloat(KEY_GLASS, safe).apply()
        ThemeCanvas.applyCardGlass(safe)
    }

    /** 从相册选图：先落盘，再解码/模糊。保存失败保留原壁纸。 */
    fun save(context: Context, uri: Uri) {
        if (busy) return
        val token = ++renderToken
        busy = true
        error = null
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { ThemeWallpaperStore(context).save(uri) }
                if (token != renderToken) return@launch
                sourceBitmap = null
                sourceImage = null
                image = null
                fileName = file.name
                ThemeCanvas.setActive(true)
                renderLocked(context, file, token)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "壁纸保存失败，请重试"
            } finally {
                if (token == renderToken) busy = false
            }
        }
    }

    /** 移除壁纸：记录与文件都删掉，页面容器立即恢复不透明。 */
    fun remove(context: Context) {
        if (busy) return
        renderToken++
        busy = true
        error = null
        scope.launch {
            try {
                val note = withContext(Dispatchers.IO) { ThemeWallpaperStore(context).remove() }
                sourceBitmap = null
                sourceImage = null
                image = null
                fileName = null
                luminance = 0.5f
                ThemeCanvas.setActive(false)
                error = note
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "壁纸移除失败，请重试"
            } finally {
                busy = false
            }
        }
    }

    /**
     * 壁纸上的黑色压暗层：用户滑杆的语义就是「画面更暗」，两套主题都直接压黑。
     * 之后还会叠一层墙纸底色的自适应薄雾，见 [veilAlpha]。
     */
    fun dimOverlayAlpha(): Float = (dim * 0.55f).coerceIn(0f, 0.55f)

    /**
     * 自适应薄雾：把**未压暗**的壁纸亮度夹到文字可读区间。
     * 浅色 → 往页面底色（近白）提；深色 → 往深色 Canvas 压。
     *
     * 🔴 压暗层是**画在薄雾之上**的（见 [ThemeWallpaperHost]）：如果把 dim 也算进这里，
     * 薄雾会把压暗抵消掉，用户拖「暗化」滑杆只看到颜色变灰、亮度不变 —— 那就不叫暗化了。
     * 压暗后的最终亮度仍有下限（浅色约 0.26、深色约 0.04），正文对比度仍在 4.5 以上。
     */
    fun veilAlpha(dark: Boolean): Float {
        val canvasLum = if (dark) 0.0086f else 0.90f
        val target = if (dark) 0.10f else 0.50f
        val base = if (dark) 0.24f else 0.30f
        if (abs(canvasLum - luminance) < 0.005f) return base
        return ((target - luminance) / (canvasLum - luminance)).coerceIn(base, 0.88f)
    }

    private fun render(context: Context, file: File, debounce: Long) {
        val token = ++renderToken
        scope.launch {
            if (debounce > 0) delay(debounce)
            if (token != renderToken) return@launch
            busy = true
            error = null
            try {
                renderLocked(context, file, token)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                error = failure.message ?: "壁纸加载失败"
                if (image == null) {
                    fileName = null
                    ThemeCanvas.setActive(false)
                }
            } finally {
                if (token == renderToken) busy = false
            }
        }
    }

    /** 必须在主线程调用；IO 只包住解码与模糊。 */
    private suspend fun renderLocked(context: Context, file: File, token: Int) {
        while (token == renderToken) {
            val existing = sourceBitmap
            val requestedBlur = blur
            val result = withContext(Dispatchers.IO) {
                val source = existing ?: WallpaperRenderer.decode(file)
                val background = WallpaperRenderer.background(source, requestedBlur)
                RenderedWallpaper(source, background, WallpaperRenderer.averageLuminance(background))
            }
            if (token != renderToken) {
                if (result.background !== result.source) result.background.recycle()
                return
            }
            sourceBitmap = result.source
            if (abs(requestedBlur - blur) >= 0.001f) {
                if (result.background !== result.source) result.background.recycle()
                continue
            }
            sourceImage = result.source.asImageBitmap()
            image = result.background.asImageBitmap()
            luminance = result.luminance
            fileName = file.name
            ThemeCanvas.setActive(true)
            return
        }
    }

    private data class RenderedWallpaper(val source: Bitmap, val background: Bitmap, val luminance: Float)

    private fun currentFile(context: Context): File? =
        runCatching { ThemeWallpaperStore(context).read() }.getOrNull()

    private fun clearBitmaps() {
        sourceBitmap = null
        sourceImage = null
        image = null
        luminance = 0.5f
    }
}
