package cn.tonglu.app.data.english

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Adapted from JustByHeart Word.kt, MIT, copyright (c) 2025 cdz-hy.
 * Uses stable cross-client IDs instead of auto-increment IDs; retains source fields.
 * Full attribution and license: assets/english/NOTICE.txt and licenses/JustByHeart.txt.
 */
@Entity(tableName = "english_words", indices = [Index(value = ["wordBank", "position"])])
data class EnglishWord(
    @PrimaryKey val id: String, val english: String, val chinese: String,
    val pronunciation: String? = null, val ukPronunciation: String? = null,
    val example: String? = null, val exampleTranslation: String? = null,
    val phrases: String? = null, val wordBank: String, val position: Int,
)

/**
 * 已导入 / 可导入的词库。
 *
 * `asset` 现在只作为「历史字段」保留（老版本指向 APK 里的文件名，新版本直接写
 * `<id>.book`）——真正的词库文件一律在 `filesDir/english/books/` 下，见 EnglishBookStore。
 * `bytes` / `sha256` 是云端元数据：列表里显示「要下多大」，下载完比对指纹再导入。
 *
 * `source` / `license` / `sourceUrl` 是**出处与许可的三项分别登记**（plan §5 第 3 条）。
 * 🔴 三项必须分开存 —— 服务端以前把它们拼进 `source` 一条字符串里
 * （`"KyleBing/english-vocabulary（BSD-3-Clause）"`），于是界面点不回原文、
 * 也发现不了「某一项是空的」。出厂清单 `assets/english/catalog.json` 里三项本来就是分开的，
 * 但**以前这个实体没有对应字段，Gson 解析时直接把它们丢了** ——
 * 数据一直在，只是没有落点。空串 = 未登记，界面要显示「未登记」而不是假装没问题。
 */
@Entity(tableName = "english_books")
data class EnglishBook(@PrimaryKey val id: String, val name: String, val category: String,
    val count: Int, val revision: String, val asset: String = "$id.book",
    val bytes: Int = 0, val sha256: String = "",
    val source: String = "", val license: String = "", val sourceUrl: String = "")

@Entity(tableName = "english_progress", primaryKeys = ["owner", "wordId"])
data class EnglishProgress(val owner: String, val wordId: String, val favorite: Boolean = false,
    val level: Int = 0, val attempts: Int = 0, val lastCorrect: Boolean? = null,
    val dueAt: Long = 0, val updatedAt: Long = 0)

@Entity(tableName = "english_events", indices = [Index(value = ["owner", "answeredAt"])])
data class EnglishEvent(@PrimaryKey val id: String, val owner: String, val wordId: String,
    val bookId: String, val revision: String, val mode: String, val correct: Boolean,
    val answeredAt: Long, val schemaVersion: Int = 1)

@Entity(tableName = "english_settings")
data class EnglishSettings(@PrimaryKey val owner: String, val bookId: String = "kylebing-cet4",
    val dailyGoal: Int = 20, val updatedAt: Long = 0)

/**
 * 今日语境词队列的一条（2026-10-13，C03）。
 *
 * 🔴 **这张表和 `english_events` 是两件事**：这里是「用户挑出来的候选词」，
 * 那边是「用户真的作答过」。把「加入今日」记成一次学习事件会污染复习间隔与统计口径，
 * 所以队列单独一张表、且**不触发任何事件写入**。
 *
 * 幂等靠**复合主键**（owner + day + entryKey）而不是靠调用方先查后插：
 * 先查后插在并发下会插出两行，而且「查」本身要多一次往返。
 * 同一个词当天加两次 = 同一个主键 = `INSERT OR IGNORE` 命中，不新增行。
 *
 * `entryKey` 是「词条 id；查不到词条时退化成小写词形」—— 因为正文点词不一定在词库里
 * （`QueueWordIntent.existingWordId` 本来就是可空的）。主键列不能为 null，所以
 * 真正做主键的是这个补齐过的 key，`wordId` 只作为普通列保留原始信息。
 *
 * `day` 是本地日期串（yyyy-MM-dd）：跨天就是新队列，不需要定时清理任务。
 */
