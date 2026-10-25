package cn.tonglu.app.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.tonglu.app.MainActivity
import cn.tonglu.app.R
import cn.tonglu.app.data.SessionStore
import cn.tonglu.app.data.ai.AssistantPersona
import cn.tonglu.app.data.focus.*
import kotlinx.coroutines.*

/** One persisted deadline, one alarm. Does not run a background per-second loop. */
object PomodoroReminder {
    private const val ALARM = 27041
    private const val NOTICE = 27042
    private fun pending(context: Context, state: PomodoroState?, action: String = "tick", request: Int = ALARM): PendingIntent {
        val intent=Intent(context,PomodoroReceiver::class.java).setAction("cn.tonglu.app.focus.$action")
        state?.let { intent.putExtra("owner",it.owner).putExtra("id",it.id).putExtra("sequence",it.sequence) }
        return PendingIntent.getBroadcast(context,request,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun sync(context: Context, state: PomodoroState?, options: PomodoroStore.Options, previous: PomodoroState?): String? {
        val alarms=context.getSystemService(AlarmManager::class.java)
        alarms.cancel(pending(context,null))
        var warning: String?=null
        if(state?.running==true) {
            val alarm=pending(context,state)
            if(Build.VERSION.SDK_INT<31 || alarms.canScheduleExactAlarms()) {
                try { alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,state.deadlineElapsed,alarm) }
                catch(e:SecurityException) {
                    Log.w("Pomodoro","精确提醒权限已变化",e)
                    alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,state.deadlineElapsed,alarm)
                    warning="后台提醒可能延迟，可在系统设置中允许闹钟和提醒"
                }
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,state.deadlineElapsed,alarm)
                warning="后台提醒可能延迟，可在系统设置中允许闹钟和提醒"
            }
        }
        val manager=NotificationManagerCompat.from(context)
        if(state==null || state.phase==PomodoroPhase.Stopped || !options.reminders) {
            manager.cancel(NOTICE);return warning
        }
        if(!manager.areNotificationsEnabled()) return listOfNotNull(warning,"通知未开启，离开应用后无法显示阶段提醒").joinToString("；")
        val channelId="pomodoro-${options.sound}-${options.vibrate}"
        if(Build.VERSION.SDK_INT>=26) {
            val channel=NotificationChannel(channelId,"番茄钟 · ${if(options.sound) "声音" else "静音"} · ${if(options.vibrate) "振动" else "不振动"}",NotificationManager.IMPORTANCE_HIGH)
            if(!options.sound) channel.setSound(null,null)
            channel.enableVibration(options.vibrate)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open=PendingIntent.getActivity(context,27043,Intent(context,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title=when(state.phase) {
            PomodoroPhase.Preparing -> "准备开始专注"
            PomodoroPhase.Focus -> "开始专注 · 第 ${state.round}/${state.preset.rounds} 轮"
            PomodoroPhase.Rest -> "专注完成，休息 ${state.preset.restMinutes} 分钟"
            PomodoroPhase.Paused -> "番茄钟已暂停"
            PomodoroPhase.Review -> "请确认番茄钟进度"
            PomodoroPhase.Complete -> "全部完成 · ${state.completedFocus} 轮专注"
            PomodoroPhase.Stopped -> "番茄钟已结束"
        }
        val detail=when(state.phase) {
            PomodoroPhase.Rest -> if(state.round==state.preset.rounds) "最后一次休息，随后结束" else "接下来：专注 ${state.preset.focusMinutes} 分钟，还剩 ${state.preset.rounds-state.round} 轮"
            PomodoroPhase.Review -> state.interruption.orEmpty()
            else -> state.label
        }
        val marker="${state.id}:${state.sequence}"
        val prefs=context.getSharedPreferences("pomodoro-notices",Context.MODE_PRIVATE)
        val boundary=previous!=null && previous.id==state.id && previous.sequence!=state.sequence
        val alert=boundary && state.phase!=PomodoroPhase.Preparing && prefs.getString("delivered",null)!=marker
        // Deduplicate alarm/foreground races. Only mark a boundary that is actually being notified.
        if(alert) check(prefs.edit().putString("delivered",marker).commit()) { "阶段提醒状态保存失败" }
        val builder=NotificationCompat.Builder(context,channelId).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("${AssistantPersona.NAME} · $title").setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail)).setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setSilent(!alert).setOnlyAlertOnce(!alert)
            .setOngoing(state.unfinished).setAutoCancel(!state.unfinished)
        if(state.running) builder.setUsesChronometer(true).setChronometerCountDown(true)
            .setWhen(System.currentTimeMillis()+state.remaining(SystemClock.elapsedRealtime()))
        if(state.running || state.phase==PomodoroPhase.Paused) {
            val resume=state.phase==PomodoroPhase.Paused
            builder.addAction(0,if(resume) "继续" else "暂停",pending(context,state,if(resume) "resume" else "pause",27044))
        }
        if(state.unfinished) builder.addAction(0,"结束",pending(context,state,"stop",27045))
        try { manager.notify(NOTICE,builder.build()) }
        catch(e:SecurityException) { Log.w("Pomodoro","通知权限已变化",e);warning="通知未开启，离开应用后无法显示阶段提醒" }
        return warning
    }
}

class PomodoroReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending=goAsync()
        scope.launch {
            try {
                val owner=SessionStore(context).ownerKey()
                val expectedOwner=intent.getStringExtra("owner")
                if(expectedOwner!=null && expectedOwner!=owner) return@launch
                val controller=PomodoroController.get(context)
                controller.bindOwner(owner)
                val id=intent.getStringExtra("id")
                if(id!=null) controller.expected(owner,id,intent.getLongExtra("sequence",-1),intent.action?.substringAfterLast('.') ?: "tick")
                else controller.refresh()
            } catch(e:Exception) {
                Log.e("Pomodoro","番茄钟提醒恢复失败",e)
            } finally { pending.finish() }
        }
    }
    companion object { private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO) }
}
