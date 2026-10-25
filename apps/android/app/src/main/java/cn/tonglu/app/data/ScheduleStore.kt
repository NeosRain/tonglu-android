package cn.tonglu.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 课表本地设置与离线缓存。
 *
 * 分两类存：
 *  · **按身份分桶**（课程缓存、导入记录）—— key 带 `@身份` 后缀，换账号不串数据；
 *  · **设备级共用**（开学日期、作息表、提醒设置）—— 这是校历和本机偏好，不属于某个账号。
 */
class ScheduleStore(context: Context, owner: String? = null) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("tonglu_schedule", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val owner: String = owner ?: SessionStore.currentOwner(context)
    private val repository = cn.tonglu.app.data.local.AcademicRepository.get(context)

    private fun key(name: String) = "$name@$owner"

    // Legacy JSON migration is transactional in AcademicRepository; don't remove recovery copies here.

    /** 教学周第 1 周的起始日期，形如 2026-09-01（约定该周为第 1 周），空表示未设置 */
    var termStart: String?
        get() = preferences.getString("term_start", null)
        set(value) {
            preferences.edit().putString("term_start", value).apply()
            // 开学日期变了 → 教学周变了 → 小部件显示哪几节课也变了。
            // 用户「设置开学日期」之后往往立刻回桌面看，不刷新会以为没生效。
            notifyWidget()
        }

    var lastImportLabel: String?
        get() = preferences.getString(key(LAST_IMPORT), null)
        set(value) = preferences.edit().putString(key(LAST_IMPORT), value).apply()

    /** 是否开启课前提醒（WakeUp 式：课前 N 分钟 + 课前 5 分钟各一条通知） */
    var remindEnabled: Boolean
        get() = preferences.getBoolean("remind_enabled", true)
        set(value) = preferences.edit().putBoolean("remind_enabled", value).apply()

    /** 提前提醒的分钟数（默认 20） */
    var remindLeadMinutes: Int
        get() = preferences.getInt("remind_lead", 20)
        set(value) = preferences.edit().putInt("remind_lead", value).apply()

    /** 自定义作息时间表（JSON），空表示用教务默认作息 */
    var periodTableJson: String?
        get() = preferences.getString("period_table", null)
        set(value) = preferences.edit().putString("period_table", value).apply()

    var periodTableSource: String?
        get() = preferences.getString("period_table_source", null)
        set(value) = preferences.edit().putString("period_table_source", value).apply()

    /** 把本机保存的作息表灌进 PeriodTable（进程启动 / 提醒服务起来时都要调一次） */
    fun applyPeriodTable() {
        val raw = periodTableJson
        if (raw.isNullOrBlank()) {
            PeriodTable.setCustom(null)
            return
        }
        val slots = runCatching {
            val type = object : TypeToken<List<PeriodSlot>>() {}.type
            gson.fromJson<List<PeriodSlot>>(raw, type)
        }.getOrNull()
        PeriodTable.setCustom(slots)
    }

    /** 保存作息表；传 null 表示恢复教务默认 */
    fun savePeriodTable(slots: List<PeriodSlot>?) {
        periodTableSource = if (slots == null) null else "手动设置"
        if (slots == null) {
            periodTableJson = null
        } else {
            periodTableJson = gson.toJson(slots.sortedBy { it.period })
        }
        PeriodTable.setCustom(slots)
        // 作息改了 → 每节课的**具体时间**跟着变（课程存的是「第几节」不是几点）
        // → 桌面小部件上显示的「08:00-09:40」必须重算。不刷新的话，
        //   用户改完作息回桌面看到还是旧时间，会以为没保存上。
        notifyWidget()
    }

    suspend fun saveCourses(courses: List<CourseDto>) {
        repository.saveCourses(owner, courses)
        notifyWidget()
    }

    suspend fun loadCourses(): List<CourseDto> {
        return repository.snapshot(owner).courses
    }

    /** 清掉当前身份的课表缓存（「清空课表」走这里，云端由后端接口一起删） */
    suspend fun clearCourses() {
        repository.saveCourses(owner, emptyList())
        preferences.edit().remove(key(COURSES)).remove(key(LAST_IMPORT)).apply()
        notifyWidget()
    }

    /**
     * 课表变了 → 让桌面小部件重画。
     *
     * 为什么在 [saveCourses] / [clearCourses] 这两个入口而不是在 ViewModel 的
     * `repository.observe` 回调里：那是**所有**变更（含成绩）都会走的地方，
     * 成绩变一次就重画一次课表小部件是白费电。
     *
     * 为什么放在数据层而不是调用方：写课表的入口有导入、备份恢复、手工增删改三处，
     * 逐个去加调用必然漏一个（漏的那处表现是「改了课表桌面不更新」，很难联想到）。
     *
     * 失败**不抛**：这是小部件的额外收益，它坏了不该让主流程（保存课表）跟着失败。
     */
    private fun notifyWidget() {
        runCatching {
            cn.tonglu.app.widget.TodayWidgetUpdater.refreshAndReschedule(appContext)
        }
    }

    /**
     * 清掉**本机全部身份**的课表缓存（仅「退出登录时勾选删除」走这里）。
     *
     * 为什么不复用 [clearCourses]：那个是「清我自己这一桶」，退出登录的勾选要表达的是
     * 「这台设备上不留我的东西」——顺着把以前登录过的账号一起清，换机/换人才是真的干净。
     */
    suspend fun clearAllOwners() {
        repository.clearAllCourses(owner)
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith("$COURSES@") || it.startsWith("$LAST_IMPORT@") }
            .forEach { editor.remove(it) }
        // 老版本遗留的无后缀 key 也一并清掉（migrateLegacy 之后正常不存在，防御性）
        editor.remove(COURSES).remove(LAST_IMPORT).apply()
    }

    private companion object {
        const val COURSES = "courses_cache"
        const val LAST_IMPORT = "last_import"
    }
}