@Entity(tableName = "english_today_queue", primaryKeys = ["owner", "day", "entryKey"],
    indices = [Index(value = ["owner", "day"])])
data class EnglishTodayQueueEntry(
    val owner: String,
    val day: String,
    val entryKey: String,
    val wordId: String? = null,
    val surface: String,
    val lemma: String? = null,
    val sentenceRef: String? = null,
    val addedAt: Long = 0,
)

/**
 * 「我的精读文章」的一条（2026-10-13，用户自建 + 导入）。
 *
 * 用户把卷子里的四级/六级阅读文章自己加进来精读 —— **只存本机、按账号隔离、不上云不分发**
 * （用户个人使用没有版权问题；App 内置真题库、代用户上传这两条路已否决，
 * 见 `docs/english/MY-READING-ARTICLES.md` §0）。
 *
 * 🔴 `sourceNote` / `licenseNote` **必填**：这是用户点名的要求（「一定要备注文章来源和版权信息」），
 * 校验在 `EnglishRepository.addMyArticle`，空串不给保存 —— 与词库「出处/许可分别登记」同一口径。
 *
 * `tags` 是竖线分隔的分类串（`"2024-06|四级|卷二"`），用户自己打，用于列表筛选；
 * 不做标签表 —— 自建文章的标签只有「筛起来方便」一个诉求，上标签表是过度设计。
 */
@Entity(tableName = "my_reading_articles", indices = [Index(value = ["owner", "addedAt"])])
data class MyReadingArticle(
    @PrimaryKey val id: String,
    val owner: String,
    val title: String,
    val body: String,
    val tags: String = "",
    val sourceNote: String,
    val licenseNote: String,
    val addedAt: Long = 0,
    val documentId: String? = null,
    val documentTitle: String? = null,
    @ColumnInfo(defaultValue = "0") val chapterIndex: Int = 0,
    @ColumnInfo(defaultValue = "'TEXT'") val sourceFormat: String = "TEXT",
    val sourceFile: String? = null,
    val sourcePage: Int? = null,
    @ColumnInfo(defaultValue = "1") val textAvailable: Boolean = true,
)

data class EnglishStats(val studied: Int = 0, val favorites: Int = 0, val wrong: Int = 0,
    val due: Int = 0, val today: Int = 0)

