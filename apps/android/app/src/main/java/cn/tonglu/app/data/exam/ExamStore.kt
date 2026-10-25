package cn.tonglu.app.data.exam

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import cn.tonglu.app.data.SessionStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 开关：首页要不要显示考试倒计时（`docs/plan/54` M2）。
 *
 * **默认开** —— 这个功能的全部价值就是「一眼看到」，默认关等于没做。
 * 运行时值放全局可观察 State（照 `OverlayMotion.enabledState` 的做法），
 * 这样用户在设置里一拨，正在显示的首页立刻跟着变，不用重启也不用把 State 往下传。
 *
 * 「关闭后首页不留任何入口」是三条硬约束，实现在 `ExamCountdownCard` 的调用处
 * （那里有注释），这里只管开关本身。**关闭不清数据** —— 关显示 ≠ 删数据。
 */
object ExamSettings {
    private const val PREF_FILE = "tonglu_settings"
    private const val PREF_KEY = "exam_countdown"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).getBoolean(PREF_KEY, true)

    var enabledState by androidx.compose.runtime.mutableStateOf(true)
        private set

    fun syncFrom(context: Context): Boolean = enabled(context).also { enabledState = it }

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_KEY, value).apply()
        enabledState = value
    }
}

/**
 * 考试本地存储（`docs/plan/54` M4）。
 *
 * **按身份分桶**：key 带 `@身份` 后缀（照 `ScheduleStore`），A 退出 B 登录不会看到 A 的考试。
 * 用 [StateFlow] 而不是「每次读一遍」—— 首页要在加进备考的瞬间就刷新，而不是等下次进页面。
 *
 * **P0 不进 `ProfileSync`**（plan 54 Q2 的建议）：考试是短周期数据，换手机重新勾一次
 * 成本很低，而同步多一条链路就多一处可能出错的地方。
 */
class ExamStore private constructor(context: Context, owner: String) {
    private val preferences = context.applicationContext.getSharedPreferences("tonglu_exam", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val key = "events@$owner"

    private val _events = MutableStateFlow<List<ExamEvent>>(readRaw())
    val events: StateFlow<List<ExamEvent>> = _events

    private fun readRaw(): List<ExamEvent> {
        val raw = preferences.getString(key, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<ExamEvent>>() {}.type
            gson.fromJson<List<ExamEvent>>(raw, type)
        }.getOrNull()?.filter { it.id.isNotBlank() && it.date.isNotBlank() } ?: emptyList()
        // 过滤掉半截数据：id 或 date 为空的记录是写坏了的，留着会让 UI 崩在空指针上。
    }

    private fun write(list: List<ExamEvent>) {
        preferences.edit().putString(key, gson.toJson(list)).apply()
        _events.value = list
    }

    fun reload() {
        val list = readRaw()
        // 顺手把过期的归档掉 —— 这是唯一会改磁盘的时机，不做常驻扫描。
        val now = System.currentTimeMillis()
        if (list.any { ExamCountdown.shouldArchive(it, now) }) {
            write(list.map { if (ExamCountdown.shouldArchive(it, now)) it.copy(archivedAt = now) else it })
        } else {
            _events.value = list
        }
    }

    /** 新增或更新（按 id 匹配） */
    fun upsert(event: ExamEvent) {
        val now = System.currentTimeMillis()
        val list = _events.value.toMutableList()
        val index = list.indexOfFirst { it.id == event.id }
        val stored = if (index >= 0) {
            event.copy(createdAt = list[index].createdAt, updatedAt = now)
        } else {
            event.copy(createdAt = event.createdAt.takeIf { it > 0 } ?: now, updatedAt = now)
        }
        if (index >= 0) list[index] = stored else list.add(stored)
        write(list)
    }

    fun remove(id: String) {
        write(_events.value.filterNot { it.id == id })
    }

    /** 加进/移出备考（首页显示与否） */
    fun setPinned(id: String, pinned: Boolean) {
        val now = System.currentTimeMillis()
        write(_events.value.map { if (it.id == id) it.copy(pinned = pinned, updatedAt = now) else it })
    }

    companion object {
        @Volatile
        private var instance: ExamStore? = null
        private var instanceOwner: String = ""

        /**
         * 进程内单例。**换账号时 owner 变了要重建** —— 否则 B 登录后会读着 A 桶里的实例。
         */
        fun get(context: Context): ExamStore {
            val owner = SessionStore.currentOwner(context)
            val current = instance
            if (current != null && instanceOwner == owner) return current
            return synchronized(this) {
                val again = instance
                if (again != null && instanceOwner == owner) {
                    again
                } else {
                    ExamStore(context, owner).also { instance = it; instanceOwner = owner }
                }
            }
        }
    }
}
