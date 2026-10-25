package cn.tonglu.app.data.community

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EmojiContractTest {
    private class PackApi : CommunityEmojiApi {
        var bearer: String? = null
        override suspend fun pack(bearer: String, id: String): EmojiPack {
            this.bearer = bearer
            // 严格照A的pack投影：没有kind/status/reviewState/mime，而不是完整资产夹具。
            return Gson().fromJson("""{"id":"noto-emoji-v2.047","license":"Apache-2.0","licenseUrl":"https://www.apache.org/licenses/LICENSE-2.0","source":"原创测试","attribution":"本地测试","items":[{"id":"a","glyph":"😀","publicUrl":"https://cdn.example.test/a.png","width":128,"height":128,"bytes":123}]}""", EmojiPack::class.java)
        }
        override suspend fun capabilities(bearer: String) = CommunityCapabilities(true)
        override suspend fun favorites(bearer: String) = emptyList<EmojiAsset>()
        override suspend fun mine(bearer: String) = emptyList<EmojiAsset>()
        override suspend fun favorite(bearer: String, id: String) { }
        override suspend fun unfavorite(bearer: String, id: String) { }
        override suspend fun remove(bearer: String, id: String) { }
        override suspend fun begin(bearer: String, input: EmojiUploadRequest): EmojiUploadTicket = error("测试不上传")
        override suspend fun complete(bearer: String, id: String): EmojiAsset = error("测试不上传")
    }
    @Test fun realPackProjectionCanBeNormalizedWithoutKotlinDefaultAssumptions() = runBlocking {
        val api = PackApi()
        var requestedSession: String? = null
        val auth = object : EmojiAuthorization {
            override suspend fun <T> execute(sessionId: String, request: suspend (String) -> T): T {
                requestedSession = sessionId
                return request("Bearer test-token")
            }
        }
        val result = CommunityEmojiRepository(api,auth).pack("owner-a")
        assertEquals("owner-a", requestedSession)
        assertEquals("Bearer test-token",api.bearer)
        assertEquals("STATIC",result.items.single().kind)
        assertTrue(result.items.single().sendable)
        assertEquals("![表情](https://cdn.example.test/a.png)",emojiMarkdown(result.items.single()))
    }
    @Test fun uploadReuseProjectionHasNoUrlOrMethodAndMustNotBeTreatedAsFreshUpload() {
        val reused = Gson().fromJson("""{"id":"a","status":"READY","reused":true}""",EmojiUploadTicket::class.java)
        assertTrue(reused.reused)
        assertNull(reused.uploadUrl)
        assertNull(reused.method)
    }
}
