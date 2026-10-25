package cn.tonglu.app.transfer

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 发现链路的阶段耗时日志标签：`adb logcat -s TongluLocalSend` 即可读。 */
private const val TAG = "TongluLocalSend"

internal data class LanDevice(val alias: String, val ip: String, val port: Int, val fingerprint: String)
internal data class TransferFile(val uri: Uri, val name: String, val size: Long, val mime: String)
internal data class ReceivedFile(val name: String, val path: String, val size: Long)
internal data class ReceiveOffer(val id: String, val alias: String, val fingerprint: String, val files: List<Pair<String, Long>>)
internal data class LanState(
    val running: Boolean = false, val starting: Boolean = true, val status: String = "正在启动加密局域网服务…",
    val error: String? = null, val devices: List<LanDevice> = emptyList(), val selected: List<TransferFile> = emptyList(),
    val offer: ReceiveOffer? = null, val received: List<ReceivedFile> = emptyList(), val busy: Boolean = false,
    val sessionId: String? = null, val bytes: Long = 0, val total: Long = 0, val addresses: List<String> = emptyList(),
    /** 正在发现中：轮询间隔收紧到 120ms，设备显示得早一点 */
    val discovering: Boolean = false,
    /** 正在握手中（已收到组播但还没验完 TLS）的设备数 */
    val probing: Int = 0,
    /** 本轮发现里第一台设备从开始广播到显示出来用了多少毫秒 */
    val firstDiscoverMs: Long? = null,
)

