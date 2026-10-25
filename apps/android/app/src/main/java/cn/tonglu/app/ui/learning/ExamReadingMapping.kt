package cn.tonglu.app.ui.learning

import cn.tonglu.app.data.LearningArticle
import cn.tonglu.app.data.english.materials.*
import cn.tonglu.app.ui.english.examentry.*
import com.google.gson.Gson
import com.google.gson.JsonParser

internal fun ExamMaterialListItem.entryRow() = ReadingArticleRow(row.articleId, row.revision, row.title,
    row.subtitle, progress, cached, row.isFullText, ExamArticleMeta(row.year, row.session, row.paper, row.readingType))

internal data class PackedExamReading(val articles: Map<String, LearningArticle>, val rows: List<ExamMaterialRow>, val warnings: List<String>)

/** 直接读取同一打包目录的考试元数据，不另造正文、示例或个人文章目录。 */
internal fun packedExamReading(json: String): PackedExamReading {
    val root = JsonParser.parseString(json).asJsonObject
    val articles = mutableMapOf<String, LearningArticle>()
    val rows = mutableListOf<ExamMaterialRow>()
    val warnings = mutableListOf<String>()
    root.getAsJsonArray("articles").forEach { value ->
        try {
            val item = value.asJsonObject
            if (!item.has("exam")) return@forEach
            val exam = item.getAsJsonObject("exam")
            val article = Gson().fromJson(item, LearningArticle::class.java)
            require(article.id.isNotBlank() && article.sourceRevision.isNotBlank()) { "缺少文章身份或版本" }
            require(article.licenseId.isNotBlank() && article.sourceUrl.isNotBlank()) { "未登记出处或许可" }
            fun string(key: String) = exam.get(key)?.takeUnless { it.isJsonNull }?.asString
            fun number(key: String) = exam.get(key)?.takeUnless { it.isJsonNull }?.asInt
            val row = RemoteExamMaterialRow(articleId = article.id, revision = article.sourceRevision,
                title = article.title, summary = article.summary, sectionId = article.sectionId,
                kind = article.kind, examType = string("examType"), language = string("language"),
                year = number("year"), session = string("session"), paper = string("paper"),
                readingType = string("readingType"), recommended = exam.get("recommended")?.asBoolean ?: false,
                availability = string("availability"), sourceUrl = article.sourceUrl, licenseId = article.licenseId,
                licenseUrl = article.licenseUrl, attribution = article.attribution,
                contentSha256 = item.get("contentSha256")?.takeUnless { it.isJsonNull }?.asString,
                updatedAt = article.updatedAt).toRow() ?: error("材料字段无效")
            require(!row.isFullText || article.body.isNotBlank()) { "全文材料缺正文" }
            require(row.isFullText || article.body.isBlank()) { "原站导航不能携带全文" }
            articles[article.id] = article
            rows += row
        } catch (invalid: Exception) { warnings += "有一条打包考试材料无法读取：${invalid.javaClass.simpleName}" }
    }
    return PackedExamReading(articles, rows, warnings)
}
