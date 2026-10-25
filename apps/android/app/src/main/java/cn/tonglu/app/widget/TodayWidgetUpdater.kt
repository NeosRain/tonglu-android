package cn.tonglu.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 「今日课表」小部件的**刷新调度**。
 *
 * 2026-10-16 拆分小组件后，这颗闹钟同时管着**三个**部件：
 * [TodayWidget] / [CalendarWidget] / [WeekWidget]（见 [doRefresh]）——
 * 它们读的是同一份本机数据，共用一颗闹钟才不会三块桌面部件各自为政。
 *
 * ## 为什么不用 WorkManager（虽然最初的设计里写了它）
 *
 * 小部件要的数据**全在本机**（SharedPreferences 里的课表 JSON），一不联网、二不需要长任务。
 * WorkManager 在这里只会带来两个负担：
 *   · 最小周期 15 分钟，而我们要的是「08:45 下课那一刻改成『今天没课』」这种分钟级准度；
 *   · 它为了「保证执行」会持有唤醒锁，而我们每次刷新只是读一次本地文件、画一张 RemoteViews。
 *
 * 所以用 **AlarmManager 单次闹钟 + 每次刷新时重排下一次**：只在我们真正关心的时刻醒来。
 * 这与项目里 [cn.tonglu.app.notify.ClassReminder] 的做法一致（那边也是 AlarmManager 排期 + 重排）。
 *
 * ## 什么时候要刷新（[TodayWidgetSource.nextRefreshAt] 算出具体时刻）
 *
 * | 情形 | 下次刷新 |
 * |---|---|
 * | 正在上课 | 这节课**下课**时（标题要从「正在上课」变回来） |
 * | 还没上 / 已上完今天某些课 | **下一节上课**时（列表要往下走） |
 * | 今天没课 | 明天 0 点（跨天后可能就是有课的日子） |
 *
 * ## 重启、换天、装机后
 *
 * 闹钟**不跨重启存活**，所以 [cn.tonglu.app.notify.BootReceiver] 里也调了
 * [scheduleNext]（见 AndroidManifest 已注册的 BootReceiver）。
 * 只有单次闹钟没有这个保险，手机重启后小部件会停在旧画面 —— 这是最容易漏的一环。
 */
object TodayWidgetUpdater {

    /** 闹钟的固定 requestCode（不按部件 id 区分：所有部件刷新的是同一份本机数据） */
    private const val REQUEST_CODE = 7101
    private const val ACTION_REFRESH = "cn.tonglu.app.ACTION_TODAY_WIDGET_REFRESH"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 立刻重画所有「今日课表」部件，并排好下一次闹钟。
     *
     * 所有入口（用户拖入部件、课表变了、闹钟到点）都走这一个函数，
     * 保证「重画」和「排下一次」不会有人只做一半。
     */
    fun refreshAndReschedule(context: Context) {
        val app = context.applicationContext
        scope.launch { doRefresh(app) }
    }

    /** 真正的工作：重画 + 重排。抽出来是为了让 [refreshAndReschedule] 和 [handleAlarm] 共用一份。 */
    private suspend fun doRefresh(app: Context) {
        // 重画：updateAll 会重新走 provideGlance（读最新课表 + 当前时间）。
        // 2026-10-16 拆分小组件后这里有**三个**部件：今日课表 / 日历 / 完整课表。
        // 它们吃的是同一份本机数据、同一个「下一个重要时刻」，一起刷才能保证
        // 三块桌面部件的内容永远一致（比如跨天那一瞬间，不能有一块还停在昨天）。
        refreshWidgets(app, TodayWidget())
        refreshWidgets(app, CalendarWidget())
        refreshWidgets(app, WeekWidget())
        // 重排：按「刚画出来的这份数据」算下次该醒的时刻
        scheduleSafely(app)
    }

    private suspend fun scheduleSafely(app: Context) {
        try { scheduleNextInternal(app) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("WidgetRefresh", "刷新排期失败：${error.javaClass.simpleName}")
        }
    }

    /** Advance state before update so an existing 45-second Glance session observes new data.
     * A failed instance must not prevent other instances or widget kinds from updating.
     */
    private suspend fun refreshWidgets(app: Context, widget: GlanceAppWidget) {
        val ids = try { GlanceAppWidgetManager(app).getGlanceIds(widget.javaClass) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("WidgetRefresh", "实例读取失败：${widget.javaClass.simpleName}/${error.javaClass.simpleName}")
            emptyList()
        }
        ids.forEach { id ->
            try {
                WidgetNavigation.invalidate(app, id)
                widget.update(app, id)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("WidgetRefresh", "实例刷新失败：${widget.javaClass.simpleName}/${error.javaClass.simpleName}")
            }
        }
    }

