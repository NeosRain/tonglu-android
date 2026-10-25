package cn.tonglu.app.data.english

import java.time.Instant
import java.time.ZoneId

/**
 * 「今日语境词队列」里**不依赖数据库的那部分规则**（2026-10-13，C03）。
 *
 * 为什么单独抽出来：幂等键的算法和「哪一天算一天」的口径，是这套东西里**唯一会悄悄错**
 * 的地方 —— 而且错了界面看不出来（照样有行、照样显示「已加入」）。
 *
 * - 幂等键算错 → 同一个词能加两次（用户在今日里看到重复条目），或者两个不同的词被
 *   当成同一个（加了一个，另一个显示「已加入」）。
 * - 日期口径算错 → 用户在「跨天」时看到昨天的队列，或者同一天被切成两段。
 *
 * 这两件事都必须在**纯函数**上固定住，而不是埋在 `EnglishRepository` 里 ——
 * 那个类的构造需要 Android `Context`，JVM 单测根本跑不起来，等于没有判据。
 */
object TodayQueueRules {

    /**
     * 幂等键：**优先词条 id**，查不到词条时退化成小写词形。
     *
     * 为什么必须退化：正文点词不一定在词库里（`QueueWordIntent.existingWordId` 本来就是可空的），
     * 而那正是「这个句子里的这个词」最值得加入今日的场景。
     * 主键列不能为 null，所以真正当主键用的一定是这个补齐过的 key。
     *
     * 🔴 用 `lowercase()` 而不是 `lowercase(Locale.ROOT)` 的坑这里避开了：
     * 土耳其语环境下 `"I".lowercase()` 会变成 `"ı"`，同一个词在两个地区会算出**两个不同的键**，
     * 于是「加过一次」的判定失效、今日里出现两条。所以显式钉死 Locale.ROOT。
     */
    fun entryKey(wordId: String?, surface: String): String {
        val id = wordId?.trim().orEmpty()
        if (id.isNotEmpty()) return id
        return surface.trim().lowercase(java.util.Locale.ROOT)
    }

    /**
     * 本地日期串（yyyy-MM-dd）。队列按它分天：跨天即新队列，**不需要定时清理任务**。
     *
     * 传 `now` 与 `zone` 而不是内部取当前时间/系统时区，是为了让「跨天」这条验收
     * 能被单测固定住 —— 否则测「跨天」就只能真的等到明天。
     * 时区也显式传入：默认取系统时区，但测试可以钉住，避免在 CI 上因机器时区不同而飘。
     */
    fun day(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
}
