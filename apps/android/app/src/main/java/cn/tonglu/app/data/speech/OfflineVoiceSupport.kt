package cn.tonglu.app.data.speech

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.IOException
import java.util.zip.ZipFile

internal const val OFFLINE_VOICE_UNSUPPORTED = "此设备不支持离线声线"

internal fun supportsOfflineVoice(deviceAbis: List<String>, packagedAbis: Set<String>): Boolean =
    deviceAbis.firstOrNull()?.let { it in packagedAbis } == true

internal object OfflineVoiceSupport {
    private val libraries = setOf("libsherpa-onnx-jni.so", "libonnxruntime.so")
    private var supported: Boolean? = null

    @Synchronized
    fun isSupported(context: Context): Boolean = supported ?: checkApks(context).also { supported = it }

    private fun checkApks(context: Context): Boolean {
        val app = context.applicationInfo
        val apkPaths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
        val abiLibraries = mutableMapOf<String, MutableSet<String>>()
        try {
            for (path in apkPaths) {
                ZipFile(path).use { apk ->
                    for (entry in apk.entries()) {
                        if (!entry.name.startsWith("lib/")) continue
                        val parts = entry.name.split('/')
                        if (parts.size == 3 && parts[2] in libraries) {
                            abiLibraries.getOrPut(parts[1]) { mutableSetOf() }.add(parts[2])
                        }
                    }
                }
            }
        } catch (error: IOException) {
            Log.w("TongluBuiltinTts", "无法检查 APK 中的离线语音库", error)
            return false
        }
        val packagedAbis = abiLibraries.filterValues { it.containsAll(libraries) }.keys
        // 转译 ABI 也可能出现在 SUPPORTED_ABIS 中，只按设备排在第一位的 ABI 放行。
        return supportsOfflineVoice(Build.SUPPORTED_ABIS.toList(), packagedAbis)
    }
}
