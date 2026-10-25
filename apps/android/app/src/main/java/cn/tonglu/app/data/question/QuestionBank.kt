package cn.tonglu.app.data.question

import android.content.Context
import android.util.Log
import cn.tonglu.app.data.QuestionBankDto
import cn.tonglu.app.data.TongluApi
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val TAG = "QuestionBank"
private val SUBJECTIVE_BANK_ID = Regex("^cet[46]-20\\d{2}-(06|12)-[1-3]-(writing|translation)$")

/**
 * 难度层（docs/plan/56 §4.4）。**只用于抽题与提示强弱，不承诺对应任何考试分数。**
 *
 * 取值的语义是「这题在这个题库内部的相对位置」，不是 CEFR 等级、不是四级分值。
 */
object Difficulty {
    const val BASIC = "basic"
    const val CORE = "core"
    const val ADVANCED = "advanced"

    /** 抽题时用的固定顺序（由易到难）。 */
    val order = listOf(BASIC, CORE, ADVANCED)

    fun labelOf(value: String): String = when (value) {
        BASIC -> "基础"
        CORE -> "核心"
        ADVANCED -> "进阶"
        else -> "未分层"
    }

    fun isValid(value: String): Boolean = value in order
}

/**
 * 题库里的一道题（docs/plan/57 §2.1）。
 *
 * 与材料自带题（`ListeningQuestion`）的区别：这里的题**不依赖任何材料**，
 * 多数没有 `segmentId`，真题可以通过 `audioUrl` 绑定原版录音，仍可脱离「听说」独立做题。
 */
data class BankQuestion(
    val id: String,
    val type: String = "single-choice",
    val prompt: String = "",
    val options: List<String> = emptyList(),
    /** 正确答案下标；`-1` 表示不可判分。 */
    val answer: Int = -1,
    val explanation: String = "",
    /** 见 [Difficulty]；取值非法时按「未分层」处理，不抛异常。 */
    val difficulty: String = Difficulty.BASIC,
    /** 该题绑定的原文句（可选）。独立题库的题多数为空。 */
    val segmentId: String = "",
    /** 自编听力的合成朗读文本；空表示普通文字题，不伪装原音。 */
    val audioText: String? = null,
    /**
     * **原版录音**直链（真题听力用）。与 [audioText] 是**两条互斥的路**：
     * - `audioUrl` 有值 → 放原音，界面明写「原版录音」；
     * - 只有 `audioText` → 走合成朗读，界面明写这是合成音。
     *
     * 🔴 绝不允许把合成音填进 `audioUrl` 冒充原音（docs/BUGS 与 HANDOFF §八 的红线）。
     */
    val audioUrl: String? = null,
    /**
     * 该题在 [audioUrl] 这条音轨里的**材料起点**与**提问结束**（毫秒）。
     *
     * 句子级时间轴折算而来（见 `scripts/cet_listening_timeline.py`），所以能「重听本题」：
     * 从材料开头听到本题提问念完。**`-1` = 没有时间轴**，此时界面不给这个按钮。
     * 区间是否可用由 [cn.tonglu.app.data.listening.AudioRange.clamp] 统一收口。
     */
    val audioStartMs: Int = -1,
    val audioEndMs: Int = -1,
) {
    val gradable: Boolean get() = type == "single-choice" && options.size >= 2 && answer in options.indices

    /** 题号与题库 id 都不得含冒号 —— 否则题目键会歧义（docs/plan/57 §一）。 */
    val keySafe: Boolean get() = id.isNotBlank() && !id.contains(':')
}

/**
 * 一个题库（docs/plan/57 §2.1）。
 *
 * `id` 是**稳定标识**，一旦发布不能改 —— 用户作答记录按 `<id>:<questionId>` 存，
 * 改了 id 等于把所有人的历史记录变成孤儿。
 */
