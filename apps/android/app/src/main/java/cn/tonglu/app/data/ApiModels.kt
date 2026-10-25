package cn.tonglu.app.data

import com.google.gson.annotations.SerializedName

data class UserDto(
    val id: String,
    val username: String,
    val displayName: String,
    val role: String = "USER",
    val avatarUrl: String? = null,
)

/** 修改昵称（账号设置页）。长度口径与后端 SetDisplayNameDto 一致（2~24）。 */
data class SetDisplayNameRequest(val displayName: String)

data class CaptchaDto(val id: String, val image: String)
data class RegisterCodeRequest(val email: String, val captchaId: String, val captchaAnswer: String)

/**
 * 邮箱注册。
 *
 * `username` 可为空：**Gson 默认不序列化 null 字段**，所以为空时这个键根本不会出现在
 * 请求体里，后端 `@IsOptional()` 正好接住 —— 于是「老客户端不发这个字段」和
 * 「新客户端没填」走的是同一条兼容路径，不需要两套代码。
 */
data class RegisterEmailRequest(
    val email: String,
    val code: String,
    val password: String,
    val username: String? = null,
    val displayName: String? = null,
    val acceptedTerms: Boolean,
)

data class AuthResponse(
    val accessToken: String,
    val user: UserDto,
    // 兼容尚未升级的后端和旧登录态，缺失时在访问令牌失效后要求重新登录。
    val refreshToken: String? = null,
    /**
     * 该账号的用户名仍是建号时自动派生的（`u_xxxxxxxx`），客户端应弹一次改名引导。
     * **不阻塞登录** —— 默认 false，所以老后端不带这个字段时也照常进主界面。
     */
    val needsUsername: Boolean = false,
    /** 建议用户名（只保证格式合法，占用与否由客户端那步再查）。 */
    val suggestedUsername: String? = null,
    /** 改名前的那串派生 ID，用于提示「原 ID 仍然可以登录」。 */
    val legacyUsername: String? = null,
)

/** `GET auth/username/available` 的响应。`code` 取值见 UsernamePolicy。 */
data class UsernameAvailableDto(
    val username: String,
    val available: Boolean,
    val code: String,
    val message: String,
)

data class SetUsernameRequest(val username: String)

data class RefreshRequest(val refreshToken: String)
data class LogoutRequest(val refreshToken: String)

/**
 * 登录：登录名可以是**用户名或邮箱**，所以两个字段都发同一个值。
 *
 * 后端 `LoginDto` 读的是 `identifier ?? username`（`identifier` 是推荐字段，`username`
 * 保留是为了兼容已经装出去的旧客户端）。只发 `identifier` 在老后端上会被判 400，
 * 只发 `username` 又没用上规范字段 —— 两个都发，两种后端都能吃。
 */
data class LoginRequest(val identifier: String, val username: String, val password: String)

// 纯用户名注册的 DTO 已删除：注册只走邮箱验证码（见 TongluApi 里的同名注释）。

data class CourseDto(
    val id: String,
    val name: String,
    val teacher: String? = null,
    val location: String? = null,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val startWeek: Int,
    val endWeek: Int,
    /** 具体周次（单双周/跳周时使用），空表示 startWeek..endWeek 连续 */
    val weeks: List<Int> = emptyList(),
    val color: String? = null,
    /** 来源：manual / jwapp / import */
    val source: String? = null,
) {
    fun activeInWeek(week: Int): Boolean =
        if (weeks.isNotEmpty()) weeks.contains(week) else week in startWeek..endWeek

    fun weekLabel(): String =
        if (weeks.isNotEmpty()) weeks.sorted().let { list ->
            when {
                list == (list.first()..list.last()).toList() && list.all { it % 2 == 1 } -> "${list.first()}-${list.last()}周(单)"
                list == (list.first()..list.last()).toList() && list.all { it % 2 == 0 } -> "${list.first()}-${list.last()}周(双)"
                list == (list.first()..list.last()).toList() -> "${list.first()}-${list.last()}周"
                else -> list.joinToString(",") + "周"
            }
        } else "${startWeek}-${endWeek}周"

    fun timeText(): String = PeriodTable.timeText(startPeriod, endPeriod)

    fun toParsed(): ParsedCourse = ParsedCourse(
        name = name,
        teacher = teacher,
        location = location,
        weekday = weekday,
        startPeriod = startPeriod,
        endPeriod = endPeriod,
        startWeek = startWeek,
        endWeek = endWeek,
        weeks = weeks,
        color = color ?: "blue",
        source = source ?: "manual",
    )
}

