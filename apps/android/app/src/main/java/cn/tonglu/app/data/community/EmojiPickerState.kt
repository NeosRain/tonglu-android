package cn.tonglu.app.data.community

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import retrofit2.HttpException

enum class EmojiSource { OFFICIAL, FAVORITES, MINE }
data class EmojiPickerState(
    val source: EmojiSource = EmojiSource.OFFICIAL, val loading: Boolean = false, val busy: Boolean = false,
    val capabilities: CommunityCapabilities? = null, val assets: List<EmojiAsset> = emptyList(),
    val pack: EmojiPack? = null, val favorites: Set<String> = emptySet(),
    val favoritesLoaded: Boolean = false,
    val error: String? = null, val notice: String? = null,
)

/** 每个登录session独立实例；关闭层取消协程，迟到响应不能写入另一个账号。 */
class EmojiPickerController(private val gateway: EmojiGateway, private val sessionId: String) {
    private val mutable = MutableStateFlow(EmojiPickerState())
    val state = mutable.asStateFlow()
    suspend fun load(source: EmojiSource) {
        if (mutable.value.loading || mutable.value.busy) return
        mutable.update { it.copy(source = source, loading = true, capabilities = null, favoritesLoaded = false, assets = emptyList(), pack = null, error = null, notice = null) }
        try { coroutineScope {
            val capRequest = async { fetch { gateway.capabilities(sessionId) } }
            val favoritesRequest = async { fetch { gateway.favorites(sessionId) } }
            val packRequest = if (source == EmojiSource.OFFICIAL) async { fetch { gateway.pack(sessionId) } } else null
            val mineRequest = if (source == EmojiSource.MINE) async { fetch { gateway.mine(sessionId) } } else null
            val cap = capRequest.await()
            mutable.update { it.copy(capabilities = cap) }
            val favorites = favoritesRequest.await()
            mutable.update { it.copy(favorites = favorites?.map { a -> a.id }?.toSet().orEmpty(), favoritesLoaded = favorites != null) }
            when (source) {
                EmojiSource.OFFICIAL -> {
                    val pack = packRequest?.await()
                    mutable.update { it.copy(pack = pack, assets = pack?.items.orEmpty()) }
                }
                EmojiSource.FAVORITES -> mutable.update { it.copy(assets = favorites.orEmpty()) }
                EmojiSource.MINE -> {
                    val mine = mineRequest?.await()
                    mutable.update { it.copy(assets = mine.orEmpty()) }
                }
            }
        } } catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(error = emojiError(e)) } }
        finally { mutable.update { it.copy(loading = false) } }
    }
    suspend fun favorite(asset: EmojiAsset) = mutate {
        if (asset.id in mutable.value.favorites) {
            gateway.unfavorite(sessionId, asset.id)
            // 服务端成功后才移本地，不能先假取消。
            mutable.update { it.copy(favorites = it.favorites - asset.id,
                assets = if (it.source == EmojiSource.FAVORITES) it.assets.filterNot { a -> a.id == asset.id } else it.assets) }
        } else {
            gateway.favorite(sessionId, asset.id)
            mutable.update { it.copy(favorites = it.favorites + asset.id) }
        }
    }
    suspend fun remove(asset: EmojiAsset) = mutate {
        gateway.remove(sessionId, asset.id)
        mutable.update { it.copy(assets = it.assets.filterNot { a -> a.id == asset.id }, favorites = it.favorites - asset.id) }
    }
    suspend fun upload(read: suspend () -> Pair<ByteArray, String>) = mutate {
        val cap = gateway.capabilities(sessionId)
        mutable.update { it.copy(capabilities = cap) }
        check(cap.canUploadImage) { cap.reason ?: "需要等级超过3级或有效学生认证才能上传图片" }
        val (bytes, mime) = read()
        val asset = gateway.upload(sessionId, bytes, mime)
        mutable.update { it.copy(source = EmojiSource.MINE, pack = null,
            assets = listOf(asset) + it.assets.filter { a -> a.id != asset.id }.takeIf { _ -> it.source == EmojiSource.MINE }.orEmpty(),
            notice = if (asset.sendable) "上传完成，可点击插入" else "上传完成，待审核通过后可发送") }
    }
    fun message(message: String) { mutable.update { it.copy(error = message) } }
    /** 列表分单元降级：收藏出错不抹掉能读取的官方包；权限未知仍禁发图。 */
    private suspend fun <T> fetch(action: suspend () -> T): T? = try { action() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            mutable.update { it.copy(error = listOfNotNull(it.error, emojiError(e)).distinct().joinToString("；")) }
            null
        }
    private suspend fun mutate(action: suspend () -> Unit) {
        if (mutable.value.busy || mutable.value.loading) return
        mutable.update { it.copy(busy = true, error = null, notice = null) }
        try { action() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { mutable.update { it.copy(error = emojiError(e)) } }
        finally { mutable.update { it.copy(busy = false) } }
    }
}
internal fun emojiError(e: Exception): String = if (e is HttpException) when (e.code()) {
    401 -> "登录已过期，请重新登录"
    403 -> "没有图片权限，需要等级超过3级或有效学生认证"
    404 -> "表情服务尚未上线，请稍后重试；文字表情仍可使用"
    409 -> "收藏最多20个，请先取消一些收藏"
    413 -> "图片超过大小限制：静图5MB、GIF3MB"
    else -> "表情服务请求失败（HTTP ${e.code()}），请重试"
} else e.message?.takeIf { it.isNotBlank() } ?: "表情操作失败，请重试"
