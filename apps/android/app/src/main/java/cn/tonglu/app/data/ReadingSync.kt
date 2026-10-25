package cn.tonglu.app.data

/**
 * 云端阅读数据（后端 `GET/PUT/DELETE /sync/reading`）。
 *
 * 和课表档案分开一张表、分开一个 version：读书进度每翻几页就写一次，
 * 共用一个乐观锁的话，读书会把课表推到 409（反之亦然）。见 `backend/src/sync/sync.service.ts`。
 *
 * payload 就用 [ReadingLibraryData] 原样打包 —— 服务端不解释内容，只校验体积（默认 2MB），
 * 所以以后给书签/笔记加字段不用改服务端。
 *
 * 字段可空：Gson 不套 Kotlin 默认值，缺字段就是 null。
 */
data class ReadingSnapshotDto(
    val version: Int = 0,
    val payload: ReadingLibraryData? = null,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
)

/** 整包上传。baseVersion 是乐观锁（见 `PutProfileRequest`）。 */
data class PutReadingRequest(
    val baseVersion: Int,
    val payload: ReadingLibraryData,
    val clientId: String = "android",
)

data class ReadingPutResponse(val version: Int = 0, val updatedAt: String? = null)

// -------------------------------------------------------------------------- 正文 feed

/** `GET /learning/feed/meta`。几 KB，客户端先拿它比版本。 */
data class LearningFeedMeta(
    val enabled: Boolean = false,
    val revision: String = "",
    val checkedAt: String? = null,
    val bytes: Int = 0,
    val articles: List<LearningFeedEntry>? = null,
) {
    val entries: List<LearningFeedEntry> get() = articles.orEmpty()
    fun revisionOf(id: String): String? = entries.firstOrNull { it.id == id }?.revision
}

data class LearningFeedEntry(
    val id: String = "",
    val revision: String = "",
    val sourceRevision: String = "",
    val bytes: Int = 0,
    val status: String = "ok",
    val fetchedAt: String? = null,
) {
    val usable: Boolean get() = status != "error" && revision.isNotEmpty()
}

/** `GET /learning/feed/article/:id`。 */
data class LearningFeedBody(
    val id: String = "",
    val revision: String? = null,
    val sourceRevision: String? = null,
    val body: String? = null,
    val bytes: Int = 0,
) {
    val text: String get() = body.orEmpty()
    val stamp: String get() = revision.orEmpty()
}
