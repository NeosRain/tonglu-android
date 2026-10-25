package cn.tonglu.app.data

import cn.tonglu.app.data.listening.QuestionAttempt

/**
 * 云端作答记录（后端 `GET/PUT /sync/practice` 的 `payload`，docs/plan/57 §5.3 T5）。
 *
 * 后端**不解释内容、只校验体积** —— 题目键的语义由客户端定义（docs/plan/57 §一），
 * 服务端当不透明数组搬运。这样改题面格式（比如以后加题型）不用动服务端。
 *
 * `attempts` 就是本机 `PracticeStore` 里那份记录原样上传；
 * 每条的 `questionKey` 是全局唯一的 `<bankId>:<questionId>`，
 * 跨设备恢复时靠它去重、靠 `id` 做幂等。
 *
 * 字段**全部可空**：Gson 反序列化缺失字段时不会套用 Kotlin 默认值，全空能避免 NPE。
 */
data class PracticePayload(
    val attempts: List<PracticeAttemptDto>? = null,
    /** 写入时间；仅用于排查「谁覆盖了谁」，客户端自己打 */
    val savedAt: String? = null,
)

/**
 * 一条作答记录（与 `PracticeStore.QuestionAttempt` 一一对应）。
 *
 * `questionId` **保留**（新老字段都在）：老的同步数据里可能还没有 `questionKey`，
 * 收到这种记录时客户端按 `legacy:<materialId>:<questionId>` 就地补键（同一套迁移规则）。
 */
data class PracticeAttemptDto(
    val id: String? = null,
    val questionId: String? = null,
    val questionKey: String? = null,
    val materialId: String? = null,
    val materialTitle: String? = null,
    val chosen: Int? = null,
    val correct: Boolean? = null,
    val answeredAt: Long? = null,
)

/** `GET /sync/practice` 响应。`version=0, payload=null` = 云端还没有记录。 */
data class PracticeSnapshotDto(
    val version: Int = 0,
    val payload: PracticePayload? = null,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
)

// ------------------------------------------------------------------ 增量同步（B6）

/**
 * 一条作答事件（`POST /sync/practice/events` 的 items）。
 *
 * `kind` 两种：
 * - `attempt`：一次作答，`payload` 是一条 [PracticeAttemptDto]；
 * - `tombstone`：撤回某条作答，`payload` 只有 `id`（点在谁身上靠它）。
 *
 * 事件是**追加事实**而不是「当前状态」—— 服务端只按序号合流，不判断谁对。
 */
data class PracticeEventDto(
    val eventId: String,
    val kind: String,
    val payload: Map<String, Any?> = emptyMap(),
) {
    companion object {
        const val KIND_ATTEMPT = "attempt"
        const val KIND_TOMBSTONE = "tombstone"

        /** 一次作答 → 一条 attempt 事件。`eventId` 直接用作答自身的 id，天然幂等。 */
        fun ofAttempt(attempt: PracticeAttemptDto): PracticeEventDto = PracticeEventDto(
            eventId = attempt.id ?: return PracticeEventDto(
                eventId = java.util.UUID.randomUUID().toString(),
                kind = KIND_ATTEMPT,
            ),
            kind = KIND_ATTEMPT,
            payload = buildMap {
                put("id", attempt.id)
                put("questionId", attempt.questionId)
                put("questionKey", attempt.questionKey)
                put("materialId", attempt.materialId)
                put("materialTitle", attempt.materialTitle)
                put("chosen", attempt.chosen)
                put("correct", attempt.correct)
                put("answeredAt", attempt.answeredAt)
            },
        )
    }
}

data class PostPracticeEventsRequest(
    val events: List<PracticeEventDto>,
    val clientId: String = "android",
)