@Dao
interface EnglishDao {
    @Upsert suspend fun words(rows: List<EnglishWord>)
    @Upsert suspend fun book(row: EnglishBook)
    @Query("SELECT * FROM english_books WHERE id=:id") suspend fun book(id: String): EnglishBook?
    @Query("SELECT COUNT(*) FROM english_words WHERE wordBank=:book") suspend fun count(book: String): Int
    @Query("DELETE FROM english_words WHERE wordBank=:book") suspend fun deleteWords(book: String)
    @Query("SELECT * FROM english_settings WHERE owner=:owner") fun settings(owner: String): Flow<EnglishSettings?>
    @Upsert suspend fun settings(row: EnglishSettings)
    @Query("SELECT * FROM english_progress WHERE owner=:owner AND wordId=:id") suspend fun progress(owner: String, id: String): EnglishProgress?
    @Query("SELECT * FROM english_progress WHERE owner=:owner AND wordId=:id") fun observeWord(owner: String, id: String): Flow<EnglishProgress?>
    @Upsert suspend fun progress(row: EnglishProgress)
    @Insert suspend fun event(row: EnglishEvent)
    @Query("SELECT * FROM english_progress WHERE owner=:owner") fun progress(owner: String): Flow<List<EnglishProgress>>
    @Query("SELECT * FROM english_events WHERE owner=:owner AND bookId=:book ORDER BY answeredAt DESC LIMIT 20") fun events(owner: String, book: String): Flow<List<EnglishEvent>>
    @Query("SELECT (SELECT COUNT(*) FROM english_progress p JOIN english_words w ON w.id=p.wordId WHERE p.owner=:owner AND w.wordBank=:book AND p.attempts>0) AS studied, (SELECT COUNT(*) FROM english_progress p JOIN english_words w ON w.id=p.wordId WHERE p.owner=:owner AND w.wordBank=:book AND p.favorite=1) AS favorites, (SELECT COUNT(*) FROM english_progress p JOIN english_words w ON w.id=p.wordId WHERE p.owner=:owner AND w.wordBank=:book AND p.lastCorrect=0) AS wrong, (SELECT COUNT(*) FROM english_progress p JOIN english_words w ON w.id=p.wordId WHERE p.owner=:owner AND w.wordBank=:book AND p.attempts>0 AND p.dueAt<=:now) AS due, (SELECT COUNT(DISTINCT wordId) FROM english_events WHERE owner=:owner AND bookId=:book AND answeredAt>=:dayStart) AS today")
    fun stats(owner: String, book: String, now: Long, dayStart: Long): Flow<EnglishStats>
    @Query("SELECT * FROM english_words WHERE wordBank=:book AND (instr(lower(english), lower(:query))>0 OR instr(chinese,:query)>0) AND (:filter='all' OR id IN (SELECT wordId FROM english_progress WHERE owner=:owner AND ((:filter='favorites' AND favorite=1) OR (:filter='wrong' AND lastCorrect=0)))) ORDER BY position LIMIT :limit")
    suspend fun browse(owner: String, book: String, query: String, filter: String, limit: Int): List<EnglishWord>
    // EF05 断点续答：按 id 取回上次会话的词。返回顺序不保证，调用方按保存的 wordIds 还原；
    // 取不全（词库重导/删书）就丢弃断点，不硬凑一组。
    @Query("SELECT * FROM english_words WHERE id IN (:ids)")
    suspend fun wordsByIds(ids: List<String>): List<EnglishWord>
    @Query("SELECT w.* FROM english_words w LEFT JOIN english_progress p ON p.wordId=w.id AND p.owner=:owner WHERE w.wordBank=:book AND (p.wordId IS NULL OR p.attempts=0) ORDER BY w.position LIMIT :limit")
    suspend fun newWords(owner: String, book: String, limit: Int): List<EnglishWord>
    @Query("SELECT w.* FROM english_words w JOIN english_progress p ON p.wordId=w.id AND p.owner=:owner WHERE w.wordBank=:book AND p.attempts>0 AND p.dueAt<=:now ORDER BY p.dueAt,w.position LIMIT :limit")
    suspend fun dueWords(owner: String, book: String, now: Long, limit: Int): List<EnglishWord>
    // 例句填空只挑有例句的词；没有例句的题目没法出，宁可这一组少几个词。
    @Query("SELECT w.* FROM english_words w LEFT JOIN english_progress p ON p.wordId=w.id AND p.owner=:owner WHERE w.wordBank=:book AND w.example IS NOT NULL AND w.example!='' AND (p.wordId IS NULL OR p.attempts=0) ORDER BY w.position LIMIT :limit")
    suspend fun newWordsWithExample(owner: String, book: String, limit: Int): List<EnglishWord>
    @Query("SELECT w.* FROM english_words w JOIN english_progress p ON p.wordId=w.id AND p.owner=:owner WHERE w.wordBank=:book AND w.example IS NOT NULL AND w.example!='' AND p.attempts>0 AND p.dueAt<=:now ORDER BY p.dueAt,w.position LIMIT :limit")
    suspend fun dueWordsWithExample(owner: String, book: String, now: Long, limit: Int): List<EnglishWord>
    @Query("SELECT w.* FROM english_words w WHERE w.wordBank=:book AND w.id!=:id AND w.chinese!=:meaning ORDER BY RANDOM() LIMIT 12")
    suspend fun distractors(book: String, id: String, meaning: String): List<EnglishWord>
    /** 看中文选英文：干扰项必须词形不同，否则两个选项一模一样。 */
    @Query("SELECT w.* FROM english_words w WHERE w.wordBank=:book AND w.id!=:id AND lower(w.english)!=lower(:word) ORDER BY RANDOM() LIMIT 12")
    suspend fun distractorWords(book: String, id: String, word: String): List<EnglishWord>
    @Query("SELECT * FROM english_progress WHERE owner=:owner") suspend fun exportProgress(owner: String): List<EnglishProgress>
    @Query("SELECT * FROM english_events WHERE owner=:owner ORDER BY answeredAt") suspend fun exportEvents(owner: String): List<EnglishEvent>

