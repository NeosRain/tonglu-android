package cn.tonglu.app.data

import android.content.Context
import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * 可同步的阅读数据：进度 / 书签 / 笔记。
 *
 * 和 `ReadingStore` 里那些「纯本机偏好」（字号、纸张、上次打开的文章）分开存，
 * 因为这几样要跟着账号走 —— 换台设备得能接着读。
 *
 * 存放位置：`filesDir/reading/<ownerHash>.json`，和 SharedPreferences 的
 * `reading@<ownerHash>` 用同一套身份分桶规则（`@<userId?:username?:"local">`），
 * 游客的数据将来登录后由 `claimLocalBucket()` 搬进账号桶。
 *
 * **字段全部可空**：Gson 绕过 Kotlin 构造器，JSON 里缺字段时非空类型会被塞成 null，
 * 一碰就 NPE。所以对外只暴露 orEmpty() 的访问器，别把这里的类型改成非空。
 */

/** 读到哪儿了。`documentPath` 为空串表示书籍的根文档（封面那篇）。 */
data class ReadingProgressRecord(
    var articleId: String? = null,
    var documentPath: String? = null,
    /** 归一化进度 0~1。**换排版、换字号之后依然能定位**，比裸 index 可靠。 */
    var ratio: Float = 0f,
    /** 精确恢复用的滚动位置；仅在 revision 相同时可信。 */
    var index: Int = 0,
    var offset: Int = 0,
    /** 正文指纹。变了说明排版/内容变了，只能退回按 ratio 定位。 */
    var revision: String? = null,
    var finished: Boolean = false,
    var updatedAt: Long = 0L,
    /** 墓碑：「继续读」里手动移掉一条也要能同步出去，否则对面会把旧的推回来。 */
    var deleted: Boolean = false,
) {
    val article: String get() = articleId.orEmpty()
    val document: String get() = documentPath.orEmpty()
    val score: Float get() = ratio.coerceIn(0f, 1f)
}

/** 书签。一篇文章可以有多枚，锚到标题锚点（抗重排）并留下摘录。 */
data class ReadingBookmarkRecord(
    var id: String? = null,
    var articleId: String? = null,
    var documentPath: String? = null,
    /** 所在标题的锚点，比 index 稳。 */
    var anchor: String? = null,
    var index: Int = 0,
    var offset: Int = 0,
    var excerpt: String? = null,
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,
    /** 墓碑：删掉的记录不直接抹掉，否则同步时会被另一台设备又推回来。 */
    var deleted: Boolean = false,
) {
    val key: String get() = id.orEmpty()
    val article: String get() = articleId.orEmpty()
    val document: String get() = documentPath.orEmpty()
    val text: String get() = excerpt.orEmpty()
}

/**
 * 笔记。两种形态共用一张表：
 *   - 整篇笔记：`startOffset = -1`（`isWholeArticle` 为真），正文写在 `body`；
 *   - 段落划线：记录被选中的区间与摘录，`body` 是随手写的想法。
 */
data class ReadingNoteRecord(
    var id: String? = null,
    var articleId: String? = null,
    var documentPath: String? = null,
    var startOffset: Int = -1,
    var endOffset: Int = -1,
    /** 选中的原文（用于重排后重新定位，也用于列表里展示）。 */
    var quote: String? = null,
    /** 划线前 24 字，重定位时用来消歧。 */
    var before: String? = null,
    var body: String? = null,
    var createdAt: Long = 0L,
    var updatedAt: Long = 0L,
    var deleted: Boolean = false,
) {
    val key: String get() = id.orEmpty()
    val article: String get() = articleId.orEmpty()
    val document: String get() = documentPath.orEmpty()
    val isWholeArticle: Boolean get() = startOffset < 0
    val text: String get() = body.orEmpty()
}

/** 落盘的整包结构。 */
data class ReadingLibraryData(
    var version: Int = 1,
    var progress: MutableList<ReadingProgressRecord>? = null,
    var bookmarks: MutableList<ReadingBookmarkRecord>? = null,
    var notes: MutableList<ReadingNoteRecord>? = null,
)

/** 界面用的计数。 */
data class ReadingCounts(val progress: Int = 0, val bookmarks: Int = 0, val notes: Int = 0) {
    val total: Int get() = progress + bookmarks + notes
}

/**
 * 阅读数据的本机库。所有写操作都会立刻落盘（文件小，不值得为它做脏标记）。
 *
 * 合并策略（换设备 / 云端回灌都走这里）：
 *   - 进度：同一篇同一文档只留一条，`updatedAt` 新的赢 —— 也就是「最近读它的那台设备说了算」；
 *   - 书签 / 笔记：按 id 合并，同 id 取 `updatedAt` 新者；删除用墓碑，避免被对面推回来。
 */
