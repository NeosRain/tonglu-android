package cn.tonglu.app.ui.english.examentry

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 组件内部层级栈 + 筛选/滚动保留。纯状态类（mutableStateOf 驱动重组），
 * 不依赖 Composition，JVM 单测直接调用即可。
 *
 * 保留规则（用户 2026-10-14 要求「筛选和列表滚动位置在逐级返回后保留」）：
 * - 筛选：回到同一次列表（同一 examKey 或再次进推荐）时保留；换一个列表重置为默认。
 * - 滚动：[listScrollKey] 作为 SaveableStateHolder 的 key，同 key 自动恢复；
 *   换列表 → 新 key → 从头开始。
 */
class ExamEntryNavState(initialFilter: ExamListFilter = ExamListFilter()) {

    enum class Level { HOME, EXAM_SELECT, EXAM_ARTICLES, RECOMMENDED }

    var level: Level by mutableStateOf(Level.HOME)
        private set
    var selectedExamKey: String? by mutableStateOf(null)
        private set
    var selectedExamTitle: String by mutableStateOf("")
        private set
    var filter: ExamListFilter by mutableStateOf(initialFilter)
        private set
    var listScrollKey: String? by mutableStateOf(null)
        private set

    /** 每次筛选实际变化时 +1：作为滚动保存 key 的一部分（改筛选 → 列表从头，返回后仍保留）。 */
    var filterVersion: Int by mutableStateOf(0)
        private set

    /** 到顶（精读入口）时返回 false，由宿主决定退出（onBack 回调）。 */
    val canPop: Boolean get() = level != Level.HOME

    fun openExamSelect() {
        level = Level.EXAM_SELECT
    }

    fun openExam(key: String, title: String) {
        val nextKey = "exam:$key"
        if (listScrollKey != nextKey) {
            // 换一个列表（换考试）：筛选与滚动重置；回到同一列表则全部保留。
            filter = ExamListFilter()
            listScrollKey = nextKey
        }
        selectedExamKey = key
        selectedExamTitle = title
        level = Level.EXAM_ARTICLES
    }

    fun openRecommended() {
        if (listScrollKey != "recommended") {
            filter = ExamListFilter()
            listScrollKey = "recommended"
        }
        level = Level.RECOMMENDED
    }

    /** 逐级返回；到顶返回 false。 */
    fun back(): Boolean {
        if (!canPop) return false
        level = when (level) {
            Level.EXAM_SELECT -> Level.HOME
            Level.EXAM_ARTICLES -> Level.EXAM_SELECT
            Level.RECOMMENDED -> Level.HOME
            Level.HOME -> return false
        }
        return true
    }

    /** 宿主（C 接线）在用户主动切列表时也可显式清筛选。 */
    fun clearFilter() {
        updateFilter(ExamListFilter())
    }

    /** 返回 true 表示筛选确实变了（组件会同步 onFilterChanged 通知宿主）。 */
    fun updateFilter(next: ExamListFilter): Boolean {
        if (next == filter) return false
        filter = next
        filterVersion += 1
        return true
    }

    /**
     * 进程重建恢复：\u001F 分隔的 8 段。restore 失败（段数不符）返回 false，
     * 保持默认状态，绝不拿半个状态硬撑。
     */
    fun save(): String = listOf(
        level.name,
        selectedExamKey ?: "",
        selectedExamTitle,
        filter.year?.toString() ?: "",
        filter.session ?: "",
        filter.readingType ?: "",
        listScrollKey ?: "",
        filterVersion.toString(),
    ).joinToString(SEP.toString())

    fun restore(saved: String): Boolean {
        val parts = saved.split(SEP)
        if (parts.size != 8) return false
        val level = Level.entries.firstOrNull { it.name == parts[0] } ?: return false
        selectedExamKey = parts[1].ifEmpty { null }
        selectedExamTitle = parts[2]
        filter = ExamListFilter(
            year = parts[3].ifEmpty { null }?.toIntOrNull(),
            session = parts[4].ifEmpty { null },
            readingType = parts[5].ifEmpty { null },
        )
        listScrollKey = parts[6].ifEmpty { null }
        filterVersion = parts[7].toIntOrNull() ?: 0
        this.level = level
        return true
    }

    companion object {
        private const val SEP = '\u001F'
    }
}
