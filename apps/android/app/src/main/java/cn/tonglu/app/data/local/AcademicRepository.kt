package cn.tonglu.app.data.local

import android.content.Context
import androidx.room.withTransaction
import cn.tonglu.app.data.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class AcademicSnapshot(val courses: List<CourseDto>, val scores: List<ScoreRecord>)

/** SQLite is authoritative after migration; the original JSON stays untouched as a recovery copy. */
class AcademicRepository private constructor(private val context: Context) {
    private val database = AcademicDatabase.get(context)
    private val dao = database.academicDao()
    private val migrationMutex = Mutex()
    private val gson = Gson()

    private suspend fun ensure(owner: String) = migrationMutex.withLock {
        withContext(Dispatchers.IO) {
            if (dao.migration("legacy_json_v1") == null) migrateLegacy(owner)
            claimLocal(owner)
        }
    }

    private suspend fun migrateLegacy(currentOwner: String) {
        val schedules = context.getSharedPreferences("tonglu_schedule", Context.MODE_PRIVATE)
        val grades = context.getSharedPreferences("tonglu_scores", Context.MODE_PRIVATE)
        val owners = (schedules.all.keys.filter { it.startsWith("courses_cache@") }.map { it.substringAfter('@') } +
            grades.all.keys.filter { it.startsWith("records@") }.map { it.substringAfter('@') }).toMutableSet()
        if (schedules.contains("courses_cache")) owners += currentOwner
        database.withTransaction {
            for (owner in owners) {
                val courseRaw = schedules.getString("courses_cache@$owner", null)
                    ?: if (owner == currentOwner) schedules.getString("courses_cache", null) else null
                val scoreRaw = grades.getString("records@$owner", null)
                val courses: List<CourseDto> = if (courseRaw == null) emptyList() else
                    gson.fromJson(courseRaw, object : TypeToken<List<CourseDto>>() {}.type)
                        ?: error("旧课表数据格式不完整；原数据已保留")
                val scores: List<ScoreRecord> = if (scoreRaw == null) emptyList() else
                    gson.fromJson(scoreRaw, object : TypeToken<List<ScoreRecord>>() {}.type)
                        ?: error("旧成绩数据格式不完整；原数据已保留")
                writeCourses(owner, courses)
                writeScores(owner, scores)
                check(dao.courses(owner).size == courses.size && dao.scores(owner).size == scores.size) {
                    "旧数据迁移数量不一致；原数据已保留"
                }
                dao.putOwner(OwnerState(owner, courseRaw != null, scoreRaw != null))
            }
            // A single transaction covers all accounts and the completion marker.
            dao.putMigration(MigrationState("legacy_json_v1", System.currentTimeMillis()))
        }
        if (schedules.contains("courses_cache") && !schedules.contains("last_import@$currentOwner")) {
            schedules.edit().putString("last_import@$currentOwner", schedules.getString("last_import", null)).apply()
        }
    }

    private suspend fun claimLocal(owner: String) {
        if (owner == SessionStore.LOCAL_OWNER) return
        database.withTransaction {
            val target = dao.owner(owner) ?: OwnerState(owner)
            val guest = dao.owner(SessionStore.LOCAL_OWNER) ?: return@withTransaction
            val moveCourses = !target.coursesInitialized && guest.coursesInitialized
            val moveScores = !target.scoresInitialized && guest.scoresInitialized
            if (!moveCourses && !moveScores) return@withTransaction
            if (moveCourses) {
                val snapshot = snapshotInsideTransaction(SessionStore.LOCAL_OWNER)
                writeCourses(owner, snapshot.courses)
                dao.deleteCourses(SessionStore.LOCAL_OWNER)
            }
            if (moveScores) {
                writeScores(owner, dao.scores(SessionStore.LOCAL_OWNER).map { it.toRecord() })
                dao.deleteScores(SessionStore.LOCAL_OWNER)
            }
            dao.putOwner(target.copy(coursesInitialized = target.coursesInitialized || moveCourses,
                scoresInitialized = target.scoresInitialized || moveScores))
            dao.putOwner(guest.copy(coursesInitialized = guest.coursesInitialized && !moveCourses,
                scoresInitialized = guest.scoresInitialized && !moveScores))
            // Prevent another account from claiming the emptied guest bucket.
            if (moveCourses) moveMetadata("tonglu_schedule", owner, listOf("last_import"))
            if (moveScores) moveMetadata("tonglu_scores", owner, listOf("last_import", "student_name", "source"))
        }
    }

