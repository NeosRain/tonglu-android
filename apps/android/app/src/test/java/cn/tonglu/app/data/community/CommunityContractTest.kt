package cn.tonglu.app.data.community

import cn.tonglu.app.data.CreatePostRequest
import cn.tonglu.app.data.CreateReportRequest
import cn.tonglu.app.data.PostDetailDto
import cn.tonglu.app.data.PostDto
import cn.tonglu.app.data.TongluApi
import com.google.gson.Gson
import kotlin.reflect.KClass
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.Request
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class CommunityContractTest {
    private val gson = Gson()

    @Test fun feedDecodesTopicJoinAndPrivateClubRouteFields() {
        val post = gson.fromJson(
            """{"id":"post","category":"HELP","body":"正文","boardId":"board","clubId":"club","channelId":"channel",
                "visibility":"COHORT","topics":[{"topic":{"id":"topic","name":"开学"}}],
                "pinnedAt":"2026-11-21T12:00:00Z","featuredAt":null}""",
            PostDto::class.java,
        )
        assertEquals("board", post.boardId)
        assertEquals("club", post.clubId)
        assertEquals("channel", post.channelId)
        assertEquals("COHORT", post.visibility)
        assertEquals("开学", post.topics?.single()?.topic?.name)
        assertEquals("2026-11-21T12:00:00Z", post.pinnedAt)
        assertNull(post.featuredAt)
    }

    @Test fun legacyDetailCanOmitCommunityFields() {
        val post = gson.fromJson("""{"id":"post","category":"WALL","body":"正文"}""", PostDetailDto::class.java)
        assertNull(post.boardId)
        assertTrue(post.topics.orEmpty().isEmpty())
        assertEquals("正文", post.body)
    }

    @Test fun createPayloadKeepsLegacyCategoryAndCommunityBoardSeparate() {
        val legacy = gson.toJsonTree(CreatePostRequest("WALL", "正文")).asJsonObject
        assertEquals("WALL", legacy["category"].asString)
        assertFalse(legacy.has("boardId"))
        assertFalse(legacy.has("topics"))
        val modern = gson.toJsonTree(CreatePostRequest(null, "正文", boardId = "board", topics = listOf("topic"))).asJsonObject
        assertEquals("board", modern["boardId"].asString)
        assertEquals("topic", modern["topics"].asJsonArray.single().asString)
        assertFalse(modern.has("category"))
    }

    @Test fun reportCategoryIsAbsentForLegacyReports() {
        val legacy = gson.toJsonTree(CreateReportRequest("不实信息")).asJsonObject
        assertFalse(legacy.has("category"))
        val modern = gson.toJsonTree(CreateReportRequest("跑题", category = PostReportCategory.OFF_TOPIC.name)).asJsonObject
        assertEquals("OFF_TOPIC", modern["category"].asString)
    }

    @Test fun legacyDraftMapsOnlyToMatchingActiveOfficialBoard() {
        val help = BoardDto("official:HELP", "求助", "", "OFFICIAL", "HELP", null, "ACTIVE")
        val userBoard = help.copy(id = "new-board", type = "USER")
        assertEquals(help, officialBoardForCategory("HELP", listOf(userBoard, help)))
        assertNull(officialBoardForCategory("HELP", listOf(userBoard, help.copy(status = "PENDING"))))
        assertNull(officialBoardForCategory("QUESTION", listOf(help)))
    }

    @Test fun topicSelectionRejectsDuplicatesAndAFourthTopic() {
        val two = listOf("a", "b")
        assertEquals(two, addPostTopic(two, "b"))
        val three = addPostTopic(two, "c")
        assertEquals(listOf("a", "b", "c"), three)
        assertEquals(three, addPostTopic(three, "d"))
    }

    @Test fun communityPreferenceMigratesEitherOldSwitchAndHonorsExplicitChoice() {
        assertFalse(communityEnabled(null, square = false, clubs = false))
        assertTrue(communityEnabled(null, square = true, clubs = false))
        assertTrue(communityEnabled(null, square = false, clubs = true))
        assertTrue(communityEnabled(null, square = true, clubs = true))
        assertFalse(communityEnabled(false, square = true, clubs = true))
        assertTrue(communityEnabled(true, square = false, clubs = false))
    }

    @Test fun missingCommunityFeedEnablesLegacyMode() = runBlocking {
        assertFalse(apiWithStatuses("/posts/feed" to 404).supportsCommunity())
    }

    @Test fun missingBoardDirectoryEnablesLegacyMode() = runBlocking {
        assertFalse(apiWithStatuses("/boards" to 404).supportsCommunity())
    }

    @Test fun backendFailureDoesNotEnableLegacyMode() = runBlocking {
        for (status in listOf(403, 500)) {
            val result = communityResult { apiWithStatuses("/posts/feed" to status).supportsCommunity() }
            assertEquals(status, (result.exceptionOrNull() as HttpException).code())
        }
    }

    @Test fun missingIndividualBoardDoesNotEnableLegacyMode() = runBlocking {
        val api = apiWithStatuses("/boards/missing" to 404)
        val result = communityResult { api.communityBoard("missing") }
        assertEquals(404, (result.exceptionOrNull() as HttpException).code())
        assertTrue(api.supportsCommunity())
    }

    @Test fun readReportsAddVisibleSecondsToServerBaselineAndRespectLimit() = runBlocking {
        val read = CommunityRead()
        val sent = mutableListOf<Int>()
        suspend fun send(request: RecordPostReadRequest): PostReadDto {
            sent += request.seconds
            return PostReadDto("first", maxOf(1750, request.seconds))
        }
        read.report(0, ::send)
        read.report(30, ::send)
        read.report(27, ::send)
        assertEquals(listOf(0, 1780, 1800), sent)
    }

    private fun apiWithStatuses(vararg statuses: Pair<String, Int>): TongluApi {
        val replies = statuses.toMap()
        val transport = Call.Factory { request -> TestCall(request, replies[request.url.encodedPath] ?: 200) }
        return Retrofit.Builder().baseUrl("https://test.invalid/").callFactory(transport)
            .addConverterFactory(GsonConverterFactory.create()).build().create(TongluApi::class.java)
    }

    private class TestCall(private val request: Request, private val status: Int) : Call {
        private val tags = mutableMapOf<Class<*>, Any>()
        private var executed = false
        private var cancelled = false
        override fun request() = request
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun cancel() { cancelled = true }
        override fun <T : Any> tag(type: KClass<T>): T? = tag(type.java)
        override fun <T> tag(type: Class<out T>): T? = type.cast(tags[type])
        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T = tag(type.java, computeIfAbsent)
        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
            type.cast(tags.getOrPut(type, computeIfAbsent))
        override fun clone(): Call = TestCall(request, status)
        override fun enqueue(responseCallback: Callback) = responseCallback.onResponse(this, execute())
        override fun execute(): Response {
            executed = true
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status)
                .message("Test response").body("[]".toResponseBody("application/json".toMediaType())).build()
        }
    }
}
