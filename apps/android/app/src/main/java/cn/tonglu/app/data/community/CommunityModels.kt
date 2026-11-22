package cn.tonglu.app.data.community

import cn.tonglu.app.data.TongluApi
import cn.tonglu.app.data.toFriendlyMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

data class BoardDto(
    val id: String,
    val name: String,
    val description: String,
    val type: String,
    val defaultCategory: String,
    val clubId: String?,
    val status: String,
)

data class TopicDto(val id: String, val name: String, val description: String?, val postCount: Int?)
data class PostTopicDto(val topic: TopicDto)
data class CommunityFollowDto(val following: Boolean)
data class CommunityLevelDto(val trustLevel: Int, val muted: Boolean)
data class ApplyBoardRequest(val name: String, val description: String)
data class CreateTopicRequest(val name: String)
data class RecordPostReadRequest(val seconds: Int)
data class PostReadDto(val firstReadAt: String, val seconds: Int)
data class ModerateBoardPostRequest(val action: String, val targetBoardId: String? = null, val reason: String? = null)
data class BoardMuteRequest(val minutes: Int, val reason: String)
data class BoardReportDecision(val action: String, val reason: String)
data class BoardTrashDto(val id: String, val title: String?, val deletedAt: String)
data class BoardReportDto(val id: String, val title: String?, val body: String, val reports: List<BoardReportReasonDto>)
data class BoardReportReasonDto(val category: String?, val reason: String, val details: String?)

internal enum class PostReportCategory(val label: String) {
    OFF_TOPIC("跑题"), UNFRIENDLY("不友善"), SPAM("垃圾广告"), ILLEGAL("违法违规"), OTHER("其他"),
}

internal const val MAX_POST_TOPICS = 3
internal const val COMMUNITY_PREF = "community"

internal fun addPostTopic(ids: List<String>, id: String): List<String> =
    if (id in ids || ids.size >= MAX_POST_TOPICS) ids else ids + id

internal fun officialBoardForCategory(category: String, boards: List<BoardDto>): BoardDto? =
    boards.firstOrNull { it.id == "official:$category" && it.type == "OFFICIAL" && it.status == "ACTIVE" }

internal fun communityEnabled(saved: Boolean?, square: Boolean, clubs: Boolean) = saved ?: (square || clubs)

/** 只有公开、固定的入口可以判定旧服务。圈子或帖子的 404 仍是资源错误。 */
internal suspend fun TongluApi.supportsCommunity(): Boolean = try {
    communityFeed("recommended", null, 0)
    communityBoards()
    true
} catch (error: HttpException) {
    if (error.code() != 404) throw error
    false
}

internal inline fun <T> communityResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}

internal fun communityErrorMessage(error: Throwable, fallback: String): String {
    if (error !is HttpException) return fallback
    if (error.code() == 404 || error.code() >= 500) return fallback
    val message = error.toFriendlyMessage()
    return message.takeIf { it != "请求失败（${error.code()}）" && it.any { char -> char in '\u4e00'..'\u9fff' } }
        ?: if (error.code() == 403) "你没有权限执行此操作" else fallback
}

/** 服务端只接收累计值。先读取已有秒数，串行提交同一次打开中的各段时间。 */
internal class CommunityRead {
    private val mutex = Mutex()
    private var baseline: Int? = null
    private var elapsed = 0

    suspend fun report(seconds: Int, send: suspend (RecordPostReadRequest) -> PostReadDto) = mutex.withLock {
        elapsed = (elapsed + seconds).coerceAtMost(1800)
        val previous = baseline ?: send(RecordPostReadRequest(0)).seconds.also { baseline = it }
        if (seconds > 0 && previous < 1800) send(RecordPostReadRequest((previous + elapsed).coerceAtMost(1800)))
    }
}
