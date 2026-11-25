package cn.tonglu.app.data.diary

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.tonglu.app.MainActivity
import cn.tonglu.app.R
import cn.tonglu.app.data.SessionStore
import cn.tonglu.app.notify.ClassReminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException

internal enum class DiaryReminderKind(val id: Int, val title: String, val message: String) {
    SUMMARY(6811, "该写小结了", "回顾这几天的计划和灵感"),
    SURVEY(6812, "本周不做清单问卷", "回顾这周的执行情况"),
}

internal sealed interface DiaryOpenRequest {
    data object Write : DiaryOpenRequest
    data class Reminder(val owner: String, val kind: DiaryReminderKind) : DiaryOpenRequest
}

internal object DiaryReminders {
    const val NOTIFICATION_TAG = "goal-diary"
    const val ACTION_OPEN = "cn.tonglu.app.action.OPEN_GOAL_DIARY"
    const val EXTRA_KIND = "diary_kind"
    const val EXTRA_OWNER = "diary_owner"
    private const val EXTRA_FIRE_AT = "diary_fire_at"
    internal const val ACTION_FIRE = "cn.tonglu.app.action.FIRE_GOAL_DIARY"
    private const val TAG = "DiaryReminders"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    fun summaryDue(data: DiaryData, now: Long): Boolean =
        DiaryRules.summaryDueDay(data)?.let { DiaryDates.atTime(it, DiaryRules.SUMMARY_HOUR, 0) <= now } == true

    fun surveyOutstanding(data: DiaryData, week: Long): Boolean =
        data.surveys.none { it.week == week && it.submitted } &&
            (data.surveys.any { it.week == week } || data.prohibitions.any { ProhibitionStats.isActive(it, week) })

    fun pendingSurveyWeek(data: DiaryData, now: Long): Long? {
        val week = DiaryDates.week(DiaryDates.dayAt(now))
        if (now >= DiaryDates.surveyAt(data.settings, week) && surveyOutstanding(data, week)) return week
        return (week - 1).takeIf { surveyOutstanding(data, it) }
    }

    fun scheduleAsync(context: Context, onDone: () -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            try {
                synchronized(lock) { schedule(app, null) }
            } catch (error: IOException) {
                Log.w(TAG, "重排日记提醒失败", error)
            } finally {
                onDone()
            }
        }
    }

    fun deliver(context: Context, intent: Intent, pending: BroadcastReceiver.PendingResult) {
        val app = context.applicationContext
        scope.launch {
            try {
                synchronized(lock) { schedule(app, intent) }
            } catch (error: IOException) {
                Log.w(TAG, "投递日记提醒失败", error)
            } finally {
                pending.finish()
            }
        }
    }

    private fun schedule(context: Context, alarm: Intent?) {
        val owner = SessionStore.currentOwner(context)
        val data = DiaryStore.forOwner(context, owner).load().data
        val now = System.currentTimeMillis()
        val today = DiaryDates.dayAt(now)
        val week = DiaryDates.week(today)
        val notices = NotificationManagerCompat.from(context)
        if (alarm != null && alarm.getStringExtra(EXTRA_OWNER) == owner) {
            val fireAt = alarm.getLongExtra(EXTRA_FIRE_AT, 0)
            val kind = DiaryReminderKind.entries.firstOrNull { it.name == alarm.getStringExtra(EXTRA_KIND) }
            val fireDay = DiaryDates.dayAt(fireAt)
            val valid = when (kind) {
                DiaryReminderKind.SUMMARY -> DiaryRules.summaryDueDay(data)?.let { due ->
                    val cycle = data.settings.summaryCycle.days
                    fireDay == today && fireDay >= due && (fireDay - due) % cycle == 0L &&
                        fireAt == DiaryDates.atTime(fireDay, DiaryRules.SUMMARY_HOUR, 0)
                } == true
                DiaryReminderKind.SURVEY -> DiaryDates.week(fireDay) == week &&
                    fireAt == DiaryDates.surveyAt(data.settings, week) && surveyOutstanding(data, week)
                null -> false
            }
            if (valid && fireAt <= now && kind != null) notify(context, notices, kind, owner)
        }
        if (!summaryDue(data, now)) notices.cancel(NOTIFICATION_TAG, DiaryReminderKind.SUMMARY.id)
        if (pendingSurveyWeek(data, now) == null) notices.cancel(NOTIFICATION_TAG, DiaryReminderKind.SURVEY.id)

        arm(context, DiaryReminderKind.SUMMARY, owner, DiaryRules.summaryDueDay(data)?.let { due ->
            val step = data.settings.summaryCycle.days
            val lastCycle = if (today < due) due else today - (today - due) % step
            val next = DiaryDates.atTime(lastCycle, DiaryRules.SUMMARY_HOUR, 0)
            if (next > now) next else DiaryDates.atTime(lastCycle + step, DiaryRules.SUMMARY_HOUR, 0)
        })
        val currentSurvey = DiaryDates.surveyAt(data.settings, week)
        val nextWeek = if (currentSurvey > now && surveyOutstanding(data, week)) week else week + 1
        arm(context, DiaryReminderKind.SURVEY, owner,
            DiaryDates.surveyAt(data.settings, nextWeek).takeIf { surveyOutstanding(data, nextWeek) })
    }

    private fun arm(context: Context, kind: DiaryReminderKind, owner: String, fireAt: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, DiaryReminderReceiver::class.java).setAction(ACTION_FIRE)
            .putExtra(EXTRA_OWNER, owner)
            .putExtra(EXTRA_KIND, kind.name)
            .putExtra(EXTRA_FIRE_AT, fireAt ?: 0L)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pending = PendingIntent.getBroadcast(context, kind.id, intent, flags)
        if (fireAt == null) {
            alarms.cancel(pending)
            pending.cancel()
            return
        }
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (exact) {
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
                return
            } catch (error: SecurityException) {
                Log.w(TAG, "精确闹钟权限已变更，使用普通提醒", error)
            }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
    }

    private fun notify(
        context: Context,
        manager: NotificationManagerCompat,
        kind: DiaryReminderKind,
        owner: String,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ClassReminder.ensureChannel(context)
        val open = Intent(context, MainActivity::class.java).setAction(ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_KIND, kind.name)
            .putExtra(EXTRA_OWNER, owner)
        val pending = PendingIntent.getActivity(context, kind.id, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(context, ClassReminder.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_class)
            .setContentTitle(kind.title)
            .setContentText(kind.message)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_TAG, kind.id, notice)
    }
}

class DiaryReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DiaryReminders.ACTION_FIRE -> DiaryReminders.deliver(context, intent, goAsync())
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                val pending = goAsync()
                DiaryReminders.scheduleAsync(context, pending::finish)
            }
        }
    }
}
