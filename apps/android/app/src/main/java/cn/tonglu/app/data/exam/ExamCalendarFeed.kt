package cn.tonglu.app.data.exam

import android.content.Context
import android.util.Log
import cn.tonglu.app.data.TongluApi
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 服务端上下发的国家考证年度表。
 *
 * 字段**一律可空**：服务端换版、字段改名、半个 JSON 都得能扛住 ——
 * 解析失败只是「没有远端表」，绝不能变成崩溃（这条容错范式照 `RemoteLearningCatalog`）。
 */
data class RemoteExamCalendar(
    val enabled: Boolean? = null,
    val source: String? = null,
    val schemaVersion: Int? = null,
    val revision: String? = null,
    val verifiedAt: String? = null,
    val groups: List<String>? = null,
    val exams: List<RemoteExamEntry>? = null,
)

data class RemoteExamEntry(
    val id: String? = null,
    val title: String? = null,
    val subjectKey: String? = null,
    val group: String? = null,
    val date: String? = null,
    val endDate: String? = null,
    val dateSource: String? = null,
    val sourceLabel: String? = null,
)

/**
 * 客户端真正用的年度表。
 *
 * @param revision 服务端内容的指纹；空表示这份来自内置表。
 * @param verifiedAt 官方公告核实日期（人话展示在页面底部）。
 * @param online 这份是不是从服务端拿的 —— 页面底部那句话要如实说清数据来源。
 */
data class ExamCalendar(
    val revision: String = "",
    val verifiedAt: String = "",
    val online: Boolean = false,
    val groups: List<String> = NationalExamSeed.Group.ALL,
    val exams: List<NationalExamSeed.NationalExam> = emptyList(),
)

/**
 * 年度表的**在线通道**（`docs/plan/54` 的 #5b：可迁移数据上云）。
 *
 * 为什么要有它：年度表原本硬编码在 APK 里（`NationalExamSeed`），官方每年发文改考试日期，
 * 用户不更新 App 就一直看着错的倒计时。现在服务端存一份，后台改完客户端下次拉就生效。
 *
 * 三条退路（按优先级）：
 *  1. 服务端那份（拉到了且结构合法）；
 *  2. 上次拉成功存在本地的那份（离线/服务端挂了也能用新的）；
 *  3. APK 内置的 `NationalExamSeed`（最坏情况，绝不空白）。
 *
 * **不自动下载**：跟英语词库一个道理 —— 不替用户花流量。由 App 在联网时顺带同步一次。
 */
object ExamCalendarFeed {

    private const val TAG = "ExamCalendarFeed"

    private val _calendar = MutableStateFlow<ExamCalendar?>(null)
    val calendar: StateFlow<ExamCalendar?> = _calendar

    /** 当前要用的年度表；还没同步过就直接用内置的（内置表本来就撑得住）。 */
    fun current(context: Context): ExamCalendar =
        _calendar.value ?: readLocal(context) ?: builtin()

    /** 还没考的场次，按日期升序。**过期场次在这里被滤掉**，服务端那份不需要按年份切。 */
    fun upcoming(context: Context, todayEpochDay: Long): List<NationalExamSeed.NationalExam> {
        val calendar = current(context)
        val list = calendar.exams.filter {
            val epochDay = ExamCountdown.dateToEpochDay(it.date) ?: return@filter false
            epochDay >= todayEpochDay
        }.sortedWith(compareBy<NationalExamSeed.NationalExam> { it.date }.thenBy { it.title })
        // 服务端那份整体过期了（有人忘了更新），退回内置表 —— 内置表兜不住还有 L1 规则层。
        return list.ifEmpty { NationalExamSeed.upcoming(todayEpochDay) }
    }

    /** 分类筛选条用：服务端没给分类就用内置那套 9 分类。 */
    fun groups(context: Context): List<String> = current(context).groups