    private fun moveMetadata(file: String, owner: String, names: List<String>) {
        val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        for (name in names) {
            val local = "$name@${SessionStore.LOCAL_OWNER}"
            if (prefs.contains(local)) {
                editor.putString("$name@$owner", prefs.getString(local, null)).remove(local)
            }
        }
        editor.apply()
    }

    suspend fun snapshot(owner: String): AcademicSnapshot {
        ensure(owner)
        return database.withTransaction { snapshotInsideTransaction(owner) }
    }

    private suspend fun snapshotInsideTransaction(owner: String): AcademicSnapshot {
        val weeks = dao.weeks(owner).groupBy { it.courseId }
        return AcademicSnapshot(dao.courses(owner).map { row -> CourseDto(row.id, row.name, row.teacher,
            row.location, row.weekday, row.startPeriod, row.endPeriod, row.startWeek, row.endWeek,
            weeks[row.id].orEmpty().map { it.week }, row.color, row.source) }, dao.scores(owner).map { it.toRecord() })
    }

    fun observe(owner: String): Flow<AcademicSnapshot> = flow {
        ensure(owner)
        emitAll(dao.observeChanges(owner).map { database.withTransaction { snapshotInsideTransaction(owner) } })
    }

    suspend fun saveCourses(owner: String, courses: List<CourseDto>) {
        ensure(owner)
        database.withTransaction {
            writeCourses(owner, courses)
            dao.putOwner((dao.owner(owner) ?: OwnerState(owner)).copy(coursesInitialized = true))
        }
    }

    private suspend fun writeCourses(owner: String, courses: List<CourseDto>) {
        require(courses.map { it.id }.distinct().size == courses.size) { "课程 ID 重复，原数据未改动" }
        dao.deleteCourses(owner)
        dao.insertCourses(courses.map { CourseEntity(owner, it.id, it.name, it.teacher, it.location,
            it.weekday, it.startPeriod, it.endPeriod, it.startWeek, it.endWeek, it.color, it.source) })
        dao.insertWeeks(courses.flatMap { course -> course.weeks.distinct().map { CourseWeekEntity(owner, course.id, it) } })
    }

    suspend fun saveScores(owner: String, scores: List<ScoreRecord>) {
        ensure(owner)
        database.withTransaction {
            writeScores(owner, scores)
            dao.putOwner((dao.owner(owner) ?: OwnerState(owner)).copy(scoresInitialized = true))
        }
    }

    private suspend fun writeScores(owner: String, scores: List<ScoreRecord>) {
        val duplicates = mutableMapOf<String, Int>()
        val rows = scores.mapIndexed { index, record ->
            val key = listOf(record.courseName.trim(), record.termCode.orEmpty(), record.examType.orEmpty()).joinToString("\u001f")
            val occurrence = duplicates.getOrDefault(key, 0)
            duplicates[key] = occurrence + 1
            val id = UUID.nameUUIDFromBytes("$owner\u001f$key\u001f$occurrence".toByteArray(Charsets.UTF_8)).toString()
            ScoreEntity(owner, id, index, record.courseName, record.scoreText, record.scoreValue, record.credit,
                record.gradePoint, record.termCode, record.examType, record.courseType, record.passed)
        }
        dao.deleteScores(owner)
        dao.insertScores(rows)
    }

    suspend fun clearAllCourses(owner: String) {
        ensure(owner)
        database.withTransaction { dao.deleteAllCourses(); dao.markAllCoursesCleared() }
    }
    suspend fun clearAllScores(owner: String) {
        ensure(owner)
        database.withTransaction { dao.deleteAllScores(); dao.markAllScoresCleared() }
    }

    companion object {
        @Volatile private var instance: AcademicRepository? = null
        fun get(context: Context): AcademicRepository = instance ?: synchronized(this) {
            instance ?: AcademicRepository(context.applicationContext).also { instance = it }
        }
    }
}

private fun ScoreEntity.toRecord() = ScoreRecord(courseName, scoreText, scoreValue, credit, gradePoint,
    termCode, examType, courseType, passed)

