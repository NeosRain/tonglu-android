package cn.tonglu.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 资料正文的本地缓存与增量拉取（后端的 learning/feed 两个读接口）。
 *
 * ⚠️ 这段注释里**不要**写出「feed 后面跟斜杠加星号」的路径——KDoc 遇到 `斜杠+星号`
 * 会当成嵌套注释的起点，整个文件从那一行开始全部 Unresolved（本项目已踩过一次）。
 *
 * 为什么要这一层：正文随 APK 打包（`assets/catalog.json`），用户不更新 App 就永远读不到
 * 上游的新版。所以服务端每天抓一次，App 只问版本号、变了才按篇下载，落在
 * `filesDir/learning/feed/` 下，读目录时覆盖上去。网络不好就继续用打包进来的那份。
 *
 * 缓存结构：
 *   `index.json` —— 整体版本号 + 每篇的指纹（决定「下一篇要不要重下」）
 *   `<id>.md`    —— 一篇的原始 Markdown
 *
 * 注意**不要**把 feed 里的 `sourceRevision` 覆盖到文章上：
 * `ReaderDocuments.book()` 用 `book.commit == article.sourceRevision` 当护栏，
 * 而 assets 里的整本书是按目录记的那个 commit 抓的，改掉它会让离线书直接打不开。
 */
object LearningFeedCache {
    private const val TAG = "LearningFeed"
    /** 单篇正文上限。和 `ReaderDocuments.MAX_DOCUMENT_CHARS` 同量级，防住异常大的文件。 */
    private const val MAX_BODY_CHARS = 1_500_000

    /** 进程内缓存：`LearningRepository.load` 会被反复调用，别每次都读 18 个文件。 */
    private var memoRevision: String? = null
    private var memoBodies: Map<String, String> = emptyMap()

    data class Doc(val id: String? = null, val revision: String? = null) {
        val key: String get() = id.orEmpty()
        val stamp: String get() = revision.orEmpty()
    }

    data class Index(val revision: String? = null, val savedAt: Long = 0L, val docs: List<Doc>? = null) {
        val version: String get() = revision.orEmpty()
        val entries: List<Doc> get() = docs.orEmpty()
        fun stampOf(id: String): String = entries.firstOrNull { it.key == id }?.stamp.orEmpty()
    }

    private fun dir(context: Context) = File(File(context.filesDir, "learning"), "feed")

    fun readIndex(context: Context): Index = runCatching {
        val file = File(dir(context), "index.json")
        if (!file.exists()) Index() else Gson().fromJson(file.readText(), Index::class.java) ?: Index()
    }.getOrElse { Index() }

    /** 已缓存的正文（id → Markdown）。文件缺失或为空的一律不返回，让上层继续用打包的那份。 */
    fun bodies(context: Context): Map<String, String> {
        val index = readIndex(context)
        if (index.version.isNotEmpty() && index.version == memoRevision) return memoBodies
        val map = LinkedHashMap<String, String>()
        index.entries.forEach { doc ->
            if (doc.key.isEmpty() || doc.stamp.isEmpty()) return@forEach
            val file = File(dir(context), "${doc.key}.md")
            if (!file.exists()) return@forEach
            val text = runCatching { file.readText() }.getOrNull()
            if (!text.isNullOrBlank()) map[doc.key] = text
        }
        memoBodies = map
        memoRevision = index.version
        return map
    }