    // ---- 今日语境词队列（2026-10-13，C03）----
    /**
     * 加入今日。`IGNORE` + 复合主键 = 幂等：同一个词当天加两次只留一行。
     * 返回 rowId；**-1 表示主键已存在**（这次没有新增），调用方据此区分「刚加入」与「早就在队列里」。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addToToday(row: EnglishTodayQueueEntry): Long

    @Query("SELECT * FROM english_today_queue WHERE owner=:owner AND day=:day ORDER BY addedAt")
    fun observeToday(owner: String, day: String): Flow<List<EnglishTodayQueueEntry>>

    @Query("SELECT * FROM english_today_queue WHERE owner=:owner AND day=:day ORDER BY addedAt")
    suspend fun today(owner: String, day: String): List<EnglishTodayQueueEntry>

    @Query("SELECT COUNT(*) FROM english_today_queue WHERE owner=:owner AND day=:day")
    suspend fun todayCount(owner: String, day: String): Int

    /**
     * 验收判据专用：某个 owner 的**学习事件总数**。
     * 「加入今日」前后这个数必须相等 —— 这是 C03 最核心的那条断言，
     * 比「队列里有几行」重要得多：队列写对了但顺手记了事件，照样是错的。
     */
    @Query("SELECT COUNT(*) FROM english_events WHERE owner=:owner")
    suspend fun eventCount(owner: String): Int

    // ---- 我的精读文章（2026-10-13，用户自建 + 导入）----
    // 🔴 所有查询带 owner：自建文章是「我自己加的」，换号绝不能看见别人的
    //（与今日队列同一条隔离纪律）。

    @Upsert suspend fun upsertMyArticle(row: MyReadingArticle)
    @Upsert suspend fun upsertMyArticles(rows: List<MyReadingArticle>)

    @Query("DELETE FROM my_reading_articles WHERE owner=:owner AND documentId=:documentId")
    suspend fun deleteMyDocument(documentId: String, owner: String): Int

    @Query("SELECT DISTINCT sourceFile FROM my_reading_articles WHERE owner=:owner AND documentId=:documentId AND sourceFile IS NOT NULL")
    suspend fun documentSourceFiles(documentId: String, owner: String): List<String>

    @Query("SELECT COUNT(*) FROM my_reading_articles WHERE owner=:owner AND sourceFile=:file")
    suspend fun sourceFileReferences(owner: String, file: String): Int

    /** 列表按加入时间倒序：最近整理的卷子在顶上。 */
    @Query("SELECT * FROM my_reading_articles WHERE owner=:owner ORDER BY addedAt DESC")
    fun observeMyArticles(owner: String): Flow<List<MyReadingArticle>>

    /** 书架只加载元数据；正文在点章节时读取，不能把几十本书一起装进内存。 */
    @Query("SELECT id,owner,title,'' AS body,tags,sourceNote,licenseNote,addedAt,documentId,documentTitle,chapterIndex,sourceFormat,sourceFile,sourcePage,textAvailable FROM my_reading_articles WHERE owner=:owner ORDER BY addedAt DESC")
    fun observeMyShelf(owner: String): Flow<List<MyReadingArticle>>

    @Query("SELECT * FROM my_reading_articles WHERE owner=:owner ORDER BY addedAt DESC")
    suspend fun myArticles(owner: String): List<MyReadingArticle>

    @Query("SELECT * FROM my_reading_articles WHERE id=:id AND owner=:owner")
    suspend fun myArticle(id: String, owner: String): MyReadingArticle?

    @Query("DELETE FROM my_reading_articles WHERE id=:id AND owner=:owner")
    suspend fun deleteMyArticle(id: String, owner: String): Int
}

@Database(entities = [EnglishWord::class, EnglishBook::class, EnglishProgress::class,
    EnglishEvent::class, EnglishSettings::class, EnglishTodayQueueEntry::class,
    MyReadingArticle::class],
    version = 6, exportSchema = true)