data class QuestionBank(
    val id: String,
    val title: String,
    val subject: String = "english",
    val level: String = "",
    /** 来源说明：必须区分自编练习与真题整理件，不冒充官方发布。 */
    val sourceNote: String = "",
    val license: String = "",
    /**
     * 原卷/资料出处直链（后端 `sourceUrl`）。**只接受 http(s)** —— 别的 scheme 一律当没有，
     * 免得被塞个 `file://` / `intent://` 进来。界面据此给一个「下载原卷」入口。
     */
    // Gson 读取旧题库会绕过默认值；缺字段必须允许 null。
    val sourceUrl: String? = null,
    val version: Int = 1,
    val questions: List<BankQuestion>? = null,
    /**
     * 远端元数据：题面还没下载时，列表要显示「约 12 KB」与「下载」，
     * 下载时还要拿 [remoteSha256] 校验。本机已下载的题库这两项为 0 / 空。
     */
    val remoteBytes: Int = 0,
    val remoteSha256: String = "",
) {
    /** 旧题库可缺原卷链接；外部入口仍只允许 HTTP(S)。 */
    val sourceLink: String? get() = sourceUrl?.trim()?.takeIf {
        it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true)
    }

    /**
     * 安全的题目列表。**声明可空是故意的**：Gson 绕过 Kotlin 构造器，
     * JSON 里缺这个键时会把非空 List 塞成 null（本项目已记过多次）。
     */
    val questionList: List<BankQuestion> get() = questions ?: emptyList()

    val size: Int get() = questionList.size

    /**
     * 这是一个「远端有、本机还没下载」的条目：有来源说明但没有题面。
     * 界面据此把卡片显示成「待下载」而不是「空题组」。
     */
    val remotePending: Boolean get() = questionList.isEmpty() && sourceNote.isNotBlank() && remoteBytes > 0

    /** 各难度层的题数，给诊断与列表页显示用。 */
    fun countOf(difficulty: String): Int = questionList.count { it.difficulty == difficulty }

    /** 这个题库的 id 能不能安全拼题目键。 */
    val keySafe: Boolean get() = id.isNotBlank() && !id.contains(':')

    /** 一道题的全局键（docs/plan/57 §一）：`<bankId>:<questionId>`。 */
    fun keyOf(question: BankQuestion): String = "$id:${question.id}"

    /**
     * 这个题库值不值得展示。三道闸，任一不过就别摆出来：
     *  1. 题库 id 与每题题号都不含冒号（否则题目键有歧义）；
     *  2. 至少有可判分题，或已发布写作/翻译题型的完整练习题干；
     *  3. 来源说明不能为空 —— 打标红线，说不清来源的题库不许上。
     */
    val usable: Boolean get() =
        keySafe && sourceNote.isNotBlank() && questionList.any {
            it.keySafe && (it.gradable || (subjective && it.type == "prompt" && it.prompt.isNotBlank()))
        }

    /** 仅放行已知考次写作/翻译题；缺答案的客观题不能伪装成主观题绕校验。 */
    val subjective: Boolean get() = SUBJECTIVE_BANK_ID.matches(id)
}

/**
 * 题库仓库（docs/plan/57 §六）。
 *
 * **三级回退，顺序很重要**（与 `EnglishBookStore` 同一套）：
 *   1. 服务器清单 + 本机已下载的题库（管理员在后台改完立刻生效）
 *   2. 本机缓存的上一份清单（没网时也能列出上次看到的题库）
 *   3. 打包进 APK 的 assets（保证第一次冷启动也不会开天窗）
 *
 * **打开英语页不自动下载**（不替用户花流量）：远端题库出现在列表里，
 * 但题面要用户点「下载」才落到本机；没下载的题库点进去会提示去下载。
 *
 * 失败一律**不崩英语页** —— 读不到就少几个题组，绝不把异常抛给界面。
 */
object QuestionBankStore {

    private const val TAG = "QuestionBank"
    private const val INDEX = "index.json"
    private const val CATALOG_FILE = "catalog.json"
    /** 单个题库文件上限。服务端一份最多 1MB，这里再收一道防异常响应写满磁盘。 */
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024

    private val gson = Gson()

    /** 出货清单：列出所有内置题库的文件名。多一个内置题库只需改这个文件 + 加一个 json。 */
    private data class BankIndex(val banks: List<String>? = null)

    /** 本机已下载题库的落点（`filesDir/questions/downloaded/<id>.json`）。 */
    private fun downloadedDir(context: Context) = File(File(context.filesDir, "questions"), "downloaded")

    private fun downloadedFile(context: Context, id: String) = File(downloadedDir(context), "$id.json")