    /**
     * 同步一次。返回「本机正文是否变了」，上层据此决定要不要重新读目录。
     *
     * 早退条件很关键：整体版本号一样、且每篇的指纹都和本地一致时才直接返回 ——
     * 上一次若有一篇下载失败（指纹对不上），这次仍会补下，不会因为整体版本号没变而永远缺一篇。
     */
    suspend fun sync(context: Context, api: TongluApi): Boolean = withContext(Dispatchers.IO) {
        val meta = runCatching { api.learningFeedMeta() }.getOrElse { error ->
            Log.i(TAG, "拉版本号失败，继续用本地正文：${error.message}")
            return@withContext false
        }
        if (!meta.enabled || meta.revision.isBlank()) return@withContext false
        val usable = meta.entries.filter { it.usable }
        if (usable.isEmpty()) return@withContext false

        val index = readIndex(context)
        val upToDate = meta.revision == index.version &&
            usable.all { entry ->
                entry.revision == index.stampOf(entry.id) &&
                    File(dir(context), "${entry.id}.md").exists()
            }
        // 正文没变也要问一次目录：这两者的版本号是各自的，不能因为正文最新就跳过目录。
        if (upToDate) {
            val home = dir(context).also { it.mkdirs() }
            return@withContext syncCatalog(home, api)
        }

        val target = dir(context)
        target.mkdirs()
        val docs = mutableListOf<Doc>()
        var downloaded = 0
        usable.forEach { entry ->
            val local = index.stampOf(entry.id)
            if (entry.revision == local && File(target, "${entry.id}.md").exists()) {
                docs += Doc(entry.id, entry.revision)
                return@forEach
            }
            val body = runCatching { api.learningFeedArticle(entry.id).text }.getOrNull()
            if (body.isNullOrBlank() || body.length > MAX_BODY_CHARS) {
                // 下载失败：保留本地那一份（指纹按本地记，下次再补）
                docs += Doc(entry.id, local)
                return@forEach
            }
            runCatching { File(target, "${entry.id}.md").writeText(body) }
                .onSuccess { docs += Doc(entry.id, entry.revision); downloaded++ }
                .onFailure { docs += Doc(entry.id, local) }
        }
        runCatching {
            File(target, "index.json").writeText(
                Gson().toJson(Index(revision = meta.revision, savedAt = System.currentTimeMillis(), docs = docs)))
        }
        memoRevision = null // 让下一次 bodies() 重读
        Log.i(TAG, "在线正文已更新：下载 $downloaded 篇，整体版本 ${meta.revision}")
        // 目录也顺带问一下：管理员在后台改了分类/加了一篇，不更新 App 也要能看到。
        val catalogChanged = syncCatalog(target, api)
        downloaded > 0 || catalogChanged
    }

    /**
     * 拉服务器上的目录并落盘。
     *
     * 两个「不」：① 拉不到就当没这回事（继续用打包的那份）；② 结构不合法**不写盘** ——
     * 写个坏文件进去，之后每次开学习页都要失败一次，比不写更糟。
     */
    private suspend fun syncCatalog(target: File, api: TongluApi): Boolean {
        val remote = runCatching { api.learningFeedCatalog() }.getOrElse { error ->
            Log.i(TAG, "拉目录失败，继续用打包的那份：${error.message}")
            return false
        }
        if (remote.enabled == false) return false
        val revision = remote.revision?.takeIf { it.isNotBlank() } ?: return false
        val current = runCatching {
            Gson().fromJson(File(target, "catalog.json").takeIf { it.exists() }?.readText(), RemoteLearningCatalog::class.java)
        }.getOrNull()
        if (current?.revision == revision) return false
        // 先自检：不合法就不写盘
        val sections = remote.sections.orEmpty()
        val articles = remote.articles.orEmpty()
        if (remote.schemaVersion != 1 || sections.isEmpty() || articles.isEmpty()) {
            Log.i(TAG, "服务器目录结构不认识（schemaVersion=${remote.schemaVersion}），跳过")
            return false
        }
        writeCatalog(target, remote)
        Log.i(TAG, "在线目录已更新：分类 ${sections.size} 篇 ${articles.size}，revision=$revision")
        return true
    }

    private fun writeCatalog(target: File, remote: RemoteLearningCatalog) {
        runCatching {
            target.mkdirs()
            val tmp = File(target, "catalog.json.tmp")
            tmp.writeText(Gson().toJson(remote))
            val dest = File(target, "catalog.json")
            if (tmp.renameTo(dest)) Unit else dest.writeText(tmp.readText())
            tmp.delete()
        }.onFailure { Log.i(TAG, "写目录缓存失败：${it.message}") }
    }

    /**
     * 服务器上下发的目录（分类 + 文章清单，含正文）。
     *
     * 没有缓存 / 读不出来一律返回 null —— 上层会继续用打包进 assets 的那份。
     * 目录是「整页能不能开」的东西，所以这里**不抛异常**，只安静地没有。
     */
    fun remoteCatalog(context: Context): RemoteLearningCatalog? {
        val file = File(dir(context), "catalog.json")
        if (!file.exists()) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return runCatching { Gson().fromJson(text, RemoteLearningCatalog::class.java) }.getOrNull()
    }

    /** 清掉本地缓存（调试用 / 空间清理）。 */
    fun clear(context: Context) {
        runCatching { dir(context).deleteRecursively() }
        memoRevision = null
        memoBodies = emptyMap()
    }
}