class ReadingLibrary private constructor(context: Context, owner: String) {
    private val file = File(File(context.filesDir, "reading"), "${hash(owner)}.json")
    /** `var` 而不是 `val`：从备份整包恢复时要整体换掉这份数据。 */
    private var data: ReadingLibraryData

    companion object {
        /**
         * 当前 owner 的那一份实例。
         *
         * **必须共用同一个对象**：这个类在内存里留了一份 `data`，写操作是「改内存 + 整包落盘」。
         * 阅读器（`ReadingStore.library`）和账号同步（AppViewModel）都会碰它，
         * 各自 new 一个的话，后落盘的那份会把对方的改动整体覆盖掉
         * （`ScheduleStore` 那种「每次现 new」的写法在这里是不安全的）。
         *
         * 只缓存当前 owner：切账号（游客 ↔ 登录）时换新实例、重新读盘，
         * 也顺手避免长期挂着已经不属于当前身份的桶。
         */
        private var cachedOwner: String? = null
        private var cached: ReadingLibrary? = null

        @Synchronized
        fun of(context: Context, owner: String): ReadingLibrary {
            val app = context.applicationContext
            if (cachedOwner != owner || cached == null) {
                cached = ReadingLibrary(app, owner)
                cachedOwner = owner
            }
            return cached!!
        }
    }

    init {
        data = read()
    }

