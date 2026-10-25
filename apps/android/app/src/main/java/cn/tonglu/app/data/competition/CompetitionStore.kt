package cn.tonglu.app.data.competition

import android.content.Context
import android.util.Log
import cn.tonglu.app.data.SessionStore
import cn.tonglu.app.data.TongluApi
import cn.tonglu.app.data.toFriendlyMessage
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 竞赛目录的本地通道（`docs/plan/56` §2.3）。
 *
 * 照 `ExamCalendarFeed` 的做法：**公开读 + 本地缓存 + 三级回退**
 * （服务端 → 上次成功的本地缓存 → 空）。**不自动下载**（不替用户花流量），
 * 由 App 在联网时顺带同步一次。
 *
 * 缓存文件里存的是**净化后的** [Competition] 列表，不是原始 JSON ——
 * 这样即使服务端后来换了字段名，本地这份也是上次读得懂的形态，
 * 不会出现「断网时界面空白」。
 */
object CompetitionCatalog {

    private const val TAG = "CompetitionCatalog"

    private val _catalog = MutableStateFlow<List<Competition>?>(null)
    val catalog: StateFlow<List<Competition>?> = _catalog

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun current(context: Context): List<Competition> =
        _catalog.value ?: readLocal(context) ?: emptyList()

    /** 同步一次。失败**不抛**，只是这轮没拿到 —— 上层继续用本地那份。 */
    suspend fun sync(context: Context, api: TongluApi): Boolean = withContext(Dispatchers.IO) {
        _error.value = null
        try {
            val remote = api.competitions()
            require(remote.items != null) { "竞赛目录缺少 items 字段" }
            val items = remote.toCatalog()
            val revision = remote.revision.orEmpty()
            // 成功返回的空目录也是有效快照，允许后台撤下全部赛事。
            val changed = revision != readRevision(context) || _catalog.value != items
            writeLocal(context, revision, items)
            _catalog.value = items
            Log.i(TAG, "竞赛目录同步成功：${items.size} 场，revision=$revision")
            changed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (cause: Exception) {
            _error.value = cause.toFriendlyMessage()
            Log.w(TAG, "拉竞赛目录失败，保留上次数据", cause)
            false
        }
    }

    /** 冷启动时先装上本地缓存的那份。 */
    fun prime(context: Context) {
        if (_catalog.value == null) _catalog.value = readLocal(context)
    }

    /** 退出登录 / 清缓存时用。 */
    fun clear(context: Context) {
        _catalog.value = null
        _error.value = null
        runCatching { file(context).delete() }.onFailure { Log.i(TAG, "清竞赛缓存失败：${it.message}") }
    }

    // ------------------------------------------------------------------ 本地

    private fun dir(context: Context) = File(context.filesDir, "competition").also { it.mkdirs() }

    private fun file(context: Context) = File(dir(context), "catalog.json")

    private data class Cached(val revision: String? = null, val items: List<Competition>? = null)

    private fun readCached(context: Context): Cached? {
        val source = file(context)
        if (!source.exists()) return null
        return try {
            Gson().fromJson(source.readText(), Cached::class.java)
        } catch (cause: Exception) {
            Log.w(TAG, "读取竞赛缓存失败", cause)
            null
        }
    }

    private fun readLocal(context: Context): List<Competition>? = readCached(context)?.items

    private fun readRevision(context: Context): String? = readCached(context)?.revision

    private fun writeLocal(context: Context, revision: String, items: List<Competition>) {
        runCatching {
            dir(context)
            val tmp = File(dir(context), "catalog.json.tmp")
            tmp.writeText(Gson().toJson(Cached(revision = revision, items = items)))
            val dest = file(context)
            if (tmp.renameTo(dest)) Unit else dest.writeText(tmp.readText())
            tmp.delete()
        }.onFailure { Log.i(TAG, "写竞赛缓存失败：${it.message}") }
    }
}

/**
 * 我的竞赛订阅（`docs/plan/56` §2.3）。
 *
 * **服务端是唯一真相**：订阅要跨设备、要有提醒，就不能只存本机 ——
 * 所以这里**不**做本地缓存，每次读都打服务端（列表很小，一条请求）。
 * 只把「上一次拿到的那份」放内存，供界面在请求返回前先渲染。
 *
 * 订阅与「我的日程」里的考试是两类：考试是用户自己敲的（本机），
 * 竞赛订阅是跟着账号走的（云端）。
 */
class CompetitionSubscriptionStore private constructor(private val context: Context) {

    private val _subs = MutableStateFlow<List<Subscription>?>(null)
    val subscriptions: StateFlow<List<Subscription>?> = _subs

    /** 最近一次操作的结果文案（成功/失败），界面读它来 toast。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun consumeMessage() { _message.value = null }

    suspend fun refresh(api: TongluApi, bearer: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { api.mySubscriptions(bearer) }.fold(
            onSuccess = {
                _subs.value = it.items.orEmpty().mapNotNull { item -> item.toSubscription() }
                // 兑现「截止前会提醒你」：订阅列表一变就重排截止提醒（订阅 / 取消都会走到这里）。
                cn.tonglu.app.notify.DeadlineReminder.scheduleAllAsync(context, _subs.value)
                // 落一份本机镜像：桌面小组件读订阅只能走盘上（打不了网络，也不该打）。
                // 按身份分桶存 —— 换账号后小部件不能显示上一个人的竞赛。
                CompetitionMirror.write(context, SessionStore.currentOwner(context), _subs.value.orEmpty())
                true
            },
            onFailure = {
                Log.i("CompetitionSubs", "拉订阅失败：${it.message}")
                false
            },
        )
    }

    /**
     * 订阅 / 取消。
     *
     * 服务端拒绝**必须把原话说出来** —— 最典型的是「未核实的期限不能订阅」，
     * 吞掉就变成「点了没反应」。
     */
    suspend fun setEnabled(api: TongluApi, bearer: String, eventId: String, deadlineId: String, enabled: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { api.setSubscription(bearer, SubscriptionRequest(eventId, deadlineId, enabled)) }.fold(
                onSuccess = {
                    _message.value = if (enabled) "已订阅，截止前会提醒你" else "已取消订阅"
                    refresh(api, bearer)
                    true
                },
                onFailure = { error ->
                    _message.value = error.toFriendlyMessage()
                    false
                },
            )
        }

    /** 换账号时不要看到上一个人的订阅。 */
    fun clear() {
        _subs.value = null
        // 空列表排一次 = 取消全部截止闹钟 + 清快照，不给上一个人留提醒。
        cn.tonglu.app.notify.DeadlineReminder.scheduleAllAsync(context, emptyList())
        // 本机镜像同理：不删的话桌面小组件还会显示上一个人的竞赛。
        CompetitionMirror.clear(context)
    }

    companion object {
        @Volatile private var instance: CompetitionSubscriptionStore? = null

        fun get(context: Context): CompetitionSubscriptionStore =
            instance ?: synchronized(this) {
                instance ?: CompetitionSubscriptionStore(context.applicationContext).also { instance = it }
            }
    }
}
