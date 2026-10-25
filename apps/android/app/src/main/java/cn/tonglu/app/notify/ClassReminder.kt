package cn.tonglu.app.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.tonglu.app.MainActivity
import cn.tonglu.app.R
import cn.tonglu.app.data.CourseDto
import cn.tonglu.app.data.Holidays
import cn.tonglu.app.data.PeriodTable
import cn.tonglu.app.data.ScheduleStore
import cn.tonglu.app.data.ScheduleText
import cn.tonglu.app.data.SessionStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一条待发的课前提醒。
 *
 * @param leadMinutes 距离上课还有多少分钟；0 表示"上课时刻"那条提醒。
 */
data class ClassAlarm(
    val fireAt: Long,
    val courseName: String,
    val location: String?,
    val teacher: String?,
    val startText: String,
    val endText: String,
    val leadMinutes: Int,
)

/**
 * 上课提醒（WakeUp 课表那种模式）。
 *
 * 与"常驻实时通知"不同：这里只在**课前**按点弹一条通知——
 *   · 课前 N 分钟：`还有 N 分钟上课 · 高等数学 · 文萃楼 402`
 *   · 课前 5 分钟：`还有 5 分钟上课 · …`
 *   · 上课时刻：`上课了 · 高等数学`
 * 用 AlarmManager 精确闹钟排期，不常驻、不占通知栏；重启后由 [BootReceiver] 重排。
 */
