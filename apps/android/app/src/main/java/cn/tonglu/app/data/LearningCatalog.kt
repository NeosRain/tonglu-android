package cn.tonglu.app.data

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import com.google.gson.Gson
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LearningSection(
    val id: String, val parentId: String?, val title: String, val description: String,
    val color: String, val order: Int,
) {
    /** 学习页配色：5 色，区分一级 / 二级 / 三级。 */
    val sectionColor: Color get() = when (color) {
        "blue" -> Color(0xFF2563EB)
        "green" -> Color(0xFF16846E)
        "amber" -> Color(0xFFD97706)
        "red" -> Color(0xFFE11D48)
        "purple" -> Color(0xFF7C3AED)
        "teal" -> Color(0xFF0E7490)
        else -> Color(0xFF2563EB)
    }
}

data class LearningArticle(
    val id: String, val sectionId: String, val title: String, val summary: String,
    val author: String, val kind: String, val sourceUrl: String, val licenseId: String,
    val licenseUrl: String, val attribution: String, val changeNote: String,
    val sourceRevision: String, val updatedAt: String, val featured: Boolean,
    val order: Int, val body: String, val sourceContentUrl: String? = null,
    // 知识网络的边。
    // 必须是可空类型：Gson 绕过 Kotlin 构造器，JSON 里缺这个字段时非空类型会被塞成 null，
    // 一碰就 NPE。所以对外只暴露 orEmpty() 的访问器。
    val tags: List<String>? = null,
    val relatedIds: List<String>? = null,
) {
    val documentUrl: String get() = sourceContentUrl?.takeIf { it.isNotBlank() } ?: sourceUrl
    val isGitHub: Boolean get() = runCatching { URI(sourceUrl).host.equals("github.com", true) }.getOrDefault(false)
    val licenseLabel: String get() = when (licenseId) {
        "ORIGINAL" -> "原创示例"
        "LINK_ONLY" -> "仅原文导航"
        else -> licenseId
    }
    /** 供知识网络使用的标签。 */
    val tagList: List<String> get() = tags.orEmpty()
    /** 作者手工指定的强关联（比共享标签更硬）。 */
    val relatedList: List<String> get() = relatedIds.orEmpty()
}

data class LearningCatalog(
    val schemaVersion: Int, val revision: String,
    val sections: List<LearningSection>, val articles: List<LearningArticle>,
) {
    companion object { val Empty = LearningCatalog(1, "", emptyList(), emptyList()) }
}

/**
 * 服务器下发的目录（`GET learning/feed/catalog`）。
 *
 * 字段一律**可空**：Gson 绕过 Kotlin 构造器，远端少一个字段时非空类型会被塞成 null，
 * 一碰就 NPE。所以这里全用 `String?` + 默认值，再由 [toCatalog] 做一次结构自检。
 */
data class RemoteLearningSection(
    val id: String? = null, val parentId: String? = null, val title: String? = null,
    val description: String? = null, val color: String? = null, val order: Int? = null,
)

/** 一篇；缺字段的会被 [RemoteLearningCatalog.toCatalog] 整条丢掉，不牵连其它篇。 */
data class RemoteLearningArticle(
    val id: String? = null, val sectionId: String? = null, val title: String? = null,
    val summary: String? = null, val author: String? = null, val kind: String? = null,
    val sourceUrl: String? = null, val licenseId: String? = null, val licenseUrl: String? = null,
    val attribution: String? = null, val changeNote: String? = null, val sourceRevision: String? = null,
    val updatedAt: String? = null, val featured: Boolean? = null, val order: Int? = null,
    val body: String? = null, val sourceContentUrl: String? = null,
    val tags: List<String>? = null, val relatedIds: List<String>? = null,
)

