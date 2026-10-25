package cn.tonglu.app.data

data class CampusIdentityDto(
    val schoolName: String,
    val campusEmailDomains: List<String>,
    val emailAvailable: Boolean,
    val manualAvailable: Boolean,
    val email: String?,
    val emailAssociated: Boolean,
    val studentVerified: Boolean,
    val validUntil: String?,
    val applications: List<CampusApplicationDto>,
) {
    fun summary(): String = when {
        studentVerified -> "$schoolName · 在读身份已核验"
        emailAssociated -> "$schoolName · 校园邮箱已验证"
        applications.any { it.status == "PENDING" } -> "校园身份审核中"
        else -> "校园身份尚未认证"
    }
}

data class CampusApplicationDto(
    val id: String,
    val schoolName: String,
    val method: String,
    val status: String,
    val decision: String?,
    val reviewedAt: String?,
    val validUntil: String?,
    val createdAt: String,
) {
    fun statusLabel(): String = when (status) {
        "PENDING" -> "待审核"
        "APPROVED" -> if (verificationExpired(validUntil)) "已到期 · 待复核" else "已通过"
        "REJECTED" -> "未通过"
        "WITHDRAWN" -> "已撤回"
        "REVOKED" -> "已撤销"
        else -> "状态待更新"
    }
}

data class EmailCodeRequest(val email: String, val purpose: String = "BIND_EMAIL")
data class EmailCodeResponse(val sent: Boolean, val expiresInSeconds: Int)
data class BindEmailRequest(val email: String, val code: String)

private fun verificationExpired(value: String?): Boolean = runCatching {
    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }
    value != null && (formatter.parse(value)?.time ?: Long.MAX_VALUE) <= System.currentTimeMillis()
}.getOrDefault(false)