abstract class EnglishDatabase : RoomDatabase() {
    abstract fun dao(): EnglishDao
    companion object {
        @Volatile private var instance: EnglishDatabase? = null
        fun get(context: Context): EnglishDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, EnglishDatabase::class.java,
                "english.db")
                // 1→2：词库搬到云端，表上补「云端元数据」两列。
                // 2→3：词库补「出处与许可」三列。
                // 3→4：新增今日语境词队列表。
                // 4→5：新增「我的精读文章」表（用户自建 + 导入）。
                // **全是手写迁移而不是破坏性重建** —— 这张库里装着全部学习进度，重建一次等于把用户的
                // 收藏、错词、复习间隔全清空，那样的「升级」没人受得了。
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build().also { instance = it }
        }
    }
}

/** 只补书籍/页码元数据；旧文章、词库和学习记录全部保留。 */
internal val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE my_reading_articles ADD COLUMN documentId TEXT")
        db.execSQL("ALTER TABLE my_reading_articles ADD COLUMN documentTitle TEXT")
        db.execSQL("ALTER TABLE my_reading_articles ADD COLUMN chapterIndex INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE my_reading_articles ADD COLUMN sourceFormat TEXT NOT NULL DEFAULT 'TEXT'")
        db.execSQL("ALTER TABLE my_reading_articles ADD COLUMN sourceFile TEXT")
        db.execSQL("ALTER TABLE my_reading_articles ADD COLUMN sourcePage INTEGER")
        db.execSQL("ALTER TABLE my_reading_articles ADD COLUMN textAvailable INTEGER NOT NULL DEFAULT 1")
    }
}

/** 加列的默认值必须是常量：SQLite 的 ALTER TABLE ADD COLUMN 不支持表达式默认值。 */
private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE english_books ADD COLUMN bytes INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE english_books ADD COLUMN sha256 TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * 2→3：词库补上「出处与许可」三列，与服务端同一口径（plan §5 第 3 条「分别登记」）。
 *
 * 同样是**手写加列、绝不破坏性重建**：这张库里装着用户的收藏、错词与复习间隔。
 * 默认值必须是常量 —— SQLite 的 `ALTER TABLE ADD COLUMN` 不支持表达式默认值。
 */
private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE english_books ADD COLUMN source TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE english_books ADD COLUMN license TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE english_books ADD COLUMN sourceUrl TEXT NOT NULL DEFAULT ''")
    }
}

/**
 * 3→4：新增今日语境词队列（C03）。
 *
 * **纯新增表，不碰任何既有表** —— 这是最安全的一类迁移：老数据一个字节不动，
 * 装过旧版的用户升级后收藏/错词/复习间隔全在。
 * 建表语句必须与 [EnglishTodayQueueEntry] 的实体定义逐字对应，否则 Room 的
 * schema 校验会在打开数据库时直接抛 IllegalStateException（不是运行时才出问题，是打不开）。
 */
private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `english_today_queue` (" +
                "`owner` TEXT NOT NULL, " +
                "`day` TEXT NOT NULL, " +
                "`entryKey` TEXT NOT NULL, " +
                "`wordId` TEXT, " +
                "`surface` TEXT NOT NULL, " +
                "`lemma` TEXT, " +
                "`sentenceRef` TEXT, " +
                "`addedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`owner`, `day`, `entryKey`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_english_today_queue_owner_day` " +
                "ON `english_today_queue` (`owner`, `day`)"
        )
    }
}

/**
 * 4→5：新增「我的精读文章」（用户自建 + 导入）。
 *
 * 与 3→4 同一条纪律：**纯新增表，不碰任何既有表**；建表语句必须与
 * [MyReadingArticle] 逐字对应，否则 Room 的 schema 校验在开库时直接抛
 * IllegalStateException（数据库打不开，不是运行时才坏）。
 */
private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `my_reading_articles` (" +
                "`id` TEXT NOT NULL, " +
                "`owner` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`body` TEXT NOT NULL, " +
                "`tags` TEXT NOT NULL, " +
                "`sourceNote` TEXT NOT NULL, " +
                "`licenseNote` TEXT NOT NULL, " +
                "`addedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_my_reading_articles_owner_addedAt` " +
                "ON `my_reading_articles` (`owner`, `addedAt`)"
        )
    }
}
