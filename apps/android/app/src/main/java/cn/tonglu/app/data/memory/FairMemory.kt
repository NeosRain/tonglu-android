package cn.tonglu.app.data.memory

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import cn.tonglu.app.data.speech.BuiltinTts
import coil3.SingletonImageLoader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** ITGSA's published TRIM/KILL protocol. It never kills processes or clears persistent data. */
object FairMemory {
    private const val TAG = "TaimiFairMemory"
    private const val TRIM = "itgsa.intent.action.TRIM"
    private const val KILL = "itgsa.intent.action.KILL"
    private val main = Handler(Looper.getMainLooper())
    private val watchdog = Executors.newSingleThreadScheduledExecutor { Thread(it, "fair-memory-deadline").apply { isDaemon = true } }
    private val owners = CopyOnWriteArrayList<(Boolean) -> Boolean>()
    private var initialized = false

    /** Owners run on the UI thread; false means state was not fully saved/released. */
    fun register(owner: (kill: Boolean) -> Boolean): AutoCloseable {
        owners += owner
        return AutoCloseable { owners -= owner }
    }

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        val thread = HandlerThread("fair-memory-receiver").apply { start() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val kill = when (intent.action) { TRIM -> false; KILL -> true; else -> return }
                try {
                    val common = intent.getBundleExtra("common") ?: return
                    val type = common.getInt("notifyType")
                    val id = common.getInt("notifyId", -1)
                    val callback = common.getBinder("callback") ?: return
                    if (type !in listOf(1000, 2000) || id < 0 ||
                        common.getString("action") != if (kill) "kill" else "trim") return
                    val pending = goAsync()
                    val request = MemoryRequest(watchdog, 2400, reply = { success ->
                        try {
                            val parcel = Parcel.obtain()
                            try {
                                parcel.writeInt(type)
                                parcel.writeInt(id)
                                parcel.writeInt(if (success) 0 else 1)
                                parcel.writeBundle(Bundle())
                                check(callback.transact(IBinder.FIRST_CALL_TRANSACTION, parcel, null, IBinder.FLAG_ONEWAY))
                                Log.i(TAG, "reply type=$type id=$id action=${intent.action} result=${if (success) 0 else 1}")
                            } finally { parcel.recycle() }
                        } catch (e: Exception) { Log.e(TAG, "System callback failed", e) }
                        finally { pending.finish() }
                    }, onError = { Log.e(TAG, "Resource operation failed", it) })
                    val tasks = mutableListOf<() -> CompletableFuture<Boolean>>(
                        { BuiltinTts.trimMemory() },
                        { onMain { SingletonImageLoader.get(app).memoryCache?.clear(); true } },
                    )
                    owners.toList().forEach { owner -> tasks += { onMain { owner(kill) } } }
                    request.handle(tasks)
                } catch (e: Exception) { Log.e(TAG, "Invalid memory notification", e) }
            }
        }
        val filter = IntentFilter().apply { addAction(TRIM); addAction(KILL) }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, null, Handler(thread.looper), Context.RECEIVER_EXPORTED)
        else app.registerReceiver(receiver, filter, null, Handler(thread.looper))
        initialized = true
    }

    private fun onMain(block: () -> Boolean): CompletableFuture<Boolean> {
        val result = CompletableFuture<Boolean>()
        main.post {
            try { result.complete(block()) }
            catch (e: Exception) { Log.e(TAG, "Resource owner failed", e); result.complete(false) }
        }
        return result
    }
}
