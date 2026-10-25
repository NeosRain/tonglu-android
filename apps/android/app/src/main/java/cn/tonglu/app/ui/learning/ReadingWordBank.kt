package cn.tonglu.app.ui.learning

import android.content.Context
import cn.tonglu.app.data.english.EnglishBookStore
import cn.tonglu.app.data.english.EnglishRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 词卡要用的**词库读口**：按词形精确命中、读书名、读写收藏。
 *
 * ## 为什么这一层在这里，而不是加在 `EnglishRepository` 上
 *
 * 2026-10-13 有一个并发会话（`english-c-persist-queue`）正占着 `data/english` 整棵子树
 * 在做「今日语境词队列」的持久化，`EnglishRepository.kt` / `EnglishDatabase.kt` 都是他正在写的文件。
 * 按本项目的并发纪律（见 `NOW.md`：看见别人的登记就绕开，不动别人的文件），
 * 这一轮**不往那两个文件里加方法**，改用它们**已有的公开 API**在本层组合：
 *
 * | 需要的 | 用谁 |
 * | --- | --- |
 * | 本机导入了哪些词库 | `EnglishBookStore.downloadedIds` + `EnglishDao.count` |
 * | 按词形找词条 | `EnglishDao.browse`（含匹配，本层再收成**精确**匹配） |
 * | 词库名 | `EnglishDao.book` |
 * | 收藏状态 | `EnglishDao.progress` |
 * | 切换收藏 | `EnglishRepository.favorite`（本身是**切换**语义） |
 *
 * 🔴 这是**临时归属**，不是最终架构：等队列那笔落地、契约冻结，
 * 这几个方法应当整体搬进 `EnglishRepository`，本类直接删掉。
 * 现在把它单独放一个文件，就是为了将来搬的时候一眼能找到、且不留半个尾巴在 `ui` 层。
 */
class ReadingWordBank(private val context: Context, private val owner: String) {
    private val app = context.applicationContext
    private val repository = EnglishRepository(app)
    private val dao get() = repository.dao

    init { require(owner.isNotBlank()) { "缺少学习数据归属" } }

    /**
     * 本机**真的装了词条**的词库 id（有序，便于结果稳定）。
     *
     * 先问 `downloadedIds`（文件在不在）再问 `count`（行在不在）——
     * 只看文件会在「文件下好但还没导入」时报「已导入」，只看行会在「刚清空」时漏掉。
     */
    suspend fun importedBooks(): List<String> = withContext(Dispatchers.IO) {
        EnglishBookStore.downloadedIds(app).sorted().filter { dao.count(it) > 0 }
    }

    /**
     * 按原文词形查词条。
     *
     * 候选顺序由 [lemmaCandidates] 给（原形永远第一）。**逐个候选在词书里精确匹配**
     * （`lower(english) == 候选`），全都没命中就是「未收录」——
     * 不拿一个「看起来像词元」的字符串去凑（那样会在词卡上写假词源，见 [lemmaCandidates] 的说明）。
     *
     * `lemma` 只在**还原后才命中**时有值：原形直接命中时是 null。
     */
    suspend fun lookup(surface: String): WordLookup = withContext(Dispatchers.IO) {
        val candidates = lemmaCandidates(surface)
        if (candidates.isEmpty()) return@withContext WordLookup.NotRecorded
        val base = surface.trim().lowercase(Locale.ROOT)
        val books = importedBooks()
        for (candidate in candidates) {
            for (book in books) {
                val word = find(book, candidate)
                if (word != null) {
                    return@withContext WordLookup.Found(WordLookupHit(
                        wordBank = word.wordBank, wordId = word.id, english = word.english,
                        chinese = word.chinese,
                        lemma = candidate.takeIf { it != base },
                        // 音标照抄词条自身的两个字段、不做推断；两个都没有就 null，
                        // 界面如实写「音标未登记」，**不用模型编**。
                        phoneticUk = word.ukPronunciation, phoneticGeneral = word.pronunciation,
                    ))
                }
            }
        }
        WordLookup.NotRecorded
    }

    /** 词库显示名。查不到就给 null，界面退回显示词库 id，不编一个名字。 */
    suspend fun bookName(wordBank: String): String? = withContext(Dispatchers.IO) {
        dao.book(wordBank)?.name
    }

    suspend fun favoriteState(wordId: String): Boolean = withContext(Dispatchers.IO) {
        dao.progress(owner, wordId)?.favorite == true
    }

    /**
     * 切换收藏并**读回真实状态**。
     *
     * `EnglishRepository.favorite` 是**切换**（toggle），不是「设为收藏」；
     * 所以这里不假设「点一下就会变成相反值」，而是写完再读一行回来 ——
     * 界面显示的是库里真实的样子。
     *
     * @return 写完之后的真实收藏状态。找不到词条时**什么都不改**，返回当前状态。
     */
    suspend fun toggleFavorite(hit: WordLookupHit): Boolean = withContext(Dispatchers.IO) {
        val word = find(hit.wordBank, hit.english)?.takeIf { it.id == hit.wordId }
            ?: return@withContext favoriteState(hit.wordId)
        repository.favorite(owner, word)
        favoriteState(hit.wordId)
    }

    /**
     * `browse` 是**模糊包含**查询（`instr(lower(english), lower(:query)) > 0`），
     * 这里只借它做一次粗筛，再用 Kotlin 侧收成精确相等。
     *
     * 上限按候选长度给：候选 ≥3 字符时匹配集很小（同一词库里含该子串的词通常是个位数），
     * 300 足够；只有 1–2 字符的候选（`a` / `I` 这类真词条）才可能撞上 LIMIT，
     * 那时放宽到全表 —— 这种候选很少见，多读几千行也好过**静默查不到词**。
     */
    private suspend fun find(book: String, candidate: String) = dao
        .browse(owner, book, candidate, "all", if (candidate.length <= 2) 50_000 else 300)
        .firstOrNull { it.english.equals(candidate, ignoreCase = true) }
}
