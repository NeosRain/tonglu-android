package cn.tonglu.app.data.english

import android.content.Context
import android.util.Log
import cn.tonglu.app.data.EnglishBookDto
import cn.tonglu.app.data.TongluApi
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 云端词库的本地仓库（**词库不再打进安装包**）。
 *
 * 以前 13 本 30MB 全塞在 APK 里：大多数人只背一本，却人人都要为 30MB 买单，
 * 加一本词库还得发一次版。现在清单和文件都放服务器，用户挑哪本下哪本，
 * 下完落在 `filesDir/english/books/<id>.book`，之后完全离线可用。
 *
 * 三级回退，顺序很重要：
 *   1. 服务器清单（最新，管理员在后台上下架立刻生效）
 *   2. 本机缓存的上一份清单（`english/catalog.json`，没网时也能换词书）
 *   3. 打包进 APK 的出厂清单（assets，保证第一次冷启动也不会开天窗）
 *
 * 目录里**只有清单、没有文件**是正常状态：那表示「可以下，但还没下」。
 *
 * ## 2026-10-18 · 随包精简词书（EF03）
 *
 * `assets/english/bundled/` 里另有一套**版本化随包词书**（精简四级/六级，见
 * `bundled/catalog.json` 的 `bundleVersion`）。它解决的是「候选只躺在 work/ 里，装到手机上根本
 * 看不见」的问题：候选词书随 APK 分发，**离线首装也能直接学**，不依赖服务端上架。
 *
 * 规则（两条都很重要）：
 * 1. **只补不换**：随包书按 id 合并进清单 —— 云端/缓存清单里已有的书原样保留、顺序不动；
 *    已上架过的旧词书与用户学习进度一律不受影响，也不强制换书。
 * 2. **先校验再落盘**：写入 `filesDir/english/books/` 前比对清单里的 bytes 与 sha256，
 *    落盘后仍走原 `EnglishRepository.ensureBook` 的导入链路 —— 和云端下载的书同一套代码。
 *
 * 为什么不等服务端上架：服务端上架只对「已发布版本」生效，而且首装/离线时清单可能是空的。
 * 随包 + 服务端是互补的：服务端有就服务端赢（同 id 去重），没有就随包补上。
 */
/**
 * 把一本词库的「出处 + 许可」拼成一行给用户看。
 *
 * 🔴 **缺哪一项就如实说哪一项缺**，不许拿别的值补位、也不许整行不显示 ——
 * 「没显示」会被读成「这本书没有出处问题」，而真相常常是「我们没登记」。
 * plan §5 第 3 条原话：**以前已发布不代表许可问题自动解决**。
 *
 * 抽成纯函数是为了能被单测钉住：这里正是最容易「好心办坏事」的地方 ——
 * 看见 `source` 写着 `KyleBing/english-vocabulary（BSD-3-Clause）` 就想从里面
 * 抠出许可证填进 `license`。**那是猜**，猜错就是编造许可信息，比留空危险得多。
 */
internal fun provenanceLine(source: String, license: String): String {
    val s = source.trim()
    val l = license.trim()
    return when {
        s.isBlank() && l.isBlank() -> "未登记出处与许可"
        s.isBlank() -> "未登记出处 · 许可 $l"
        l.isBlank() -> "来源 $s · 未登记许可"
        else -> "来源 $s · 许可 $l"
    }
}

object EnglishBookStore {
    private const val TAG = "EnglishBooks"
    private const val CATALOG_FILE = "catalog.json"

    /** 随包词书目录：与云端清单分开登记，换成新一代随包书只需要替换目录 + 提 bundleVersion。 */
    private const val BUNDLED_DIR = "english/bundled"
    private const val BUNDLED_MANIFEST = "english/bundled/catalog.json"
    /**
     * 单个词库文件的硬上限：**必须等于**服务端 `MAX_FILE_BYTES`
     * （`backend/src/english/english.service.ts`，32MB —— 上传时就是这个数）。
     *
     * 这道闸是「异常响应别把磁盘写满」。以前写的是 64MB，比服务端上限还松一倍 ——
     * 结果是**它永远不会关**：服务端根本不可能吐出 32MB 以上的词库。
     * `scripts/audit_cross_end_limits.py` 的「词库单文件」契约守着这条。
     */
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024

