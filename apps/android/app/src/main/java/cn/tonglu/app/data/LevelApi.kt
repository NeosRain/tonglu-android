package cn.tonglu.app.data

import com.google.gson.annotations.SerializedName
import kotlin.jvm.Transient

/**
 * 等级与通知的接口模型（docs/plan/66 §七）。
 *
 * 🔴 `trustLevel` / `trustName` / `trustConditions` **只有本人视角**才有值：
 * 服务端 `me/level` 才返回这些，`users/{id}/level-card` 不返回（红线 8，TL 不公开）。
 * 所以这里的字段在「他人等级卡」里会是 null —— 别在共用组件里假设它一定有。
 */

/** TL 的逐条件达成态。UI 要靠它渲染「还差哪一条」，不能只给一个结论。 */
data class TrustConditionDto(
    val key: String,
    val label: String,
    val current: Int,
    val required: Int,
    val met: Boolean,
)

data class MyLevelDto(
    val level: Int = 1,
    val levelName: String = "新生",
    val nextLevel: Int? = null,
    val nextLevelName: String? = null,
    val expLearn: Int = 0,
    val expSocial: Int = 0,
    val expTotal: Int = 0,
    val expToNext: Int = 0,
    val progress: Float = 0f,
    @SerializedName("levelUpAt") val levelUpAt: String? = null,
    val streakDays: Int = 0,
    // —— 信任（仅本人）——
    val trustLevel: Int? = null,
    val trustName: String? = null,
    val trustConditions: List<TrustConditionDto>? = null,
    val trustUpgradeReady: Boolean = false,
    val trustGraceUntil: String? = null,
    val muted: Boolean = false,
    val growthOptedOut: Boolean = false,
) {
    val isTrustVisible: Boolean get() = trustLevel != null
}

/** 他人等级卡：**不含 TL、不含 EXP 明细**（plan/66 §七 / §十一·红线 8）。 */
data class LevelCardDto(
    val userId: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val level: Int = 1,
    val levelName: String = "新生",
    val progress: Float = 0f,
)

data class NoticeDto(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    @SerializedName("readAt") val readAt: String? = null,
    @SerializedName("createdAt") val createdAt: String? = null,
)

data class NoticesDto(
    val items: List<NoticeDto> = emptyList(),
    val unread: Int = 0,
)

data class TrustLogDto(
    val id: String,
    val from: Int,
    val to: Int,
    val cause: String,
    /** 人类可读原因，服务端保证不写代号（红线 4：降级必须给原因）。 */
    val reason: String,
    @SerializedName("createdAt") val createdAt: String? = null,
)

/** 学习计数上报体。**只传数字，不传读了什么**（红线 10）。 */
data class LearnReportRequest(
    val wordsLearned: Int = 0,
    val exercisesDone: Int = 0,
    val readSeconds: Int = 0,
    val packsDone: Int = 0,
    val examsDone: Int = 0,
    /** 幂等键：同 clientId 重发不重记。 */
    val clientId: String,
    /**
     * 本机逐桶快照（metric → [今天, 昨天]）。**纯本地用，不上传**。
     *
     * @Transient 让 Gson 跳过它 —— 服务端不知道也不需要知道客户端怎么记账，
     * 多发一个字段只是把本机实现细节泄进协议，以后想改记账方式还得兼容它。
     */
    @Transient val snapshot: Map<String, List<Int>> = emptyMap(),
)