    /** 题库 id 规则：小写字母数字与连字符，字母开头。与后端 `QUESTION_BANK_ID` 对齐。 */
    private val BANK_ID = Regex("^[a-z][a-z0-9-]{2,63}$")

    /**
     * 全部可用题库（已过滤掉不可用的）。
     *
     * 远端清单 + 本机已下载优先；远端拉不到时退回本机缓存清单，再退回 assets。
     * 顺序按清单声明顺序 —— 顺序有意义（先给基础题组），不排序。
     */
    suspend fun banks(context: Context, api: TongluApi?): List<QuestionBank> = withContext(Dispatchers.IO) {
        val remote = if (api != null) {
            runCatching { api.questionBanks() }.getOrElse { error ->
                Log.i(TAG, "拉题库清单失败，用本机缓存：${error.message}")
                null
            }?.mapNotNull { it.toBankMeta() }
        } else null

        if (!remote.isNullOrEmpty()) {
            saveCatalog(context, remote)
            val listed = remote.mapNotNull { bank ->
                // 远端题库：优先读本机已下载的那份题面；没下载就显示成「待下载」（题目为空）
                loadDownloaded(context, bank.id) ?: bank
            }
            return@withContext listed.filter { it.usable || it.remotePending }
        }

        // 本机缓存清单 → 每个都尽量读本机题面
        val cachedList = cached(context)
        if (!cachedList.isNullOrEmpty()) {
            return@withContext cachedList
                .mapNotNull { loadDownloaded(context, it.id) ?: it }
                .filter { it.usable || it.remotePending }
        }

        // 出厂 assets
        assetsBanks(context)
    }

    /**
     * 兼容旧签名（只读 assets）：给不持有 api 的调用方（如单测、旧界面）用。
     */
    fun banks(context: Context): List<QuestionBank> = assetsBanks(context)

    /**
     * **我的题库**（`docs/plan/58` §4）：只列当前登录用户自己上传的私有题库。
     *
     * 与 [banks] 的关键差别：
     *  - 需要 `bearer`；没登录直接返回 `null`（界面据此**不显示**这一段，而不是显示空列表）。
     *  - **不与公开清单合并** —— 两批题的 scope 不同，混在一起会让用户以为自己的题被别人看到了。
     *  - **失败必须区分「错了」和「空」**：返回 null = 拉不到（网络/401），
     *    返回 `emptyList()` = 拉到了、你确实一份都没传。界面文案完全不同。
     *  - 用 `ownerKey` 分桶缓存，换账号不会看到上一个账号的清单。
     */
    suspend fun myBanks(context: Context, api: TongluApi?, bearer: String?, ownerKey: String): List<QuestionBank>? {
        if (api == null || bearer.isNullOrBlank()) return null
        return withContext(Dispatchers.IO) {
            val remote = runCatching { api.myQuestionBanks(bearer) }.getOrElse { error ->
                Log.i(TAG, "拉我的题库清单失败：${error.message}")
                return@withContext null
            }.mapNotNull { it.toBankMeta() }
            saveMineCatalog(context, ownerKey, remote)
            remote.mapNotNull { bank -> loadDownloaded(context, bank.id) ?: bank }
                .filter { it.usable || it.remotePending }
        }
    }

    /** 上一次成功拉到的「我的题库」清单（离线可见）。没有缓存返回 null。 */
    fun cachedMyBanks(context: Context, ownerKey: String): List<QuestionBank>? = runCatching {
        val file = File(downloadedDir(context), mineCatalogName(ownerKey))
        if (!file.exists()) return null
        gson.fromJson<List<QuestionBank>>(file.readText(), object : com.google.gson.reflect.TypeToken<List<QuestionBank>>() {}.type)
    }.getOrNull()

    /** 全部内置题库（只读 assets，已过滤掉不可用的）。 */
    private fun assetsBanks(context: Context): List<QuestionBank> = try {
        val indexText = context.assets.open("questions/$INDEX").bufferedReader().use { it.readText() }
        val files = gson.fromJson(indexText, BankIndex::class.java)?.banks.orEmpty()
        files.mapNotNull { loadAssetBank(context, it) }.filter { it.usable }
    } catch (cause: Exception) {
        Log.e(TAG, "内置题库清单读取失败", cause)
        emptyList()
    }