data class RemoteLearningCatalog(
    val enabled: Boolean? = null, val source: String? = null, val schemaVersion: Int? = null,
    val revision: String? = null,
    val sections: List<RemoteLearningSection>? = null,
    val articles: List<RemoteLearningArticle>? = null,
) {
    /**
     * 转成客户端能用的目录。**不合法返回 null**，让上层继续用打包进 assets 的那份 ——
     * 目录是「整页能不能开」的东西，宁可退回旧版也不能开天窗。
     *
     * @param fallbackBodies 打包版里各篇的正文；远端某篇没带正文时用它补，避免整篇空白。
     */
    fun toCatalog(fallbackBodies: Map<String, String> = emptyMap()): LearningCatalog? {
        // schemaVersion 是「结构变了」的信号：不是 1 说明老代码读不懂，直接不要。
        if (schemaVersion != 1) return null
        val sections = sections.orEmpty().mapNotNull { item ->
            val id = item.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            LearningSection(
                id = id, parentId = item.parentId?.takeIf { it.isNotBlank() },
                title = item.title.orEmpty(), description = item.description.orEmpty(),
                color = item.color.orEmpty(), order = item.order ?: 0,
            )
        }
        if (sections.isEmpty()) return null
        if (sections.map { it.id }.distinct().size != sections.size) return null
        val knownSections = sections.map { it.id }.toSet()
        val articles = articles.orEmpty().mapNotNull { item ->
            val id = item.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sectionId = item.sectionId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // 挂在不存在分类下的文章整条丢掉：留着会让「选中分类列文章」那块查不到归属
            if (sectionId !in knownSections) return@mapNotNull null
            val body = item.body?.takeIf { it.isNotBlank() } ?: fallbackBodies[id].orEmpty()
            LearningArticle(
                id = id, sectionId = sectionId, title = item.title.orEmpty(), summary = item.summary.orEmpty(),
                author = item.author.orEmpty(), kind = item.kind.orEmpty(), sourceUrl = item.sourceUrl.orEmpty(),
                licenseId = item.licenseId.orEmpty(), licenseUrl = item.licenseUrl.orEmpty(),
                attribution = item.attribution.orEmpty(), changeNote = item.changeNote.orEmpty(),
                sourceRevision = item.sourceRevision.orEmpty(), updatedAt = item.updatedAt.orEmpty(),
                featured = item.featured ?: false, order = item.order ?: 0, body = body,
                sourceContentUrl = item.sourceContentUrl?.takeIf { it.isNotBlank() },
                tags = item.tags, relatedIds = item.relatedIds,
            )
        }
        if (articles.isEmpty()) return null
        if (articles.map { it.id }.distinct().size != articles.size) return null
        return LearningCatalog(1, revision.orEmpty(), sections, articles)
    }
}

/** Android 只读取版本目录；编辑入口在管理后台。目录随 APK 打包，正文可被在线更新覆盖。 */
object LearningRepository {
    suspend fun load(context: Context): LearningCatalog = withContext(Dispatchers.IO) {
        val text = context.assets.open("catalog.json").bufferedReader().use { it.readText() }
        val packed = Gson().fromJson(text, LearningCatalog::class.java).also { catalog ->
            // schemaVersion 是「结构变了」的信号，不是内容版本 —— 内容版本走 revision。
            // 真升到 2 的唯一后果是：老包配上新目录会直接 require 失败、整页读不出来。
            require(catalog.schemaVersion == 1)
            require(catalog.sections.map { it.id }.distinct().size == catalog.sections.size)
            require(catalog.articles.map { it.id }.distinct().size == catalog.articles.size)
            require(catalog.articles.all { a -> catalog.sections.any { it.id == a.sectionId } })
        }
        // 管理员在后台改过目录的话，服务器上那份比打包的这份新 —— 用它当底稿。
        // 解析/校验失败（返回 null）就当没这回事，继续用打包的那份。
        val packedBodies = packed.articles.associate { it.id to it.body }
        val remote = runCatching { LearningFeedCache.remoteCatalog(context)?.toCatalog(packedBodies) }
            .onFailure { Log.w("LearningRepository", "在线目录不可用，用打包的：${it.message}") }
            .getOrNull()
        val catalog = remote ?: packed
        // 在线正文（服务端每天抓一次上游 README）覆盖底稿里那一份。
        // 只换 body：`sourceRevision` 是 assets 里整本书的 commit，改掉会让离线书打不开 ——
        // 见 LearningFeedCache 的注释。
        val online = LearningFeedCache.bodies(context)
        val merged = if (online.isEmpty()) catalog else catalog.copy(
            articles = catalog.articles.map { article ->
                online[article.id]?.takeIf { it.isNotBlank() }?.let { article.copy(body = it) } ?: article
            }
        )
        val markdown = merged.articles.count { it.kind == "MARKDOWN" }
        val linked = merged.articles.count { it.kind == "LINK" }
        Log.i("LearningRepository",
            "catalog source=${if (remote != null) "online" else "packed"} revision=${merged.revision} " +
                "sections=${merged.sections.size} markdown=$markdown link=$linked onlineBody=${online.size}")
        return@withContext merged
    }
}

/** 阅读偏好。纸张风格独立 id，可在设置面板里多选；老的 paper 布尔保留用于兼容。 */
object ReadingPrefs {
    const val PAPER_DEFAULT = "default"
    const val PAPER_WARM = "warm"
    const val PAPER_BAMBOO = "bamboo"
    const val PAPER_KRAFT = "kraft"
    const val PAPER_INK = "ink"
    val ALL = listOf(PAPER_DEFAULT, PAPER_WARM, PAPER_BAMBOO, PAPER_KRAFT, PAPER_INK)
    const val FONT_MIN = 13
    const val FONT_MAX = 28
    const val FONT_DEFAULT = 16
    const val LINE_MIN = 1.5f
    const val LINE_MAX = 2.2f
    const val LINE_DEFAULT = 1.6f
}

