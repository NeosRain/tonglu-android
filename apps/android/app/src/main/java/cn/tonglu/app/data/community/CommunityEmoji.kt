package cn.tonglu.app.data.community

import cn.tonglu.app.data.ApiFactory
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.http.*

data class CommunityCapabilities(val canUploadImage: Boolean = false, val reason: String? = null)
data class EmojiAsset(
    val id: String, val publicUrl: String, val kind: String = "STATIC", val status: String = "READY",
    val reviewState: String = "UNREVIEWED", val mime: String? = null, val bytes: Long = 0,
    val width: Int? = null, val height: Int? = null, val glyph: String? = null,
) {
    /** 未审核或未完成资产可管理，但不可冒充可公开发送的成品。 */
    val sendable: Boolean get() = status == "READY" && reviewState == "CLEAR"
}
data class EmojiPack(val id: String, val attribution: String, val license: String,
    val licenseUrl: String, val source: String, val items: List<EmojiAsset> = emptyList())
data class EmojiUploadRequest(val kind: String, val mime: String, val bytes: Int, val sha256: String)
data class EmojiUploadTicket(val id: String, val uploadUrl: String? = null, val method: String? = null,
    val contentType: String? = null, val reused: Boolean = false)

interface CommunityEmojiApi {
    @GET("community/me/capabilities") suspend fun capabilities(@Header("Authorization") bearer: String): CommunityCapabilities
    @GET("community/emoji/packs/{id}") suspend fun pack(@Header("Authorization") bearer: String, @Path("id") id: String): EmojiPack
    @GET("community/emoji/favorites") suspend fun favorites(@Header("Authorization") bearer: String): List<EmojiAsset>
    @GET("community/emoji/mine") suspend fun mine(@Header("Authorization") bearer: String): List<EmojiAsset>
    @POST("community/emoji/{id}/favorite") suspend fun favorite(@Header("Authorization") bearer: String, @Path("id") id: String)
    @DELETE("community/emoji/{id}/favorite") suspend fun unfavorite(@Header("Authorization") bearer: String, @Path("id") id: String)
    @DELETE("community/emoji/{id}") suspend fun remove(@Header("Authorization") bearer: String, @Path("id") id: String)
    @POST("community/emoji/uploads") suspend fun begin(@Header("Authorization") bearer: String, @Body input: EmojiUploadRequest): EmojiUploadTicket
    @POST("community/emoji/uploads/{id}/complete") suspend fun complete(@Header("Authorization") bearer: String, @Path("id") id: String): EmojiAsset
}

/** 使用现有SessionManager续期、换号检查，不另造凭据存储。 */
interface EmojiAuthorization {
    suspend fun <T> execute(sessionId: String, request: suspend (String) -> T): T
}
interface EmojiGateway {
    suspend fun capabilities(sessionId: String): CommunityCapabilities
    suspend fun pack(sessionId: String): EmojiPack
    suspend fun favorites(sessionId: String): List<EmojiAsset>
    suspend fun mine(sessionId: String): List<EmojiAsset>
    suspend fun favorite(sessionId: String, id: String)
    suspend fun unfavorite(sessionId: String, id: String)
    suspend fun remove(sessionId: String, id: String)
    suspend fun upload(sessionId: String, data: ByteArray, mime: String): EmojiAsset
}