internal class LocalSendController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(LanState())
    val ui = state.asStateFlow()
    private var multicastLock: WifiManager.MulticastLock? = null
    private val root = File(context.filesDir, "localsend-received").apply { mkdirs() }
    private val history = File(root, "history.json")
    private var closed = false
    private val owner = java.util.UUID.randomUUID().toString()

    init {
        scope.launch {
            try {
                val saved = runCatching { JSONArray(history.readText()).let { array -> (0 until array.length()).map { i ->
                    val f = array.getJSONObject(i); ReceivedFile(f.getString("name"), f.getString("path"), f.getLong("size"))
                }.filter { File(it.path).isFile && File(it.path).canonicalPath.startsWith(root.canonicalPath + File.separator) }.take(100) } }.getOrDefault(emptyList())
                state.update { it.copy(received = saved) }
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                multicastLock = wifi?.createMulticastLock("campus-localsend")?.apply { setReferenceCounted(false); acquire() }
                val result = call("start", JSONObject().put("identityPath", File(context.filesDir, "localsend-identity.json").absolutePath)
                    .put("receivePath", root.absolutePath).put("alias", "校园互传 · ${Build.MODEL}"))
                state.update { it.copy(running = true, starting = false, status = "已开启 · 等待同一网络的设备", addresses = result.optJSONArray("addresses")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()) }
                while (isActive) {
                    val events = call("poll").optJSONArray("events") ?: JSONArray()
                    for (i in 0 until events.length()) handle(events.getJSONObject(i))
                    // 发现中收紧到 120ms（原生侧已经在持续广播，早一点显示出来），空闲回到 250ms
                    delay(if (state.value.discovering) 120 else 250)
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                state.update { it.copy(starting = false, error = if (e is LinkageError) "当前设备缺少原生传输组件" else e.message ?: "局域网服务无法启动", busy = false) }
                multicastLock?.let { if (it.isHeld) it.release() }
            }
        }
    }
    private fun call(command: String, extra: JSONObject = JSONObject()): JSONObject {
        val response = JSONObject(LocalSendNative.invoke(extra.put("command", command).put("owner", owner).toString()))
        check(response.optBoolean("ok")) { response.optString("message", "操作失败") }
        return response
    }
    private fun action(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: Exception) { if (e is CancellationException) throw e; state.update { it.copy(error = e.message ?: "操作失败", busy = false) } } } }
    fun scan() = action {
        state.update { it.copy(error = null, status = "正在发现附近设备…", discovering = true, firstDiscoverMs = null, probing = 0) }
        call("scan")
    }
    /** 手动输入 IP 连接。原生侧现在是异步的 —— 立刻返回，成功/失败都走事件，不再卡住轮询。 */
    fun probe(ip: String, port: Int) = action {
        val target = ip.trim()
        state.update { it.copy(error = null, status = "正在连接 $target…") }
        call("probe", JSONObject().put("ip", target).put("port", port))
    }
    fun choose(uris: List<Uri>) = action {
        check(!state.value.busy) { "请等待当前传输结束" }
        check(uris.size <= 32) { "一次最多选择 32 个文件" }
        val files = uris.map { uri ->
            var name = "文件"; var size = -1L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME); val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
            if (size < 0) context.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            check(size in 0..2L * 1024 * 1024 * 1024) { "文件大小未知或超过 2 GiB，请先保存到本机再选择" }
            TransferFile(uri, name.take(120), size, context.contentResolver.getType(uri) ?: "application/octet-stream")
        }
        check(files.sumOf { it.size } <= 4L * 1024 * 1024 * 1024) { "单批文件总量不能超过 4 GiB" }
        state.update { it.copy(selected = files, error = null) }
    }
    fun send(device: LanDevice) = action {
        val files = state.value.selected
        check(files.isNotEmpty() && !state.value.busy) { "先选择文件" }
        val opened = mutableListOf<ParcelFileDescriptor>()
        try {
            val packets = JSONArray()
            files.forEach { f ->
                val descriptor = context.contentResolver.openFileDescriptor(f.uri, "r") ?: error("无法读取 ${f.name}")
                opened += descriptor
                packets.put(JSONObject().put("fd", descriptor.fd).put("name", f.name).put("size", f.size).put("mime", f.mime))
            }
            // Native duplicates descriptors before returning; the provider-owned originals always close below.
            state.update { it.copy(busy = true, bytes = 0, total = 0, status = "等待 ${device.alias} 确认接收", error = null) }
            val result = call("send", JSONObject().put("ip", device.ip).put("port", device.port).put("fingerprint", device.fingerprint).put("files", packets))
            state.update { it.copy(sessionId = result.getString("id")) }
        } finally { opened.forEach { it.close() } }
    }
    fun decide(accept: Boolean) = action {
        val offer = state.value.offer ?: return@action
        if (accept) check(android.os.StatFs(root.absolutePath).availableBytes > offer.files.sumOf { it.second } + 32L * 1024 * 1024) { "本机存储空间不足，请拒绝本次接收" }
        call("decide", JSONObject().put("id", offer.id).put("accept", accept))
        state.update { it.copy(offer = null, busy = accept, sessionId = if (accept) offer.id else null, bytes = 0, total = 0, status = if (accept) "已接受 · 等待发送方传输" else "已拒绝接收") }
    }
    fun cancel() = action { state.value.sessionId?.let { call("cancel", JSONObject().put("id", it)) }; state.update { it.copy(busy = false, sessionId = null, status = "传输已取消") } }
    fun saveTo(uri: Uri, file: ReceivedFile) = action {
        val source = File(file.path)
        check(source.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "文件不可用" }
        context.contentResolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out, 64 * 1024) } } ?: error("无法写入目标文件")
        state.update { it.copy(status = "已保存 ${file.name}", error = null) }
    }
    fun exportLicenses(uri: Uri) = action {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            context.assets.open("licenses/localsend-dependencies.zip").use { it.copyTo(out) }
        } ?: error("无法保存许可证")
        state.update { it.copy(status = "已保存依赖许可证", error = null) }
    }
    /**
     * 发现链路的阶段耗时打一行 logcat。
     *
     * 交接文档 §3 第 1 步要的是「先量，别先改」：打开页→监听就绪→组播接收→验证→显示
     * 各段各花多久。这些数字在界面上看不出来（界面上只显示「已发现 X · 首次 N 秒」），
     * 真机复现时必须能从 `adb logcat -s TongluLocalSend` 直接读出来。
     *
     * **只打类型与耗时，不打文件名、别名、IP** —— 发现日志不该带用户数据。
     */
    private fun trace(e: JSONObject) {
        when (e.optString("type")) {
            "discovery" -> Log.d(TAG, "discovery state=${e.optString("state")} round=${e.optLong("round")}")
            "timing" -> Log.d(TAG, "timing stage=${e.optString("stage")} round=${e.optLong("round")} at=${e.optLong("ms")}ms")
            "probing" -> Log.d(TAG, "probing handshake started")
            "probeFailed" -> Log.d(TAG, "probing handshake failed")
            "device" -> Log.d(TAG, "device shown discoveredInMs=${e.optLong("discoveredInMs")}")
            else -> {}
        }
    }

    private fun handle(e: JSONObject) {
        trace(e)
        when (e.optString("type")) {
            "device" -> {
                val device = LanDevice(e.getString("alias"), e.getString("ip"), e.getInt("port"), e.getString("fingerprint"))
                // 首次发现耗时：原生侧从「这一轮开始广播」算起，用来证明发现到底慢在哪一段
                val ms = e.optLong("discoveredInMs", 0L).takeIf { it > 0 }
                state.update {
                    it.copy(
                        devices = (it.devices.filterNot { old -> old.fingerprint == device.fingerprint } + device).takeLast(100),
                        probing = (it.probing - 1).coerceAtLeast(0),
                        discovering = false,
                        firstDiscoverMs = it.firstDiscoverMs ?: ms,
                        status = if (ms != null) "已发现 ${device.alias} · 首次 %.1f 秒".format(ms / 1000.0) else "已发现 ${device.alias}",
                    )
                }
            }
            // 已收到组播、正在验 TLS。以前这段时间界面完全没反应，用户以为「没发现任何设备」。
            "probing" -> state.update { it.copy(probing = it.probing + 1) }
            // 组播里的设备没验过去（对端已退出 / 证书不符），不算错误，只是把它从「验证中」里去掉
            "probeFailed" -> state.update { it.copy(probing = (it.probing - 1).coerceAtLeast(0)) }
            "probe" -> state.update {
                if (e.optBoolean("ok")) it.copy(status = "已连接 ${e.optString("ip")}", probing = 0, error = null)
                else it.copy(error = e.optString("message").ifBlank { "连接失败：对方可能已关闭接收页面" }, probing = 0)
            }
            "discovery" -> state.update {
                if (e.optString("state") == "idle") it.copy(discovering = false, probing = 0) else it.copy(discovering = true)
            }
            "request" -> {
                val files = e.getJSONObject("files"); val list = files.keys().asSequence().map { key -> files.getJSONObject(key).let { f -> f.getString("fileName") to f.getLong("size") } }.toList()
                state.update { it.copy(offer = ReceiveOffer(e.getString("id"), e.getString("alias"), e.getString("fingerprint"), list)) }
            }
            "requestExpired" -> state.update { if (it.offer?.id == e.optString("id")) it.copy(offer = null, status = "对方已取消或请求过期") else it }
            "progress" -> state.update { it.copy(bytes = e.optLong("bytes"), total = e.optLong("size"), status = if (e.optString("direction") == "send") "正在发送…" else "正在接收…") }
            "received" -> {
                val file = ReceivedFile(e.getString("name"), e.getString("path"), e.getLong("size"))
                state.update { it.copy(received = (listOf(file) + it.received).take(100), status = "已收到 ${file.name}") }
                val array = JSONArray(); state.value.received.forEach { array.put(JSONObject().put("name", it.name).put("path", it.path).put("size", it.size)) }
                runCatching {
                    val temp = File(root, "history.json.tmp"); temp.writeText(array.toString()); check(temp.renameTo(history))
                }.onFailure { state.update { it.copy(error = "文件已收到，但接收记录保存失败") } }
            }
            "sent", "sessionEnd" -> state.update { it.copy(busy = false, sessionId = null, status = if (e.optString("type") == "sent") "发送完成" else "本次接收已结束") }
            "error" -> state.update { it.copy(error = e.optString("message"), busy = false, sessionId = null) }
            "notice" -> state.update { it.copy(status = e.optString("message")) }
        }
    }
    fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        // Keep blocking native teardown off the UI thread; stop releases listeners and pending decisions.
        CoroutineScope(Dispatchers.IO).launch { runCatching { call("stop") }; multicastLock?.let { if (it.isHeld) it.release() } }
    }
}
