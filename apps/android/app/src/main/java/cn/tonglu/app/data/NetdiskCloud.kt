package cn.tonglu.app.data

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import java.io.OutputStream

/**
 * 内置网盘（服务端 /netdisk 系列接口）+ **直连存储的传输层**。
 *
 * ⚠️ 写文档注释时，**不要在注释里连着写「斜杠 + 星号」**：Kotlin 的块注释可以嵌套，
 * 那个字符组合会开一个内层注释、把后面的注释结束符吃掉，整文件报「Unclosed comment」，
 * 而报错只指向文件末尾、不指向真正出问题的那一行。这个坑项目里踩过不止一次。
 *
 * ## 与上一版（OpenList → 阿里云盘）的区别
 *
 * 上一版：文件在**用户自己的阿里云盘**上 → 不得已让服务器把字节捞回来再转发
 * （官方禁止「帐号被多 IP 访问」，直链给谁就等于把谁的 IP 送到云盘面前）。
 * 结果是上传和下载都把 2C2G 那台机器的带宽吃满。
 *
 * 现在：存储是**我们自己的 Cloudflare R2 桶**，多客户端直连正是它的用途。
 * 于是拆成三步 ——
 *   ① `POST netdisk/uploads` 拿一条预签名 PUT 地址（服务端只签名，不碰字节）
 *   ② App 直接 PUT 到 R2（[netdiskPutToStorage]）
 *   ③ `POST netdisk/uploads/:id/complete` 确认（服务端 HEAD 复核真实大小）
 * 下载同理：`GET netdisk/objects/:id/url` 拿一条 10 分钟的链接，然后 [netdiskGetFromStorage]。
 *
 * ## 两条必须守住的约定
 * - **对象键是随机的**，桶上永远不出现用户的文件名；文件名只在服务端库里，
 *   下载时靠 `response-content-disposition` 还原成「另存为」的默认名。
 * - **预签名链接本身就是凭据**。所以它只活 10 分钟 / 30 分钟，
 *   而且 [ApiFactory.storageClient] 的日志**故意不打印查询串**（里面有签名）。
 *
 * 字段**全部可空 + 默认值**：Gson 反序列化缺字段时不会套用 Kotlin 默认值，全空能避免 NPE。
 */
data class NetdiskStatusDto(
    val enabled: Boolean = false,
    val ttlHours: Int = 24,
    val maxFileBytes: Long = 0,
    /** 我自己的用量（界面上「已用 / 在存」用的就是它）。 */
    val usedBytes: Long = 0,
    val liveObjects: Int = 0,
    /**
     * 「临时空间」上限是全体共用的，所以空间压力必须看全体用量，不能看我自己的。
     * 服务端给的是**字节**（2GB = 2147483648 B）；显示一律走 [netdiskHumanSize]，
     * 它是 1024 进制 —— 别在这里自己除，也别换成 1000 进制。
     */
    val maxTotalBytes: Long = 0,
    val sharedUsedBytes: Long = 0,
    val sharedLiveObjects: Int = 0,
    /**
     * 全体共用的临时空间还剩多少**字节**（服务端算好）。
     * 老服务端不返回这个字段时 Gson 得到 0，用 [netdiskSharedRemaining] 兜底。
     */
    val sharedRemainingBytes: Long = 0,
    val minLevel: Int = 0,
    val storageReachable: Boolean = true,
)

data class NetdiskObjectDto(
    val id: String = "",
    val name: String = "",
    val size: Long = 0,
    val createdAt: String? = null,
    val expiresAt: String? = null,
    /** 剩余保留毫秒数，由服务端算（客户端时钟不可信） */
    val remainingMs: Long = 0,
    val mine: Boolean = true,
    /**
     * 分享链接与提取码。**只有自己的文件才非空** ——
     * 服务端在列表里按 owner 过滤（开着共享板时别人的那几行一律为 null），
     * 所以这里可以直接当「是不是我能分享的」来用。
     */
    val shareUrl: String? = null,
    val shareCode: String? = null,
    /** 被**成功取走**的次数（不是「点开过链接」）。 */
    val shareViews: Int = 0,
    val shareViewedAt: String? = null,
)

/**
 * 分享信息（单独一条接口的返回：`objects/:id/share` 与 `.../share/renew`）。
 *
 * 正常情况下用不到：`netdiskCompleteUpload` 返回里就已经带了同样的字段 ——
 * 用户传完的那一刻就该看到链接与提取码，而不是还要再点一次「生成分享」。
 * 留着是为了「本地丢了那次响应」和「换一把钥匙」两种情况。
 */
data class NetdiskShareInfoDto(
    val id: String = "",
    /** 分享的是哪个文件 —— 卡片标题要用，别让用户对着一串链接猜。 */
    val name: String = "",
    val shareUrl: String = "",
    val shareCode: String = "",
    val shareViews: Int = 0,
    val shareViewedAt: String? = null,
    /**
     * 剩余保留毫秒数。**服务端算**，客户端只负责往下数 —— 手机时钟不可信。
     * 卡片上的倒计时按它起算，每 30 秒减一次。
     */
    val remainingMs: Long = 0,
)

data class NetdiskListDto(
    val objects: List<NetdiskObjectDto>? = null,
    val ttlHours: Int = 24,
)

