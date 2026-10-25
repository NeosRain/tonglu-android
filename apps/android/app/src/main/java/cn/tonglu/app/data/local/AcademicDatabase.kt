package cn.tonglu.app.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "courses", primaryKeys = ["owner", "id"])
data class CourseEntity(
    val owner: String, val id: String, val name: String, val teacher: String?, val location: String?,
    val weekday: Int, val startPeriod: Int, val endPeriod: Int, val startWeek: Int, val endWeek: Int,
    val color: String?, val source: String?,
)

@Entity(tableName = "course_weeks", primaryKeys = ["owner", "courseId", "week"],
    foreignKeys = [ForeignKey(entity = CourseEntity::class, parentColumns = ["owner", "id"],
        childColumns = ["owner", "courseId"], onDelete = ForeignKey.CASCADE)])
data class CourseWeekEntity(val owner: String, val courseId: String, val week: Int)

@Entity(tableName = "scores", primaryKeys = ["owner", "id"])
data class ScoreEntity(
    val owner: String, val id: String, val position: Int, val courseName: String, val scoreText: String,
    val scoreValue: Double?, val credit: Double?, val gradePoint: Double?, val termCode: String?,
    val examType: String?, val courseType: String?, val passed: Boolean,
)

@Entity(tableName = "owner_state")
data class OwnerState(@PrimaryKey val owner: String, val coursesInitialized: Boolean = false,
    val scoresInitialized: Boolean = false)

@Entity(tableName = "migration_state")
data class MigrationState(@PrimaryKey val name: String, val completedAt: Long)

@Dao
interface AcademicDao {
    @Query("SELECT * FROM courses WHERE owner = :owner ORDER BY weekday, startPeriod, id")
    suspend fun courses(owner: String): List<CourseEntity>
    @Query("SELECT * FROM course_weeks WHERE owner = :owner ORDER BY week")
    suspend fun weeks(owner: String): List<CourseWeekEntity>
    @Query("SELECT * FROM scores WHERE owner = :owner ORDER BY position")
    suspend fun scores(owner: String): List<ScoreEntity>
    @Query("SELECT * FROM owner_state WHERE owner = :owner")
    suspend fun owner(owner: String): OwnerState?
    @Query("SELECT * FROM migration_state WHERE name = :name")
    suspend fun migration(name: String): MigrationState?
    @Insert suspend fun insertCourses(rows: List<CourseEntity>)
    @Insert suspend fun insertWeeks(rows: List<CourseWeekEntity>)
    @Insert suspend fun insertScores(rows: List<ScoreEntity>)
    @Upsert suspend fun putOwner(row: OwnerState)
    @Upsert suspend fun putMigration(row: MigrationState)
    @Query("DELETE FROM courses WHERE owner = :owner") suspend fun deleteCourses(owner: String)
    @Query("DELETE FROM scores WHERE owner = :owner") suspend fun deleteScores(owner: String)
    @Query("DELETE FROM courses") suspend fun deleteAllCourses()
    @Query("DELETE FROM scores") suspend fun deleteAllScores()
    @Query("UPDATE owner_state SET coursesInitialized = 1") suspend fun markAllCoursesCleared()
    @Query("UPDATE owner_state SET scoresInitialized = 1") suspend fun markAllScoresCleared()
    // Room observes all three tables, including edits that don't change the number of records.
    @Query("SELECT (SELECT COUNT(*) FROM courses WHERE owner=:owner) + " +
        "(SELECT COUNT(*) FROM course_weeks WHERE owner=:owner) + " +
        "(SELECT COUNT(*) FROM scores WHERE owner=:owner)")
    fun observeChanges(owner: String): Flow<Long>
}

@Database(entities = [CourseEntity::class, CourseWeekEntity::class, ScoreEntity::class,
    OwnerState::class, MigrationState::class], version = 1, exportSchema = true)
abstract class AcademicDatabase : RoomDatabase() {
    abstract fun academicDao(): AcademicDao

    companion object {
        @Volatile private var instance: AcademicDatabase? = null
        fun get(context: Context): AcademicDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AcademicDatabase::class.java,
                "academic.db").build().also { instance = it }
        }
    }
}