/**
 * 上传回执：`accepted` 是**本批真正写入的条数**；`cursor` 是**该用户事件的最大序号**。
 *
 * ⚠️ `cursor` **不能当成本机的下载游标**（`docs/reviews/2026-10-06-FINDINGS.md` #2）：
 * 它只说明服务端收到了哪些，不代表本机已经**读到并落地**其中每一条。
 * 拿它推进下载游标会跳过别的设备在同一时刻写的事件，而且永久补不回来。
 * 下载游标只能由 `GET sync/practice/changes` 里真正处理完的那几页推进。
 */
data class PracticeEventsAckDto(
    val accepted: Int = 0,
    val cursor: Long = 0,
)

/** 服务端回的一条增量事件（带服务端序号与墓碑标记）。 */
data class PracticeChangeItemDto(
    val seq: Long = 0,
    val eventId: String? = null,
    val kind: String? = null,
    val payload: Map<String, Any?>? = null,
    val deletedAt: String? = null,
)

/**
 * `GET /sync/practice/changes` 响应。
 *
 * `nextCursor` 是**本页最后一条的 seq**（不是全局最新）——
 * 一页拉满时要带着它继续，跳到全局最新会漏掉中间的事件。
 * 没有更多时 `nextCursor == cursor`（不再前进），客户端据此停下。
 */
data class PracticeChangesDto(
    val items: List<PracticeChangeItemDto> = emptyList(),
    val nextCursor: Long = 0,
    val hasMore: Boolean = false,
)

/**
 * 云端增量事件 → 本机记录。**只处理 `attempt`** —— 墓碑不是「新增一条」，
 * 它由 [practiceMergeEvents] 单独翻译成 `tombstone`。
 *
 * 缺 `id` 的返回 null（没有身份的记录没法判重、也没法被墓碑命中）。
 * 缺 `questionKey` 的交给 `withMigratedKey()` 补（与本地迁移同一套规则）。
 */
internal fun PracticeChangeItemDto.toAttemptOrNull(): QuestionAttempt? {
    if (!deletedAt.isNullOrBlank()) return null
    if (kind != null && kind != PracticeEventDto.KIND_ATTEMPT) return null
    val p = payload ?: return null
    val id = p["id"] as? String
    if (id.isNullOrBlank()) return null
    return QuestionAttempt(
        id = id,
        questionId = p["questionId"] as? String ?: "",
        questionKey = (p["questionKey"] as? String).orEmpty(),
        materialId = p["materialId"] as? String ?: "",
        materialTitle = p["materialTitle"] as? String ?: "",
        chosen = (p["chosen"] as? Number)?.toInt() ?: -1,
        correct = p["correct"] as? Boolean ?: false,
        answeredAt = (p["answeredAt"] as? Number)?.toLong() ?: 0L,
    )
}

/**
 * 把**一页**云端增量翻成本机可合并的事件列表，**保序**（按服务端 `seq` 升序）。
 *
 * ## 为什么保序是硬要求（FINDINGS #3）
 * 一页里常常既有「新增 A」也有「撤回 A」。以前是先把整页墓碑**全删一遍**、
 * 再把整页新增**全插一遍**，于是「先新增 A（seq 小）、后删除 A（seq 大）」这一页
 * 被重排成「删掉一个本机还没有的 A（空操作）→ 再把 A 加回来」——
 * **对方已经撤回的记录在本机复活**，而它的墓碑已经消费过了，永远不会再被删掉。
 * 反过来「先删后加」被重排成「先加再删」，则是把别人刚做的题抹掉。
 * 顺序错了就是错了，不存在「大约正确」。
 *
 * ## 这里只翻译、不落地
 * 落地交给 `PracticeStore.applyChanges`，它对事件列表是**逐条按列表顺序**执行的
 * （见 `mergeByEvents`）。所以整页一次合并 = 顺序正确 **且** 只读写一次盘，
 * 比「每条各自 read-modify-write」省掉 99 次磁盘往返。
 *
 * 缺 `id` 的事件在这里就丢掉 —— 没有身份的记录没法判重、也没法被墓碑命中，
 * 存进去只会变成永远删不掉的幽灵。
 */