    private fun dir(context: Context) = File(File(context.filesDir, "english"), "books")

    /** 一本词库在本机的落点。文件名只用 id，和清单里的 asset 无关（老版本那个字段已废弃）。 */
    fun file(context: Context, book: EnglishBook): File = File(dir(context), "${book.id}.book")

    fun isDownloaded(context: Context, book: EnglishBook): Boolean =
        runCatching { file(context, book).length() > 0 }.getOrDefault(false)

    /** 已下载的词库 id 集合（UI 拿它决定按钮是「下载」还是「开始」）。 */
    fun downloadedIds(context: Context): Set<String> = runCatching {
        dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".book") && it.length() > 0 }
            ?.map { it.name.removeSuffix(".book") }?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    /**
     * 取词库清单。
     *
     * @param api 为 null 时（例如还没初始化网络层）只走本地，照样能列出已下载的词库。
     */
    suspend fun catalog(context: Context, api: TongluApi?): List<EnglishBook> = withContext(Dispatchers.IO) {
        val base = if (api != null) {
            val remote = runCatching { api.englishBooks() }.getOrElse { error ->
                Log.i(TAG, "拉词库清单失败，用本机缓存：${error.message}")
                null
            }?.mapNotNull { it.toBook() }
            if (!remote.isNullOrEmpty()) {
                saveCatalog(context, remote)
                remote
            } else {
                cached(context)?.takeIf { it.isNotEmpty() } ?: bundled(context)
            }
        } else {
            cached(context)?.takeIf { it.isNotEmpty() } ?: bundled(context)
        }
        // 随包词书只「补」云端/缓存清单里没有的 id：已有的书（含用户已下载/已学过的）不动。
        // 先落盘校验再返回列表 —— 写不进去的书不进列表，界面上不会出现点开没反应的一行。
        val manifest = bundledManifest(context)
        val extras = validBundledBooks(manifest?.books)
        val known = base.associateBy { it.id }
        // 服务端/缓存清单里没有的：落盘后补进列表。
        // 同 id 已存在时只有「内容与随包完全一致」才落盘（用于离线）；内容不同说明服务端更新过，
        // **不写本地旧文件** —— 否则旧随包文件会被当成新版本导入，ensureBook 的条数校验会失败。
        val missing = extras.filter { known[it.id] == null }
        val identical = extras.filter { extra ->
            known[extra.id]?.let { it.bytes == extra.bytes && it.sha256.equals(extra.sha256, ignoreCase = true) } == true
        }
        materializeBundled(context, missing + identical)
        val ready = missing.filter { isDownloaded(context, it) }
        mergeBundledBooks(base, ready, manifest?.insertAfter)
    }

    /**
     * 随包清单（`assets/english/bundled/catalog.json`）。
     *
     * 解析失败按「没有随包书」处理并记日志：**不能因为一份可选清单把整个换词书列表打崩** ——
     * 云端书照常可用，比整页报错好。
     */
    private fun bundledManifest(context: Context): BundledManifest? = runCatching {
        context.assets.open(BUNDLED_MANIFEST).bufferedReader().use { reader ->
            Gson().fromJson(reader, BundledManifest::class.java)
        }
    }.onFailure { Log.w(TAG, "随包词书清单读取失败：${it.message}") }.getOrNull()

    /**
     * 把随包词书写到 `filesDir/english/books/`（与云端下载同一落点）。
     *
     * 三道闸与下载链路一致：临时文件 + 大小比对 + sha256 比对；校验不过就删临时文件、只记日志。
     * 已存在且指纹一致时跳过（已教过的书不重复写盘）。
     */
    private suspend fun materializeBundled(context: Context, books: List<EnglishBook>) = withContext(Dispatchers.IO) {
        if (books.isEmpty()) return@withContext
        val dir = dir(context).apply { mkdirs() }
        books.forEach { book ->
            val target = File(dir, "${book.id}.book")
            if (target.exists() && target.length() == book.bytes.toLong() && sha256Of(target) == book.sha256.lowercase()) {
                return@forEach
            }
            val temp = File(dir, ".${book.id}.${System.currentTimeMillis()}.tmp")
            try {
                var total = 0L
                context.assets.open("$BUNDLED_DIR/${book.asset}").use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_FILE_BYTES) throw IOException("随包词库文件过大，已中止写入")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (total != book.bytes.toLong()) {
                    throw IOException("随包词库大小对不上：应 ${book.bytes} 字节，实到 $total 字节")
                }
                if (sha256Of(temp) != book.sha256.lowercase()) throw IOException("随包词库校验不通过")
                if (!temp.renameTo(target)) throw IOException("随包词库无法写入本机")
            } catch (error: Exception) {
                // 落盘失败不挡词书列表（书不会出现在列表里），但要留痕，便于排查磁盘/打包问题。
                Log.w(TAG, "随包词库 ${book.id} 写入失败：${error.message}")
                temp.takeIf { it.exists() }?.delete()
            }
        }
    }

    /**
     * 下载一本词库并落盘。
     *
     * 三道闸，缺一不可：
     * - 边下边写临时文件（`.tmp`），成功才改名 —— 中途断网不会留下一本坏词库；
     * - 比对服务端给的字节数；
     * - 比对 sha256 —— 传输出错、运营商插东西、缓存串味都过不去这一关。
     *
     * @param onProgress 0~100，只在整数百分比变化时回调（别每 64KB 刷一次界面）。
     */
    suspend fun download(context: Context, api: TongluApi, book: EnglishBook,
        onProgress: (Int) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val dir = dir(context).apply { mkdirs() }
        val temp = File(dir, ".${book.id}.${System.currentTimeMillis()}.tmp")
        try {
            val body = api.downloadEnglishBook(book.id)
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            var last = -1
            body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        if (total > MAX_FILE_BYTES) throw IOException("词库文件过大，已中止下载")
                        if (book.bytes > 0) {
                            val percent = ((total * 100) / book.bytes.toLong()).toInt().coerceIn(0, 100)
                            if (percent != last) { last = percent; onProgress(percent) }
                        }
                    }
                }
            }
            if (book.bytes > 0 && temp.length() != book.bytes.toLong()) {
                throw IOException("词库大小对不上：应下 ${book.bytes} 字节，实到 ${temp.length()} 字节")
            }
            if (book.sha256.isNotBlank() && sha256Of(temp) != book.sha256.lowercase()) {
                throw IOException("词库校验不通过，请重新下载")
            }
            val final = file(context, book)
            if (!temp.renameTo(final)) throw IOException("词库无法写入本机")
            onProgress(100)
            final
        } finally {
            temp.takeIf { it.exists() }?.delete()
        }
    }

    /** 删掉本机的一本词库（学习进度不动，重新下载后接着背）。 */
    fun remove(context: Context, book: EnglishBook) {
        runCatching { file(context, book).delete() }
    }

    private fun saveCatalog(context: Context, books: List<EnglishBook>) {
        runCatching {
            dir(context).apply { mkdirs() }
            File(dir(context), CATALOG_FILE).writeText(Gson().toJson(books))
        }.onFailure { Log.w(TAG, "词库清单缓存失败：${it.message}") }
    }

    private fun cached(context: Context): List<EnglishBook>? = runCatching {
        val file = File(dir(context), CATALOG_FILE)
        if (!file.exists()) return null
        Gson().fromJson<List<EnglishBook>>(file.readText(), object : TypeToken<List<EnglishBook>>() {}.type)
    }.getOrNull()

    /** 出厂清单：只有 id/名称/分类/词数，没有大小与指纹 —— 走这条路时下载后不校验指纹。 */
    private fun bundled(context: Context): List<EnglishBook> = runCatching {
        context.assets.open("english/catalog.json").bufferedReader().use { reader ->
            Gson().fromJson<List<EnglishBook>>(reader, object : TypeToken<List<EnglishBook>>() {}.type)
        }
    }.getOrDefault(emptyList())

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 随包清单的 JSON 外形。字段全部可空：Gson 绕开构造器，缺字段读成 null 而不是崩。 */
    private data class BundledManifest(
        val bundleVersion: Int = 0,
        val bundleId: String? = null,
        val insertAfter: String? = null,
        val books: List<EnglishBook>? = null,
    )

    /**
     * 服务端的一条转成词库。**不合法返回 null**（缺 id 或词数为 0 的一本宁可丢掉）：
     * 少一本用户还有 12 本可选，多一本「点了没反应」的词库最难排查。
     */
    private fun EnglishBookDto.toBook(): EnglishBook? {
        val id = id?.takeIf { it.isNotBlank() } ?: return null
        val count = count ?: 0
        if (count <= 0) return null
        return EnglishBook(
            id = id,
            name = name?.takeIf { it.isNotBlank() } ?: id,
            category = category?.takeIf { it.isNotBlank() } ?: "未分类",
            count = count,
            revision = revision.orEmpty(),
            asset = "$id.book",
            bytes = bytes ?: 0,
            sha256 = sha256?.lowercase().orEmpty(),
            // 出处三项照搬，**不做「从 source 里抠出许可证」这种猜测** ——
            // 老服务端把它们拼在一条字符串里，猜错了就是编造许可信息，比留空更糟。
            source = source.orEmpty(),
            license = license.orEmpty(),
            sourceUrl = sourceUrl.orEmpty(),
        )
    }
}

