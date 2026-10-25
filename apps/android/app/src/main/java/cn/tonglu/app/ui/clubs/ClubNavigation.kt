package cn.tonglu.app.ui.clubs

import cn.tonglu.app.data.ClubDto

/** 列表入口与左侧头像栏共用同一份示例，切换时不丢掉其他社团。 */
internal val previewClubTargets = listOf(
    ClubDetailTarget(PREVIEW_CLUB_ID_PREFIX + "interest", "示例 · 游戏社", "兴趣社团", "游戏开发、游戏体验、电竞与活动。", false),
    ClubDetailTarget(PREVIEW_CLUB_ID_PREFIX + "department", "示例 · 院级社团", "院级社团", "院级与校级社团独立展示。", false),
)

/** 不受列表搜索过滤影响；已打开的社团即使暂时不在接口列表里也保留。 */
internal fun clubRailTargets(clubs: List<ClubDto>, selected: ClubDetailTarget,
    includeExamples: Boolean = !selected.real): List<ClubDetailTarget> {
    val available = clubs.map { it.toTarget() } + if (includeExamples) previewClubTargets else emptyList()
    return (available + selected).distinctBy { it.id }.map { if (it.id == selected.id) selected else it }
}

/**
 * 左侧头像轨道的候选社团（任务口径：「**全部已加入社团**头像轨道」）。
 *
 * 未加入的社团**不进轨道** —— 想加入的去列表页申请，轨道是「我已在的社团」的快速切换器。
 *
 * 🔴 但 [ClubMembershipSnapshot.loaded] 为 false 时**不过滤**：拿不到「我加入了哪些」就过滤，
 * 会把轨道清空成「只剩当前这一个」，看起来像「我的社团全没了」。
 * **不确定时退化成放宽（展示全部），不退化成收紧（什么都看不到）** —— 这条方向性
 * 与「状态未知」不猜成「未加入」是同一条原则。
 */
internal fun railCandidates(clubs: List<ClubDto>, membership: ClubMembershipSnapshot): List<ClubDto> =
    if (!membership.loaded) clubs else clubs.filter { it.id in membership.myIds }

/**
 * `GET clubs` 的一行 → 界面模型。
 *
 * 真实字段（`schoolId` / `ownerId` / `_count.members`）**原样带上**：详情层顶栏与
 * 「社团资料」要显示成员数、归属，不必再查一次列表（也避免两处各算一遍口径不一致）。
 */
internal fun ClubDto.toTarget(): ClubDetailTarget = ClubDetailTarget(
    id = id, name = name, category = category, description = description, real = true,
    schoolId = schoolId, ownerId = ownerId, membersCount = membersCount,
)

internal fun ClubDetailTarget.avatarLabel(): String = name.removePrefix("示例 · ").take(1)

/**
 * 我在某个社团里的身份。**优先级由服务端口径决定**：我创建的 > 已加入 > 申请中 > 无。
 *
 * [UNKNOWN] 是独立一档，不是「没加入」：请求失败/未登录时我们**不知道**，
 * 把「不知道」画成「未加入」会让已加入的用户看到「加入」按钮（点下去服务端说
 * 「已是成员」），用户会以为 App 坏了。
 */
internal enum class ClubMembership { UNKNOWN, NONE, PENDING, JOINED, OWNER }

/**
 * 「我的社团关系」快照（来自 `GET clubs/mine`）。纯数据 + 纯函数，**便于单测钉住优先级**。
 *
 * 🔴 [loaded] = false 时一律 [ClubMembership.UNKNOWN]：空集合有「真的没有」和
 * 「没拉到」两种含义，只有服务端明确回过一次（哪怕回的是空列表）才算「真的没有」。
 */
internal data class ClubMembershipSnapshot(
    val myIds: Set<String> = emptySet(),
    val ownerIds: Set<String> = emptySet(),
    val pendingIds: Set<String> = emptySet(),
    val loaded: Boolean = false,
) {
    fun of(clubId: String): ClubMembership = when {
        !loaded -> ClubMembership.UNKNOWN
        clubId in ownerIds -> ClubMembership.OWNER
        clubId in myIds -> ClubMembership.JOINED
        clubId in pendingIds -> ClubMembership.PENDING
        else -> ClubMembership.NONE
    }
}

/** 从 [cn.tonglu.app.data.AppUiState] 取快照。放在这里是为了让三个调用点（列表/顶栏/详情）口径一致。 */
internal fun cn.tonglu.app.data.AppUiState.clubMembership(): ClubMembershipSnapshot =
    ClubMembershipSnapshot(
        myIds = myClubIds, ownerIds = ownerClubIds,
        pendingIds = pendingClubIds, loaded = clubMembershipLoaded,
    )

/**
 * 我的身份文案。**角色（负责人/成员）与账号等级是两回事** ——
 * 这里只说社团内的角色，不带任何等级数字（`docs/community/README.md`：身份角色与用户等级分开呈现）。
 */
internal fun membershipLabel(membership: ClubMembership): String = when (membership) {
    ClubMembership.OWNER -> "社团负责人"
    ClubMembership.JOINED -> "社团成员"
    ClubMembership.PENDING -> "申请待审批"
    ClubMembership.NONE -> "尚未加入"
    ClubMembership.UNKNOWN -> "状态未知"
}

/**
 * 归属文案。
 *
 * 🔴 `schoolId` 为空**只能说「归属待确认」**，不能反推成「校外社团」：
 * 这个字段是多校改造才留的（`Club.schoolId` 可空），**存量社团全是 null** ——
 * 把它们一律判成「校外」就是编数据（任务红线：不伪造归属）。
 * 也不能把 schoolId 直接显示给用户（那是一串 id，不是校名）。
 */
internal fun affiliationLabel(schoolId: String?): String =
    if (schoolId.isNullOrBlank()) "归属待确认" else "校内社团"

/** 成员数文案。[count] 来自 `_count.members`；0 是**真实**的 0（零成员社团必须能显示出来）。 */
internal fun memberCountLabel(count: Int): String = "$count 名成员"