    /**
     * 按 id 取一个题库：先本机已下载，再远端清单里的（若已下载了就直接给整份）。
     * 找不到返回 null（界面显示「这个题组不在本机」，而不是空题组）。
     */
    suspend fun bank(context: Context, api: TongluApi?, id: String): QuestionBank? =
        loadDownloaded(context, id) ?: banks(context, api).firstOrNull { it.id == id }

    /** 这个题库的题面是不是已经在本机（决定按钮是「下载」还是「开始做」）。 */
    fun isDownloaded(context: Context, id: String): Boolean =
        runCatching { downloadedFile(context, id).length() > 0 }.getOrDefault(false)

    /** 已下载的题库 id 集合。 */
    fun downloadedIds(context: Context): Set<String> = runCatching {
        downloadedDir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".json") && it.length() > 0 }
            ?.map { it.name.removeSuffix(".json") }?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    /**
     * 下载一份题库并落盘。
     *
     * 三道闸，缺一不可（与词库下载同一套）：
     *  - 边下边写临时文件（`.tmp`），成功才改名 —— 中途断网不会留下半份坏题库；
     *  - 比对服务端给的字节数；
     *  - 比对 sha256 —— 传输出错、运营商插东西、缓存串味都过不去这一关。
     *
     * 下载完**再解析一遍**确认是合法题库（`usable`），否则删掉临时文件报错 ——
     * 不然会出现「下载成功但点进去是空的」这种最难排查的状态。
     */
    suspend fun download(
        context: Context,
        api: TongluApi,
        id: String,
        expectBytes: Int = 0,
        expectSha256: String = "",
        onProgress: (Int) -> Unit = {},
    ): QuestionBank = downloadBinary(context, id, expectBytes, expectSha256, onProgress) {
        api.downloadQuestionBank(id)
    }

    /**
     * 下载一份**自己的**私有题库（`GET /me/question-banks/:id/download`）。
     *
     * 与公开下载**共用同一个落盘实现**（[downloadBinary]）—— 两条路的权限判断在服务端，
     * 客户端这边「边下边写 / 比字节数 / 比 sha256 / 落盘前先解析」必须一字不差，
     * 抄成两份迟早只改一处（这正是 CROSS-END 那条纪律）。
     */
    suspend fun downloadMine(
        context: Context,
        api: TongluApi,
        bearer: String,
        id: String,
        expectBytes: Int = 0,
        expectSha256: String = "",
        onProgress: (Int) -> Unit = {},
    ): QuestionBank = downloadBinary(context, id, expectBytes, expectSha256, onProgress) {
        api.downloadMyQuestionBank(bearer, id)
    }

    private suspend fun downloadBinary(
        context: Context,
        id: String,
        expectBytes: Int,
        expectSha256: String,
        onProgress: (Int) -> Unit,
        fetch: suspend () -> okhttp3.ResponseBody,
    ): QuestionBank = withContext(Dispatchers.IO) {
        if (!BANK_ID.matches(id)) throw IOException("题库 id 不合法")
        val dir = downloadedDir(context).apply { mkdirs() }
        val temp = File(dir, ".$id.${System.currentTimeMillis()}.tmp")
        try {
            val body = fetch()
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
                        if (total > MAX_FILE_BYTES) throw IOException("题库文件过大，已中止下载")
                        if (expectBytes > 0) {
                            val percent = ((total * 100) / expectBytes.toLong()).toInt().coerceIn(0, 100)
                            if (percent != last) { last = percent; onProgress(percent) }
                        }
                    }
                }
            }
            if (expectBytes > 0 && temp.length() != expectBytes.toLong()) {
                throw IOException("题库大小对不上：应下 $expectBytes 字节，实到 ${temp.length()} 字节")
            }
            if (expectSha256.isNotBlank() && sha256Of(temp) != expectSha256.lowercase()) {
                throw IOException("题库校验不通过，请重新下载")
            }
            // 下载完先解析验证，合法才落盘
            val parsed = gson.fromJson(temp.readText(), QuestionBank::class.java)
                ?: throw IOException("题库内容无法解析")
            if (!parsed.usable) throw IOException("下载到的题库不完整，已丢弃")
            val final = downloadedFile(context, id)
            if (!temp.renameTo(final)) throw IOException("题库无法写入本机")
            onProgress(100)
            parsed
        } finally {
            temp.takeIf { it.exists() }?.delete()
        }
    }

    /** 删掉本机的一份题库（作答记录不动）。 */
    fun removeDownloaded(context: Context, id: String) {
        runCatching { downloadedFile(context, id).delete() }
    }

    private fun loadDownloaded(context: Context, id: String): QuestionBank? = runCatching {
        if (!BANK_ID.matches(id)) return null
        val file = downloadedFile(context, id)
        if (!file.exists() || file.length() == 0L) return null
        gson.fromJson(file.readText(), QuestionBank::class.java)?.takeIf { it.usable }
    }.getOrNull()

    private fun saveCatalog(context: Context, banks: List<QuestionBank>) {
        runCatching {
            val dir = downloadedDir(context).apply { mkdirs() }
            File(dir, CATALOG_FILE).writeText(gson.toJson(banks))
        }.onFailure { Log.w(TAG, "题库清单缓存失败：${it.message}") }
    }

    /**
     * 「我的题库」清单缓存文件名**按账号分桶**。
     *
     * `ownerKey` 只用来拼文件名，所以要先把非文件名字符换掉 —— 用户 id 是 uuid、
     * username 可能含点/斜杠，直接当文件名会写到别的目录去（路径穿越）。
     */
    private fun mineCatalogName(ownerKey: String): String {
        val safe = ownerKey.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
        return "catalog-mine-$safe.json"
    }

    private fun saveMineCatalog(context: Context, ownerKey: String, banks: List<QuestionBank>) {
        runCatching {
            val dir = downloadedDir(context).apply { mkdirs() }
            File(dir, mineCatalogName(ownerKey)).writeText(gson.toJson(banks))
        }.onFailure { Log.w(TAG, "我的题库清单缓存失败：${it.message}") }
    }

    private fun cached(context: Context): List<QuestionBank>? = runCatching {
        val file = File(downloadedDir(context), CATALOG_FILE)
        if (!file.exists()) return null
        gson.fromJson<List<QuestionBank>>(file.readText(), object : com.google.gson.reflect.TypeToken<List<QuestionBank>>() {}.type)
    }.getOrNull()

    private fun loadAssetBank(context: Context, fileName: String): QuestionBank? = try {
        // 路径护栏：文件名里不许出现分隔符（有 ../ 就能读到 assets 之外）。
        // 刻意不写 contains("..") —— 上游真出现过正常文件名带 "..." 被误伤的情况。
        if (fileName.isBlank() || fileName.split('/').any { it == "." || it == ".." }) {
            Log.w(TAG, "题库文件名不合法，已跳过：$fileName")
            null
        } else {
            val text = context.assets.open("questions/$fileName").bufferedReader().use { it.readText() }
            gson.fromJson(text, QuestionBank::class.java)
        }
    } catch (cause: IOException) {
        Log.e(TAG, "题库 $fileName 读取失败", cause)
        null
    } catch (cause: Exception) {
        Log.e(TAG, "题库 $fileName 解析失败", cause)
        null
    }

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

    /**
     * 服务端的一条转成题库**元数据**（题目为空，等下载或已有本机副本）。
     * 缺 id / 缺来源说明 / id 不合规的**直接丢掉** —— 打标红线，说不清出处的不上架。
     */
    private fun QuestionBankDto.toBankMeta(): QuestionBank? {
        val bankId = id?.takeIf { it.isNotBlank() } ?: return null
        if (!BANK_ID.matches(bankId)) return null
        val note = sourceNote?.trim().orEmpty()
        if (note.isBlank()) return null
        return QuestionBank(
            id = bankId,
            title = title?.takeIf { it.isNotBlank() } ?: bankId,
            subject = subject?.takeIf { it.isNotBlank() } ?: "english",
            level = level?.takeIf { it.isNotBlank() }.orEmpty(),
            sourceNote = note,
            license = license?.takeIf { it.isNotBlank() }.orEmpty(),
            // 只认 http(s)：服务端字段是自由文本，别让它把奇怪的 scheme 带进界面。
            sourceUrl = sourceUrl?.trim()
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty(),
            version = version ?: 1,
            questions = null,
            remoteBytes = bytes ?: 0,
            remoteSha256 = sha256?.lowercase().orEmpty(),
        )
    }
}