data class CreateCourseRequest(
    val name: String,
    val location: String?,
    val teacher: String? = null,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val startWeek: Int,
    val endWeek: Int,
    val weeks: List<Int> = emptyList(),
    val color: String? = null,
    val source: String? = null,
)

/** 教务导入：整份课表一次提交 */
data class ImportCoursesRequest(val courses: List<CreateCourseRequest>, val replace: Boolean = true)

data class ImportCoursesResponse(val imported: Int = 0, val replaced: Int = 0, val source: String? = null)

/** 清空本人课表的返回：{ deleted: 27 } */
data class ClearCoursesResponse(val deleted: Int = 0)

/**
 * 帖子作者投影。`realName`/`cohortYear` 只在**社团帖成员上下文**由 C1 契约的
 * 身份投影下发（`clubs/{id}/posts*` 路由）；公共接口永远不返回这两个字段 ——
 * 实名仅授权社团范围展示（EXECUTION-COMMUNITY-MVP §4）。Gson：缺字段即 null。
 */
data class PostAuthorDto(
    val id: String,
    val displayName: String,
    val avatarUrl: String? = null,
    /** 社团实名档案姓名（仅社团帖成员上下文；公共帖恒 null）。 */
    val realName: String? = null,
    /** 作者在**本社团**的入学年份（非全局属性；公共帖恒 null）。 */
    val cohortYear: Int? = null,
)

/**
 * 帖子贴附的学习资料包卡片元数据（47 §3 P4）。
 *
 * 服务端按 `linkedPackageId@linkedPackageVersion` 现查现给，**找不到或已下架返回 null**
 * （帖子本身照常显示）。`available=false` 表示这份资料现在不能下载了 ——
 * 界面要如实写「内容已下架」，不能假装还能点。
 */
data class LinkedPackDto(
    val packageId: String? = null,
    val version: Int? = null,
    val title: String? = null,
    val summary: String? = null,
    val language: String? = null,
    val level: String? = null,
    val author: String? = null,
    val license: String? = null,
    val attribution: String? = null,
    val capabilities: List<String>? = null,
    val bytes: Long? = null,
    /** 当前是否仍可下载。false = 已下架，卡片灰掉并标注。 */
    val available: Boolean? = null,
)

data class PostDto(
    val id: String,
    val category: String,
    val title: String? = null,
    val body: String,
    val status: String? = null,
    val author: PostAuthorDto? = null,
    /**
     * 列表接口本来就返回了 `createdAt`（Prisma 默认 scalar 全选），
     * 只是客户端 DTO 一直没接 —— 于是广场卡片上一直没有「几分钟前」。
     * 补上不涉及任何后端改动。
     */
    val createdAt: String? = null,
    /**
     * 发帖响应才带的三个审核字段（列表接口不返回）。
     * 服务端机审不判死：被判 REJECT 的内容会封顶成 QUARANTINED（隔离待审），
     * 此时 visible=false、hint 是给作者看的一句话。客户端必须照它显示，
     * 否则用户会以为「发成功了却搜不到」—— 内容无声消失是最差的体验。
     */
    val moderationState: String? = null,
    val visible: Boolean? = null,
    val hint: String? = null,
    val _count: PostCountDto? = null,
    /** 贴附的资料包（没贴就是 null）。 */
    val linkedPack: LinkedPackDto? = null,
)

/**
 * 发帖请求。`title` 在服务端是可选的（1~100 字）——
 * 广场列表只有正文时很难扫，笔记发布过来时标题还能帮别人搜到。
 *
 * `packageId`/`packageVersion` **要么都给、要么都不给**（服务端会校验）：
 * 只给一半会被 400 挡下，所以客户端拼请求时也要成对。
 */
data class CreatePostRequest(
    val category: String,
    val body: String,
    val title: String? = null,
    val packageId: String? = null,
    val packageVersion: Int? = null,
)

/** `GET /posts/:id` 的 `_count` 字段（Prisma 的关联计数）。 */
data class PostCountDto(val comments: Int = 0, val likes: Int = 0)

/** `GET /posts/:id`：帖子详情。只有公开可见的内容能取到，否则服务端 404。 */
/**
 * 社团帖的所属频道投影（列表卡片与详情共用；公共帖响应没有这个字段 → null，Gson 天然兼容）。
 * `cohortYear` 非空 = 年级频道（「2024 级」这类）—— 详情来源行据此写真实频道名而不是分类名。
 */
