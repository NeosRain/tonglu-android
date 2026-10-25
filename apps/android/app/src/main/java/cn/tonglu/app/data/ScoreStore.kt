package cn.tonglu.app.data

import android.content.Context



/**
 * 成绩本地存档。
 *
 * 和课表一样按身份分桶（key 带 `@身份` 后缀），换账号不会串；
 * 退出登录时可选一起清掉（和课表用同一个勾选）。
 */
class ScoreStore(context: Context, owner: String? = null) {
    private val preferences = context.applicationContext
        .getSharedPreferences("tonglu_scores", Context.MODE_PRIVATE)

    private val owner: String = owner ?: SessionStore.currentOwner(context)
    private val repository = cn.tonglu.app.data.local.AcademicRepository.get(context)

    private fun key(name: String) = "$name@$owner"

    // Guest ownership and legacy records are migrated by AcademicRepository.

    suspend fun load(): List<ScoreRecord> {
        return repository.snapshot(owner).scores
    }

    suspend fun save(records: List<ScoreRecord>) {
        repository.saveScores(owner, records)
    }

    suspend fun clear() {
        repository.saveScores(owner, emptyList())
        preferences.edit()
            .remove(key(RECORDS))
            .remove(key(LAST_IMPORT))
            .remove(key(STUDENT))
            .remove(key(SOURCE))
            .apply()
    }

    /** 清掉**本机全部身份**的成绩存档（退出登录勾选删除时用，理由见 ScheduleStore.clearAllOwners） */
    suspend fun clearAllOwners() {
        repository.clearAllScores(owner)
        val editor = preferences.edit()
        preferences.all.keys
            .filter {
                it.startsWith("$RECORDS@") || it.startsWith("$LAST_IMPORT@") ||
                it.startsWith("$STUDENT@") || it.startsWith("$SOURCE@")
            }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    var lastImport: String?
        get() = preferences.getString(key(LAST_IMPORT), null)
        set(value) = preferences.edit().putString(key(LAST_IMPORT), value).apply()

    /**
     * 这份成绩是哪来的："demo" = 内置示例数据，"import" = 教务导入，null = 老数据/未知。
     * 成绩页靠它给示例数据打标 —— 示例和真实成绩长得太像，不打标用户会当成自己的成绩。
     */
    var source: String?
        get() = preferences.getString(key(SOURCE), null)
        set(value) = preferences.edit().putString(key(SOURCE), value).apply()

    /** 导入时顺手拿到的姓名（成绩页用来显示「这是谁的成绩」） */
    var studentName: String?
        get() = preferences.getString(key(STUDENT), null)
        set(value) = preferences.edit().putString(key(STUDENT), value).apply()

    private companion object {
        const val RECORDS = "records"
        const val LAST_IMPORT = "last_import"
        const val STUDENT = "student_name"
        const val SOURCE = "source"
    }
}

