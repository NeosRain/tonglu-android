package cn.tonglu.app.data.community

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class EmojiPickerTest {
    private val asset = EmojiAsset("a", "https://cdn.example.test/a.png", reviewState = "CLEAR")
    private class Fake(private val item: EmojiAsset) : EmojiGateway {
        var allow = true
        var favorites = listOf<EmojiAsset>()
        var failure: Exception? = null
        var favoritesError = false
        var capabilitiesError = false
        var uploaded = 0
        var uploadResult = item.copy(reviewState = "UNREVIEWED")
        override suspend fun capabilities(sessionId: String): CommunityCapabilities {
            if (capabilitiesError) throw IllegalStateException("权限读取失败")
            return CommunityCapabilities(allow, "需要学生认证")
        }
        override suspend fun pack(sessionId: String) = EmojiPack("pack", "原创示例", "测试", "https://example.test", "本地", listOf(item))
        override suspend fun favorites(sessionId: String): List<EmojiAsset> {
            if (favoritesError) throw IllegalStateException("收藏读取失败")
            return favorites
        }
        override suspend fun mine(sessionId: String) = listOf(item)
        override suspend fun favorite(sessionId: String, id: String) { failure?.let { throw it }; favorites = listOf(item) }
        override suspend fun unfavorite(sessionId: String, id: String) { failure?.let { throw it }; favorites = emptyList() }
        override suspend fun remove(sessionId: String, id: String) { failure?.let { throw it } }
        override suspend fun upload(sessionId: String, data: ByteArray, mime: String): EmojiAsset { uploaded++; return uploadResult }
    }
    @Test fun onlyClearedReadyAssetsAreSendable() {
        assertTrue(asset.sendable)
        assertFalse(asset.copy(reviewState = "UNREVIEWED").sendable)
        assertFalse(asset.copy(status = "PENDING").sendable)
    }
    @Test fun imageMarkdownEscapesParentheses() {
        assertEquals("![表情](https://cdn.example.test/a%28b%29.png)", emojiMarkdown(asset.copy(publicUrl = "https://cdn.example.test/a(b).png")))
    }
    @Test fun rejectedAssetCannotBecomeMarkdown() {
        assertThrows(IllegalArgumentException::class.java) { emojiMarkdown(asset.copy(reviewState = "UNREVIEWED")) }
    }
    @Test fun linksMustBeHttpsAndWithoutCredentialsOrFragments() {
        for (url in listOf("http://x.test/a", "file:///a", "https://user:pass@x.test/a", "https://x.test/a#fragment")) {
            assertThrows(IllegalArgumentException::class.java) { emojiMarkdown(asset.copy(publicUrl = url)) }
        }
    }
    @Test fun gifAndStaticByteLimitsAreIndependent() {
        validateEmojiUpload(3*1024*1024, "image/gif")
        validateEmojiUpload(5*1024*1024, "image/png")
        assertThrows(IllegalArgumentException::class.java) { validateEmojiUpload(3*1024*1024+1,"image/gif") }
        assertThrows(IllegalArgumentException::class.java) { validateEmojiUpload(5*1024*1024+1,"image/jpeg") }
        assertThrows(IllegalArgumentException::class.java) { validateEmojiUpload(0,"image/png") }
        assertThrows(IllegalArgumentException::class.java) { validateEmojiUpload(100,"text/html") }
    }
    @Test fun realSignaturesAreUsedInsteadOfFilename() {
        assertEquals("image/gif", sniffEmojiMime("GIF89a".toByteArray()))
        assertEquals("image/png", sniffEmojiMime(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)))
        assertEquals("image/jpeg", sniffEmojiMime(byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte())))
        assertEquals("image/webp", sniffEmojiMime("RIFF0000WEBP".toByteArray()))
        assertThrows(IllegalStateException::class.java) { sniffEmojiMime("<html>".toByteArray()) }
    }
    @Test fun officialPackSurvivesFavoritesFailure() = runBlocking {
        val fake = Fake(asset).apply { favoritesError = true }
        val controller = EmojiPickerController(fake,"owner-a")
        controller.load(EmojiSource.OFFICIAL)
        assertEquals(listOf(asset),controller.state.value.assets)
        assertTrue(controller.state.value.error!!.contains("收藏读取失败"))
        assertFalse(controller.state.value.favoritesLoaded)
    }
    @Test fun unknownPermissionDoesNotHideReadableAssetsOrAllowSend() = runBlocking {
        val controller = EmojiPickerController(Fake(asset).apply { capabilitiesError = true }, "owner-a")
        controller.load(EmojiSource.OFFICIAL)
        assertEquals(1, controller.state.value.assets.size)
        assertNull(controller.state.value.capabilities)
        assertNotNull(controller.state.value.error)
    }
    @Test fun favoriteUpdatesOnlyAfterServerSuccess() = runBlocking {
        val fake = Fake(asset)
        val controller = EmojiPickerController(fake,"owner-a")
        controller.load(EmojiSource.OFFICIAL)
        fake.failure = HttpException(Response.error<Unit>(409,"{}".toResponseBody()))
        controller.favorite(asset)
        assertTrue(controller.state.value.favorites.isEmpty())
        assertTrue(controller.state.value.error!!.contains("20"))
        assertFalse(controller.state.value.busy)
        fake.failure = null
        controller.favorite(asset)
        assertEquals(setOf("a"),controller.state.value.favorites)
    }
    @Test fun failedDeleteKeepsAssetAndFavorite() = runBlocking {
        val fake = Fake(asset).apply { favorites = listOf(asset); failure = IllegalStateException("删除失败") }
        val controller = EmojiPickerController(fake,"owner-a")
        controller.load(EmojiSource.MINE)
        controller.remove(asset)
        assertEquals(listOf(asset), controller.state.value.assets)
        assertEquals(setOf("a"),controller.state.value.favorites)
        assertNotNull(controller.state.value.error)
    }
    @Test fun unfavoriteRemovesOnlyAfterServerSuccess() = runBlocking {
        val fake = Fake(asset).apply { favorites = listOf(asset); failure = IllegalStateException("取消失败") }
        val controller = EmojiPickerController(fake,"owner-a")
        controller.load(EmojiSource.FAVORITES)
        controller.favorite(asset)
        assertEquals(1,controller.state.value.assets.size)
        fake.failure = null
        controller.favorite(asset)
        assertTrue(controller.state.value.assets.isEmpty())
        assertTrue(controller.state.value.favorites.isEmpty())
    }
    @Test fun revokedPermissionStopsBeforeReadingFile() = runBlocking {
        val fake = Fake(asset).apply { allow = false }
        val controller = EmojiPickerController(fake,"owner-a")
        var read = false
        controller.upload { read = true; byteArrayOf(1) to "image/png" }
        assertFalse(read)
        assertEquals(0,fake.uploaded)
        assertNotNull(controller.state.value.error)
    }
    @Test fun successfulUnreviewedUploadIsNotReportedAsSendable() = runBlocking {
        val controller = EmojiPickerController(Fake(asset),"owner-a")
        controller.upload { byteArrayOf(1) to "image/png" }
        assertTrue(controller.state.value.notice!!.contains("待审核"))
        assertFalse(controller.state.value.assets.single().sendable)
    }
    @Test fun cancellationIsNeverConvertedToSuccessOrError() = runBlocking {
        val fake = Fake(asset).apply { failure = CancellationException("退出") }
        val controller = EmojiPickerController(fake,"owner-a")
        try { controller.favorite(asset); fail("取消必须向上抛") }
        catch (_: CancellationException) { assertFalse(controller.state.value.busy) }
        assertTrue(controller.state.value.favorites.isEmpty())
        assertNull(controller.state.value.error)
    }
    @Test fun differentOwnersDoNotSharePrivateState() = runBlocking {
        val fake = Fake(asset)
        val first = EmojiPickerController(fake,"owner-a")
        first.load(EmojiSource.OFFICIAL); first.favorite(asset)
        val second = EmojiPickerController(Fake(asset),"owner-b")
        assertTrue(second.state.value.favorites.isEmpty())
        assertTrue(second.state.value.assets.isEmpty())
    }
    @Test fun sendingRechecksPermissionAfterInsertion() = runBlocking {
        val fake = Fake(asset)
        assertEmojiSendPermission(fake, "owner-a")
        fake.allow = false
        try { assertEmojiSendPermission(fake,"owner-a"); fail("撤销后不得沿用旧权限") }
        catch (e: IllegalStateException) { assertEquals("需要学生认证", e.message) }
    }
    @Test fun sendingCannotBypassMissingGatewayOrSession() = runBlocking {
        try { assertEmojiSendPermission(null,"owner-a"); fail("缺少接口不能发图片") }
        catch (_: IllegalStateException) { }
        try { assertEmojiSendPermission(Fake(asset),null); fail("缺少会话不能发图片") }
        catch (_: IllegalStateException) { }
    }
}
