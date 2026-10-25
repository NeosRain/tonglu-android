package cn.tonglu.app.widget

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.currentState
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import cn.tonglu.app.data.SessionStore
import java.util.Calendar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Selection belongs to this widget AND owner; it never changes the application's selected week. */
internal object WidgetNavigation {
    val kind = ActionParameters.Key<String>("schedule-kind")
    val step = ActionParameters.Key<Int>("schedule-step")
    private fun key(context: Context) = intPreferencesKey("date-offset@${SessionStore.currentOwner(context)}")
    private val revision = longPreferencesKey("schedule-revision")

    data class Selection(val owner: String, val dayOffset: Int, val revision: Long)

    private fun selection(context: Context, prefs: Preferences): Selection {
        val owner = SessionStore.currentOwner(context)
        return Selection(owner, prefs[intPreferencesKey("date-offset@$owner")] ?: 0, prefs[revision] ?: 0L)
    }

    suspend fun selection(context: Context, id: GlanceId): Selection =
        selection(context, getAppWidgetState(context, PreferencesGlanceStateDefinition, id))

    suspend fun invalidate(context: Context, id: GlanceId) {
        updateAppWidgetState(context, id) { it[revision] = (it[revision] ?: 0L) + 1L }
    }

    suspend fun offset(context: Context, id: GlanceId): Int =
        getAppWidgetState(context, PreferencesGlanceStateDefinition, id)[key(context)] ?: 0

    fun date(offset: Int, now: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_MONTH, offset)
    }.timeInMillis

    suspend fun move(context: Context, id: GlanceId, step: Int) {
        updateAppWidgetState(context, id) { prefs ->
            val key = key(context)
            prefs[key] = if (step == 0) 0 else ((prefs[key] ?: 0) + step).coerceIn(-364, 364)
            prefs[revision] = (prefs[revision] ?: 0L) + 1L
        }
    }

    /** update() does NOT restart provideGlance while its session is alive. Observe LocalState. */
    @Composable
    fun <T> Observe(context: Context, initial: Selection, initialData: T,
                    load: suspend (Long) -> T, content: @Composable (T, Int) -> Unit) {
        val requested = selection(context, currentState<Preferences>())
        var loaded by remember { mutableStateOf(initial to initialData) }
        var failed by remember { mutableStateOf(false) }
        LaunchedEffect(requested) {
            if (loaded.first == requested) return@LaunchedEffect
            failed = false
            try {
                val data = withContext(Dispatchers.IO) { load(date(requested.dayOffset)) }
                loaded = requested to data
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("WidgetData", "课表更新失败：${error.javaClass.simpleName}")
                failed = true
            }
        }
        when {
            failed -> Text("课表更新失败，打开苔米林重试", modifier = GlanceModifier.padding(12.dp)
                .clickable(androidx.glance.appwidget.action.actionStartActivity(widgetOpenAppIntent(context))),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp))
            loaded.first.owner != requested.owner -> Text("正在读取课表…",
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp))
            // Keep the previous date and its matching content together until the next read completes.
            else -> content(loaded.second, loaded.first.dayOffset)
        }
    }
}

class WidgetDateAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        try {
            val type = parameters[WidgetNavigation.kind] ?: return
            val step = parameters[WidgetNavigation.step] ?: return
            if (step !in listOf(-7, -1, 0, 1, 7)) return
            WidgetNavigation.move(context, glanceId, step)
            when (type) {
                "week" -> WeekWidget().update(context, glanceId)
                "today" -> TodayWidget().update(context, glanceId)
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("WidgetDateAction", "日期切换失败：${error.javaClass.simpleName}")
        }
    }
}

@Composable
internal fun WidgetDateHeader(title: String, kind: String, offset: Int) {
    val days = if (kind == "week") 7 else 1
    fun action(step: Int) = actionRunCallback<WidgetDateAction>(actionParametersOf(
        WidgetNavigation.kind to kind, WidgetNavigation.step to step,
    ))
    /**
     * 中间标题的文案：偏移到别的日子/周时，它必须说清「点一下回到今天/本周」。
     *
     * 旧实现把「回到今天」当标题传进来、再拼一次 " · 回今天"，结果变成
     * 「回到今天 · 回今天」；这里只留一份。周视图保留「第N周」——只写「回到本周」
     * 会让用户看不出现在看的是哪一周。
     */
    val label = when {
        offset != 0 && kind == "week" && title.startsWith("第") -> "$title · 回本周"
        offset != 0 && kind == "week" -> "回到本周"
        offset != 0 -> "回到今天"
        else -> title
    }
    /**
     * 🔴 热区只到「看得见的按钮文字」为止（2026-10-18 用户反馈：空白处也能切换前后日）。
     *
     * 旧实现把中间标题设成 defaultWeight 且整块 clickable —— 一行里从「前日」到「后日」
     * 之间 200 多 dp 的空白全成了「回到今天」热区，用户点空白也会跳日期。
     * 现在：左右按钮各自固定宽度、clickable 只挂在按钮文字上；中间标题只让**文字本身**可点，
     * 两侧空白由 defaultWeight 的 Box 撑开，Box 上不挂任何 clickable。
     * Row 本身、日期行、课程列表、卡片底部留白因此都不会响应左右导航。
     */
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (kind == "week") "‹ 上周" else "‹ 前日",
            modifier = GlanceModifier.width(48.dp).padding(vertical = 5.dp).clickable(action(-days)),
            style = TextStyle(color = WIDGET_ACCENT_PROVIDER, fontSize = 11.sp), maxLines = 1)
        Box(GlanceModifier.defaultWeight(), contentAlignment = Alignment.Center) {
            Text(label,
                modifier = GlanceModifier.padding(horizontal = 6.dp, vertical = 5.dp).clickable(action(0)),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp), maxLines = 1)
        }
        Text(if (kind == "week") "下周 ›" else "后日 ›",
            modifier = GlanceModifier.width(48.dp).padding(vertical = 5.dp).clickable(action(days)),
            style = TextStyle(color = WIDGET_ACCENT_PROVIDER, fontSize = 11.sp), maxLines = 1)
    }
}
