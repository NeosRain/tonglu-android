package cn.tonglu.app.ui.clubs

/**
 * 社团频道的**本地预览**数据（`docs/plan/62-SQUARE-CLUB-EXECUTION.md` 第一轮第 4 条）。
 *
 * ## 为什么还留着这个文件（2026-10-12 更新）
 * 2026-10-11 起频道**已经实装**：服务端有 `ClubChannel` / `ClubMessage` 模型、真实接口
 * （`GET clubs/:id/channels`、`channels/:channelId/messages`），Android 也接了真实数据。
 * 但**未登录**、**加载失败**、**点的是本地示例社团**这三种情况下，界面仍然回落到这套预览，
 * 用来把「窄头像栏 + 频道列表 + 频道内容」的版式撑住并明确打标 —— 所以它不退场，
 * 只是从「唯一数据源」降级为「兜底版式」。
 *
 * ## 三条不许越过的线
 * 1. **不伪造成员数 / 未读数**：`PreviewChannel` 里**故意没有** `memberCount`、`unread` 这类字段。
 *    单测用反射把这条钉住了 —— 以后谁加回来，测试变红。
 *    （真实社团的**成员数**走 `ClubDetailTarget.membersCount`，那是服务端 `_count.members`，
 *    与这里的示例数据是两条完全不同的路径。）
 * 2. **三处打标**：入口（页面顶部的预览横幅）、列表（每条频道的「示例」标签）、
 *    操作处（频道内容的说明卡）。少一处，用户就会把示例当真实数据。
 * 3. **不上传、不入库**：这里全是常量，没有任何网络调用，也不会写进 Room / 本机存储。
 *
 * 真实事件连接实现之前，**不许出现「实时」字样**（plan/62 第一轮第 5 条）。
 */
internal enum class ChannelKind(val label: String) {
    /** 公告：默认只允许管理员发言（真实权限留到第二轮）。 */
    ANNOUNCEMENT("公告"),
    /** 讨论：成员按权限发言。 */
    DISCUSSION("讨论"),
    /** 活动：详情与报名。 */
    ACTIVITY("活动"),
    /** 外链：明确的站外跳转。 */
    EXTERNAL("外链"),
}

internal data class PreviewChannel(
    val id: String,
    val name: String,
    val kind: ChannelKind,
    /** 列表里那一行摘要。**不是未读数**，也不是「最后一条消息时间」。 */
    val summary: String,
    /**
     * 全量预览标记：**恒为 true**。
     * 界面只有在它为 true 时才画「示例」标签 —— 所以「忘了打标」是编译期就做不到的事，
     * 而不是靠人记得。
     */
    val preview: Boolean = true,
)

/**
 * 频道内容里的一条消息（本地预览）。
 *
 * 🔴 **故意没有时间字段**：真实的收发时间不存在，编一个 `14:20` 出来就会让「版式示例」
 * 变成「看起来像真数据」。气泡右下角那个时间位照 Telegram 的结构留着，但里面写的是
 * 常量「示例」两个字 —— 版式看得见、数据不造假。
 * 同理没有 `id`：没有消息表，就没有消息 id 可言。
 */
internal data class PreviewMessage(
    /** 发送人显示名。**每条都必须自带「示例」**（单测钉住），否则脱离横幅就只剩一个像真人的名字。 */
    val sender: String,
    val text: String,
    /** 被引用的上一条内容（Telegram 气泡里的引用块）。null = 普通消息。 */
    val replyTo: String? = null,
)

/** 频道过滤 chips（对齐 Telegram 列表页的过滤条）。**不带计数** —— 计数依赖服务端聚合。 */
internal enum class ChannelFilter(val label: String) {
    ALL("全部"),
    ANNOUNCEMENT("公告"),
    DISCUSSION("讨论"),
    ACTIVITY("活动"),
    EXTERNAL("外链"),
}

/** 按过滤项筛频道；[ChannelFilter.ALL] 不过滤。 */
internal fun filterChannels(channels: List<PreviewChannel>, filter: ChannelFilter): List<PreviewChannel> =
    if (filter == ChannelFilter.ALL) channels else channels.filter { it.kind.name == filter.name }

internal object ClubChannelPreview {

    /** 列表与卡片上的示例标签。三处打标都用它，避免各写各的。 */
    const val TAG = "示例"

    /** 入口处的横幅：先说清「下面是本地预览」，再展示任何东西。 */
    const val BANNER_TITLE = "社团频道：本地预览"

    const val BANNER_BODY =
        "登录并加入社团后才能看到真实频道与消息；未登录时这一套是版式示例，" +
            "不代表真实社团、真实成员或真实聊天。示例不上传、不入库。"

    /** 「我加入的社团」状态取自 `GET clubs/mine`；拉不到时显示「状态未知」，不猜成「未加入」。 */
    const val MY_CLUBS_MISSING =
        "「我加入 / 申请中 / 我创建的」由服务端 `clubs/mine` 下发；未登录或请求失败时显示「状态未知」，不会猜成「未加入」。"

