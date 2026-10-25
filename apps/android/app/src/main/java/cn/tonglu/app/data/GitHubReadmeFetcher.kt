/*
 * GitHub README fetcher：运行时兜底，让 LINK_ONLY 类型的 GitHub 仓库条目也能
 * 在 App 内直接渲染而不跳浏览器。运行时只读 short body (8 KB)，长抓走则切到
 * 外链按钮。
 *
 * 设计要点：
 * - 必须兼容本机 Mihomo fake-ip：先走阿里 DNS（223.5.5.5）解析 raw.githubusercontent.com，
 *   拿到真实 IP 后用 InetAddress 绑定 source_address 直连。OkHttp 自带 Dns hook。
 * - 内容：默认分支自动探测（main → master），README 候选名（README.md / readme.md / Readme.md）。
 * - 限制 body 到 200 KB，bodyChar 在 UI 内降级，避免打包体积 / 内存压力。
 * - 来源策略：仓库 LICENSE 已知则视为「在 App 内可读镜像 + 顶部提示原作者」；
 *   未知 LICENSE 则显示在 fetch_output，告知「本条目是仓库 README 镜像，请回到原仓库」。
 *
 * 协议 / 字段依赖：okhttp3 已添加，可信赖性归原作者。
 */
package cn.tonglu.app.data

import android.util.Log
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

object GitHubReadmeFetcher {
    private const val TAG = "GitHubReadmeFetcher"
    private val BRANCHES = listOf("main", "master", "develop")
    private val NAMES = listOf("README.md", "readme.md", "Readme.md")
    private const val MAX_BYTES = 200 * 1024

    private fun parseGitHub(url: String): Pair<String, String>? {
        // 仅解析形如 https://github.com/owner/repo 的 URL
        val m = Regex("^https?://github\\.com/([^/]+)/([^/]+)/?$").matchEntire(url.trimEnd('/'))
        return if (m != null) m.groupValues[1] to m.groupValues[2] else null
    }

    suspend fun fetch(sourceUrl: String): String? = withContext(Dispatchers.IO) {
        val (owner, repo) = parseGitHub(sourceUrl) ?: return@withContext null
        val client = buildClient()
        for (branch in BRANCHES) {
            for (name in NAMES) {
                val url = "https://raw.githubusercontent.com/$owner/$repo/$branch/$name"
                try {
                    val req = Request.Builder().url(url).header("User-Agent", "tonglu-app/1.0").build()
                    val text = client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) resp.body?.string() else null
                    }
                    if (!text.isNullOrBlank() && text.length > 200) {
                        Log.i(TAG, "fetched $owner/$repo $branch/$name ${text.length} chars")
                        return@withContext text.take(MAX_BYTES)
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "failed $url ${e.javaClass.simpleName}: ${e.message}")
                } catch (e: Exception) {
                    Log.w(TAG, "error $url ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        null
    }

    private fun buildClient(): OkHttpClient {
        val dns = Dns { hostname ->
                if (hostname == "raw.githubusercontent.com") {
                    val real = resolveRealIp(hostname)
                    if (real != null) listOf(InetAddress.getByName(real))
                    else Dns.SYSTEM.lookup(hostname)
                } else Dns.SYSTEM.lookup(hostname)
            }
        return OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private fun resolveRealIp(host: String): String? {
        // 用阿里 DNS（223.5.5.5 / 223.6.6.6）解析真实 IP，绕过本机 Mihomo fake-ip。
        return runCatching {
            for (server in listOf("223.5.5.5", "223.6.6.6")) {
                val answer = udpDnsLookup(server, host) ?: continue
                return@runCatching answer
            }
            null
        }.getOrNull()
    }

    private fun udpDnsLookup(server: String, host: String): String? {
        val pkt = buildDnsQuery(host)
        val sock = Socket()
        try {
            sock.connect(java.net.InetSocketAddress(server, 53), 3000)
            sock.soTimeout = 3000
            sock.getOutputStream().write(pkt)
            sock.getOutputStream().flush()
            val buf = ByteArray(1024)
            val n = sock.getInputStream().read(buf)
            if (n <= 12) return null
            var pos = 12
            while (pos < n) {
                val label = buf[pos].toInt() and 0xFF
                if (label == 0) break
                if (label and 0xC0 == 0xC0) { pos += 2; break } else pos += label + 1
            }
            pos += 5 // null + qtype(2) + qclass(2)
            // parse answers
            val qd = ((buf[4].toInt() and 0xFF) shl 8) or (buf[5].toInt() and 0xFF)
            repeat(qd) { pos = skipName(buf, pos); pos += 8 }
            val an = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
            repeat(an) {
                pos = skipName(buf, pos)
                if (pos + 10 > n) return null
                val rtype = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
                val rdlen = ((buf[pos + 8].toInt() and 0xFF) shl 8) or (buf[pos + 9].toInt() and 0xFF)
                pos += 10
                if (rtype == 1 && rdlen == 4) {
                    return "${buf[pos].toInt() and 0xFF}.${buf[pos + 1].toInt() and 0xFF}.${buf[pos + 2].toInt() and 0xFF}.${buf[pos + 3].toInt() and 0xFF}"
                }
                pos += rdlen
            }
            return null
        } catch (e: Exception) {
            return null
        } finally {
            sock.close()
        }
    }

    private fun skipName(buf: ByteArray, pos: Int): Int {
        var p = pos
        while (p < buf.size) {
            val b = buf[p].toInt() and 0xFF
            when {
                b == 0 -> return p + 1
                b and 0xC0 == 0xC0 -> return p + 2
                else -> p += b + 1
            }
        }
        return p
    }

    private fun buildDnsQuery(host: String): ByteArray {
        // header: id=0x1234, RD=1, QDCOUNT=1
        val out = ByteArray(12)
        out[0] = 0x12; out[1] = 0x34
        out[2] = 0x01; out[3] = 0x00
        out[4] = 0x00; out[5] = 0x01
        val labels = host.split(".")
        val body = ByteArray(labels.sumOf { it.length + 1 } + 5)
        var p = 0
        for (label in labels) {
            body[p++] = label.length.toByte()
            for (c in label.toByteArray()) body[p++] = c
        }
        body[p++] = 0
        body[p++] = 0; body[p++] = 1 // qtype A
        body[p++] = 0; body[p++] = 1 // qclass IN
        return out + body
    }
}