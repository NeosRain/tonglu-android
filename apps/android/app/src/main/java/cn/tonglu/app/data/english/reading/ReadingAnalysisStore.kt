package cn.tonglu.app.data.english.reading

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
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

@Dao
interface ReadingAnalysisDao {
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
@Database(entities = [StoredSentenceAnalysis::class, StoredReadingConversation::class], version = 1, exportSchema = true)
abstract class ReadingAnalysisDatabase : RoomDatabase() {
    abstract fun dao(): ReadingAnalysisDao
    companion object {
        @Volatile private var instance: ReadingAnalysisDatabase? = null
        fun of(context: Context): ReadingAnalysisDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext,
                ReadingAnalysisDatabase::class.java, "english-reading.db").build().also { instance = it }
        }
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