data class ClubPostChannelDto(
    val id: String,
    val name: String,
    val cohortYear: Int? = null,
    val kind: String? = null,
)

data class PostDetailDto(
    val id: String,
    val category: String,
    val title: String? = null,
    val body: String,
    val status: String? = null,
    val author: PostAuthorDto? = null,
    val createdAt: String? = null,
    val visible: Boolean? = null,
    val _count: PostCountDto? = null,
    /** 贴附的资料包（没贴就是 null）。 */
    val linkedPack: LinkedPackDto? = null,
    /** 问答帖：被采纳回复的 id；null = 待解答。 */
    val answerCommentId: String? = null,
    /** 问答帖：采纳时间（null = 未解决）。 */
    val solvedAt: String? = null,
    /**
     * 发布响应才带：机审状态与提示（详情接口不返回）。
     * `visible=false` = 进人工复核 —— 界面必须如实告知，不能让内容看起来「发成功却搜不到」。
     */
    val moderationState: String? = null,
    val hint: String? = null,
    /** 社团帖才有：所属频道（详情来源行用真实频道名；公共帖为 null）。 */
    val channel: ClubPostChannelDto? = null,
)

/**
 * 一条回复。**两层结构**（贴吧式）：
 * - `parentId == null` → 它是一层楼，`floor` 是楼层号（1 楼、2 楼…）
 * - 顶层评论的 `replies` 是它下面的楼中楼，`floor` 与父层相同
 */
data class PostCommentDto(
    val id: String,
    val postId: String? = null,
    val parentId: String? = null,
    val floor: Int = 0,
    val body: String = "",
    val author: PostAuthorDto? = null,
    val createdAt: String? = null,
    val moderationState: String? = null,
    val visible: Boolean? = null,
    val hint: String? = null,
    val replies: List<PostCommentDto>? = null,
    val _count: CommentCountDto? = null,
)
data class CommentCountDto(val likes: Int = 0)
data class LikeResponse(val liked: Boolean, val count: Int)

/** 帖子级点赞状态（GET /posts/:id/like）。 */
data class PostLikeState(val liked: Boolean, val count: Int)

/** 帖子收藏状态（GET /posts/:id/favorite）。 */
data class FavoriteState(val favorited: Boolean)

/**
 * 我的收藏列表条目（GET /posts/favorites）：帖子卡片 + 收藏时间。
 * 卡片字段与广场列表同口径；服务端已过滤掉后来下线/隔离的帖子。
 */
data class FavoritePostDto(
    val id: String,
    val category: String,
    val title: String? = null,
    val body: String = "",
    val author: PostAuthorDto? = null,
    val createdAt: String? = null,
    val _count: PostCountDto? = null,
    val linkedPack: LinkedPackDto? = null,
    val favoritedAt: String? = null,
)

/** 编辑自己的帖子（PATCH /posts/:id；都可选，传了才改） */
data class UpdatePostRequest(val title: String? = null, val body: String? = null)

/** 编辑自己的回复（PATCH /posts/:id/comments/:commentId） */
data class UpdateCommentRequest(val body: String)

/** 采纳/撤销采纳的返回（answerCommentId 供客户端直接更新详情态） */
data class AcceptAnswerResponse(
    val id: String? = null,
    val answerCommentId: String? = null,
    val solvedAt: String? = null,
    val solved: Boolean? = null,
)

/** 发回复：不传 `parentId` 就是新开一层楼。 */
data class CreateCommentRequest(val body: String, val parentId: String? = null)

/**
 * 社团帖发布（C0 契约 §3.2 冻结：`POST /clubs/:id/posts`）。
 *
 * 🔴 `visibility` 只允许 `MEMBERS`/`COHORT` —— 社团入口**不能**发公开帖，
 * 想发公开内容走广场公共创建接口（公共 DTO 也不收这些字段，带 clubs 字段直接 400）。
 * COHORT 必须挂在年级频道（`channel.cohortYear` 非空）—— 年级来自服务端频道配置，
 * 客户端申报的年份换不来权限。
 */
data class CreateClubPostRequest(
    val channelId: String,
    val category: String,
    val title: String? = null,
    val body: String,
    val visibility: String,
)

data class CreateReportRequest(val reason: String, val details: String? = null)