class CommunityEmojiRepository(private val api: CommunityEmojiApi, private val auth: EmojiAuthorization) : EmojiGateway {
    override suspend fun capabilities(sessionId: String) = auth.execute(sessionId) { api.capabilities(it) }
    override suspend fun pack(sessionId: String): EmojiPack = auth.execute(sessionId) { api.pack(it, "noto-emoji-v2.047") }.let { pack ->
        // 包列表不下发reviewState/status；官方包服务端只投影READY且已登记素材。
        // Gson不会执行含必填参数的Kotlin默认值；不能copy缺kind的投影对象。
        pack.copy(items = pack.items.map { EmojiAsset(id = it.id, publicUrl = it.publicUrl,
            kind = "STATIC", status = "READY", reviewState = "CLEAR", bytes = it.bytes,
            width = it.width, height = it.height, glyph = it.glyph) })
    }
    override suspend fun favorites(sessionId: String) = auth.execute(sessionId) { api.favorites(it) }
    override suspend fun mine(sessionId: String) = auth.execute(sessionId) { api.mine(it) }
    override suspend fun favorite(sessionId: String, id: String) = auth.execute(sessionId) { api.favorite(it, id) }
    override suspend fun unfavorite(sessionId: String, id: String) = auth.execute(sessionId) { api.unfavorite(it, id) }
    override suspend fun remove(sessionId: String, id: String) = auth.execute(sessionId) { api.remove(it, id) }
    override suspend fun upload(sessionId: String, data: ByteArray, mime: String): EmojiAsset {
        validateEmojiUpload(data.size, mime)
        val kind = if (mime == "image/gif") "GIF" else "STATIC"
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val ticket = auth.execute(sessionId) { api.begin(it, EmojiUploadRequest(kind, mime, data.size, sha)) }
        if (!ticket.reused) {
            val url = ticket.uploadUrl ?: throw IOException("服务器未返回上传地址")
            requireHttpsUrl(url)
            if (ticket.method != "PUT" || ticket.contentType != mime) throw IOException("上传契约不匹配，请重试")
            withContext(Dispatchers.IO) {
                // 独立存储客户端，不向签名URL附带应用Bearer；不跟随重定向。
                ApiFactory.storageClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
                    .newCall(Request.Builder().url(url).put(data.toRequestBody(mime.toMediaType())).build())
                    .execute().use { if (!it.isSuccessful) throw IOException("表情直传失败（HTTP ${it.code}）") }
            }
        }
        // 复用也由complete重新校验身份/资格；没有成功响应不伪造上传成功。
        return auth.execute(sessionId) { api.complete(it, ticket.id) }
    }
}

internal fun validateEmojiUpload(bytes: Int, mime: String) {
    require(mime in setOf("image/jpeg", "image/png", "image/webp", "image/gif")) { "只支持 JPG、PNG、WebP 或 GIF" }
    val limit = if (mime == "image/gif") 3 * 1024 * 1024 else 5 * 1024 * 1024
    require(bytes in 1..limit) { if (mime == "image/gif") "GIF不能超过3MB" else "静图不能超过5MB" }
}
internal fun requireHttpsUrl(url: String) {
    val parsed = try { URI(url) } catch (e: java.net.URISyntaxException) {
        // 错误文案不附带原URL，尤其不能把预签名查询串显示在日志或界面。
        throw IllegalArgumentException("表情链接格式无效")
    }
    require(parsed.scheme == "https" && !parsed.host.isNullOrBlank() && parsed.userInfo == null && parsed.fragment == null) { "表情地址不是有效的HTTPS链接" }
}
fun emojiMarkdown(asset: EmojiAsset): String {
    require(asset.sendable) { "表情尚未审核通过，暂不能发送" }
    requireHttpsUrl(asset.publicUrl)
    val url = asset.publicUrl.replace("(", "%28").replace(")", "%29")
    require(!url.any { it.isWhitespace() || it == '<' || it == '>' }) { "表情链接格式无效" }
    return "![表情]($url)"
}

/** 插入后的草稿可能保留很久；真正发送前再查一次资格，不沿用旧权限。 */
suspend fun assertEmojiSendPermission(gateway: EmojiGateway?, sessionId: String?) {
    check(gateway != null && sessionId != null) { "登录后才能发送图片表情" }
    val capability = gateway.capabilities(sessionId)
    check(capability.canUploadImage) { capability.reason ?: "需要等级超过3级或有效学生认证才能发送图片" }
}
