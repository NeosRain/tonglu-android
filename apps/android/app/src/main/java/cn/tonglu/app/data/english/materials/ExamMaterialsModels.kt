package cn.tonglu.app.data.english.materials

import com.google.gson.annotations.SerializedName

/**
 * 考试材料的**远端 DTO**（`GET learning/exam` 系列端点）。
 *
 * 字段一律**可空**：Gson 绕过 Kotlin 构造器，远端少一个字段时非空类型会被塞成 null，
 * 一碰就 NPE（同 RemoteLearningCatalog 的教训）。结构自检放在 [toOption] / [toRow]：
 * 必填缺失的条目整条丢掉，不牵连其它篇。
 *
 * 契约边界（docs/english/exam-entry-contract.md）：
 * - 未知考试日期（year/session/paper）服务端保持 null，客户端不补猜测值；
 * - availability=SOURCE_LINK 表示「只有原站链接」，不承诺离线全文——
 *   历年真题未获授权，CET4/CET6 现阶段只有导航条目，UI 必须如实显示待收录状态。
 */
data class RemoteExamOption(
    val key: String? = null,
    val title: String? = null,
    @SerializedName("articleCount") val articleCount: Int? = null,
    @SerializedName("fullTextCount") val fullTextCount: Int? = null,
)

data class RemoteExamMaterialRow(
    val articleId: String? = null,
    val revision: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val summary: String? = null,
    val sectionId: String? = null,
    val kind: String? = null,
    val examType: String? = null,
    val language: String? = null,
    val year: Int? = null,
    val session: String? = null,
    val paper: String? = null,
    val readingType: String? = null,
    val recommended: Boolean? = null,
    val availability: String? = null,
    val sourceUrl: String? = null,
    val licenseId: String? = null,
    val licenseUrl: String? = null,
    val attribution: String? = null,
    @SerializedName("contentSha256") val contentSha256: String? = null,
    val updatedAt: String? = null,
)

data class RemoteExamMaterialsPage(
    val total: Int? = null,
    val items: List<RemoteExamMaterialRow>? = null,
)

data class RemoteExamMaterialDetail(
    val articleId: String? = null,
    val revision: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val summary: String? = null,
    val sectionId: String? = null,
    val kind: String? = null,
    val examType: String? = null,
    val language: String? = null,
    val year: Int? = null,
    val session: String? = null,
    val paper: String? = null,
    val readingType: String? = null,
    val recommended: Boolean? = null,
    val availability: String? = null,
    val sourceUrl: String? = null,
    val licenseId: String? = null,
    val licenseUrl: String? = null,
    val attribution: String? = null,
    @SerializedName("contentSha256") val contentSha256: String? = null,
    val updatedAt: String? = null,
    val body: String? = null,
    val bodySource: String? = null,
)

// ------------------------------------------------------------- 领域模型

/** 考试选择页选项（契约 A 的 ExamOption 形状 + fullTextCount 供 UI 显示待收录状态）。 */
data class ExamMaterialOption(
    val key: String, val title: String, val articleCount: Int, val fullTextCount: Int,
)

/** 全文 / 仅链接。FULLTEXT=正文可离线获取；SOURCE_LINK=只有原站链接，点击跳转。 */
enum class ExamAvailability { FULLTEXT, SOURCE_LINK;

    companion object {
        fun of(raw: String?): ExamAvailability = if (raw == "FULLTEXT") FULLTEXT else SOURCE_LINK
    }
}

/** 列表行元数据（不含正文——正文只在 [ExamMaterialDetail] 给）。 */
data class ExamMaterialRow(
    val articleId: String,
    /** 阅读记录键的正文版本（沿用目录 sourceRevision，与现有阅读位置同键）。 */
    val revision: String,
    val title: String,
    val subtitle: String,
    val summary: String,
    val sectionId: String,
    val kind: String,
    val examType: String?,
    val language: String?,
    /** 未知考试日期一律 null，不补猜测值。 */
    val year: Int?,
    val session: String?,
    val paper: String?,
    val readingType: String?,
    val recommended: Boolean,
    val availability: ExamAvailability,
    val sourceUrl: String,
    val licenseId: String,
    val licenseUrl: String,
    val attribution: String,
    val contentSha256: String?,
    val updatedAt: String,
) {
    val isFullText: Boolean get() = availability == ExamAvailability.FULLTEXT
}

/** 详情：元数据 + 正文。SOURCE_LINK 条目 body 恒为 null。 */
data class ExamMaterialDetail(
    val row: ExamMaterialRow,
    val body: String?,
    /** feed=在线抓取通道；catalog=目录自带正文；null=没有正文（SOURCE_LINK 或待收录）。 */
    val bodySource: String?,
)

/** RemoteExamOption → 领域选项；必填缺失返回 null。 */
fun RemoteExamOption.toOption(): ExamMaterialOption? {
    val k = key?.takeIf { it.isNotBlank() } ?: return null
    return ExamMaterialOption(
        key = k, title = title.orEmpty(),
        articleCount = articleCount ?: 0, fullTextCount = fullTextCount ?: 0,
    )
}

/** RemoteExamMaterialRow → 领域行；id/revision 缺失的整条丢掉（阅读记录没法键）。 */
fun RemoteExamMaterialRow.toRow(): ExamMaterialRow? {
    val id = articleId?.takeIf { it.isNotBlank() } ?: return null
    return ExamMaterialRow(
        articleId = id,
        revision = revision.orEmpty(),
        title = title.orEmpty(),
        subtitle = subtitle.orEmpty(),
        summary = summary.orEmpty(),
        sectionId = sectionId.orEmpty(),
        kind = kind.orEmpty(),
        examType = examType?.takeIf { it.isNotBlank() },
        language = language?.takeIf { it.isNotBlank() },
        year = year,
        session = session?.takeIf { it.isNotBlank() },
        paper = paper?.takeIf { it.isNotBlank() },
        readingType = readingType?.takeIf { it.isNotBlank() },
        recommended = recommended ?: false,
        availability = ExamAvailability.of(availability),
        sourceUrl = sourceUrl.orEmpty(),
        licenseId = licenseId.orEmpty(),
        licenseUrl = licenseUrl.orEmpty(),
        attribution = attribution.orEmpty(),
        contentSha256 = contentSha256?.takeIf { it.isNotBlank() },
        updatedAt = updatedAt.orEmpty(),
    )
}

/** RemoteExamMaterialDetail → 领域详情；行不合法返回 null。SOURCE_LINK 永不带正文。 */
fun RemoteExamMaterialDetail.toDetail(): ExamMaterialDetail? {
    // 详情和列表共用一套行校验：把详情字段投影成 Row 形状再走同一个 toRow。
    val row = RemoteExamMaterialRow(
        articleId = articleId, revision = revision, title = title, subtitle = subtitle,
        summary = summary, sectionId = sectionId, kind = kind, examType = examType,
        language = language, year = year, session = session, paper = paper,
        readingType = readingType, recommended = recommended, availability = availability,
        sourceUrl = sourceUrl, licenseId = licenseId, licenseUrl = licenseUrl,
        attribution = attribution, contentSha256 = contentSha256, updatedAt = updatedAt,
    ).toRow() ?: return null
    // 双保险：服务端已拦，客户端再拦一次——「仅原站链接」的资源绝不能被当成离线全文。
    val safeBody = if (row.isFullText) body?.takeIf { it.isNotBlank() } else null
    return ExamMaterialDetail(
        row = row, body = safeBody,
        bodySource = if (safeBody != null) bodySource else null,
    )
}
