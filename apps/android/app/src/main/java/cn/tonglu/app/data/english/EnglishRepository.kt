package cn.tonglu.app.data.english

import android.content.Context
import androidx.room.withTransaction
import cn.tonglu.app.data.TongluApi
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.GZIPInputStream

/** Independent Room database: never changes or resets the academic database. */
class EnglishRepository(context: Context) {
    private val app = context.applicationContext
    private val db = EnglishDatabase.get(context)
    val dao = db.dao()
    private val gson = Gson()
    /**
     * 词库清单（云端优先，见 EnglishBookStore 的三级回退）。
     * `api` 传 null 时只列本机缓存 / 出厂清单 —— 没网也能看见已下载的词库。
     */
    suspend fun catalog(api: TongluApi? = null): List<EnglishBook> = EnglishBookStore.catalog(app, api)

    /** 一本词库在本机有没有下好。UI 拿它决定按钮是「下载」还是「开始学习」。 */
    fun isDownloaded(book: EnglishBook): Boolean = EnglishBookStore.isDownloaded(app, book)

    /**
     * 把下载好的词库导入 Room。
     *
     * 词库文件现在在 `filesDir/english/books/` 下（云端下载），**不再读 assets** ——
     * 安装包里已经没有 30MB 的词库了。没下好就抛异常，由上层提示用户去下载，
     * 而不是静默失败后让人对着一片空白猜。
     */
    suspend fun ensureBook(book: EnglishBook) = withContext(Dispatchers.IO) {
        importLock.withLock {
            if (dao.book(book.id)?.revision == book.revision && dao.count(book.id) == book.count) return@withLock
            val file: File = EnglishBookStore.file(app, book)
            if (!file.exists()) throw IOException("词库还没下载，请先下载")
            // Streaming parse and batched inserts. The transaction prevents half-imported books.
            db.withTransaction {
                dao.deleteWords(book.id)
                GZIPInputStream(file.inputStream()).bufferedReader().use { reader ->
                    val batch = ArrayList<EnglishWord>(250)
                    var count = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        val word = gson.fromJson(line, EnglishWord::class.java)
                        require(word.wordBank == book.id && word.id.isNotBlank() && word.english.isNotBlank()) { "词库格式不正确" }
                        batch.add(word)
                        count++
                        if (batch.size == 250) { dao.words(batch.toList()); batch.clear() }
                    }
                    if (batch.isNotEmpty()) dao.words(batch)
                    require(count == book.count) { "词库条目不完整" }
                }
                dao.book(book)
            }
        }
    }
    suspend fun favorite(owner: String, word: EnglishWord) = db.withTransaction {
        val old = dao.progress(owner, word.id) ?: EnglishProgress(owner, word.id)
        dao.progress(old.copy(favorite = !old.favorite, updatedAt = System.currentTimeMillis()))
    }
    /**
     * 记录一次答题。
     *
     * 返回**本次是否「首次学会」**（原来答对且此前 level 仍为 0）——
     * 成长等级的学习线 `words.learned` 计的就是这个，不是「答题次数」。
     * 🔴 为什么必须在这里判定而不是上层：`old.level` 只有这一层看得到，
     * 上层拿到的 `correct` 只是「这次答对没」，重复复习答对会被误算成新学会的词。
     */
    suspend fun answer(owner: String, book: EnglishBook, word: EnglishWord, mode: String, correct: Boolean): Boolean =
        db.withTransaction {
            val old = dao.progress(owner, word.id) ?: EnglishProgress(owner, word.id)
            val now = System.currentTimeMillis()
            val level = if (correct) (old.level + 1).coerceAtMost(intervalDays.size) else 0
            val interval = if (correct) intervalDays[level - 1] * 86_400_000L else 300_000L
            // 首次学会 = 这次答对 且 此前没记住过（level 还是 0、也没答错过）。
            // 判据抽在 Levels.isFirstLearned 里，可被单测固定住。
            val firstLearned = cn.tonglu.app.data.Levels.isFirstLearned(correct, old.level, old.attempts)
            dao.progress(old.copy(level = level, attempts = old.attempts + 1, lastCorrect = correct,
                dueAt = now + interval, updatedAt = now))
            dao.event(EnglishEvent(UUID.randomUUID().toString(), owner, word.id, book.id,
                book.revision, mode, correct, now))
            firstLearned
        }
    suspend fun export(owner: String, settings: EnglishSettings): String = withContext(Dispatchers.IO) {
        db.withTransaction {
            gson.toJson(mapOf("schemaVersion" to 1, "exportedAt" to System.currentTimeMillis(),
                "settings" to settings, "progress" to dao.exportProgress(owner), "events" to dao.exportEvents(owner)))
        }
    }

    // ---- 今日语境词队列（2026-10-13，C03）----

    /**
     * 把正文里挑中的词加入「今日」候选队列。
     *
     * 🔴 **只写队列表，绝不写 `english_events`、绝不碰 `english_progress`。**
     * 「挑一个词出来看」不是「作答」：记成学习事件会污染 `dueAt` 的复习间隔和
     * `stats.today` 的今日学习数 —— 用户只是点了一下词，统计却涨了，那是假的进度。
     *
     * 参数刻意都是**标量**而不是 `QueueWordIntent`：那个类型在 `ui.english.contextword` 包，
     * `data` 层不该反向依赖 `ui`。调用方负责把 intent 拆开传进来（B 包的规则本来就只收标量）。
     *
     * 幂等由复合主键 `(owner, day, entryKey)` 保证，**不是**「先查后插」——
     * 后者在并发下会插出两行，而且多一次往返。
     *
     * @return `true` 表示本次**新增**了一行；`false` 表示这个词今天已经在队列里了（重复加入，没动数据）。
     */
    suspend fun queueToday(owner: String, day: String, wordId: String?, surface: String,
                           lemma: String? = null, sentenceRef: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            // owner 是隔离维度本身，空值不能退回默认账号（那等于让一个账号看见所有人的队列）。
            require(owner.isNotBlank()) { "缺少 owner，拒绝写入今日队列" }
            require(day.isNotBlank()) { "缺少日期，拒绝写入今日队列" }
            val key = TodayQueueRules.entryKey(wordId, surface)
            require(key.isNotBlank()) { "缺少词条与词形，无法确定要加入哪个词" }
            val row = EnglishTodayQueueEntry(
                owner = owner,
                day = day,
                entryKey = key,
                wordId = wordId?.takeIf { it.isNotBlank() },
                surface = surface.trim(),
                lemma = lemma,
                sentenceRef = sentenceRef,
                addedAt = System.currentTimeMillis(),
            )
            // rowId == -1L 表示主键冲突被 IGNORE 掉了 —— 也就是「早就加过了」。
            dao.addToToday(row) != -1L
        }

    /** 观察某天的队列（退出重进后要能读回同一份）。 */
    fun observeToday(owner: String, day: String) = dao.observeToday(owner, day)

    suspend fun todayCount(owner: String, day: String): Int = withContext(Dispatchers.IO) {
        dao.todayCount(owner, day)
    }

    /**
     * 今天这个词加过了没有。给界面用：已加入时按钮该显示「已在今日」而不是「加入今日」。
     * 用的键**必须与 [queueToday] 走同一个 [TodayQueueRules.entryKey]**，否则会出现
     * 「明明加进去了，按钮还是显示未加入」这种自相矛盾的状态。
     */
    suspend fun queuedToday(owner: String, day: String, wordId: String?, surface: String): Boolean =
        withContext(Dispatchers.IO) {
            val key = TodayQueueRules.entryKey(wordId, surface)
            if (owner.isBlank() || key.isBlank()) return@withContext false
            dao.today(owner, day).any { it.entryKey == key }
        }

    // ---- 我的精读文章（2026-10-13，用户自建 + 导入）----
    // 设计：docs/english/MY-READING-ARTICLES.md。只存本机、按账号隔离、不上云不分发。

    /**
     * 新增 / 更新一篇自建文章，返回文章 id。
     *
     * 🔴 四项必填（标题 / 正文 / 来源 / 版权）—— 来源与版权是**用户点名的要求**
     * （「一定要备注文章来源和版权信息，别忘了」），空着不给保存，宁可在表单上报错。
     * 与词库「出处/许可分别登记」是同一个口径：没有出处的内容连本机都不留。
     */
    suspend fun addMyArticle(owner: String, id: String?, title: String, body: String,
        tags: String, sourceNote: String, licenseNote: String): String =
        withContext(Dispatchers.IO) {
            require(owner.isNotBlank()) { "缺少学习数据归属" }
            // 校验是纯函数（MyReadingRules），表单按钮与这里用同一份判据 —— 见该类说明。
            MyReadingRules.validate(title, body, sourceNote, licenseNote)?.let { throw IllegalArgumentException(it) }
            val previous = id?.let { dao.myArticle(it, owner) }
            require(id == null || previous != null) { "文章不存在或不属于当前账号" }
            val row = MyReadingArticle(
                id = id?.takeIf { it.isNotBlank() } ?: ("my-" + UUID.randomUUID().toString()),
                owner = owner, title = title.trim(), body = body,
                tags = MyReadingRules.normalizeTags(tags),
                sourceNote = sourceNote.trim(), licenseNote = licenseNote.trim(),
                addedAt = System.currentTimeMillis(),
                documentId = previous?.documentId, documentTitle = previous?.documentTitle,
                chapterIndex = previous?.chapterIndex ?: 0, sourceFormat = previous?.sourceFormat ?: "TEXT",
                sourceFile = previous?.sourceFile, sourcePage = previous?.sourcePage,
                textAvailable = previous?.textAvailable ?: true,
            )
            dao.upsertMyArticle(row)
            row.id
        }

    fun observeMyArticles(owner: String) = dao.observeMyArticles(owner)
    fun observeMyShelf(owner: String) = dao.observeMyShelf(owner)

    /** 一次性读全量（Reader 打开自建文章时的解析兜底用）。 */
    suspend fun myArticles(owner: String): List<MyReadingArticle> =
        withContext(Dispatchers.IO) { dao.myArticles(owner) }

    suspend fun myArticle(id: String, owner: String): MyReadingArticle? =
        withContext(Dispatchers.IO) { dao.myArticle(id, owner) }

    /** 删除是本机操作（无服务端），`deleted > 0` 才是真删掉了 —— 返回给界面做提示。 */
    suspend fun deleteMyArticle(id: String, owner: String): Boolean =
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            val row = dao.myArticle(id, owner) ?: return@withContext false
            val deleted = dao.deleteMyArticle(id, owner) > 0
            if (deleted) cleanImportedSource(owner, row.sourceFile)
            deleted
        }

    /** 一本书一次事务提交，失败不留下半本；只读本机，从不进入公共资源目录。 */
    suspend fun saveImportedDocument(owner: String,
        parsed: cn.tonglu.app.data.readingimport.ParsedReadingDocument,
        source: String, license: String, file: String?, tags: String = ""): String = withContext(Dispatchers.IO) {
        require(owner.isNotBlank() && source.isNotBlank() && license.isNotBlank()) { "归属、来源与版权备注不能为空" }
        require(parsed.chapters.isNotEmpty() && parsed.chapters.size <= cn.tonglu.app.data.readingimport.ReadingImportParser.MAX_PARTS)
        require(parsed.chapters.all { it.body.isNotBlank() && it.body.length <= cn.tonglu.app.data.readingimport.ReadingImportParser.MAX_CHAPTER_CHARS })
        val id = UUID.randomUUID().toString()
        val grouped = parsed.format in listOf("PDF", "EPUB") || parsed.chapters.size > 1
        val now = System.currentTimeMillis()
        val normalizedTags = MyReadingRules.normalizeTags(tags)
        val rows = parsed.chapters.mapIndexed { index, chapter ->
            MyReadingArticle("my-$id-$index", owner, chapter.title, chapter.body,
                tags = normalizedTags,
                sourceNote = source.trim(), licenseNote = license.trim(), addedAt = now,
                documentId = id.takeIf { grouped }, documentTitle = parsed.title.takeIf { grouped },
                chapterIndex = index, sourceFormat = parsed.format, sourceFile = file,
                sourcePage = chapter.page, textAvailable = chapter.textAvailable)
        }
        db.withTransaction { dao.upsertMyArticles(rows) }
        id
    }

    /**
     * 把一本已导入的 PDF 文档「按试卷整理」：整份文档打上 `试卷` 标签，并把每一页标记为待校对。**
     *
     * 用途：旧版本导入的 PDF（没有试卷标记、按页做成了章节）可以原地转成试卷视图，
     * 不用重新导入（用户原试题册就是这种情况）。只动本机 Room，不碰 PDF 原件、不上传。
     * 返回更新的页数；文档不存在或不属于当前账号时抛出中文错误。
     */
    suspend fun markDocumentAsPaper(owner: String, documentId: String): Int =
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            require(owner.isNotBlank() && documentId.isNotBlank()) { "缺少文档归属" }
            val rows = dao.myArticles(owner).filter { it.documentId == documentId }
            require(rows.isNotEmpty()) { "这本书不存在或不属于当前账号" }
            val updated = rows.map {
                it.copy(
                    tags = MyReadingRules.normalizeTags(it.tags + "|试卷"),
                    // 之前的自动文字层/旧章节文字不算人工校对；转试卷后按原版逐页重新校对，
                    // 避免把「机器提取的文字」误当成用户已确认的内容。
                    textAvailable = false,
                )
            }
            db.withTransaction { dao.upsertMyArticles(updated) }
            updated.size
        }

    suspend fun deleteMyDocument(id: String, owner: String): Boolean =
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            val files = dao.documentSourceFiles(id, owner)
            val deleted = dao.deleteMyDocument(id, owner) > 0
            if (deleted) files.forEach { cleanImportedSource(owner, it) }
            deleted
        }

    private suspend fun cleanImportedSource(owner: String, name: String?) {
        if (name == null || dao.sourceFileReferences(owner, name) != 0) return
        val file = cn.tonglu.app.data.readingimport.ReadingImportFiles.pdf(app, owner, name)
        check(!file.exists() || file.delete()) { "阅读记录已删除，但 PDF 副本未能清理，请重试或清理应用储存" }
    }

    /**
     * 自建文章 → `LearningArticle`，复用现有阅读器（ArticleReader 接的是 LearningArticle）。
     *
     * - `sectionId = "my-reading"`：文章按 sectionId 归类，自建文章统一挂这个虚拟分类下；
     * - `licenseId = "USER"`：`licenseLabel` 会原样显示「USER」，界面侧翻译成「用户自建」；
     * - `attribution` 把来源与版权拼在一起给阅读器展示 —— 本来就是同一个人填的两行备注。
     *
     * 🔴 `sourceRevision` **不能给空串**：精读选区契约要求 revision 非空
     * （`ReadingSentence.select` 的 require，「缺少文章或内容版本」）——
     * 空串会让点段落必抛异常、词卡永远弹不出来（2026-10-13 MuMu 走查抓到的真缺陷，
     * 六次点段落全失败就是这个）。取**正文 digest**：内容不变 revision 不变
     * （阅读进度 / 对话 / 分析缓存都不失效），内容一改 revision 变（旧选区自然作废）。
     */
    fun toLearningArticle(row: MyReadingArticle): cn.tonglu.app.data.LearningArticle =
        cn.tonglu.app.data.LearningArticle(
            id = row.id, sectionId = "my-reading", title = row.documentTitle?.let { "$it · ${row.title}" } ?: row.title,
            summary = "", author = "", kind = "MARKDOWN", sourceUrl = "",
            licenseId = "USER", licenseUrl = "",
            attribution = listOf(row.sourceNote, row.licenseNote)
                .filter { it.isNotBlank() }.joinToString("｜"),
            changeNote = "",
            sourceRevision = cn.tonglu.app.data.english.reading
                .readingDigest(row.body).take(16),
            updatedAt = java.time.Instant.ofEpochMilli(row.addedAt).toString(),
            featured = false, order = 0, body = row.body,
        )

    companion object {
        private val importLock = Mutex()
        // Transparent fixed interval schedule; deliberately not called FSRS.
        private val intervalDays = longArrayOf(1, 3, 7, 14, 30, 60)
    }
}
