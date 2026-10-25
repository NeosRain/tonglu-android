package cn.tonglu.app.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.tonglu.app.MainActivity
import cn.tonglu.app.R
import cn.tonglu.app.data.SessionStore
import cn.tonglu.app.data.competition.CompetitionPrecision
import cn.tonglu.app.data.competition.Subscription
import cn.tonglu.app.data.exam.ExamCountdown
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Calendar
import java.util.TimeZone

/**
 * 一条已排期的竞赛截止提醒。
 *
 * @param deadlineMillis 截止时刻（epoch 毫秒）。`DATE` 精度没有时刻，这里存的是
 *   「截止日 **次日** 北京时间 00:00」（即该日历日结束的那一瞬），只用于核对过期。
 */
data class DeadlineAlarm(
    val fireAt: Long,
    val subscriptionId: String,
    val deadlineMillis: Long,
    val title: String,
    val kindLabel: String,
    /** `DATE` = 原文只有日期没有钟点。 */
    val dateOnly: Boolean,
)

/**
 * 竞赛截止提醒 —— 兑现订阅 toast 里「截止前会提醒你」的承诺（此前订阅只落服务端，
 * App 端没有任何本地提醒，见 2026-10-15 R09 走查记录）。
 *
 * **照 [ClassReminder] 的既有模式**，不另起炉灶：
 * AlarmManager 精确闹钟 + 广播接收器到点核对 + [BootReceiver] 重排 ——
 * 不用 WorkManager（项目里没有先例，且截止提醒和课表提醒是同类需求，分两套机制没有收益）。
 *
 * ## 与课表提醒的三点不同
 * 1. **数据源是服务端**（订阅跟着账号走，本机没有 Room 副本）。排期输入是
 *    `CompetitionSubscriptionStore.refresh()` 拉回的列表；排好的闹钟**另存一份快照到盘上**
 *    （`files/competition/deadline-reminders.json`），重启后 [BootReceiver] 靠它重排 ——
 *    否则重启后所有提醒静默消失（课表提醒能重排是因为课表本来就在本地盘上）。
 * 2. **到点核对读快照，不打网络**（BroadcastReceiver 里拉服务端既慢又不可靠）：
 *    快照里找得到该订阅才发；快照文件整个读不出来时**宁多发不漏发**（多发一条提醒的代价
 *    远小于静默漏掉「今天截止」）。
 * 3. **时区固定 UTC+8**，与 `CompetitionCountdown` 同一规矩 —— 竞赛公告都是北京时间。
 *
 * ## 文案红线
 * `DATE` 精度**不补时刻**（不编 23:59，照 `CompetitionCountdown` 规矩）：提醒只在
 * 截止日按策略提前 N 天的**北京时间 09:00** 发，正文明说「时间以原文为准」。
 * 通知里的天数是触发那一刻按 UTC+8 日界**现算**的，不信任排期时写死的 extra ——
 * 系统把闹钟推迟几小时送达时，文案仍然是对的。
 */
object DeadlineReminder {
    private const val TAG = "DeadlineReminder"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduleMutex = Mutex()

    const val CHANNEL_ID = "deadline_reminder"
    const val ACTION_REMIND = "cn.tonglu.app.ACTION_DEADLINE_REMIND"

    const val EXTRA_SUB_ID = "deadline_sub_id"
    const val EXTRA_TITLE = "deadline_title"
    const val EXTRA_KIND = "deadline_kind"
    const val EXTRA_FIRE_AT = "deadline_fire_at"
    const val EXTRA_DEADLINE_AT = "deadline_deadline_at"
    const val EXTRA_DATE_ONLY = "deadline_date_only"
    private const val EXTRA_OWNER = "deadline_owner"

    private const val DAY_MS = 24L * 3600 * 1000
    private const val UTC_PLUS_8_MS = 8L * 3600 * 1000

    /** 一次最多排多少条（每条订阅一个策略一条闹钟；目录规模下绰绰有余）。 */
    private const val MAX_ALARMS = 32
    private const val REQUEST_BASE = 5400
    private const val NOTIFICATION_BASE = 6300

