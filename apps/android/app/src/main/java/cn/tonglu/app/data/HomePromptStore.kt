package cn.tonglu.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 首页「从教务导入课表」那张提示卡的状态。 */
enum class ImportPromptStatus {
    /** 没关过、也没导入成功 —— 首页显示提示卡。 */
    Unseen,

    /** 用户点了叉。 */
    Dismissed,

    /** 导入成功了，没必要再提示。 */
    Completed,
}

// DataStore 的委托只能声明在文件顶层，一个文件一份。
private val Context.homePromptDataStore: DataStore<Preferences> by preferencesDataStore(name = "home_prompts")

/**
 * 首页提示卡的持久化。**设备级** —— 不按账号分桶。
 *
 * 为什么改：以前是 `tonglu_settings` 里按 `import_prompt_dismissed@<owner>` 存的，
 * 游客关掉之后登录，key 从 `local` 变成 userId，**提示又冒出来了**（plan/56 §2.2）。
 * 「这张卡我看过了、不想再看到」是设备上的一次选择，不是某个账号的数据。
 *
 * 课表本身仍然按 owner 隔离，这里只管提示卡。
 */
class HomePromptStore(private val context: Context) {

    private val store = context.applicationContext.homePromptDataStore

    private object Keys {
        val STATUS = stringPreferencesKey("academicImportPrompt.status")
        val SCHEMA = intPreferencesKey("academicImportPrompt.schemaVersion")
        val DISMISSED_AT = longPreferencesKey("academicImportPrompt.dismissedAt")
    }

    private companion object {
        const val TAG = "HomePromptStore"
        const val SCHEMA_VERSION = 1
        /** 旧版按账号存的前缀，迁移时扫这些键。 */
        const val LEGACY_PREFIX = "import_prompt_dismissed@"
    }

    /**
     * 状态流。**`null` = 还没读出来** —— 界面此时不许显示提示卡，
     * 否则每次启动都会先闪一下再自己消失（plan/56 §2.2）。
     */
    val status: Flow<ImportPromptStatus?> = store.data
        .catch { bad ->
            android.util.Log.w(TAG, "读提示状态失败：${bad::class.java.simpleName}: ${bad.message}")
            emit(emptyPreferences())
        }
        .map { prefs ->
            prefs[Keys.STATUS]?.let { raw -> ImportPromptStatus.entries.firstOrNull { it.name == raw } }
        }

    /**
     * 初始化并做一次旧数据迁移。**只有状态还没落盘时才动**。
     *
     * 旧键一律**不删除**：万一用户装回旧版本，那边还得认。
     */
    suspend fun ensureInitialized(legacy: SharedPreferences) {
        if (store.data.first()[Keys.STATUS] != null) return
        val dismissedBefore = legacy.all.keys
            .filter { it.startsWith(LEGACY_PREFIX) }
            .any { legacy.getBoolean(it, false) }
        write(
            status = if (dismissedBefore) ImportPromptStatus.Dismissed else ImportPromptStatus.Unseen,
            dismissedAtMillis = 0L,
        )
    }

    /** 点叉。写盘失败返回 false —— 界面要如实说没保存，不能假称已保存。 */
    suspend fun dismiss(): Boolean =
        write(ImportPromptStatus.Dismissed, System.currentTimeMillis())

    /** 导入成功。**只在这里标 Completed**，导入失败不能误标。 */
    suspend fun complete(): Boolean = write(ImportPromptStatus.Completed, 0L)

    /** 「我的 → 首页设置 → 恢复导入提示」显式重置。 */
    suspend fun reset(): Boolean = write(ImportPromptStatus.Unseen, 0L)

    private suspend fun write(status: ImportPromptStatus, dismissedAtMillis: Long): Boolean = try {
        store.edit { prefs ->
            prefs[Keys.STATUS] = status.name
            prefs[Keys.SCHEMA] = SCHEMA_VERSION
            if (dismissedAtMillis > 0L) prefs[Keys.DISMISSED_AT] = dismissedAtMillis
            else prefs.remove(Keys.DISMISSED_AT)
        }
        true
    } catch (bad: Exception) {
        // 只在主线程外调用，写不进去也不会卡界面；但必须让调用方知道没写上。
        android.util.Log.w(TAG, "提示状态没写进去：${bad::class.java.simpleName}: ${bad.message}")
        false
    }
}
