package cn.tonglu.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import android.provider.Settings
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.lifecycleScope
import cn.tonglu.app.R
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.AppTheme
import cn.tonglu.app.ui.ThemeSystemBars
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Launcher shortcut: choose a real provider, then let the launcher ask for confirmation. */
class WidgetPickerActivity : ComponentActivity() {
    private var requesting by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var status by mutableStateOf<String?>(null)
    private var token: String? = null
    private var pendingKind: WidgetKind? = null
    private var requestedAt = 0L
    private var waitJob: Job? = null
    private var beforeIds: IntArray? = null
    private val resultListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> checkResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppTheme.syncFrom(this)
        setTheme(android.R.style.Theme_Material_NoActionBar)
        super.onCreate(savedInstanceState)
        token = savedInstanceState?.getString("pin-token")
        pendingKind = WidgetKind.entries.firstOrNull { it.name == savedInstanceState?.getString("pin-kind") }
        requestedAt = savedInstanceState?.getLong("pin-at") ?: 0L
        beforeIds = savedInstanceState?.getIntArray("pin-before-ids")
        requesting = token != null && pendingKind != null
        window.setBackgroundDrawable(ColorDrawable(AppColors.Canvas.toArgb()))
        enableEdgeToEdge()
        val manager = AppWidgetManager.getInstance(this)
        val supported = Build.VERSION.SDK_INT >= 26 && manager.isRequestPinAppWidgetSupported
        setContent {
            val base = if (AppTheme.darkState) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = base.copy(
                primary = AppColors.Accent, onPrimary = AppColors.OnAccent,
                background = AppColors.Canvas, onBackground = AppColors.TextPrimary,
                surface = AppColors.SurfaceOpaque, onSurface = AppColors.TextPrimary,
                onSurfaceVariant = AppColors.TextSecondary, outline = AppColors.Divider,
            )) {
                CompositionLocalProvider(LocalContentColor provides AppColors.TextPrimary) {
                    ThemeSystemBars()
                    WidgetPickerScreen(supported, requesting, error, status, ::finish,
                        onHome = ::openHome, onSettings = ::openSettings) { kind -> pin(manager, kind) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        WidgetPinResult.store(this).registerOnSharedPreferenceChangeListener(resultListener)
        checkResult()
        if (requesting) awaitConfirmation()
    }

    override fun onStop() {
        WidgetPinResult.store(this).unregisterOnSharedPreferenceChangeListener(resultListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        checkResult()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pin-token", token)
        outState.putString("pin-kind", pendingKind?.name)
        outState.putLong("pin-at", requestedAt)
        beforeIds?.let { outState.putIntArray("pin-before-ids", it) }
        super.onSaveInstanceState(outState)
    }

    private fun checkResult(): Boolean {
        val token = token ?: return false
        val kind = pendingKind ?: return false
        return try {
            val manager = AppWidgetManager.getInstance(this)
            val provider = ComponentName(this, kind.receiver)
            // HyperOS may bind successfully without delivering the optional callback.
            // An existing widget is never evidence: require a newly bound ID of this provider.
            val newBinding = beforeIds?.let { before ->
                manager.getAppWidgetIds(provider).any { id ->
                    id !in before && manager.getAppWidgetInfo(id)?.provider == provider
                }
            } ?: false
            if (!WidgetPinResult.confirmed(this, token, kind) && !newBinding) false else {
                requesting = false
                error = null
                status = "${kind.title}已添加到桌面"
                this.token = null
                pendingKind = null
                beforeIds = null
                waitJob?.cancel()
                true
            }
        } catch (bad: RuntimeException) {
            Log.w("WidgetPin", "添加结果核对失败：${bad.javaClass.simpleName}")
            false
        }
    }

    private fun awaitConfirmation() {
        waitJob?.cancel()
        waitJob = lifecycleScope.launch {
            val remaining = (8_000L - (SystemClock.elapsedRealtime() - requestedAt)).coerceIn(0L, 8_000L)
            val deadline = SystemClock.elapsedRealtime() + remaining
            while (SystemClock.elapsedRealtime() < deadline) {
                if (checkResult()) return@launch
                delay(400)
            }
            if (!checkResult()) {
                requesting = false
                status = null
                // Keep this request identity: the user may confirm the system card later.
                error = if (Build.MANUFACTURER.equals("Xiaomi", true) || Build.MANUFACTURER.equals("Redmi", true))
                    "未完成添加。若没有弹出系统卡片，请在应用设置 → 权限管理 → 其他权限中允许「桌面快捷方式」，再重试。"
                else "尚未收到桌面确认。可重试，或从桌面的小组件列表添加。"
            }
        }
    }

    private fun pin(manager: AppWidgetManager, kind: WidgetKind) {
        if (requesting || Build.VERSION.SDK_INT < 26 || !manager.isRequestPinAppWidgetSupported) return
        requesting = true
        error = null
        status = "等待桌面确认添加…"
        val requestToken = UUID.randomUUID().toString()
        token = requestToken
        pendingKind = kind
        requestedAt = SystemClock.elapsedRealtime()
        try {
            beforeIds = manager.getAppWidgetIds(ComponentName(this, kind.receiver))
            val preview = if (kind == WidgetKind.Week) RemoteViews(packageName, R.layout.widget_week_preview)
                else RemoteViews(packageName, R.layout.widget_pin_preview).apply {
                    setTextViewText(R.id.widget_pin_title, kind.title)
                    setTextViewText(R.id.widget_pin_description, kind.summary)
                    setImageViewResource(R.id.widget_pin_image, if (kind == WidgetKind.Today)
                        R.drawable.ic_widget_preview else R.drawable.ic_calendar_widget_preview)
                }
            val extras = Bundle().apply { putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, preview) }
            if (manager.requestPinAppWidget(ComponentName(this, kind.receiver), extras,
                    WidgetPinResult.callback(this, kind, requestToken))) {
                // Keep the visible Activity alive while OEM launchers create their confirmation UI.
                awaitConfirmation()
            } else {
                error = "桌面未接受请求，请长按桌面空白处，从小组件列表添加同路。"
                requesting = false
                status = null
                token = null
                pendingKind = null
                beforeIds = null
            }
        } catch (bad: RuntimeException) {
            Log.w("WidgetPicker", "桌面未能添加小组件", bad)
            error = "暂时无法添加，请从桌面的小组件列表添加同路。"
            requesting = false
            status = null
            token = null
            pendingKind = null
            beforeIds = null
        }
    }

    private fun openHome() {
        try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }
        catch (error: RuntimeException) {
            Log.w("WidgetPin", "桌面入口不可用：${error.javaClass.simpleName}")
            this.error = "请按系统主页键返回桌面，再长按空白处打开小组件列表。"
        }
    }

    private fun openSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (error: RuntimeException) {
            Log.w("WidgetPin", "应用设置不可用：${error.javaClass.simpleName}")
            this.error = "请到系统设置的应用管理中打开同路。"
        }
    }
}