object ClassReminder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduleMutex = Mutex()

    fun scheduleAllAsync(context: Context) {
        scope.launch { runCatching { scheduleAll(context.applicationContext) } }
    }

    /**
     * 开机 / 覆盖安装后重排课程提醒。
     *
     * 收尾用 [onDone] 而不是自己持有 PendingResult：BootReceiver 里两条排期
     * （课程提醒 + 竞赛截止提醒）**必须共用同一次 `goAsync()`**，谁先跑完谁
     * `finish()` 都可能让系统回收进程、把另一条排期丢掉，所以由调用方在全部
     * 排期完成后统一收尾。详见 [BootReceiver]。
     */
    fun scheduleFromReceiver(context: Context, onDone: () -> Unit) {
        scope.launch {
            try { runCatching { scheduleAll(context.applicationContext) } }
            finally { onDone() }
        }
    }

    /** 到点后读取当前课表再核对，不能直接信任旧 PendingIntent 中的课程。 */
    fun deliverFromReceiver(context: Context, intent: Intent, pendingResult: BroadcastReceiver.PendingResult) {
        scope.launch {
            val app = context.applicationContext
            try {
                runCatching {
                    scheduleMutex.withLock {
                        val now = System.currentTimeMillis()
                        val fireAt = intent.getLongExtra(EXTRA_FIRE_AT, -1L)
                        val lead = intent.getIntExtra(EXTRA_LEAD, -1)
                        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
                        // 旧版本没有排程时间的提醒丢弃并重排；假期不发自动课前通知。
                        if (fireAt <= 0 || fireAt > now + 3_000 || lead < 0 || name.isBlank() || Holidays.isHoliday(now)) return@withLock
                        val owner = intent.getStringExtra(EXTRA_OWNER) ?: return@withLock
                        if (owner != SessionStore.currentOwner(app)) return@withLock
                        val classStart = fireAt + lead * 60_000L
                        if (Holidays.isHoliday(classStart) || ScheduleText.startOfDay(fireAt) != ScheduleText.startOfDay(now)) return@withLock
                        if (lead > 0 && now >= classStart) return@withLock
                        val store = ScheduleStore(app, owner)
                        if (!store.remindEnabled) return@withLock
                        store.applyPeriodTable()
                        val alarm = ClassAlarm(
                            fireAt = fireAt, courseName = name,
                            location = intent.getStringExtra(EXTRA_LOCATION),
                            teacher = intent.getStringExtra(EXTRA_TEACHER),
                            startText = intent.getStringExtra(EXTRA_START).orEmpty(),
                            endText = intent.getStringExtra(EXTRA_END).orEmpty(), leadMinutes = lead,
                        )
                        val endAt = ScheduleText.startOfDay(classStart) + PeriodTable.parseMinutes(alarm.endText) * 60_000L
                        if (now >= endAt) return@withLock
                        // 用原触发时间重新计算，兼容系统稍晚送达，且核对删课、周次和作息变化。
                        val current = plan(store.loadCourses(), store.termStart, store.remindLeadMinutes, fireAt - 3_001)
                        if (alarm in current && owner == SessionStore.currentOwner(app)) show(app, alarm)
                    }
                }
                runCatching { scheduleAll(app) }
            } finally { pendingResult.finish() }
        }
    }

    const val CHANNEL_ID = "class_reminder"
    const val ACTION_REMIND = "cn.tonglu.app.ACTION_CLASS_REMIND"

    const val EXTRA_NAME = "alarm_name"
    const val EXTRA_LOCATION = "alarm_location"
    const val EXTRA_TEACHER = "alarm_teacher"
    const val EXTRA_START = "alarm_start"
    const val EXTRA_END = "alarm_end"
    const val EXTRA_LEAD = "alarm_lead"
    private const val EXTRA_FIRE_AT = "alarm_fire_at"
    private const val EXTRA_OWNER = "alarm_owner"

    /** 一次最多排多少条闹钟（4~5 天量，打开 App 会不断补排） */
    private const val MAX_ALARMS = 24
    // 长假超过原来的 8 天窗口时，也要排到假期后的课程，避免提醒断档。
    private const val LOOKAHEAD_DAYS = 31
    private const val REQUEST_BASE = 5200
    private const val NOTIFICATION_BASE = 6100

    /** 固定再补一条"课前 5 分钟"的闹钟（用户设定的提前量之外） */
    private const val SECOND_LEAD = 5

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "上课提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "课前 N 分钟提醒你还有多少分钟上课"
                setShowBadge(true)
                enableVibration(true)
            },
        )
    }

    /** 读取课表并重排全部提醒（打开 App、导入课表、改设置后调用） */
    suspend fun scheduleAll(context: Context) = scheduleMutex.withLock {
        cancelAll(context)
        // 更新安装/打开应用时清掉此前已经发出的假期课程通知，仅清本频道。
        if (Holidays.isHoliday(System.currentTimeMillis()) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)?.let { manager ->
                manager.activeNotifications.filter { it.notification.channelId == CHANNEL_ID }
                    .forEach { manager.cancel(it.tag, it.id) }
            }
        }
        val owner = SessionStore.currentOwner(context)
        val store = ScheduleStore(context, owner)
        store.applyPeriodTable()
        if (!store.remindEnabled) return@withLock
        val courses = store.loadCourses()
        if (courses.isEmpty()) return@withLock
        val alarms = plan(courses, store.termStart, store.remindLeadMinutes, System.currentTimeMillis())
        if (alarms.isEmpty()) return@withLock
        val manager = context.getSystemService(AlarmManager::class.java) ?: return@withLock
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        alarms.forEachIndexed { index, alarm ->
            val pending = pendingIntent(context, index, alarm, owner)
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.fireAt, pending)
            } else {
                // 没有精确闹钟权限时退化为"大致准点"（系统可能推迟几分钟）
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.fireAt, pending)
            }
        }
    }

    fun cancelAll(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        for (index in 0 until MAX_ALARMS) manager.cancel(pendingIntent(context, index, null))
    }

    /** 精确闹钟权限是否可用（Android 12+ 可能需要用户手动开启） */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    }

    /**
     * 计算接下来要发的提醒。遍历未来一个月，把每门课折算成绝对时间，
     * 再按提前量倒推出触发时刻；已过去的直接丢掉。
     */
    fun plan(
        courses: List<CourseDto>,
        termStart: String?,
        leadMinutes: Int,
        now: Long,
    ): List<ClassAlarm> {
        val leads = listOf(leadMinutes, SECOND_LEAD).filter { it > 0 }.distinct().sortedDescending()
        val out = mutableListOf<ClassAlarm>()
        val dayStart = ScheduleText.startOfDay(now)
        for (offset in 0 until LOOKAHEAD_DAYS) {
            val base = dayStart + offset * 24L * 3600 * 1000
            if (Holidays.isHoliday(base)) continue
            val weekday = ScheduleText.weekdayOf(base)
            val week = ScheduleText.weekOf(termStart, base)
            courses
                .filter { it.weekday == weekday && (week == null || it.activeInWeek(week)) }
                .forEach { course ->
                    val slot = PeriodTable.slot(course.startPeriod) ?: return@forEach
                    val endSlot = PeriodTable.slot(course.endPeriod) ?: slot
                    val startAt = base + PeriodTable.parseMinutes(slot.start) * 60_000L
                    val endAt = base + PeriodTable.parseMinutes(endSlot.end) * 60_000L
                    if (startAt <= now) return@forEach
                    leads.forEach { lead ->
                        val fireAt = startAt - lead * 60_000L
                        if (fireAt > now + 3_000 && !Holidays.isHoliday(fireAt)) {
                            out += ClassAlarm(fireAt, course.name, course.location, course.teacher, slot.start, endSlot.end, lead)
                        }
                    }
                    if (startAt > now + 3_000) {
                        out += ClassAlarm(startAt, course.name, course.location, course.teacher, slot.start, endSlot.end, 0)
                    }
                }
        }
        return out.sortedBy { it.fireAt }.take(MAX_ALARMS)
    }

    /** 弹一条提醒通知 */
    fun show(context: Context, alarm: ClassAlarm) {
        ensureChannel(context)
        val title: String
        val text: String
        if (alarm.leadMinutes > 0) {
            title = "还有 ${alarm.leadMinutes} 分钟上课"
            text = listOfNotNull(alarm.courseName, alarm.location?.takeIf { it.isNotBlank() }).joinToString(" · ")
        } else {
            title = "上课了 · ${alarm.courseName}"
            text = listOfNotNull(alarm.location?.takeIf { it.isNotBlank() }, alarm.teacher?.takeIf { it.isNotBlank() })
                .joinToString(" · ").ifBlank { "开始上课" }
        }
        val subText = "${alarm.startText}-${alarm.endText}"

        val pending = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_class)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(subText)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        // 各家"灵动岛"的附加参数（无授权时系统忽略，不影响普通通知）
        IslandAdapter.applyVendorExtras(context, builder, title, text, subText)

        val id = NOTIFICATION_BASE + (((alarm.fireAt / 60_000L) % 100_000L).toInt() * 8 + alarm.leadMinutes)
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
    }

    private fun pendingIntent(context: Context, index: Int, alarm: ClassAlarm?, owner: String? = null): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMIND
            if (alarm != null) {
                putExtra(EXTRA_FIRE_AT, alarm.fireAt)
                putExtra(EXTRA_OWNER, owner)
                putExtra(EXTRA_NAME, alarm.courseName)
                putExtra(EXTRA_LOCATION, alarm.location)
                putExtra(EXTRA_TEACHER, alarm.teacher)
                putExtra(EXTRA_START, alarm.startText)
                putExtra(EXTRA_END, alarm.endText)
                putExtra(EXTRA_LEAD, alarm.leadMinutes)
            }
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_BASE + index,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** 闹钟到点：弹通知，并顺手把后续提醒补齐 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ClassReminder.ACTION_REMIND) return
        ClassReminder.deliverFromReceiver(context, intent, goAsync())
    }
}

