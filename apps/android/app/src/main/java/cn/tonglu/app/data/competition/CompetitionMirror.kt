package cn.tonglu.app.data.competition

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

/**
 * 订阅列表的**本机镜像** —— 服务端仍是唯一真相，这份只是「上次同步成功的那一份」。
 *
 * ## 为什么要有它
 *
 * [CompetitionSubscriptionStore] 刻意**不做本地缓存**（订阅要跨设备、要有提醒，
 * 服务端必须说了算）。但有两类读法打不了网络：
 *   · **桌面小组件**：跑在桌面进程的广播里，`provideGlance` 每次刷新都去拉服务端
 *     既慢又不靠谱（同 [cn.tonglu.app.notify.DeadlineReminder] 的判断）；
 *   · 断网时想看一眼「我订阅的竞赛还有几天」。
 *
 * 所以每次 [CompetitionSubscriptionStore.refresh] **成功**后覆盖写一份到盘上。
 *
 * ## 三条规矩
 *
 * 1. 🔴 **只覆盖不删除**：refresh 失败时保留旧的那一份 —— 断网不能让小部件变空。
 * 2. 🔴 **按身份分桶**：文件里带 owner，读的时候对不上就返回空。换账号后
 *    小部件绝不能显示上一个人的竞赛（照 `ScheduleStore` / `ExamStore` 的分桶做法）。
 * 3. 退出登录 [clear]：连文件一起删（`CompetitionSubscriptionStore.clear()` 里调）。
 *
 * ## 与 [DeadlineReminder] 快照的区别（不要把两者合并）
 *
 * 那边的快照存的是「已排期的闹钟」——只包含设了提醒策略、且期限已核实的订阅，
 * 且日期是反推出来的（`DATE` 精度存的是**次日** 00:00）。拿它当展示数据源，
 * 小部件的天数会和 App 里 `CompetitionCountdown.of` 算出来的**不是同一个值**。
 * 这里存的是**净化后的订阅本体**，小部件直接调同一个 `CompetitionCountdown.of`，
 * 保证「桌面上的天数」和「竞赛页里的天数」永远一致。
 */
object CompetitionMirror {

    private const val TAG = "CompetitionMirror"
    private const val FILE_NAME = "subscriptions.json"

    private data class Payload(val owner: String? = null, val items: List<Subscription>? = null)

    private fun dir(context: Context) = File(context.filesDir, "competition").also { it.mkdirs() }

    private fun file(context: Context) = File(dir(context), FILE_NAME)

    /** 覆盖写。失败只记日志 —— 小部件看到旧数据总好过整个功能不可用。 */
    fun write(context: Context, owner: String, subs: List<Subscription>) {
        runCatching {
            val tmp = File(dir(context), "$FILE_NAME.tmp")
            tmp.writeText(Gson().toJson(Payload(owner = owner, items = subs)))
            val dest = file(context)
            if (tmp.renameTo(dest)) Unit else dest.writeText(tmp.readText())
            tmp.delete()
        }.onFailure { Log.w(TAG, "写订阅镜像失败：${it.message}") }
    }

    /**
     * 读回来。任何异常 / 文件不存在 / **owner 对不上**都返回空列表。
     *
     * 返回空而不是抛：调用方是小部件，抛出去就是桌面上一个「加载小部件时出错」。
     */
    fun read(context: Context, owner: String): List<Subscription> {
        val source = file(context)
        if (!source.exists()) return emptyList()
        val payload = runCatching {
            Gson().fromJson(source.readText(), Payload::class.java)
        }.onFailure { Log.w(TAG, "读订阅镜像失败：${it.message}") }.getOrNull() ?: return emptyList()
        if (payload.owner != owner) return emptyList()
        return payload.items.orEmpty()
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
            .onFailure { Log.i(TAG, "删订阅镜像失败：${it.message}") }
    }
}
