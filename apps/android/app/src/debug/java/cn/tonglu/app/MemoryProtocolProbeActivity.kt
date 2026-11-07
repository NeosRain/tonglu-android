package cn.tonglu.app

import android.app.Activity
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Parcel
import android.os.SystemClock
import cn.tonglu.app.data.memory.FairMemory
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only end-to-end Binder probe. All fixture files live under files/qa-fair-memory. */
class MemoryProtocolProbeActivity : Activity() {
    private var owner: AutoCloseable? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("mode") ?: "success"
        val action = if (intent.getBooleanExtra("trim", false)) "trim" else "kill"
        val root = File(filesDir, "qa-fair-memory").apply { mkdirs() }
        val saved = File(root, "$mode-$action.saved")
        val callbacks = AtomicInteger()
        val report = JSONObject().put("mode", mode).put("action", action)
        owner = FairMemory.register {
            when (mode) {
                "timeout" -> { Thread.sleep(3200); true }
                "failure" -> false
                else -> { saved.writeText("own-app-state-$action"); true }
            }
        }
        val started = SystemClock.elapsedRealtime()
        val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                synchronized(report) {
                    callbacks.incrementAndGet()
                    report.put("notifyType", data.readInt()).put("notifyId", data.readInt())
                        .put("result", data.readInt()).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    data.readBundle()
                }
                return true
            }
        }
        val common = Bundle().apply {
            putInt("notifyType", 1000); putInt("notifyId", 731)
            putString("action", action); putString("reason", "isolated-debug-probe")
            putBinder("callback", callback)
        }
        sendBroadcast(Intent("itgsa.intent.action.${action.uppercase()}").setPackage(packageName)
            .putExtra("common", common).putExtra("extra", Bundle()))
        val thread = HandlerThread("memory-probe-result").apply { start() }
        Handler(thread.looper).postDelayed({
            synchronized(report) {
                report.put("callbacks", callbacks.get()).put("fixtureSaved", saved.exists())
                File(root, "$mode-$action.json").writeText(report.toString(2))
            }
            runOnUiThread { finish() }
            thread.quitSafely()
        }, 3700)
    }

    override fun onDestroy() {
        owner?.close()
        super.onDestroy()
    }
}
