package cn.tonglu.app.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** 系统栏跟随应用主题；弹层使用自己的窗口，不能只改 Activity 的窗口。 */
@Composable
internal fun ThemeSystemBars(dark: Boolean = AppTheme.darkState) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dialogWindow = generateSequence(view.parent) { it.parent }
        .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
    var context = view.context
    while (context is ContextWrapper && context !is Activity) context = context.baseContext
    val window = dialogWindow ?: (context as? Activity)?.window ?: return
    val applyAppearance = {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    SideEffect { applyAppearance() }
    // 从系统安装页、设置页回来时重新应用，防止窗口恢复默认浅色标志。
    DisposableEffect(window, lifecycle, dark) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) applyAppearance()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