    /** 归属取自 `Club.schoolId`（2026-10-12 起 `GET clubs` 已有该字段）；未绑定只能说「待确认」。 */
    const val AFFILIATION_MISSING =
        "归属来自 `Club.schoolId`；未绑定学校时写「归属待确认」，**不反推成校外社团**" +
            "（存量社团都是未绑定，判成校外就是编数据）。"

    /** 频道消息：登录并加入后看真实数据；未登录/失败时回落到打标的版式示例。 */
    const val MESSAGES_MISSING =
        "登录并加入社团后才能查看真实频道消息；下面是版式示例，不能发送、也不会保存。"

    /**
     * 频道内容顶部的副标题（对齐 Telegram 顶栏的「频道名 + 副标题」）。
     * 把诚实打标**做进版式本身**：截一张频道截图，副标题就在上面。
     */
    const val CHANNEL_SUBTITLE = "版式示例 · 未接入实时"

    /** 气泡右下角那个时间位里写的字。留位置是为了版式完整，写「示例」是为了不造假时间。 */
    const val TIME_SLOT = "示例"

    /** 气泡里引用的那条「上一条」。 */
    const val REPLY_QUOTE = "（示例）公告与活动会发在这里"

    /**
     * 某个社团下的频道。
     *
     * 对**所有** clubId 都返回同一套版式（社团之间没有真实差异可言，
     * 编造差异只会让走查时误以为数据是真的）。
     */
    fun channelsFor(@Suppress("UNUSED_PARAMETER") clubId: String): List<PreviewChannel> = listOf(
        PreviewChannel(
            id = "announce",
            name = "公告",
            kind = ChannelKind.ANNOUNCEMENT,
            summary = "示例：活动安排、招新与场地变更（默认只由管理员发布）",
        ),
        PreviewChannel(
            id = "general",
            name = "闲聊",
            kind = ChannelKind.DISCUSSION,
            summary = "示例：日常交流与组队",
        ),
        PreviewChannel(
            id = "dev",
            name = "开发",
            kind = ChannelKind.DISCUSSION,
            summary = "示例：技术讨论与作品互评",
        ),
        PreviewChannel(
            id = "play",
            name = "体验",
            kind = ChannelKind.DISCUSSION,
            summary = "示例：试玩与反馈",
        ),
        PreviewChannel(
            id = "esports",
            name = "电竞",
            kind = ChannelKind.DISCUSSION,
            summary = "示例：赛事与训练安排",
        ),
        PreviewChannel(
            id = "activity",
            name = "活动",
            kind = ChannelKind.ACTIVITY,
            summary = "示例：活动详情与报名（报名通道未接入）",
        ),
        PreviewChannel(
            id = "external",
            name = "QQ 群 / 公众号",
            kind = ChannelKind.EXTERNAL,
            summary = "示例：站外群与公众号（会明确提示「外部跳转」）",
        ),
    )

    /**
     * 频道内容预览：同样是常量，标着「示例」。**没有**发送框、**没有**时间。
     *
     * 「活动」频道**故意返回空列表** —— plan/62 的验收里有「空频道」这一条，
     * 如果所有频道都有内容，空态就永远走查不到（那种「代码里有、实际到不了」的分支
     * 正是我们要避免的）。单测会断言「至少有一个频道是空的」。
     *
     * 非空频道都给同一条三连消息：一条管理员公告、一条**带引用块**的回复、一条成员发言 ——
     * 「发送人着色」「引用块」「时间位」三个版式元素一次全能看见。
     */
    fun messagesFor(channelId: String): List<PreviewMessage> = when {
        channelId == "activity" -> emptyList()
        else -> listOf(
            PreviewMessage(
                sender = "示例 · 管理员",
                text = "（示例）公告与活动会发在这里。真实消息接入后按时间顺序显示。",
            ),
            PreviewMessage(
                sender = "示例 · 成员 A",
                text = "（示例）收到，那我把上周的作品发到「开发」频道。",
                replyTo = REPLY_QUOTE,
            ),
            PreviewMessage(
                sender = "示例 · 成员 B",
                text = "（示例）公告频道只允许管理员发言；讨论频道成员按权限发言。",
            ),
        )
    }

    /** 气泡里发送人名字的颜色（按出现顺序取），对齐 Telegram「发送人按成员着色」。 */
    fun senderColorArgb(index: Int): Long = when (index % 4) {
        0 -> 0xFFD9480F
        1 -> 0xFF2563EB
        2 -> 0xFF099268
        else -> 0xFF6741D9
    }

    /** 外链示例。真实外链第二轮才有（需要权限与审核）。 */
    fun externalLinks(): List<Pair<String, String>> = listOf(
        "QQ 群" to "示例：群号与二维码",
        "公众号" to "示例：公众号名称",
    )

    /**
     * 归属标签。真实数据一律「待确认」——
     * 未经学校授权不使用「学校官方认证」（plan/61 §2）。
     */
    fun affiliationLabel(): String = "归属待确认"

    /** 核实状态。三个维度（学生身份 / 社团真实性 / 管理资格）分别核实，现在都还没核实。 */
    fun verificationLabel(): String = "待确认"
}
