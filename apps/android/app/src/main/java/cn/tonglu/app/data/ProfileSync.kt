package cn.tonglu.app.data

/**
 * 云端个人档案（后端 `GET/PUT /sync/profile` 的 `payload`）。
 *
 * 后端**不解释内容、只校验体积**（默认 1MB），所以以后要加字段不用改服务端 —— 见
 * `backend/src/sync/sync.service.ts`。这里只放**用户自己产生的、非凭据类**数据。
 *
 * ⚠️ **不放学校统一身份认证 / 企业微信的 Cookie**。那是登录凭据，等于把别人冒用你身份的钥匙
 * 交出去；项目此前的红线也是「Cookie 留在 WebView 里，App 不读取、不导出、不同步」
 * （见 `ui/JwWebSupport.kt`）。换机需要重新登录学校系统时，让用户在新设备重新扫码一次。
 *
 * 字段**全部可空**：Gson 反序列化缺失字段时不会套用 Kotlin 默认值，全空能避免 NPE。
 */
data class ProfilePayload(
    val courses: List<CourseDto>? = null,
    val scores: List<ScoreRecord>? = null,
    /** 学期开始日期（第 1 周周一），课表按它算当前教学周 */
    val termStart: String? = null,
    /** 写入时间；仅用于排查「谁覆盖了谁」，客户端自己打 */
    val savedAt: String? = null,
)

/** `GET /sync/profile` 响应。`version=0, payload=null` = 云端还没有档案。 */
data class ProfileSnapshotDto(
    val version: Int = 0,
    val payload: ProfilePayload? = null,
    val updatedAt: String? = null,
    val updatedBy: String? = null,
)

/**
 * 整包上传。[baseVersion] 是乐观锁：客户端必须带「上次看到的云端版本号」，
 * 服务端只在两者一致时才写，挡「离线已久的旧设备把已删数据复活」。
 */
data class PutProfileRequest(
    val baseVersion: Int,
    val payload: ProfilePayload,
    val clientId: String = "android",
)

data class ProfilePutResponse(val version: Int = 0, val updatedAt: String? = null)