internal fun practiceMergeEvents(page: List<PracticeChangeItemDto>): List<PracticeEventDto> =
    page.sortedBy { it.seq }.mapNotNull { item ->
        val payload = item.payload
        val id = payload?.get("id") as? String
        if (id.isNullOrBlank()) return@mapNotNull null
        if (!item.deletedAt.isNullOrBlank()) {
            PracticeEventDto(
                eventId = item.eventId ?: id,
                kind = PracticeEventDto.KIND_TOMBSTONE,
                payload = mapOf("id" to id),
            )
        } else {
            val attempt = item.toAttemptOrNull() ?: return@mapNotNull null
            PracticeEventDto(
                eventId = item.eventId ?: attempt.id,
                kind = PracticeEventDto.KIND_ATTEMPT,
                payload = mapOf(
                    "id" to attempt.id,
                    "questionId" to attempt.questionId,
                    "questionKey" to attempt.questionKey,
                    "materialId" to attempt.materialId,
                    "materialTitle" to attempt.materialTitle,
                    "chosen" to attempt.chosen,
                    "correct" to attempt.correct,
                    "answeredAt" to attempt.answeredAt,
                ),
            )
        }
    }

// ------------------------------------------------------------------ 数量契约

/**
 * 单批上传上限 —— **后端 DTO 的硬限制**（`arrayMaxSize: 200`，见
 * `backend/src/sync/sync.dto.ts`）。一次把本机全部作答塞进一个请求，
 * 历史累计超过 200 条之后就是**每次都被 400 拒回来**，而且重试发的还是同一个超包体，
 * 表现为「老用户永远同步不上」（FINDINGS #1）。所以必须分批。
 *
 * ⚠️ 这个数字和后端那个是**同一个契约的两端**，改一边必须改另一边 ——
 * `scripts/audit_cross_end_limits.py` 守着这条（只改一边会悄悄复发）。
 */
internal const val PRACTICE_UPLOAD_BATCH = 200

/**
 * 增量拉取每页条数与最大轮次。
 *
 * 每页 100 是**服务端硬上限**（`GetPracticeChangesQueryDto` 的 `@Max(100)`），
 * 调大只会被拒。所以积压能一次追平多少，全看轮次：50 轮 = 5000 条/次
 * （FINDINGS #2 要求「超过 2000 条积压仍无遗漏」，20 轮刚好卡在 2000，不够）。
 * 再往上加会撞服务端限流（该接口 120 次/分钟），不如**留着游标下次继续** ——
 * 每一页落地后都会先记游标，中断不会丢进度。
 */
internal const val PRACTICE_PULL_LIMIT = 100
internal const val PRACTICE_MAX_ROUNDS = 50

// ------------------------------------------------------------------ 拉取 / 推送的规则（可测）

/**
 * 下载游标推进规则。
 *
 * 只在 `nextCursor` **真的更大**时才前进：服务端没有更多时会回传等于当前游标的值，
 * 也可能因为序号回退给出更小的值。**倒退**会让本机重复消费已经处理过的页 ——
 * 新增是幂等的、墓碑第二次是空操作，看起来没事，但游标一旦退回去
 * 就会反复拉同一批，别的设备新写的那批永远追不上。
 */
internal fun practiceNextCursor(current: Long, page: PracticeChangesDto): Long =
    if (page.nextCursor > current) page.nextCursor else current

/**
 * 还要不要再拉一页。
 *
 * `hasMore=true` **但本页是空的**时必须停 —— 否则就是死循环
 * （服务端「还有」但一次也不给，客户端永远拉不完，同步整天卡在那）。
 */
internal fun practiceHasNextPage(page: PracticeChangesDto): Boolean =
    page.hasMore && page.items.isNotEmpty()

/** 一轮拉取的结果：[cursor] 是已推进到的位置，下次从这里继续。 */
internal data class PracticePullResult(
    val cursor: Long,
    /** 轮次触顶而停（不是拉完）。为 true 时**还有积压**，下次同步会接着追。 */
    val cappedAtRoundLimit: Boolean = false,
)