    private fun hash(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun read(): ReadingLibraryData = runCatching {
        if (!file.exists()) return@runCatching ReadingLibraryData()
        Gson().fromJson(file.readText(), ReadingLibraryData::class.java) ?: ReadingLibraryData()
    }.getOrElse { ReadingLibraryData() }

    private fun flush() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Gson().toJson(data))
        }
    }

    private fun progressList() = data.progress ?: mutableListOf<ReadingProgressRecord>().also { data.progress = it }
    private fun bookmarkList() = data.bookmarks ?: mutableListOf<ReadingBookmarkRecord>().also { data.bookmarks = it }
    private fun noteList() = data.notes ?: mutableListOf<ReadingNoteRecord>().also { data.notes = it }

    // ---------------------------------------------------------------- 进度

    /** 原始记录（**含墓碑**）。对外展示请用 [articleProgress] / [recentProgress] / [counts]。 */
    fun progress(articleId: String, documentPath: String): ReadingProgressRecord? =
        progressList().firstOrNull { it.article == articleId && it.document == documentPath }

    /** 某篇文章的整体进度：所有章节按字数加权不现实，这里取「读得最靠后的一章」的比例。 */
    fun articleProgress(articleId: String): ReadingProgressRecord? =
        progressList().filter { it.article == articleId && !it.deleted }.let { alive ->
            alive.filter { !it.finished }.ifEmpty { alive }
        }.maxByOrNull { it.score }

    fun finishedCount(articleId: String): Int =
        progressList().count { it.article == articleId && !it.deleted && it.finished }

    fun saveProgress(
        articleId: String, documentPath: String, ratio: Float, index: Int, offset: Int,
        revision: String, finished: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val clamped = ratio.coerceIn(0f, 1f)
        val existing = progress(articleId, documentPath)
        if (existing == null) {
            // 打开就退出、压根没动过的不记账：否则一本 1000 章的书会在书架上留下一堆 0% 的空记录
            if (!finished && index <= 0 && offset <= 0) return
            progressList() += ReadingProgressRecord(
                articleId = articleId, documentPath = documentPath, ratio = clamped,
                index = index, offset = offset, revision = revision,
                finished = finished, updatedAt = now,
            )
        } else {
            existing.ratio = clamped
            existing.index = index
            existing.offset = offset
            existing.revision = revision
            // 已读完就不因为往前翻而取消
            existing.finished = existing.finished || finished
            existing.updatedAt = now
            // 从「继续读」里移掉过又读了回来，墓碑要消掉
            existing.deleted = false
        }
        flush()
    }

    /** 从「继续读」里移掉一条记录。走墓碑，这样删除动作能同步出去。 */
    fun removeProgress(articleId: String, documentPath: String) {
        val record = progress(articleId, documentPath) ?: return
        record.deleted = true
        record.updatedAt = System.currentTimeMillis()
        flush()
    }

    // ---------------------------------------------------------------- 书签

    fun bookmarks(articleId: String? = null, documentPath: String? = null): List<ReadingBookmarkRecord> =
        bookmarkList().filter {
            !it.deleted &&
                (articleId == null || it.article == articleId) &&
                (documentPath == null || it.document == documentPath)
        }.sortedByDescending { it.createdAt }

    fun bookmarkCount(articleId: String): Int = bookmarks(articleId).size

    fun addBookmark(
        articleId: String, documentPath: String, anchor: String?, index: Int, offset: Int,
        excerpt: String,
    ): ReadingBookmarkRecord {
        val now = System.currentTimeMillis()
        val record = ReadingBookmarkRecord(
            id = UUID.randomUUID().toString(), articleId = articleId, documentPath = documentPath,
            anchor = anchor, index = index, offset = offset, excerpt = excerpt,
            createdAt = now, updatedAt = now,
        )
        bookmarkList() += record
        flush()
        return record
    }

    fun removeBookmark(id: String) {
        val record = bookmarkList().firstOrNull { it.key == id } ?: return
        record.deleted = true
        record.updatedAt = System.currentTimeMillis()
        flush()
    }

    // ---------------------------------------------------------------- 笔记

    fun notes(articleId: String? = null, documentPath: String? = null): List<ReadingNoteRecord> =
        noteList().filter {
            !it.deleted &&
                (articleId == null || it.article == articleId) &&
                (documentPath == null || it.document == documentPath)
        }.sortedWith(compareByDescending<ReadingNoteRecord> { it.isWholeArticle }.thenByDescending { it.createdAt })

    fun noteCount(articleId: String): Int = notes(articleId).size

    fun upsertNote(
        id: String?, articleId: String, documentPath: String, startOffset: Int, endOffset: Int,
        quote: String, before: String, body: String,
    ): ReadingNoteRecord {
        val now = System.currentTimeMillis()
        val existing = id?.let { key -> noteList().firstOrNull { it.key == key } }
        if (existing != null) {
            existing.body = body
            existing.quote = quote
            existing.startOffset = startOffset
            existing.endOffset = endOffset
            existing.before = before
            existing.deleted = false
            existing.updatedAt = now
            flush()
            return existing
        }
        val record = ReadingNoteRecord(
            id = UUID.randomUUID().toString(), articleId = articleId, documentPath = documentPath,
            startOffset = startOffset, endOffset = endOffset, quote = quote, before = before,
            body = body, createdAt = now, updatedAt = now,
        )
        noteList() += record
        flush()
        return record
    }

    fun removeNote(id: String) {
        val record = noteList().firstOrNull { it.key == id } ?: return
        record.deleted = true
        record.updatedAt = System.currentTimeMillis()
        flush()
    }

    /** 摘录文本：把正文里的一段裁成一小句，给书签 / 笔记当标题用。 */
    fun excerpt(text: String, at: Int, length: Int = 42): String {
        if (text.isEmpty()) return ""
        val start = at.coerceIn(0, text.length)
        val end = (start + length).coerceAtMost(text.length)
        return text.substring(start, end).replace('\n', ' ').trim()
    }

    // ---------------------------------------------------------------- 同步

    /** 供界面展示的计数（只算没删的）。 */
    fun counts(): ReadingCounts = ReadingCounts(
        progress = progressList().count { !it.deleted && (it.ratio > 0.005f || it.finished) },
        bookmarks = bookmarkList().count { !it.deleted },
        notes = noteList().count { !it.deleted },
    )

    /**
     * 「继续读」列表：按最近阅读时间倒序。
     *
     * 过滤条件必须和 [counts] 一致，否则入口卡写「继续读 1 篇」、点进去却是 2 条，
     * 用户会以为丢了一条或者多了一条。
     */
    fun recentProgress(limit: Int = 20): List<ReadingProgressRecord> =
        progressList().filter { it.article.isNotEmpty() && !it.deleted && (it.ratio > 0.005f || it.finished) }
            .sortedByDescending { it.updatedAt }.take(limit)

    /** 导出给云端。墓碑要带上，否则删除动作传不出去。 */
    fun snapshot(): ReadingLibraryData = ReadingLibraryData(
        version = 1,
        progress = progressList().toMutableList(),
        bookmarks = bookmarkList().toMutableList(),
        notes = noteList().toMutableList(),
    )

    fun toJson(): String = Gson().toJson(snapshot())

    /**
     * 供**个人备份**导出（B6）。返回 `{progress, bookmarks, notes}` 的 JSONObject。
     *
     * 与 [toJson] 的区别：那个返回 Gson 序列化的 `ReadingLibraryData`（键名跟 data class 走），
     * 这个的形状**与云端 `PUT /sync/reading` 的 payload 一致** ——
     * 备份文件与云端快照用同一套形状，将来要从备份换成云端、或反过来，不用写第二套解析。
     */
    fun exportJson(): org.json.JSONObject = org.json.JSONObject(Gson().toJson(snapshot()))

    /**
     * 从备份**整包**恢复。**只在 [isEmpty] 为真时调用**（判断由调用方负责）。
     *
     * 与 `PracticeStore.restoreIfEmpty` 同一条保守策略：本机已经读过的进度、加过的书签笔记
     * 不该被一份备份抹掉 —— 换机导入发生在本机还是干净的时候，这个前提必须成立。
     *
     * @return 恢复出来的条数（进度 + 书签 + 笔记）；备份里这一段是空的则返回 0。
     */
    fun restoreFromBackup(json: org.json.JSONObject): Int {
        val parsed = runCatching {
            Gson().fromJson(json.toString(), ReadingLibraryData::class.java)
        }.getOrNull() ?: return 0
        if (parsed.progress.isNullOrEmpty() && parsed.bookmarks.isNullOrEmpty() && parsed.notes.isNullOrEmpty()) {
            return 0
        }
        data = parsed
        flush()
        return (parsed.progress?.size ?: 0) + (parsed.bookmarks?.size ?: 0) + (parsed.notes?.size ?: 0)
    }

    /** 空库（没有进度、书签、笔记）才允许整包换成云端内容 —— 和课表档案一个规矩。 */
    fun isEmpty(): Boolean = progressList().isEmpty() && bookmarkList().isEmpty() && noteList().isEmpty()

    /**
     * 与云端合并，返回是否有变化。
     *
     * 平局（两边 `updatedAt` 一样）一律留本机那份：本机刚发生的操作不该被覆盖，
     * 而且没有墓碑的一方本来就不该赢。
     */
    fun mergeFrom(remote: ReadingLibraryData?): Boolean {
        if (remote == null) return false
        var changed = false

        remote.progress.orEmpty().forEach { incoming ->
            if (incoming.article.isEmpty()) return@forEach
            val mine = progress(incoming.article, incoming.document)
            if (mine == null) {
                progressList() += incoming
                changed = true
            } else if (incoming.updatedAt > mine.updatedAt) {
                mine.ratio = incoming.score
                mine.index = incoming.index
                mine.offset = incoming.offset
                mine.revision = incoming.revision
                mine.finished = incoming.finished
                mine.deleted = incoming.deleted
                mine.updatedAt = incoming.updatedAt
                changed = true
            }
        }

        mergeById(remote.bookmarks.orEmpty(), bookmarkList(), { it.key }, { it.updatedAt }) { incoming, mine ->
            mine.anchor = incoming.anchor
            mine.index = incoming.index
            mine.offset = incoming.offset
            mine.excerpt = incoming.excerpt
            mine.deleted = incoming.deleted
            mine.updatedAt = incoming.updatedAt
        }.let { changed = changed || it }

        mergeById(remote.notes.orEmpty(), noteList(), { it.key }, { it.updatedAt }) { incoming, mine ->
            mine.startOffset = incoming.startOffset
            mine.endOffset = incoming.endOffset
            mine.quote = incoming.quote
            mine.before = incoming.before
            mine.body = incoming.body
            mine.deleted = incoming.deleted
            mine.updatedAt = incoming.updatedAt
        }.let { changed = changed || it }

        if (changed) flush()
        return changed
    }

    /**
     * 按 id 对齐合并（书签和笔记共用）：同 id 取 `updatedAt` 新者，删除靠 `deleted` 墓碑传播。
     *
     * 写成泛型而不是两个重载：`List<ReadingBookmarkRecord>` 和 `List<ReadingNoteRecord>`
     * 擦除之后 JVM 签名一模一样（`List + List + Function2`），重载会直接报
     * "Platform declaration clash" 编译不过。抽 key / stamp 两个取值函数出来即可。
     */
    private fun <T : Any> mergeById(
        incoming: List<T>,
        mine: MutableList<T>,
        key: (T) -> String,
        stamp: (T) -> Long,
        apply: (T, T) -> Unit,
    ): Boolean {
        var changed = false
        incoming.forEach { item ->
            val id = key(item)
            if (id.isEmpty()) return@forEach
            val existing = mine.firstOrNull { key(it) == id }
            if (existing == null) {
                mine += item
                changed = true
            } else if (stamp(item) > stamp(existing)) {
                apply(item, existing)
                changed = true
            }
        }
        return changed
    }

    /** 极端情况下的兜底：文件损坏就直接重建。 */
    fun reset() {
        data.progress = mutableListOf()
        data.bookmarks = mutableListOf()
        data.notes = mutableListOf()
        flush()
    }
}
