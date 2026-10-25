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
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

data class PersonalBookWord(
    val surface: String,
    val lemma: String = surface,
    val meaning: String = "",
    val sentence: String = "",
    val articleId: String = "",
    val sourcePosition: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val correct: Int = 0,
) {
    val key: String get() = normalize(lemma)
    companion object {
        fun normalize(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
            .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
    }
}

data class PersonalWordBook(val id: String, val name: String,
    val words: List<PersonalBookWord> = emptyList(), val createdAt: Long = System.currentTimeMillis())
data class PersonalBooksSnapshot(val owner: String, val books: List<PersonalWordBook> = emptyList(), val schema: Int = 1)
data class BookMutationResult(val succeeded: List<String>, val failed: Map<String, String>)

/** Personal books are device only. No remote deletion is simulated or silently queued. */
class PersonalWordBookStore(context: Context, private val owner: String) {
    private val gson = Gson()
    private val file: AtomicFile
    init {
        require(owner.isNotBlank()) { "缺少账号归属" }
        val account = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray())
            .joinToString("") { "%02x".format(it) }
        file = AtomicFile(File(context.applicationContext.filesDir, "english/personal/$account.json"))
    }

    suspend fun snapshot(): PersonalBooksSnapshot = withContext(Dispatchers.IO) { lock.withLock { read() } }

    suspend fun create(name: String): PersonalWordBook = change { old ->
        val clean = name.trim()
        require(clean.isNotBlank() && clean.length <= 40) { "词书名须为1–40字" }
        require(old.books.size < MAX_BOOKS) { "最多创建3本个人词书，请先删除一本" }
        require(old.books.none { it.name.equals(clean, true) }) { "已有同名词书" }
        val book = PersonalWordBook(UUID.randomUUID().toString(), clean)
        old.copy(books = old.books + book) to book
    }

    /** Stable member key, never overwrites a user's previous meaning or context. */
    suspend fun add(bookId: String, word: PersonalBookWord): Boolean = change { old ->
        require(word.key.isNotBlank() && word.key.length <= 160) { "词条须为1–160字符" }
        require(word.meaning.isNotBlank()) { "请核对并填写释义" }
        val book = old.books.firstOrNull { it.id == bookId } ?: error("词书不存在或不属于当前账号")
        val fresh = book.words.none { it.key == word.key }
        val next = if (fresh) book.copy(words = book.words + word.copy(surface = word.surface.trim(), lemma = word.lemma.trim())) else book
        old.copy(books = old.books.map { if (it.id == bookId) next else it }) to fresh
    }

    suspend fun answer(bookId: String, key: String, correct: Boolean) = change { old ->
        val book = old.books.firstOrNull { it.id == bookId } ?: error("词书不存在")
        require(book.words.any { it.key == key }) { "词条已移除" }
        val next = book.copy(words = book.words.map { if (it.key == key)
            it.copy(attempts = it.attempts + 1, correct = it.correct + if (correct) 1 else 0) else it })
        old.copy(books = old.books.map { if (it.id == bookId) next else it }) to Unit
    }

    /**
     * 批量删除词书：一次原子写，逐项回报。
     * 已不存在的 id 记入 failed（可重试时如实提示，不冒充成功）；
     * 写盘失败则整体抛异常，成功/失败都不含糊。
     */
    suspend fun deleteBooks(ids: Set<String>): BookMutationResult = change { old ->
        val present = old.books.filter { it.id in ids }
        old.copy(books = old.books.filterNot { it.id in ids }) to BookMutationResult(
            present.map { it.id },
            (ids - present.map { it.id }.toSet()).associateWith { "词书已不存在" })
    }

    /** 批量移除某本词书内的词条：同一次原子写；跨书同词不受影响（key 只作用于本书）。 */
    suspend fun removeWords(bookId: String, keys: Set<String>): BookMutationResult = change { old ->
        val book = old.books.firstOrNull { it.id == bookId } ?: error("词书不存在或不属于当前账号")
        val present = book.words.filter { it.key in keys }
        val next = book.copy(words = book.words.filterNot { it.key in keys })
        old.copy(books = old.books.map { if (it.id == bookId) next else it }) to BookMutationResult(
            present.map { it.key },
            (keys - present.map { it.key }.toSet()).associateWith { "词条已不存在" })
    }

    private fun read(): PersonalBooksSnapshot {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return PersonalBooksSnapshot(owner)
        val snapshot = file.openRead().bufferedReader().use { gson.fromJson(it, PersonalBooksSnapshot::class.java) }
        require(snapshot.owner == owner && snapshot.schema == 1) { "个人词书归属或格式不正确，未改动原数据" }
        return snapshot
    }

    private suspend fun <T> change(transform: (PersonalBooksSnapshot) -> Pair<PersonalBooksSnapshot, T>): T =
        withContext(Dispatchers.IO) { lock.withLock {
            val (next, result) = transform(read())
            file.baseFile.parentFile?.mkdirs()
            val stream = file.startWrite()
            try { stream.write(gson.toJson(next).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
            result
        } }

    companion object { const val MAX_BOOKS = 3; private val lock = Mutex() }
}