    /**
     * 同步一次。失败**不抛**，只是这一轮没拿到 —— 上层继续用本地/内置那份。
     *
     * 结构不合法**不写盘**：写个坏文件进去，之后每次开日历页都要失败一次，比不写更糟。
     */
    suspend fun sync(context: Context, api: TongluApi): Boolean = withContext(Dispatchers.IO) {
        val remote = runCatching { api.examCalendar() }.getOrElse { error ->
            Log.i(TAG, "拉年度表失败，继续用本地那份：${error.message}")
            return@withContext false
        }
        if (remote.enabled == false) return@withContext false
        val calendar = remote.toCalendar() ?: run {
            Log.i(TAG, "服务器年度表结构不认识（schemaVersion=%s），跳过".format(remote.schemaVersion))
            return@withContext false
        }
        val revision = remote.revision.orEmpty()
        if (revision.isNotBlank() && revision == readLocal(context)?.revision) {
            // 内容没变，但内存里那份可能还没装上（冷启动） —— 装上去，别写盘。
            _calendar.value = calendar
            return@withContext false
        }
        writeLocal(context, remote)
        _calendar.value = calendar
        Log.i(TAG, "在线年度表已更新：${calendar.exams.size} 场，revision=$revision")
        true
    }

    /** 冷启动时先装上本地缓存的那份（同步可能在后面才轮到）。 */
    fun prime(context: Context) {
        if (_calendar.value == null) _calendar.value = readLocal(context)
    }

    /** 退出登录 / 清缓存时用：只清内存与文件，内置那份永远在。 */
    fun clear(context: Context) {
        _calendar.value = null
        runCatching { file(context).delete() }.onFailure { Log.i(TAG, "清年度表缓存失败：${it.message}") }
    }

    // ------------------------------------------------------------------ 内置 / 本地

    private fun builtin(): ExamCalendar = ExamCalendar(
        revision = "",
        verifiedAt = "2026-10-04",
        online = false,
        groups = NationalExamSeed.Group.ALL,
        // 全量内置场次（含已考完的），由 upcoming() 按今天过滤
        exams = NationalExamSeed.all(),
    )

    private fun dir(context: Context) = File(context.filesDir, "exam").also { it.mkdirs() }

    private fun file(context: Context) = File(dir(context), "calendar.json")

    private fun readLocal(context: Context): ExamCalendar? {
        val text = runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull()
            ?: return null
        val remote = runCatching { Gson().fromJson(text, RemoteExamCalendar::class.java) }.getOrNull()
            ?: return null
        return remote.toCalendar()
    }

    private fun writeLocal(context: Context, remote: RemoteExamCalendar) {
        runCatching {
            dir(context)
            val tmp = File(dir(context), "calendar.json.tmp")
            tmp.writeText(Gson().toJson(remote))
            val dest = file(context)
            if (tmp.renameTo(dest)) Unit else dest.writeText(tmp.readText())
            tmp.delete()
        }.onFailure { Log.i(TAG, "写年度表缓存失败：${it.message}") }
    }

}

/**
 * 远端 → 客户端结构。**不合法返回 null**（上层继续用内置那份）。
 *
 * 校验点都是「留着会出事」的：schemaVersion 不对说明读不懂；id 为空会让「已加入」
 * 判定错位；日期解析不出来就根本算不出倒计时。
 *
 * 放在 object 外面是为了能写单测 —— 这条转换是「服务器数据能不能用」的唯一判据，
 * 不给它测试，就只能等线上发了一份坏数据才发现。
 */
internal fun RemoteExamCalendar.toCalendar(): ExamCalendar? {
    if (schemaVersion != 1) return null
    val entries = exams.orEmpty()
    if (entries.isEmpty()) return null
    val exams = entries.mapNotNull { item ->
        val id = item.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val title = item.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val date = item.date?.takeIf { ExamCountdown.dateToEpochDay(it) != null } ?: return@mapNotNull null
        val group = item.group?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val source = item.dateSource?.takeIf { it.isNotBlank() } ?: ExamDateSource.RULE
        NationalExamSeed.NationalExam(
            id = id, title = title,
            subjectKey = item.subjectKey?.takeIf { it.isNotBlank() } ?: ExamSubjects.CUSTOM,
            group = group, date = date,
            endDate = item.endDate?.takeIf { ExamCountdown.dateToEpochDay(it) != null },
            timeText = null, dateSource = source, sourceLabel = item.sourceLabel,
        )
    }
    if (exams.isEmpty()) return null
    if (exams.map { it.id }.distinct().size != exams.size) return null
    val groups = groups.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return ExamCalendar(
        revision = revision.orEmpty(),
        verifiedAt = verifiedAt.orEmpty(),
        online = true,
        groups = groups.ifEmpty { NationalExamSeed.Group.ALL },
        exams = exams,
    )
}