    /** 已过提醒点但还没到截止时，兜底提前量：订阅后一分钟内必响一条，兑现 toast 承诺。 */
    private const val LATE_GRACE_MS = 60_000L

    fun scheduleAllAsync(context: Context, subs: List<Subscription>?) {
        if (subs == null) return
        scope.launch { runCatching { scheduleAll(context.applicationContext, subs) } }
    }

    /**
     * 开机 / 覆盖安装后按快照重排竞赛截止提醒。
     *
     * 收尾用 [onDone] 而不是自己持有 PendingResult —— BootReceiver 把课程提醒与
     * 本排期合并在**同一次 `goAsync()`**里，必须等两条都完成才能 finish（原因见 [BootReceiver]）。
     */
    fun scheduleFromReceiver(context: Context, onDone: () -> Unit) {
        scope.launch {
            try { runCatching { rescheduleFromSnapshot(context.applicationContext) } }
            finally { onDone() }
        }
    }

    /** 到点核对后弹通知，并顺手把剩余提醒重排一遍。 */
    fun deliverFromReceiver(context: Context, intent: Intent, pendingResult: BroadcastReceiver.PendingResult) {
        scope.launch {
            val app = context.applicationContext
            try {
                runCatching {
                    val owner = intent.getStringExtra(EXTRA_OWNER) ?: return@runCatching
                    if (owner != SessionStore.currentOwner(app)) return@runCatching
                    val subId = intent.getStringExtra(EXTRA_SUB_ID) ?: return@runCatching
                    val fireAt = intent.getLongExtra(EXTRA_FIRE_AT, -1L)
                    if (fireAt <= 0 || fireAt > System.currentTimeMillis() + 3_000) return@runCatching
                    val deadlineMillis = intent.getLongExtra(EXTRA_DEADLINE_AT, -1L)
                    val snapshot = readSnapshot(app)
                    if (snapshot != null) {
                        // 快照在：照 ClassReminder「重读当前数据核对」的原则，取消订阅 / 已重排掉的不发。
                        val item = snapshot.firstOrNull { it.subscriptionId == subId }
                        if (item == null || item.deadlineMillis <= System.currentTimeMillis()) return@runCatching
                    }
                    if (deadlineMillis <= System.currentTimeMillis()) return@runCatching
                    show(
                        app,
                        DeadlineAlarm(
                            fireAt = fireAt,
                            subscriptionId = subId,
                            deadlineMillis = deadlineMillis,
                            title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                            kindLabel = intent.getStringExtra(EXTRA_KIND).orEmpty(),
                            dateOnly = intent.getBooleanExtra(EXTRA_DATE_ONLY, false),
                        ),
                    )
                }
                runCatching { rescheduleFromSnapshot(app) }
            } finally { pendingResult.finish() }
        }
    }