/** 申请上传：**必须报准大小**，服务端要靠它做配额与单文件上限的第一道判断。 */
data class NetdiskBeginUploadDto(
    val name: String,
    val size: Long,
    val contentType: String? = null,
)

/** 服务端返回的直传票据。 */
data class NetdiskUploadTicketDto(
    val id: String = "",
    val name: String = "",
    val method: String = "PUT",
    /** 预签名 PUT 地址（绝对地址，指向 R2）。带 `X-Amz-Signature`，**不要打日志**。 */
    val uploadUrl: String = "",
    val contentType: String? = null,
    val uploadUrlExpiresInSec: Long = 0,
    val maxFileBytes: Long = 0,
    val expiresAt: String? = null,
)

/** 短期下载链接。 */
data class NetdiskUrlDto(
    val url: String = "",
    val name: String = "",
    val size: Long = 0,
    val expiresInSec: Long = 0,
)

/**
 * 从 `content://` 流式读出的请求体。
 *
 * 不用 multipart：直传的目标是预签名 PUT 地址，收的就是**原始字节**。
 * 也不需要把整个文件读进内存 —— 内存里灌一个 100MB 的文件，
 * 在低端机上就是一次 OOM 崩。
 *
 * `contentLength` 必须给准：它是「单文件上限」的第一道判断依据，
 * 也是 OkHttp 决定用定长还是分块传输的依据（拿不准就走 chunked）。
 */
class NetdiskRequestBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val onProgress: (written: Long, total: Long) -> Unit = { _, _ -> },
) : RequestBody() {
    override fun contentType() = OCTET

    override fun contentLength(): Long =
        runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }
            .getOrDefault(-1L)
            .takeIf { it > 0 } ?: -1L

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        val input = resolver.openInputStream(uri) ?: throw java.io.IOException("读不到所选文件")
        input.use { stream ->
            stream.source().use { source ->
                var written = 0L
                val buffer = okio.Buffer()
                while (true) {
                    val read = source.read(buffer, 64 * 1024)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    written += read
                    onProgress(written, total)
                }
            }
        }
    }

    companion object {
        private val OCTET = "application/octet-stream".toMediaType()
    }
}

/**
 * 把请求体**直接 PUT 到预签名地址**（R2）。字节不经过我们的后端。
 *
 * 失败时抛 `IOException`，由 `AppViewModel.netdiskErrorMessage` 统一转成人话。
 * 注意这里**不带任何 Authorization 头**：签名已经在 URL 的查询串里，
 * 多带一个我们的 JWT 反而可能让 R2 按别的规则校验。
 */
fun netdiskPutToStorage(url: String, body: RequestBody) {
    val request = Request.Builder().url(url).put(body).build()
    ApiFactory.storageClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw java.io.IOException("直传失败（HTTP ${response.code}）")
    }
}

/**
 * 从预签名地址**直接下载**并写入用户选好的位置。
 *
 * 流式复制，不整块读进内存；100MB 的文件在这条路上只占用几十 KB 缓冲。
 */
fun netdiskGetFromStorage(url: String, sink: OutputStream) {
    val request = Request.Builder().url(url).get().build()
    ApiFactory.storageClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw java.io.IOException("直下失败（HTTP ${response.code}）")
        val source = response.body?.source() ?: throw java.io.IOException("存储没有返回内容")
        val buffer = okio.Buffer()
        while (true) {
            val read = source.read(buffer, 64 * 1024)
            if (read == -1L) break
            sink.write(buffer.readByteArray(read))
        }
    }
}

/**
 * 人类可读大小。
 *
 * 🔴 **进制是 1024，标签写 KB/MB/GB**（本机全部大小文案都是这个口径，别改成 1000）：
 * 1 KB = 1024 B、1 MB = 1024 KB、1 GB = 1024 MB。
 * 服务端的配额也按这个口径配置：2GB 的共享临时空间 = 2147483648 B → 这里显示 2.00 GB。
 * 2026-10-18 用户反馈「说好 2G 怎么显示 1.95G」，根因就是服务端曾按十进制配了 2000MB，
 * 而这里是 1024 进制 —— 单位口径必须两边同源，不能再各算各的。
 */
fun netdiskHumanSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

/**
 * 共享临时空间的剩余字节数。
 *
 * 优先用服务端算好的 [NetdiskStatusDto.sharedRemainingBytes]；老服务端不返回它时
 * 退化成「总量 − 已用」—— 两者口径完全一样，只是避免客户端与服务端的取整差异。
 */
fun netdiskSharedRemaining(status: NetdiskStatusDto): Long =
    if (status.sharedRemainingBytes > 0L) {
        status.sharedRemainingBytes
    } else {
        (status.maxTotalBytes - status.sharedUsedBytes).coerceAtLeast(0L)
    }

/**
 * 剩余保留时间。
 *
 * 故意不显示「几月几号到期」：文件最多活 24 小时，说「还有 5 小时」比让人自己算日期清楚。
 */
fun netdiskRemaining(ms: Long): String {
    if (ms <= 0L) return "即将销毁"
    val minutes = ms / 60_000
    if (minutes < 1) return "不到 1 分钟"
    if (minutes < 60) return "还有 $minutes 分钟"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0L) "还有 $hours 小时" else "还有 $hours 小时 $rest 分"
}
