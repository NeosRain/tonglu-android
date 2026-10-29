package cn.tonglu.app.data.english.reading

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/** 分析缓存只保存学习结果，不保存密钥；owner 是主键的一部分，游客和账号不能互读。 */
@Entity(tableName = "sentence_analyses", primaryKeys = ["owner", "cacheKey"])
data class StoredSentenceAnalysis(val owner: String, val cacheKey: String, val articleId: String,
    val revision: String, val sentenceHash: String, val resultJson: String, val updatedAt: Long)

/** 对话绑定不变的来源快照，文章改版后仍能解释当时讨论的原句。 */
@Entity(tableName = "reading_conversations", primaryKeys = ["owner", "threadId"])
data class StoredReadingConversation(val owner: String, val threadId: String,
    val contextJson: String, val messagesJson: String, val draft: String, val updatedAt: Long)

@Entity(tableName = "reading_history", primaryKeys = ["owner", "id"],
    indices = [Index(value = ["owner", "articleKey", "sentenceId"]), Index(value = ["owner", "inputKey"])])
data class StoredReadingHistory(val owner: String, val id: String, val articleKey: String,
    val articleTitle: String, val sentenceId: String, val sourceText: String, val kind: String,
    val text: String, val inputText: String, val inputKey: String, val model: String, val createdAt: Long)

@Entity(tableName = "reading_sentence_favorites", primaryKeys = ["owner", "articleKey", "sentenceId"])
data class StoredReadingFavorite(val owner: String, val articleKey: String, val sentenceId: String,
    val articleTitle: String, val sourceText: String, val createdAt: Long)

@Dao
interface ReadingAnalysisDao {
    @Query("SELECT * FROM reading_history WHERE owner = :owner ORDER BY createdAt DESC, id DESC")
    suspend fun history(owner: String): List<StoredReadingHistory>
    @Query("SELECT * FROM reading_history WHERE owner = :owner AND inputKey = :inputKey ORDER BY createdAt DESC LIMIT 1")
    suspend fun historyCache(owner: String, inputKey: String): StoredReadingHistory?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun saveHistory(value: StoredReadingHistory)
    @Query("SELECT * FROM reading_sentence_favorites WHERE owner = :owner ORDER BY createdAt DESC")
    suspend fun sentenceFavorites(owner: String): List<StoredReadingFavorite>
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun favoriteSentence(value: StoredReadingFavorite)
    @Query("DELETE FROM reading_sentence_favorites WHERE owner = :owner AND articleKey = :articleKey AND sentenceId = :sentenceId")
    suspend fun unfavoriteSentence(owner: String, articleKey: String, sentenceId: String)
    @Query("SELECT * FROM sentence_analyses WHERE owner = :owner AND cacheKey = :key LIMIT 1")
    suspend fun analysis(owner: String, key: String): StoredSentenceAnalysis?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAnalysis(value: StoredSentenceAnalysis)
    @Query("SELECT * FROM reading_conversations WHERE owner = :owner AND threadId = :id LIMIT 1")
    suspend fun conversation(owner: String, id: String): StoredReadingConversation?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveConversation(value: StoredReadingConversation)
}

/** 独立新增数据库，不迁移或清空原有单词/阅读记录；以后升级必须追加保数据迁移。 */
@Database(entities = [StoredSentenceAnalysis::class, StoredReadingConversation::class,
    StoredReadingHistory::class, StoredReadingFavorite::class], version = 2, exportSchema = true)
abstract class ReadingAnalysisDatabase : RoomDatabase() {
    abstract fun dao(): ReadingAnalysisDao
    companion object {
        @Volatile private var instance: ReadingAnalysisDatabase? = null
        fun of(context: Context): ReadingAnalysisDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,
                ReadingAnalysisDatabase::class.java, "english-reading.db")
                .addMigrations(READING_MIGRATION_1_2).build().also { instance = it }
        }
    }
}

internal val READING_MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_history (owner TEXT NOT NULL, id TEXT NOT NULL, articleKey TEXT NOT NULL, articleTitle TEXT NOT NULL, sentenceId TEXT NOT NULL, sourceText TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, inputText TEXT NOT NULL, inputKey TEXT NOT NULL, model TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(owner, id))")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_history_owner_articleKey_sentenceId ON reading_history (owner, articleKey, sentenceId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_reading_history_owner_inputKey ON reading_history (owner, inputKey)")
        db.execSQL("CREATE TABLE IF NOT EXISTS reading_sentence_favorites (owner TEXT NOT NULL, articleKey TEXT NOT NULL, sentenceId TEXT NOT NULL, articleTitle TEXT NOT NULL, sourceText TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(owner, articleKey, sentenceId))")
    }
}

interface ReadingAnalysisCache {
    suspend fun load(owner: String, key: String): String?
    suspend fun save(record: StoredSentenceAnalysis)
}

class RoomReadingAnalysisCache(private val dao: ReadingAnalysisDao) : ReadingAnalysisCache {
    override suspend fun load(owner: String, key: String): String? = dao.analysis(owner, key)?.resultJson
    override suspend fun save(record: StoredSentenceAnalysis) = dao.saveAnalysis(record)
}
