package cn.tonglu.app.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class LearningExcerpt(val title: String, val text: String)
data class LearningQuestion(val question: String, val sources: List<LearningExcerpt>, val strategy: String = "general")
data class LearningAnswer(val state: String, val answer: String, val citedIndices: List<Int>, val model: String?)
data class LearningHit(val article: LearningArticle, val documentPath: String?, val heading: String,
    val excerpt: String, val sourceUrl: String, val anchor: String?)

/** Public bundled texts only. No academic records or account profile enter the search index. */
class LearningSearch(private val context: Context) {
    private var index: List<LearningHit>? = null
    suspend fun find(catalog: LearningCatalog, query: String, articleId: String? = null): List<LearningHit> = withContext(Dispatchers.Default) {
        val all = index ?: build(catalog).also { index = it }
        val normalized = query.lowercase().trim()
        val stop = setOf("怎么", "什么", "如何", "可以", "应该", "请问", "有没有", "想要", "我想", "关于", "一下", "哪些")
        val terms = Regex("[a-z0-9_+#.]+|[\\p{IsHan}]+").findAll(normalized).flatMap { match ->
            val text = match.value
            if (text.first().isLetterOrDigit() && text.first().code < 128) sequenceOf(text)
            else text.windowed(2).asSequence().filterNot { it in stop }
        }.distinct().toList()
        if (terms.isEmpty()) return@withContext emptyList()
        all.asSequence().filter { articleId == null || it.article.id == articleId }.map { hit ->
            val title = "${hit.article.title} ${hit.heading}".lowercase()
            val body = hit.excerpt.lowercase()
            val score = terms.sumOf { term -> (if (title.contains(term)) 7 else 0) + (if (body.contains(term)) 1 else 0) } +
                (if (title.contains(normalized)) 10 else 0)
            hit to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(5).map { it.first }.toList()
    }

    private suspend fun build(catalog: LearningCatalog): List<LearningHit> = withContext(Dispatchers.IO) {
        val result = mutableListOf<LearningHit>()
        fun add(article: LearningArticle, path: String?, title: String, body: String, url: String) {
            var heading = title
            var anchor: String? = null
            var lines = mutableListOf<String>()
            val anchors = mutableMapOf<String, Int>()
            fun flush() {
                val text = lines.joinToString("\n").trim()
                // Split lengthy blocks with overlap; never truncate the entire book to the README.
                if (text.isNotBlank()) {
                    if (path != null) {
                        // The author's skill requires the entire entry including notes and sources.
                        // Do not feed truncated medical/legal conditions to the model.
                        if (text.length <= 12000) result.add(LearningHit(article, path, "$title · $heading", text, url, anchor))
                        lines = mutableListOf(); return
                    }
                    var offset = 0
                    while (offset < text.length) {
                        result.add(LearningHit(article, path, heading, text.substring(offset, minOf(offset + 3000, text.length)), url, anchor))
                        if (offset + 3000 >= text.length) break
                        offset += 2700
                    }
                }
                lines = mutableListOf()
            }
            var fenced = false
            body.lineSequence().forEach { line ->
                if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) fenced = !fenced
                val header = if (!fenced) Regex(if (path == null) "^#{1,6}\\s+(.+)$" else "^#{1,3}\\s+(.+)$").find(line) else null
                if (header != null) {
                    flush(); heading = header.groupValues[1].replace(Regex("[*`~]"), "").trim()
                    val base = heading.lowercase().replace(Regex("[^\\p{L}\\p{N}_\\-\\s]"), "").replace(Regex("\\s"), "-")
                    val count = anchors[base] ?: 0; anchors[base] = count + 1
                    anchor = if (count == 0) base else "$base-$count"
                } else lines.add(line)
            }
            flush()
        }
        catalog.articles.forEach { article ->
            coroutineContext.ensureActive()
            // **单篇 / 单章坏掉不能让整次检索挂掉。**
            // ReaderDocuments.read() 里有两个 require（路径护栏、单篇 40 万字符上限），
            // 任何一条不满足就抛 IllegalArgumentException；以前它直接冒到 LearningSearchDialog，
            // 界面只显示「资料暂时无法检索，请重试」—— 用户看到的正是「AI 搜索不能用」。
            // 现在按章节降级：坏的那章跳过，其余照常进索引；整篇都进不去就退回文章摘要。
            try {
                val book = ReaderDocuments.book(context, article)
                if (book == null) {
                    add(article, null, article.title, article.body.ifBlank { article.summary }, article.documentUrl)
                } else {
                    var indexed = 0
                    book.chapters.forEach { chapter ->
                        coroutineContext.ensureActive()
                        val document = try { ReaderDocuments.read(context, article, book, chapter) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (bad: Exception) {
                            Log.w(TAG, "跳过章节 ${article.id}：${bad::class.java.simpleName}: ${bad.message}")
                            null
                        }
                        if (document != null) { add(article, chapter.path, chapter.title, document.body, book.url(chapter)); indexed++ }
                    }
                    if (indexed == 0) add(article, null, article.title, article.body.ifBlank { article.summary }, article.documentUrl)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (bad: Exception) {
                Log.w(TAG, "跳过文章 ${article.id}：${bad::class.java.simpleName}: ${bad.message}")
            }
        }
        result
    }

    private companion object { const val TAG = "TongluLearnSearch" }
}

/** AI 翻译（learning/translate）：服务器只做缓存；生成在用户自己的 API 侧完成（docs/plan/37）。 */
data class LearningTranslateRequest(val articleId: String?, val text: String, val lang: String? = null)
data class LearningTranslateResponse(val state: String, val text: String, val model: String?, val cachedAt: String?)

/**
 * 阅读器页内翻译（docs/plan/37 B1）：按 Markdown 块成批翻译。
 * `state`：CACHED（公共缓存命中）/ OK（本次用户 API 生成）/ NEEDS_MODEL（未命中且没配 API，零调用）。
 * `blocks` 与入参等长且顺序一致；NEEDS_MODEL 时为空。
 */
data class LearningBlockTranslation(val state: String, val blocks: List<String>, val model: String?)

/** 翻完后的提交口（learning/translate/submit）：服务端复算指纹并限频。 */
data class LearningTranslateSubmit(val articleId: String?, val text: String, val translated: String,
    val contentHash: String? = null, val model: String? = null, val lang: String? = null)
data class LearningTranslateSubmitResult(val state: String, val contentHash: String? = null)