    // ── 排期 ──────────────────────────────────────────────────────────────

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "竞赛截止提醒",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "订阅的竞赛在报名 / 提交截止前提醒你"
                setShowBadge(true)
                enableVibration(true)
            },
        )
    }

    /** 读当前订阅列表重排全部提醒（订阅列表刷新后调用）。 */
    suspend fun scheduleAll(context: Context, subs: List<Subscription>) = scheduleMutex.withLock {
        cancelAll(context)
        val alarms = plan(subs, System.currentTimeMillis())
        persistSnapshot(context, alarms)
        arm(context, alarms)
    }

    /** 重启后从快照重排（快照里存的是已算好触发点的闹钟）。 */
    suspend fun rescheduleFromSnapshot(context: Context) = scheduleMutex.withLock {
        cancelAll(context)
        val snapshot = readSnapshot(context) ?: return@withLock
        val now = System.currentTimeMillis()
        arm(context, snapshot.filter { it.deadlineMillis > now })
    }

    private fun arm(context: Context, alarms: List<DeadlineAlarm>) {
        if (alarms.isEmpty()) return
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val owner = SessionStore.currentOwner(context)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
        alarms.forEachIndexed { index, alarm ->
            val pending = pendingIntent(context, index, alarm, owner)
            if (exact) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.fireAt, pending)
            } else {
                // 没有精确闹钟权限时退化为「大致准点」（系统可能推迟几分钟）
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.fireAt, pending)
            }
        }
    }

    fun cancelAll(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        for (index in 0 until MAX_ALARMS) manager.cancel(pendingIntent(context, index, null))
    }

    /**
     * 订阅列表 → 提醒排期。**纯函数**，确定性用例在 `DeadlineReminderPlanTest` 里锁。
     */
    fun plan(subs: List<Subscription>, now: Long): List<DeadlineAlarm> {
        val out = mutableListOf<DeadlineAlarm>()
        for (sub in subs) {
            if (!sub.enabled || sub.invalidated) continue
            val policy = policyDays(sub.reminderPolicy) ?: continue // off / 未知策略不提醒
            val deadline = sub.deadline ?: continue
            if (!deadline.countable) continue // 未知期限不提醒（与界面倒计时同一道闸）
            val deadlineDay = ExamCountdown.dateToEpochDay(deadline.date ?: continue) ?: continue
            val today = ExamCountdown.todayEpochDay(now, fixedUtcPlus8 = true)
            if (deadlineDay < today) continue // 已过（按日历日）

            val fireAt: Long
            val deadlineMillis: Long
            if (deadline.precision == CompetitionPrecision.DATETIME && deadline.deadlineAt != null) {
                val at = isoToEpochMillis(deadline.deadlineAt) ?: continue
                deadlineMillis = at
                if (at <= now + 3_000) continue
                val natural = at - policy * DAY_MS
                fireAt = if (natural > now + 3_000) natural else now + LATE_GRACE_MS
            } else {
                // DATE 精度：原文没给钟点，绝不自己编 —— 提醒放在（截止日 - 策略天）的北京时间 09:00。
                deadlineMillis = (deadlineDay + 1) * DAY_MS - UTC_PLUS_8_MS
                val natural = (deadlineDay - policy) * DAY_MS - UTC_PLUS_8_MS + 9L * 3600 * 1000
                fireAt = if (natural > now + 3_000) natural else now + LATE_GRACE_MS
            }
            out += DeadlineAlarm(
                fireAt = fireAt,
                subscriptionId = sub.id,
                deadlineMillis = deadlineMillis,
                title = sub.title,
                kindLabel = deadline.kindLabel,
                dateOnly = deadline.precision != CompetitionPrecision.DATETIME || deadline.deadlineAt == null,
            )
        }
        return out.sortedBy { it.fireAt }.take(MAX_ALARMS)
    }

    // ── 纯函数（供 plan 与测试共用） ─────────────────────────────────────

    /** 提醒策略 → 提前天数。`off` 与未知值一律不提醒（照 schema 注释的口径）。 */
    internal fun policyDays(policy: String?): Int? = when (policy?.trim()?.lowercase()) {
        "1d" -> 1
        "3d" -> 3
        "7d" -> 7
        else -> null
    }

    /**
     * UTC ISO 时刻 → epoch 毫秒。只认服务端 `DateTime.toISOString()` 的形态
     * （`...Z`）、带偏移（`±HH:MM`）与无时区三种；无时区按**北京时间墙钟**解释
     * （与 `CompetitionCountdown.isoToLocalDate` 的口径一致）。解析不出来返回 null。
     */
    internal fun isoToEpochMillis(iso: String): Long? {
        if (iso.length < 10) return null
        val day = ExamCountdown.dateToEpochDay(iso.substring(0, 10)) ?: return null
        var minutes = 0L
        if (iso.length >= 16 && iso[10] == 'T') {
            val hour = iso.substring(11, 13).toIntOrNull() ?: return null
            val minute = iso.substring(14, 16).toIntOrNull() ?: return null
            minutes = hour * 60L + minute
            val offsetMatch = Regex("([+-])(\\d{2}):?(\\d{2})$").find(iso)
            when {
                iso.endsWith("Z", ignoreCase = true) -> Unit
                offsetMatch != null -> {
                    val sign = if (offsetMatch.groupValues[1] == "-") -1 else 1
                    minutes -= sign * (offsetMatch.groupValues[2].toInt() * 60L + offsetMatch.groupValues[3].toInt())
                }
                // 没写时区就当作已经是北京时间
                else -> minutes -= 8L * 60
            }
        }
        return day * DAY_MS + minutes * 60_000
    }

    /** 触发时刻的主标题，如「还有 3 天报名截止」/「明天报名截止」/「今天报名截止」。天数现算不信任排期。 */
    internal fun fireTitle(kindLabel: String, deadlineMillis: Long, now: Long): String {
        val days = ((deadlineMillis + UTC_PLUS_8_MS) / DAY_MS - (now + UTC_PLUS_8_MS) / DAY_MS).toInt()
        val when_ = when {
            days > 1 -> "还有 $days 天"
            days == 1 -> "明天"
            else -> "今天"
        }
        return "$when_$kindLabel"
    }

    /** `DATETIME` 的截止钟点按北京时间展示（服务端 display 字段还是 UTC ISO，见 S95 留账，不直接用）。 */
    internal fun fireSubText(alarm: DeadlineAlarm): String {
        if (alarm.dateOnly) return "当日截止，时间以原文为准"
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = alarm.deadlineMillis + UTC_PLUS_8_MS
        }
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        return "${month}月${day}日 %02d:%02d 截止（北京时间）".format(hour, minute)
    }

    // ── 快照（重启后重排 / 到点核对的数据源） ────────────────────────────

    private fun dir(context: Context) = File(context.filesDir, "competition").also { it.mkdirs() }

    private fun snapshotFile(context: Context) = File(dir(context), "deadline-reminders.json")

    private fun persistSnapshot(context: Context, alarms: List<DeadlineAlarm>) {
        runCatching {
            val tmp = File(dir(context), "deadline-reminders.json.tmp")
            tmp.writeText(Gson().toJson(alarms))
            val dest = snapshotFile(context)
            if (tmp.renameTo(dest)) Unit else dest.writeText(tmp.readText())
            tmp.delete()
        }.onFailure { Log.w(TAG, "写截止提醒快照失败：${it.message}") }
    }

    /** 读不出来返回 null（调用方决定语义：重排 = 没有；核对 = 宁多发不漏发）。 */
    private fun readSnapshot(context: Context): List<DeadlineAlarm>? {
        val file = snapshotFile(context)
        if (!file.exists()) return null
        return runCatching {
            Gson().fromJson(file.readText(), Array<DeadlineAlarm>::class.java).toList()
        }.onFailure { Log.w(TAG, "读截止提醒快照失败：${it.message}") }.getOrNull()
    }

    // ── 通知 ─────────────────────────────────────────────────────────────

    private fun show(context: Context, alarm: DeadlineAlarm) {
        ensureChannel(context)
        val now = System.currentTimeMillis()
        val title = fireTitle(alarm.kindLabel.ifBlank { "截止" }, alarm.deadlineMillis, now)
        val text = alarm.title.ifBlank { "竞赛" }
        val subText = fireSubText(alarm)

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

        IslandAdapter.applyVendorExtras(context, builder, title, text, subText)

        val id = NOTIFICATION_BASE + ((alarm.subscriptionId.hashCode().rem(100_000) + 100_000) % 100_000)
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
    }

    private fun pendingIntent(context: Context, index: Int, alarm: DeadlineAlarm?, owner: String? = null): PendingIntent {
        val intent = Intent(context, DeadlineReceiver::class.java).apply {
            action = ACTION_REMIND
            if (alarm != null) {
                putExtra(EXTRA_FIRE_AT, alarm.fireAt)
                putExtra(EXTRA_OWNER, owner)
                putExtra(EXTRA_SUB_ID, alarm.subscriptionId)
                putExtra(EXTRA_TITLE, alarm.title)
                putExtra(EXTRA_KIND, alarm.kindLabel)
                putExtra(EXTRA_DEADLINE_AT, alarm.deadlineMillis)
                putExtra(EXTRA_DATE_ONLY, alarm.dateOnly)
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

/** 闹钟到点：核对后弹截止提醒。 */
class DeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DeadlineReminder.ACTION_REMIND) return
        DeadlineReminder.deliverFromReceiver(context, intent, goAsync())
    }
}
