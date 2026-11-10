package cn.tonglu.app.ui.glass

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import cn.tonglu.app.ui.AppColors
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported

/** Background-only sibling layer. It must never contain the glass that samples it. */
internal val LocalGlassBaseBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Transparent text layers need the actual page background underneath the blurred copy. */
@Composable
internal fun rememberBottomBackdrop(content: Backdrop): Backdrop {
    val canvas = AppColors.CanvasOpaque
    val fallback = rememberCanvasBackdrop { drawRect(canvas) }
    return rememberCombinedBackdrop(LocalGlassBaseBackdrop.current ?: fallback, content)
}

/** One material for action bars; navigation and comment capsules use the same endpoints. */
@Composable
internal fun GlassBottomActionPanel(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    style: GlassPanelStyle = GlassPanelStyle.Bottom,
    shape: Shape = RoundedCornerShape(GlassBottomTokens.CornerDp.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val supported = remember { Build.VERSION.SDK_INT >= 33 && isRuntimeShaderSupported() }
    CompositionLocalProvider(LocalContentColor provides AppColors.TextPrimary) {
        if (supported) GlassActionPanel(rememberBottomBackdrop(backdrop), modifier, style = style, shape = shape, content = content)
        else Surface(modifier, shape = shape,
            color = if (GlassTokens.DarkGlass) GlassTokens.ContainerDark else GlassTokens.ContainerLight,
            shadowElevation = 4.dp) { Column(content = content) }
    }
}