/**
 * 一整轮增量拉取：**拉到穷尽 / 触顶为止**。
 *
 * 网络与存储由 [fetch] / [apply] / [onCursor] 注入，所以这段控制流**能在 JVM 单测里
 * 直接跑**（`PracticeSyncRulesTest`）—— 上一版它写死在 `AppViewModel` 的私有方法里，
 * 那是个 Android ViewModel，单测起不来，改坏了没人拦得住。
 *
 * 三条规则全在这里，别在调用方重建：
 * 1. **先 [apply] 再推进游标**（FINDINGS #2）。整页处理失败（抛异常）时游标**原地不动**，
 *    下次从同一处重拉；反过来「先推进再处理」，半页失败就**永久漏掉**那批事件。
 * 2. **游标只前进不后退**（[practiceNextCursor]），且只在真的前进后才 [onCursor] 落盘。
 * 3. 轮次触顶时**返回已推进到的位置**，下次接着追，而不是放弃从头再来。
 */
internal suspend fun practicePullLoop(
    startCursor: Long,
    maxRounds: Int = PRACTICE_MAX_ROUNDS,
    fetch: suspend (cursor: Long) -> PracticeChangesDto,
    onCursor: (Long) -> Unit = {},
    apply: suspend (page: PracticeChangesDto) -> Unit,
): PracticePullResult {
    require(maxRounds > 0) { "拉取轮次上限必须为正，否则一页都拉不到" }
    var cursor = startCursor
    var rounds = 0
    var capped = false
    while (rounds < maxRounds) {
        rounds++
        val page = fetch(cursor)
        apply(page)                                  // 1) 这里抛异常 → 游标不动
        val next = practiceNextCursor(cursor, page)
        if (next != cursor) {
            cursor = next
            onCursor(cursor)                         // 2) 只在真的前进后才落盘
        }
        if (!practiceHasNextPage(page)) return PracticePullResult(cursor)
        capped = true
    }
    return PracticePullResult(cursor, cappedAtRoundLimit = capped)   // 3)
}

/**
 * 本机待上传事件**分批**送出，返回服务端确认写入的条数之和。
 *
 * ⚠️ 回执里的 `cursor` **刻意不往外带**（FINDINGS #2）：它是「该用户事件的最大序号」，
 * 不代表本机已经**读到并落地**其中每一条。拿它推进下载游标，会跳过别的设备
 * 在同一时间写的那批，而且永久补不回来。让这个函数**结构上没有带回 cursor 的通道**，
 * 比在调用处写一行注释更靠得住 —— 下载游标只能由 [practicePullLoop] 推进。
 *
 * 分批本身是 FINDINGS #1 的修法：不分批的话，历史攒过 [PRACTICE_UPLOAD_BATCH] 条之后
 * 每次都发同一个超包体，被 400 拒回来，重试还是同一个包 —— **永远传不上去**。
 */
internal suspend fun practicePushBatches(
    events: List<PracticeEventDto>,
    batchSize: Int = PRACTICE_UPLOAD_BATCH,
    post: suspend (batch: List<PracticeEventDto>) -> PracticeEventsAckDto,
): Int {
    require(batchSize > 0) { "分批大小必须为正，否则会无限切分" }
    var accepted = 0
    events.chunked(batchSize).forEach { batch -> accepted += post(batch).accepted }
    return accepted
}

/**
 * 整包上传。[baseVersion] 是乐观锁：客户端必须带「上次看到的云端版本号」，
 * 服务端只在两者一致时才写，挡「离线已久的旧设备把已删记录复活」。
 */
data class PutPracticeRequest(
    val baseVersion: Int,
    val payload: PracticePayload,
    val clientId: String = "android",
)

data class PracticePutResponse(val version: Int = 0, val updatedAt: String? = null)