/**
 * `GET /posts/mine` 的条目（**只有作者本人**能看到自己的隔离/拒绝内容）。
 * 比公开列表多出审核状态与风险信息，用来在「我的内容」里如实标出「审核中 / 未通过」。
 */
data class MyPostDto(
    val id: String,
    val category: String,
    val title: String? = null,
    val body: String,
    val status: String? = null,
    /** CLEAR / QUARANTINED / REJECTED / APPEALING */
    val moderationState: String? = null,
    val riskScore: Int? = null,
    val riskLabels: List<String>? = null,
    val createdAt: String? = null,
)

/** 提交申诉（reason 服务端要求 5~300 字）。 */
data class CreateAppealRequest(val reason: String)

/** `GET /posts/:id/appeal`：没有申诉时服务端返回 null。 */
data class PostAppealDto(
    val id: String,
    /** OPEN / ACCEPTED / REJECTED */
    val status: String,
    val reason: String? = null,
    val decision: String? = null,
    val createdAt: String? = null,
    val handledAt: String? = null,
)

/** `GET clubs` 里的成员计数（Prisma 的 `_count`）。字段名带下划线，只能靠注解映射。 */
data class ClubCountDto(val members: Int = 0)

/**
 * 社团。
 *
 * 🔴 **`ownerId` / `schoolId` / `_count` 服务端一直都在返回**（`ClubsService.list()` 是
 * `findMany` 不带 `select`，即整行 + `_count`），只是此前 DTO 没声明这几个字段，
 * **Gson 会静默丢掉没声明的字段** —— 于是界面上「成员数 / 归属」永远是空的，
 * 看起来像「后端没这数据」。补上声明即可，不需要后端改动。
 */
data class ClubDto(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    /** 创建者 id：用于「我创建的」标记（与 `mine().ownerIds` 互为印证）。 */
    val ownerId: String? = null,
    /**
     * 多校预留的学校归属（`Club.schoolId`）。
     *
     * 🔴 **null ≠「校外社团」**：只说明这个社团没绑定学校（字段是 2026 年多校改造才留的，
     * 存量社团全是 null）。界面据此只能说「归属待确认」，**不能反推成校外**。
     */
    val schoolId: String? = null,
    /** 成员数通过 `_count.members` 下发。 */
    @SerializedName("_count") val count: ClubCountDto? = null,
) {
    /** 成员数（无计数时按 0）。Gson 不读这个 computed 属性（没有 backing field），只读上面的字段。 */
    val membersCount: Int get() = count?.members ?: 0
}

/**
 * 我与社团的关系（`GET clubs/mine`）。
 *
 * 服务端算好三个 id 集合下发，客户端**不猜**「我是不是成员」：
 * [ids] 含我创建的（创建者本身也是 OWNER 成员），[ownerIds] 是其中由我创建的那些。
 */
data class ClubMineDto(
    val ids: List<String> = emptyList(),
    val ownerIds: List<String> = emptyList(),
    val pendingIds: List<String> = emptyList(),
)

/**
 * 一条待审的加入申请（`GET clubs/:id/requests`，**仅创建者**能看到）。
 *
 * `user` 复用 [ClubMessageSenderDto] 的形状（服务端 select 的字段完全一致：
 * `id/username/displayName/avatarUrl`）—— 不新造一个一模一样的类型。
 * 🔴 里面**没有**实名 / 学号 / 年级：审批看的是网名与申请留言（`docs/community/README.md`
 * 「社团成员投影」口径），身份材料归认证流程，不进审批列表。
 */
data class ClubJoinRequestDto(
    val id: String,
    val message: String? = null,
    val createdAt: String? = null,
    val user: ClubMessageSenderDto? = null,
)

/** 审批结果：APPROVED / REJECTED。 */
data class ReviewClubRequestRequest(val status: String)

data class ClubJoinRequestDecisionDto(val id: String, val status: String)

// ---------------------------------------------------------------- 社团活动（活动即帖子）

/**
 * 一条社团活动。
 *
 * 服务端 `ClubActivity` 行的形状（`clubs.service.ts::listActivities` 逐字段映射）。
 * 🔴 **没有封面图字段**：`ClubActivity` 表里不存在 cover/图片列，所以界面**不能画大图封面**
 * —— 本版本用文字版式（标题 + 时间地点 + 主办社团），等后端加字段后再上图。
 * 编一张图或热链第三方图都是造假，不做。
 *
 * 活动**没有取消状态**字段：后端的「不可见」只有两种来源 —— 帖子没上架（`status != PUBLISHED`）
 * 或机审未通过（`moderationState != CLEAR`），而这两种在列表里**根本查不到**（where 已过滤）。
 * 所以界面只能判「已结束 / 进行中 / 未开始」，不能显示「已取消」。
 */