/** 开机及覆盖安装后重排提醒，清除旧版本排下的课程闹钟。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED || action == "android.intent.action.QUICKBOOT_POWERON") {
            // 🔴 `BroadcastReceiver.goAsync()` **一个 receiver 只能调用一次**：AOSP 实现里
            // 它把 mPendingResult 交出去后就置空，第二次直接返回 null。此前课程与竞赛截止
            // 两条排期各调一次，第二条拿到 null → Kotlin 非空断言失败 →
            // `Unable to start receiver ... BootReceiver` → **覆盖安装与开机必崩进程**：
            //   2026-10-15 实测（模拟器 127.0.0.1:5571，API 35，装机后冷启动）：
            //   FATAL EXCEPTION: main  Process: cn.tonglu.app
            //   java.lang.RuntimeException: Unable to start receiver cn.tonglu.app.notify.BootReceiver:
            //     java.lang.NullPointerException: goAsync(...) must not be null
            //       at cn.tonglu.app.notify.BootReceiver.onReceive(ClassReminder.kt:307)
            //   ActivityTaskManager: Force finishing activity cn.tonglu.app/.MainActivity
            //   —— 用户在桌面看到的就是「开屏动画没播完就被弹回去」。
            // 现在只取一次 PendingResult，两条排期共享同一个收尾；等**全部**完成才 finish
            // （早于最后一条完成就 finish，进程可能被回收，剩下那条排期会丢）。
            val pending = goAsync()
            val remaining = AtomicInteger(2)
            val done = { if (remaining.decrementAndGet() == 0) pending.finish() }
            ClassReminder.scheduleFromReceiver(context, done)
            // 🔴 闹钟**不跨重启存活** —— 小部件的刷新闹钟同样没了，
            // 不重排的话重启后小部件会一直停在旧画面（最容易漏的一环）。
            // 放在这里而不是给 widget 再注册一个 receiver：一次开机只起一个进程，省一次冷启动。
            runCatching { cn.tonglu.app.widget.TodayWidgetUpdater.refreshAndReschedule(context.applicationContext) }
            // 竞赛截止提醒同样不跨重启存活 —— 从快照（files/competition/deadline-reminders.json）重排。
            DeadlineReminder.scheduleFromReceiver(context, done)
        }
    }
}
