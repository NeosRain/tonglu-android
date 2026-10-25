package cn.tonglu.app.widget

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.background
import androidx.glance.color.ColorProvider as DayNightColorProvider

/** 按组件种类保存：现有桌面实例与添加页共用，默认保持不透明。 */
internal data class WidgetAppearance(val opacityPercent: Int = 100, val radiusDp: Int = 20) {
    fun normalized() = copy(opacityPercent = opacityPercent.coerceIn(85, 100), radiusDp = radiusDp.coerceIn(12, 28))
    val alpha: Float get() = normalized().opacityPercent / 100f
}

internal object WidgetAppearanceStore {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("widget_appearance", Context.MODE_PRIVATE)
    fun read(context: Context, kind: String): WidgetAppearance {
        val prefs = prefs(context)
        return WidgetAppearance(prefs.getInt("opacity.$kind", 100), prefs.getInt("radius.$kind", 20)).normalized()
    }
    fun save(context: Context, kind: String, style: WidgetAppearance) {
        require(kind in setOf("Calendar", "Today", "Week"))
        val safe = style.normalized()
        check(prefs(context).edit().putInt("opacity.$kind", safe.opacityPercent).putInt("radius.$kind", safe.radiusDp).commit()) { "外观保存失败，请重试" }
    }
    @Composable fun observe(context: Context, kind: String): WidgetAppearance {
        var value by remember(context, kind) { mutableStateOf(read(context, kind)) }
        DisposableEffect(context, kind) {
            val preferences = prefs(context)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "opacity.$kind" || key == "radius.$kind") value = read(context, kind)
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            value = read(context, kind)
            onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }
        return value
    }
}

@Composable
internal fun GlanceModifier.widgetAppearance(context: Context, kind: String): GlanceModifier {
    val style = WidgetAppearanceStore.observe(context, kind)
    val surface = GlanceTheme.colors.surface
    fun color(night: Int) = surface.getColor(context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
    })).copy(alpha = style.alpha)
    // 保留 RemoteViews 自带的浅深配色，不能把当前主题解析成一个固定色后交给桌面。
    val background = if (style.opacityPercent == 100) surface else DayNightColorProvider(
        color(Configuration.UI_MODE_NIGHT_NO), color(Configuration.UI_MODE_NIGHT_YES),
    )
    return background(background).widgetRounded(style.radiusDp.dp)
}
