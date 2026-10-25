package cn.tonglu.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.util.Log

/** Explicit, private callback; a request's Boolean return value is not an installation result. */
internal object WidgetPinResult {
    const val ACTION = "cn.tonglu.app.WIDGET_PINNED"
    const val TOKEN = "pin-token"
    const val KIND = "pin-kind"
    fun store(context: Context): SharedPreferences = context.getSharedPreferences("widget-pin-result", Context.MODE_PRIVATE)

    fun callback(context: Context, kind: WidgetKind, token: String): PendingIntent {
        val intent = Intent(context, WidgetPinReceiver::class.java).setAction(ACTION)
            .setData(Uri.parse("tonglu-widget://pin/$token"))
            .putExtra(TOKEN, token).putExtra(KIND, kind.name)
        // The launcher fills in EXTRA_APPWIDGET_ID. An immutable PendingIntent would discard it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    fun confirmed(context: Context, token: String, kind: WidgetKind): Boolean {
        val prefs = store(context)
        if (prefs.getString(TOKEN, null) != token || prefs.getString(KIND, null) != kind.name) return false
        val id = prefs.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val manager = AppWidgetManager.getInstance(context)
        return manager.getAppWidgetInfo(id)?.provider == ComponentName(context, kind.receiver) &&
            manager.getAppWidgetIds(ComponentName(context, kind.receiver)).contains(id)
    }
}

class WidgetPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetPinResult.ACTION) return
        val token = intent.getStringExtra(WidgetPinResult.TOKEN) ?: return
        val kind = WidgetKind.entries.firstOrNull { it.name == intent.getStringExtra(WidgetPinResult.KIND) } ?: return
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        try {
            val manager = AppWidgetManager.getInstance(context)
            val provider = ComponentName(context, kind.receiver)
            if (manager.getAppWidgetInfo(id)?.provider != provider || !manager.getAppWidgetIds(provider).contains(id)) {
                Log.w("WidgetPin", "桌面回调的小组件尚未绑定，未判为成功")
                return
            }
            WidgetPinResult.store(context).edit().putString(WidgetPinResult.TOKEN, token)
                .putString(WidgetPinResult.KIND, kind.name).putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id).apply()
        } catch (error: RuntimeException) {
            Log.w("WidgetPin", "添加结果读取失败：${error.javaClass.simpleName}")
        }
    }
}