/** 这里只保存小量阅读偏好和位置；正文来自只读目录，不放入 SharedPreferences。 */
class ReadingStore(context: Context, owner: String) {
    private val ownerKey = java.security.MessageDigest.getInstance("SHA-256")
        .digest(owner.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private val prefs = context.getSharedPreferences("reading@$ownerKey", Context.MODE_PRIVATE)

    /**
     * 可同步的阅读数据（进度 / 书签 / 笔记）。
     * 偏好留在这边的 SharedPreferences，需要跟账号走的放这里 —— 见 [ReadingLibrary]。
     * 走 [ReadingLibrary.of] 拿共享实例：账号同步那边也在写同一个文件。
     */
    val library = ReadingLibrary.of(context, owner)

    val favorites: Set<String> get() = prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()
    fun recordOpen(article: LearningArticle) {
        prefs.edit().putInt("opens@${article.id}", openCount(article.id) + 1)
            .putString("seen@${article.id}", "${article.updatedAt}|${article.sourceRevision}").apply()
    }
    fun openCount(id: String): Int = prefs.getInt("opens@$id", 0)
    fun hasUpdate(article: LearningArticle): Boolean = prefs.getString("seen@${article.id}", null) != "${article.updatedAt}|${article.sourceRevision}"
    fun favorite(id: String, enabled: Boolean) {
        prefs.edit().putStringSet("favorites", favorites.toMutableSet().apply {
            if (enabled) add(id) else remove(id)
        }).apply()
    }
    var lastArticle: String
        get() = prefs.getString("last_article", "").orEmpty()
        set(value) { prefs.edit().putString("last_article", value).apply() }
    var fontSize: Int
        get() = prefs.getInt("font_size", ReadingPrefs.FONT_DEFAULT)
            .coerceIn(ReadingPrefs.FONT_MIN, ReadingPrefs.FONT_MAX)
        set(value) { prefs.edit().putInt("font_size",
            value.coerceIn(ReadingPrefs.FONT_MIN, ReadingPrefs.FONT_MAX)).apply() }
    var lineHeight: Float
        get() = prefs.getFloat("line_height", ReadingPrefs.LINE_DEFAULT)
            .coerceIn(ReadingPrefs.LINE_MIN, ReadingPrefs.LINE_MAX)
        set(value) { prefs.edit().putFloat("line_height",
            value.coerceIn(ReadingPrefs.LINE_MIN, ReadingPrefs.LINE_MAX)).apply() }
    // English typography is independent of other books. Existing explicit preferences remain the fallback.
    var englishFontSize: Int
        get() = prefs.getInt("english_font_size", if (prefs.contains("font_size")) fontSize else 19)
            .coerceIn(ReadingPrefs.FONT_MIN, ReadingPrefs.FONT_MAX)
        set(value) { prefs.edit().putInt("english_font_size",
            value.coerceIn(ReadingPrefs.FONT_MIN, ReadingPrefs.FONT_MAX)).apply() }
    var englishLineHeight: Float
        get() = prefs.getFloat("english_line_height", if (prefs.contains("line_height")) lineHeight else 1.8f)
            .coerceIn(ReadingPrefs.LINE_MIN, ReadingPrefs.LINE_MAX)
        set(value) { prefs.edit().putFloat("english_line_height",
            value.coerceIn(ReadingPrefs.LINE_MIN, ReadingPrefs.LINE_MAX)).apply() }
    var paper: Boolean
        get() = paperStyle != ReadingPrefs.PAPER_DEFAULT
        set(value) {
            // 老的 paper 布尔只用作 default → warm 的快速切换；新逻辑以 paperStyle 为准。
            paperStyle = if (value) ReadingPrefs.PAPER_WARM else ReadingPrefs.PAPER_DEFAULT
        }
    var paperStyle: String
        get() = prefs.getString("paper_style", null) ?: run {
            if (prefs.getBoolean("paper", false)) ReadingPrefs.PAPER_WARM else ReadingPrefs.PAPER_DEFAULT
        }
        set(value) { prefs.edit().putString("paper_style", value).apply() }

    /**
     * 用户**显式**选过纸张没有。没选过 = `null`。
     *
     * 为什么要单独开这一个口子：[paperStyle] 会把「没选过」和「显式选了白纸」都算成
     * `default`，读者用它就没法「跟随主题自动选夜间纸」—— 一进夜间文章还是一片白。
     * 2026-10-08 用户报的正是这条。
     */
    val paperStyleChosen: String? get() = prefs.getString("paper_style", null)
    /** 已收藏 / 上次阅读的位置（按文章 id + revision 区分） */
    fun position(id: String, revision: String): Pair<Int, Int> =
        prefs.getInt("index@$id@$revision", 0) to prefs.getInt("offset@$id@$revision", 0)
    fun savePosition(id: String, revision: String, index: Int, offset: Int) {
        prefs.edit().putInt("index@$id@$revision", index).putInt("offset@$id@$revision", offset).apply()
    }
}
