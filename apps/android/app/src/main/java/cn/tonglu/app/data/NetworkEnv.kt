package cn.tonglu.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 导入教务数据前的**网络环境体检** —— 只回答两个问题：开着 VPN 吗？学校系统够得着吗？
 *
 * 为什么需要：教务系统在境外 IP 下会被学校的出口策略挡住（页面一直白屏、卡在"加载中"），
 * 而导入页以前没有任何网络反馈，用户只能干等。有了体检就能**明确告诉用户"这个网络导不进来"**，
 * 而不是让人以为是 App 坏了。
 *
 * 边界（与 docs/PLATFORM_INTEGRATION.md 一致）：
 *  · 只做「能不能连上」的探活，**不带任何凭证、不登录、不读页面内容**，就是一个 GET；
 *  · 不接教务的私有接口，只是访问它对外公开的首页；
 *  · 探活结果不上传、不落盘。
 */
object NetworkEnv {

    /**
     * 系统 VPN 是否连着（含第三方 VPN App 建的隧道）。
     * 只认 TRANSPORT_VPN —— 单纯的 HTTP 代理（很多"回国加速器"走这个）不算，
     * 那种情况由 [campusReachable] 兜住：连不上就是连不上。
     */
    fun vpnActive(context: Context): Boolean {
        val cm = runCatching {
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }.getOrNull() ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val nw = cm.activeNetwork
                if (nw != null &&
                    cm.getNetworkCapabilities(nw)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                ) return true
            }
            // 有些 ROM 的 activeNetwork 仍指向底层网络：把已连的网络都看一遍。
            @Suppress("MissingPermission", "DEPRECATION")
            for (network in cm.allNetworks) {
                if (cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return true
            }
            false
        }.getOrDefault(false)
    }

    /**
     * 目标地址够得着吗：**真的发一次 HTTPS 请求**，拿到任何 HTTP 状态码都算通。
     *
     * 只用 TCP 连一下是不够的 —— 这台机器的 DNS 常被劫持到 198.18.0.0/15，
     * 那种情况下端口"连得上"但握手必然超时，看起来像通、其实根本到不了学校。
     * 走完整握手能把这种假通挡在外面。
     *
     * @param url 探活地址。传教务公开首页即可（默认由调用方给，避免 data 层反向依赖 ui 的常量）。
     * @return true = 能访问；false = 超时 / DNS 失败 / SSL 失败 / 任何 IO 异常。
     */
    suspend fun campusReachable(url: String, timeoutMs: Int = 6000): Boolean = withContext(Dispatchers.IO) {
        val connection = runCatching { URL(url).openConnection() as? HttpsURLConnection }.getOrNull()
            ?: return@withContext false
        try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "tonglu-reachability/1")
            val code = connection.responseCode
            // 拿到任何状态码都说明学校那台服务器真的回话了（3xx/4xx/5xx 都算通，
            // 我们要的是"够得着"，不是"页面正常"）。
            code >= 200 || code in 300..599
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}
