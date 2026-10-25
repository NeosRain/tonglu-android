package cn.tonglu.app.data

import android.content.Context
import com.google.gson.Gson
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ReaderDocument(val path: String, val title: String)
data class ReaderBook(val repository: String, val commit: String, val documents: List<ReaderDocument>) {
    /**
     * 章节目录。
     *
     * 老约定（高性价比人生指南）：只有 `book/` 前缀的才算章节，README 是封面。
     * 新约定（抓取进来的开源仓库）：整个仓库的文档都是章节，没有 `book/` 前缀。
     * 所以这里退化为「有 book/ 就用 book/，没有就全用」——两种书共用一套 UI。
     */
    val chapters: List<ReaderDocument> get() =
        documents.filter { it.path.startsWith("book/") }.sortedBy { it.path }
            .ifEmpty { documents }
    fun url(document: ReaderDocument): String = "$repository/blob/$commit/${document.path}"
    fun find(url: String): ReaderDocument? = runCatching {
        val target = URI(url)
        val base = URI(repository)
        if (target.scheme != "https" || target.userInfo != null || target.host != base.host) return@runCatching null
        val prefix = "${base.path}/blob/$commit/"
        if (!target.path.startsWith(prefix)) return@runCatching null
        documents.firstOrNull { it.path == target.path.removePrefix(prefix) }
    }.getOrNull()
}

/**
 * Asset manifest is the allowlist. Only bundled licensed files can be opened locally.
 *
 * 一篇文章 = 一本书：正文放在 `assets/books/<文章 id>/` 下，`index.json` 是清单。
 * 目录里没有对应目录就返回 null —— 这篇没有离线正文，退回「原文导航」形态。
 * assets 根目录由 `app/build.gradle.kts` 指向仓库里的 `content/learning/`。
 */
object ReaderDocuments {
    private const val ROOT = "books"
    /** 单篇文档的字符上限。抓下来的仓库 README 有 20 万字符级的，所以放宽到 40 万。 */
    private const val MAX_DOCUMENT_CHARS = 400_000

    /** 文章 id 由目录校验保证是 `[a-z0-9][a-z0-9_-]*`，不含斜杠，可以安全拼路径。 */
    private fun dir(article: LearningArticle) = "$ROOT/${article.id}"

    suspend fun book(context: Context, article: LearningArticle): ReaderBook? = withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open("${dir(article)}/index.json").bufferedReader().use {
                Gson().fromJson(it.readText(), ReaderBook::class.java)
            }.takeIf { it.repository == article.sourceUrl && it.commit == article.sourceRevision }
        }.getOrNull()
    }

    /**
     * 章节路径护栏。
     *
     * **不要写成 `!path.contains("..")`** —— 上游仓库里就有
     * `notes/64. 求 1+2+3+...+n.md` 这种文件名（省略号 `...` 里带 `..`），
     * 会被误判成目录穿越。2026-10-03 实测：正因为这一条，
     * `cs-notes-cyc2018` 的整本书都读不出来，`LearningSearch` 索引全崩，
     * 界面上表现为「AI 搜索不能用」。
     *
     * 正确的是按 `/` 分段判：任何一段是 `.` 或 `..` 才算穿越。
     */
    private fun String.isSafeAssetPath(): Boolean {
        if (isEmpty() || startsWith('/') || contains('\\') || contains('\u0000')) return false
        return split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    suspend fun read(context: Context, root: LearningArticle, book: ReaderBook, document: ReaderDocument): LearningArticle = withContext(Dispatchers.IO) {
        // 消息写具体：`require` 不带消息时只抛 "Failed requirement."，
        // 排查「哪一章、哪一条」要多花一轮 —— 这里直接说清楚。
        require(document in book.documents) { "章节不在清单里：${document.path}" }
        require(document.path.isSafeAssetPath()) { "章节路径不安全：${document.path}" }
        val body = context.assets.open("${dir(root)}/${document.path}").bufferedReader().use { it.readText() }
        require(body.length <= MAX_DOCUMENT_CHARS) { "章节超过 ${MAX_DOCUMENT_CHARS} 字符：${document.path}" }
        root.copy(id = "${root.id}:${document.path}", title = document.title, body = body,
            sourceUrl = book.url(document), sourceContentUrl = book.url(document),
            summary = "${root.title} · 离线章节", sourceRevision = book.commit)
    }
}

/** Resolve Markdown paths relative to the actual source file, preserving Unicode paths. */
fun resolveReaderUrl(base: String, link: String): String? = runCatching {
    // URI rejects spaces. Keep encoded sequences intact; encode only literal spaces.
    val target = URI(link.replace(" ", "%20"))
    val resolved = if (base.isNotBlank()) URI(base).resolve(target) else target
    resolved.toString().takeIf { resolved.scheme == "https" && !resolved.host.isNullOrBlank() && resolved.userInfo == null }
}.getOrNull()