data class ClubActivityDto(
    val id: String,
    val clubId: String,
    val clubName: String? = null,
    val clubCategory: String? = null,
    /** 活动正文与讨论都在这个帖子里（活动即帖子）。 */
    val postId: String,
    val title: String,
    val startAt: String,
    val endAt: String? = null,
    val location: String? = null,
    val createdAt: String? = null,
    /**
     * 回应总人数（服务端 `_count.rsvps` —— 数的是 `ClubActivityRsvp` 的**全部行**）。
     *
     * 🔴 **不是「报名人数」**：`GOING`（报名）与 `INTERESTED`（感兴趣）存在同一张表里，
     * 两种状态走同一个 upsert，所以这个数把两者合并了。界面文案必须写成
     * 「N 人报名或感兴趣」（判据见 `ClubActivityModelsTest`）。
     * 另外它**不是名单** —— 服务端没有名单接口。
     */
    val going: Int = 0,
)

/** 我在各活动的报名状态（`GET clubs/rsvps/mine`）：activityId → `GOING` / `INTERESTED`。 */
data class ClubRsvpMapDto(val map: Map<String, String> = emptyMap())

/** 报名 / 取消报名。`GOING`、`INTERESTED`、`CANCEL`（CANCEL = 取消报名）。 */
data class ClubRsvpRequest(val status: String)

data class ClubRsvpResponse(val status: String)

data class CreateClubRequest(val name: String, val description: String, val category: String)

// ---------------------------------------------------------------- 社团频道（Telegram 频道模式，2026-10-11 实装）

/**
 * 社团频道。kind：`ANNOUNCEMENT` = 公告（仅创建者/管理员可发）、`DISCUSSION` = 讨论（成员可发）。
 *
 * `joined` / `canPost` 由**服务端**按我的成员身份算好下发 —— 客户端不自行判定权限
 * （客户端猜权限 = 界面给了输入框、服务端 403，用户白打一段字）。
 */
data class ClubChannelDto(
    val id: String,
    val name: String,
    val kind: String,
    val createdAt: String,
    val joined: Boolean = false,
    val canPost: Boolean = false,
    /** 年级频道才有的入学年份；null = 跨年级/公告频道。C1 契约：COHORT 权限以此为准。 */
    val cohortYear: Int? = null,
    /** 归档频道只读（服务端 canPost 已含此判定，这里留给 UI 显式说明）。 */
    val archived: Boolean = false,
)

data class ClubMessageSenderDto(
    val id: String,
    val username: String? = null,
    val displayName: String? = null,
    val avatarUrl: String? = null,
)

data class ClubMessageDto(
    /**
     * 服务端自增序号，同时是补拉游标（`?before=` / `?after=`）。
     * 🔴 **按字符串处理**：BigInt 口径，客户端不得解析成 Long/Int 参与运算。
     */
    val seq: String,
    val text: String,
    /** CLEAR = 时间线可见；QUARANTINED = 被机审隔离（不会出现在时间线，字段留作兜底） */
    val screenState: String = "CLEAR",
    val createdAt: String,
    val replyToSeq: String? = null,
    /** 被引用消息的文本快照；null = 原消息已撤回或不可见（界面显示占位，不显示原文） */
    val replyToText: String? = null,
    val sender: ClubMessageSenderDto,
)

data class ClubMessagesDto(
    /** 时间正序 */
    val messages: List<ClubMessageDto> = emptyList(),
    /** 还有更早的消息 → 继续带 `before = 第一条 seq` 往前翻 */
    val hasMore: Boolean = false,
)

data class SendChannelMessageRequest(val text: String, val replyToSeq: String? = null)

// ── PC 扫码登录 ──────────────────────────────────────────────────────────

/** App 确认页展示的会话信息（是哪台电脑在请求登录 + 还剩多久） */
data class QrTicketDto(
    val status: String? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null,
    /** 发起方浏览器 UA 摘要，帮用户识别「是我在用的那台电脑吗」 */
    val userAgent: String? = null,
)

data class QrTokenRequest(val token: String)
