package cn.tonglu.app.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import top.yukonga.miuix.kmp.theme.LocalContentColor as MiuixContentColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.AppTheme

/**
 * 根层壁纸宿主：只负责画「壁纸 + 压暗 + 自适应薄雾」，内容由调用方给。
 *
 * 🔴 这里绝不逐帧做 blur：壁纸在 [ThemeWallpaper] 里已经模糊成一张小图，
 * 本 Composable 只画静态 ImageBitmap。壁纸未就绪时退回不透明页面底色，不闪白。
 */
@Composable
internal fun ThemeWallpaperHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val dark = AppTheme.darkState
    val image = ThemeWallpaper.image
    val active = ThemeWallpaper.active
    val dimAlpha = ThemeWallpaper.dimOverlayAlpha()
    val veilAlpha = ThemeWallpaper.veilAlpha(dark)
    val veil = AppColors.CanvasOpaque

    Box(modifier.background(AppColors.CanvasOpaque)) {
        if (active && image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // 先薄雾（保可读），再压暗（用户滑杆真的会让画面变暗，而不是被薄雾抵消）。
            Box(Modifier.fillMaxSize().background(veil.copy(alpha = veilAlpha)))
            if (dimAlpha > 0.001f) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimAlpha)))
            }
        }
        // Miuix Text reads its own content-color local, independent of Material3's local.
        // The root wallpaper host is also the root of all app pages and dialogs.
        CompositionLocalProvider(MiuixContentColor provides AppColors.TextPrimary) {
            content()
        }
    }
}