    /**
     * 排下一次闹钟。**只排、不重画**。
     *
     * ⚠️ 名字不能叫 `scheduleNext` —— 公开的那个（给 receiver 用的非挂起入口）
     * 参数类型同样是 `Context`，两个 `Context` 参数的重载**在 JVM 上签名相同**，
     * Kotlin 会直接报 `Conflicting overloads`（第一版就踩了）。
     *
     * 是挂起函数：算下次刷新时刻要读一遍课表（[TodayWidgetSource.nextRefreshAt]），
     * 那是 Room 查询，不能在主线程。
     */
    private suspend fun scheduleNextInternal(app: Context) {
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val snapshot = TodayWidgetSource.load(app, now)
        // The legacy state keeps four rows for fixed layouts. The scrollable widget needs
        // the fifth and later courses when selecting the next start/end boundary as well.
        val state = (snapshot.state as? TodayWidgetModel.State.Courses)?.let {
            it.copy(rows = snapshot.allRows.ifEmpty { it.rows })
        } ?: snapshot.state
        val next = TodayWidgetSource.nextRefreshAtState(state, now)

        val pending = pendingIntent(app)
        // 用 set 而不是 setExactAndAllowWhileIdle：精确闹钟在 Android 12+ 需要用户授权
        // （SCHEDULE_EXACT_ALARM），拿不到就抛异常。这里是「刷新桌面一个数字」，
        // 晚几分钟无关紧要，不值得为它去要精确闹钟权限。
        // ⚠️ 但也不能用 setInexactRepeating —— 我们的间隔是变化的（下课/上课/明天 0 点），
        //    固定周期表达不了。
        manager.set(AlarmManager.RTC, next, pending)
    }

    /** 排下一次闹钟的非挂起入口（给 receiver / BootReceiver 用） */
    private fun reschedule(context: Context) {
        val app = context.applicationContext
        scope.launch { scheduleSafely(app) }
    }

    /**
     * 只排下一次闹钟（不重画）。
     *
     * 保留这个非挂起入口：小部件 receiver 的 `onEnabled` / `onUpdate` 回调
     * **没有协程作用域**，只能走 fire-and-forget。
     */
    fun scheduleNext(context: Context) = reschedule(context)

    /** 部件被移除后取消闹钟，别让它继续为空桌面耗电 */
    fun cancel(context: Context) {
        val app = context.applicationContext
        val widgets = android.appwidget.AppWidgetManager.getInstance(app)
        if (listOf(TodayWidgetReceiver::class.java, CalendarWidgetReceiver::class.java, WeekWidgetReceiver::class.java)
                .any { widgets.getAppWidgetIds(android.content.ComponentName(app, it)).isNotEmpty() }) {
            scheduleNext(app)
            return
        }
        val manager = context.applicationContext.getSystemService(AlarmManager::class.java) ?: return
        try { manager.cancel(pendingIntent(app)) }
        catch (error: RuntimeException) { Log.w("WidgetRefresh", "刷新取消失败：${error.javaClass.simpleName}") }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, TodayWidgetRefreshReceiver::class.java).apply {
            action = ACTION_REFRESH
        }
        // ⚠️ FLAG_IMMUTABLE 在 Android 12+ 是**硬要求**（targetSdk 31+ 用 mutable 会崩），
        //    而我们这个 intent 不需要被系统填任何东西。
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 闹钟到点：重画 + 排下一次。
     *
     * @param pendingResult 由 receiver 的 `goAsync()` 传入；传了就由这里负责 `finish()`，
     *        保证系统的进程宽限期覆盖到异步工作结束。
     */
    fun handleAlarm(context: Context, pendingResult: BroadcastReceiver.PendingResult? = null) {
        val app = context.applicationContext
        scope.launch {
            try {
                doRefresh(app)
            } finally {
                pendingResult?.finish()
            }
        }
    }

    /** 供 receiver 判断 action 用 */
    internal fun isRefreshAction(action: String?): Boolean = action == ACTION_REFRESH
}

/**
 * 闹钟到点接收器。
 *
 * 🔴 为什么这里的写法和普通 receiver 不一样：
 * `onReceive` 返回后进程随时可能被杀，而我们要做的是「读文件 + 渲染 RemoteViews」这类异步活。
 * 所以先 `goAsync()` 拿一个 [android.content.BroadcastReceiver.PendingResult] 把
 * 「我还没做完」告诉系统，**等工作真正结束再 finish()**。
 *
 * 常见错误（本文件第一版就写错了）：`goAsync()` 之后立刻 `finish()`，
 * 而真正的工作还在协程里 —— 系统看到 finish 就把进程的宽限期收了，
 * 表现是「小部件偶尔不刷新」，是极难复现的那种 bug。
 * 所以这里把 pendingResult 一路传给协程，在 finally 里收尾。
 */
class TodayWidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!TodayWidgetUpdater.isRefreshAction(intent.action)) return
        // goAsync 必须在 onReceive 里同步调用；finish 交给协程
        TodayWidgetUpdater.handleAlarm(context.applicationContext, goAsync())
    }
}
