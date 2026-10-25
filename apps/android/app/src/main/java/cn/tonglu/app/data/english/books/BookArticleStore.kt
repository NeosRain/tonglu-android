package cn.tonglu.app.data.english.books

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** 一篇保存下来的 AI 生成文章。`aiGenerated` 恒为 true —— 界面与列表都要如实标注。 */
data class BookArticleRecord(
    val id: String,
    val bookId: String,
    val bookName: String,
    val title: String,
    val paragraphs: List<String>,
    val level: String,
    val wordKeys: List<String>,
    val coverageRatio: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val aiGenerated: Boolean = true,
)

data class BookArticlesSnapshot(val owner: String, val articles: List<BookArticleRecord> = emptyList(), val schema: Int = 1)

/** AI 生成文章的本机保存（按 owner 隔离，同个人词书：仅本机，不上传）。 */
class BookArticleStore(context: Context, private val owner: String) {
    private val gson = Gson()
    private val file: AtomicFile

    init {
        require(owner.isNotBlank()) { "缺少账号归属" }
        val account = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray())
            .joinToString("") { "%02x".format(it) }
        file = AtomicFile(File(context.applicationContext.filesDir, "english/personal/articles/$account.json"))
    }

    suspend fun snapshot(): BookArticlesSnapshot = withContext(Dispatchers.IO) { lock.withLock { read() } }

    /** 保存草稿为正式记录；条数上限防无限膨胀（每账号 50 篇，超了要求先删）。 */
    suspend fun save(record: BookArticleRecord): Unit = change { old ->
        require(old.articles.size < MAX_ARTICLES || record.id in old.articles.map { it.id }) { "最多保存 $MAX_ARTICLES 篇，请先删除旧文章" }
        val next = listOf(record) + old.articles.filterNot { it.id == record.id }
        old.copy(articles = next) to Unit
    }

    suspend fun delete(id: String): Unit = change { old ->
        require(old.articles.any { it.id == id }) { "文章已不存在" }
        old.copy(articles = old.articles.filterNot { it.id == id }) to Unit
    }

    private fun read(): BookArticlesSnapshot {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return BookArticlesSnapshot(owner)
        val snapshot = file.openRead().bufferedReader().use { gson.fromJson(it, BookArticlesSnapshot::class.java) }
        require(snapshot.owner == owner && snapshot.schema == 1) { "文章保存归属或格式不正确，未改动原数据" }
        return snapshot
    }

    private suspend fun change(transform: (BookArticlesSnapshot) -> Pair<BookArticlesSnapshot, Unit>): Unit =
        withContext(Dispatchers.IO) { lock.withLock {
            val (next, _) = transform(read())
            file.baseFile.parentFile?.mkdirs()
            val stream = file.startWrite()
            try { stream.write(gson.toJson(next).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        } }

    companion object { const val MAX_ARTICLES = 50; private val lock = Mutex() }
}