/** 字符串字段的归一：Gson 绕开构造器时缺字段会是 null，不能直接当非空 String 用。 */
private fun String?.trimmed(): String = this?.trim().orEmpty()

/**
 * 随包清单里可用的词书：字段不全、指纹不像 sha256、同 id 重复的一律丢掉。
 *
 * 宁可少显示一本，也不让列表里出现「点开没反应」的一行 —— 与云端清单 `toBook()` 同一个口径。
 */
internal fun validBundledBooks(books: List<EnglishBook>?): List<EnglishBook> {
    val result = ArrayList<EnglishBook>()
    val seen = HashSet<String>()
    for (candidate in books.orEmpty()) {
        val id = candidate.id.trimmed()
        val name = candidate.name.trimmed()
        val revision = candidate.revision.trimmed()
        val asset = candidate.asset.trimmed()
        val sha = candidate.sha256.trimmed().lowercase()
        if (id.isEmpty() || name.isEmpty() || revision.isEmpty() || asset.isEmpty()) continue
        if (candidate.count <= 0 || candidate.bytes <= 0) continue
        if (sha.length != 64 || sha.any { it !in '0'..'9' && it !in 'a'..'f' }) continue
        if (!seen.add(id)) continue
        result.add(candidate.copy(
            id = id, name = name, revision = revision, asset = asset, sha256 = sha,
            category = candidate.category.trimmed().ifEmpty { "未分类" },
            source = candidate.source.trimmed(), license = candidate.license.trimmed(),
            sourceUrl = candidate.sourceUrl.trimmed(),
        ))
    }
    return result
}

/**
 * 把随包词书合并进云端/缓存清单：**只补 id 不重复的书，其余书原样保留、顺序不动**。
 *
 * `insertAfter` 命中时插到那本后面（让精简书挨着完整四级/六级显示），否则追加到末尾。
 * 同 id 时以云端/缓存清单为准 —— 服务端上架的书优先，随包只是兜底。
 */
internal fun mergeBundledBooks(base: List<EnglishBook>, extras: List<EnglishBook>, insertAfter: String?): List<EnglishBook> {
    if (extras.isEmpty()) return base
    val known = base.mapTo(HashSet()) { it.id }
    val fresh = extras.filter { it.id !in known }
    if (fresh.isEmpty()) return base
    val anchor = base.indexOfFirst { it.id == insertAfter }
    return if (anchor < 0) base + fresh
    else base.subList(0, anchor + 1) + fresh + base.subList(anchor + 1, base.size)
}