private fun minutesOf(hhmm: String): Int = PeriodTable.parseMinutes(hhmm)

/** 课表计算：某天/某周有什么课、现在上什么、下一节是什么 */
object ScheduleBoard {

    fun ofDay(courses: List<CourseDto>, weekday: Int, week: Int?): List<CourseDto> =
        courses.filter { it.weekday == weekday && (week == null || it.activeInWeek(week)) }
            .sortedBy { it.startPeriod }

    /** 当前正在上的课 */
    fun current(courses: List<CourseDto>, weekday: Int, week: Int?, nowMinutes: Int): CourseDto? =
        ofDay(courses, weekday, week).firstOrNull { course ->
            val start = PeriodTable.slot(course.startPeriod)?.let { minutesOf(it.start) } ?: return@firstOrNull false
            val end = PeriodTable.slot(course.endPeriod)?.let { minutesOf(it.end) } ?: return@firstOrNull false
            nowMinutes in start..end
        }

    /** 下一节要上的课 + 距离上课还有多少分钟（跨天时返回 null，由 UI 提示"今天没课了"） */
    fun next(courses: List<CourseDto>, weekday: Int, week: Int?, nowMinutes: Int): Pair<CourseDto, Int>? {
        val today = ofDay(courses, weekday, week)
        today.firstOrNull { course ->
            val start = PeriodTable.slot(course.startPeriod)?.let { minutesOf(it.start) } ?: return@firstOrNull false
            start > nowMinutes
        }?.let { course ->
            val start = minutesOf(PeriodTable.slot(course.startPeriod)!!.start)
            return course to (start - nowMinutes)
        }
        return null
    }

    /**
     * 未来 7 天里最近的一次上课（今天没有课时用）。
     *
     * **国定假期里的日子直接跳过** —— 放假期间报「明天有课」是错的（2026-10-05 用户报告：
     * 国庆 10-05 还在放假，首页却在数「还剩 4 节课」）。调休上班日（如 10-10 周六）补的是
     * 哪天的课学校各不相同，课表的「周几」模型表达不了，这里不做特殊处理，以学校通知为准。
     *
     * @param todayStart 今天 0 点的毫秒数，默认取系统当前时间；传固定值是为了可测试。
     */
    fun nextInDays(
        courses: List<CourseDto>,
        weekday: Int,
        week: Int?,
        nowMinutes: Int,
        todayStart: Long = ScheduleText.startOfDay(System.currentTimeMillis()),
    ): Pair<CourseDto, Int>? {
        val dayMillis = 24L * 3600 * 1000
        for (offset in 0..6) {
            if (Holidays.isHoliday(todayStart + offset * dayMillis)) continue
            val day = (weekday - 1 + offset) % 7 + 1
            val pool = if (offset == 0) {
                next(courses, day, week, nowMinutes)?.let { return it }
                continue
            } else ofDay(courses, day, week?.plus((weekday - 1 + offset) / 7))
            val first = pool.firstOrNull() ?: continue
            val start = PeriodTable.slot(first.startPeriod)?.let { minutesOf(it.start) } ?: continue
            val gap = offset * 24 * 60 + start - nowMinutes
            return first to gap
        }
        return null
    }

    /** "3 小时后" / "25 分钟后" 这类人话 */
    fun gapText(minutes: Int): String = when {
        minutes < 60 -> "$minutes 分钟后"
        minutes < 24 * 60 -> "${minutes / 60} 小时 ${minutes % 60} 分后"
        else -> {
            // 跨天按就近取整：下午 6 点看「后天早上的课」，整除会得 1 天（实际差 2 天）
            val days = Math.round(minutes / (24 * 60.0)).toInt()
            "$days 天后"
        }
    }
}

