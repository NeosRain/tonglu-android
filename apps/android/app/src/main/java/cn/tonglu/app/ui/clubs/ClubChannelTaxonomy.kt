package cn.tonglu.app.ui.clubs

/**
 * 频道一级分类（产品口径：公告 / 年级 / 讨论 / 机器人 / 资源 / 活动）。
 *
 * ## 🔴 为什么只有两个能真用
 * 服务端 `ClubChannel.kind` **目前只有两个值**：`ANNOUNCEMENT` / `DISCUSSION`
 * （见 `backend/src/clubs/clubs.dto.ts` 的 `CreateChannelDto` 与 `clubs.service.ts`）。
 * 其余四类（年级 / 机器人 / 资源 / 活动）是产品分类，**后端还没有对应 kind**。
 *
 * 所以本文件的作用是：把「哪些分类现在真有数据支撑」写成**代码里的事实**，
 * 界面据此把没支撑的分类**置灰并说明**，而不是：
 * - 按频道标题猜类型（叫「2024级」就当成年级频道）——任务红线明确禁止，
 *   而且猜错会把讨论频道当公告渲染，权限提示跟着一起错；
 * - 给空分类画一个「暂无频道」，让人以为「建过、只是空的」。
 *
 * 真实活动已经有独立接口（`GET clubs/:id/activities` + 报名 `PUT .../rsvp`，活动即帖子），
 * 所以 [ACTIVITY] **可用**：但它点开显示的是**活动列表**，不是按 kind 筛出来的频道
 * —— 真频道只有 `ANNOUNCEMENT` / `DISCUSSION` 两种 kind。
 */
internal enum class ChannelCategory(val label: String, val icon: String) {
    ALL("全部", "≡"),
    ANNOUNCEMENT("公告", "📢"),
    COHORT("年级", "🎓"),
    DISCUSSION("讨论", "#"),
    BOT("机器人", "🤖"),
    RESOURCE("资源", "📎"),
    ACTIVITY("活动", "🎉"),
}

/**
 * 服务端 `ClubChannel.kind` **已实现**的取值。
 *
 * 新增一个 kind 时必须**同时**改三处，否则界面永远置灰：
 * ① 后端 `CreateChannelDto` 的 `@IsIn([...])` 与 `listChannels` 的 `canPost` 判定；
 * ② 这里；
 * ③ `channelCategoryOfKind` 的映射。
 */
internal val SERVER_CHANNEL_KINDS: Set<String> = setOf("ANNOUNCEMENT", "DISCUSSION")

/**
 * 真实频道的 `kind` → 一级分类。**只认 kind，不读名字**（红线）。
 *
 * 返回 null = 服务端出现了一个我们不认识的 kind：这种频道只在「全部」里显示，
 * 不硬塞进任何分类（塞错比不显示更坏——用户会按错的分类去理解权限）。
 */
internal fun channelCategoryOfKind(kind: String): ChannelCategory? = when (kind) {
    "ANNOUNCEMENT" -> ChannelCategory.ANNOUNCEMENT
    "DISCUSSION" -> ChannelCategory.DISCUSSION
    else -> null
}

/** 某个分类在**真实数据**下是否可用。 */
internal fun ChannelCategory.hasRealSupport(): Boolean = when (this) {
    ChannelCategory.ALL, ChannelCategory.ANNOUNCEMENT, ChannelCategory.DISCUSSION -> true
    // 「活动」有真实支撑，但它**不是频道**：服务端把活动做成「活动即帖子」+ 独立接口
    // （`GET clubs/:id/activities`）。点它显示的是活动列表，不是按 kind 筛频道。
    ChannelCategory.ACTIVITY -> true
    // 这三类后端还没有对应 kind —— 置灰，不给「点进去是空列表」的错觉。
    ChannelCategory.COHORT, ChannelCategory.BOT, ChannelCategory.RESOURCE -> false
}

/** 这个分类下的内容是「频道」还是「活动」。（决定点开之后拉哪个接口。） */
internal fun ChannelCategory.isActivityLane(): Boolean = this == ChannelCategory.ACTIVITY

/** 按分类筛真实频道；[ChannelCategory.ALL] 不过滤（含未知 kind）。 */
internal fun filterRealChannels(
    channels: List<cn.tonglu.app.data.ClubChannelDto>,
    category: ChannelCategory,
): List<cn.tonglu.app.data.ClubChannelDto> =
    if (category == ChannelCategory.ALL) channels else channels.filter { channelCategoryOfKind(it.kind) == category }

/**
 * 未接入分类的说明。**必须说清「没有」是「服务端还没有这个能力」，不是「还没人建」**，
 * 这两句话对用户的意义完全不同（前者等更新，后者他自己就能建）。
 */
internal const val CATEGORY_UNSUPPORTED_HINT =
    "年级 / 机器人 / 资源 频道需要服务端提供对应的频道类型，当前版本还没有，暂时不能点。" +
        "「活动」不是频道：它显示的是这个社团的真实活动（活动即帖子）。"

/**
 * 成员区块文案。
 *
 * 🔴 服务端**没有**社团成员名单接口（`GET clubs/:id/members` 不存在），
 * 所以这里只能显示真实的**成员数**（`_count.members`）。
 * 姓名 / 年级 / 角色标签要等成员接口（`ClubMemberProfile`）落地 ——
 * 在那之前**一个假名字都不许出现**：任务红线是「不伪造实名、不显示假的已入群」。
 */
internal const val MEMBERS_ROSTER_PENDING =
    "成员名单（姓名 / 年级 / 角色）需要服务端成员接口，当前版本还没有，所以这里只显示成员数。"

/** 年级标签缺失时的说明（不是「校外」，是「没确认过」）。 */
internal const val COHORT_PENDING = "年级待确认"

/** 认证材料说明：普通账号看不到他人的认证材料。 */
internal const val VERIFICATION_PRIVACY =
    "认证材料（学信网 / 人工材料）只有本人和审核人员可见，其他成员看不到。"
