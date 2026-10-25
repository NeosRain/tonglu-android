package cn.tonglu.app.data.listening

import android.content.Context
import android.util.Log
import cn.tonglu.app.data.LearningPackDto
import cn.tonglu.app.data.TongluApi
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val TAG = "LearningPacks"

/** 一份已下载资料包在清单里的记录（`packs/index.json`），用来算「已加入 / 可更新」。 */
data class InstalledPack(
    val packageId: String,
    val version: Int,
    /** 入库后 `ListeningMaterial.id`，用来跳去学习工作区。 */
    val materialId: String,
    /** 入库那一刻的资产指纹（manifest 的 sha256），版本没变就不重下。 */
    val manifestSha256: String = "",
    val installedAt: Long = System.currentTimeMillis(),
)

/** 资料包清单里的一本可学内容（客户端视图模型，不是网络 DTO）。 */
data class LearningPack(
    val packageId: String,
    val version: Int,
    val title: String,
    val summary: String,
    val language: String,
    val level: String,
    val author: String,
    val sourceUrl: String,
    val license: String,
    val licenseUrl: String,
    val attribution: String,
    val capabilities: List<String>,
    /** 整包字节总数（manifest + 资产），只用来显示大小。 */
    val bytes: Long,
    /** manifest 文件自身的 sha256，用来判断「服务器上这一包换没换」。 */
    val manifestSha256: String,
) {
    val hasAudio: Boolean get() = capabilities.contains("listening") || capabilities.contains("shadowing")
    val hasExercises: Boolean get() = capabilities.contains("exercises")
    /** 界面上的能力短语，如「精听 · 跟读 · 练习」。 */
    val capabilityLabel: String get() = capabilities.mapNotNull { CAPABILITY_LABEL[it] }.distinct().joinToString(" · ")
    val sizeLabel: String
        get() = when {
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }

    companion object {
        private val CAPABILITY_LABEL = mapOf(
            "listening" to "精听", "shadowing" to "跟读",
            "reading" to "阅读", "exercises" to "练习",
        )
    }
}

// ---------------------------------------------------------------- manifest 协议

/** 44 §六 的最小包协议。字段全可空，因为 manifest 来自网络；校验在 `validate()` 里做。 */
internal data class PackManifest(
    val schemaVersion: Int = 0,
    val packageId: String = "",
    val version: Int = 0,
    val title: String = "",
    val summary: String = "",
    val language: String = "en",
    val level: String = "",
    val author: String = "",
    val sourceUrl: String = "",
    val license: String = "",
    val licenseUrl: String = "",
    val attribution: String = "",
    val capabilities: List<String> = emptyList(),
    val assets: List<PackAsset> = emptyList(),
    val segments: List<PackSegment> = emptyList(),
    val exercises: List<PackExercise> = emptyList(),
)

internal data class PackAsset(
    val id: String = "", val kind: String = "", val path: String = "",
    val mime: String = "", val bytes: Long = 0, val sha256: String = "", val durationMs: Long = 0,
)

internal data class PackSegment(
    val id: String = "", val text: String = "",
    val startMs: Long = -1, val endMs: Long = -1, val voice: String = "",
)

internal data class PackExercise(
    val id: String = "", val type: String = "", val segmentId: String = "",
    val prompt: String = "", val options: List<String> = emptyList(),
    val answer: Int = -1, val explanation: String = "",
)

/** 客户端认识的协议版本。**只认 1**：见到更高的版本宁可拒绝，也不要用旧解析器猜新格式。 */
private const val SUPPORTED_SCHEMA = 1

/** 单个资产的硬上限（服务端整包上限 64MB，这里按资产再收一道，防异常响应写满磁盘）。 */
private const val MAX_ASSET_BYTES = 64L * 1024 * 1024
/** 整包上限。服务端 64MB，客户端同值。 */
private const val MAX_PACK_BYTES = 64L * 1024 * 1024

/**
 * 学习资料包适配器（docs/plan/44 §七 A）：**服务器下载 → 一键学习**。
 *
 * 职责单一，只做四件事：
 *   1. 拉清单（对象存储无关，走我们自己的后端 `learning/packs`）；
 *   2. 下载 manifest 与资产到**临时目录**；
 *   3. 逐项校验（协议版本 / 路径分段 / 字节数 / sha256 / 时间轴）；
 *   4. 全部通过后**原子入库**到已有的 [ListeningLibrary]，复用现有播放器与跟读工作区。
 *
 * 刻意**不重写学习工作区**：包里的句子直接落成 `ListeningMaterial`，
 * 带真实时间轴（`startMs/endMs` 来自服务端逐句合成，不是按字数估的），
 * 所以用户下完就能听、跟读、做题。手工导入仍留在学习页作为高级入口。
 *
 * 「点了没反应」和「下了一半」是两个最容易出的事故，各自的闸：
 * - 下载全在 `.staging-*` 目录，中途失败整目录删掉 —— 不会出现半份可学的材料；
 * - 同一个包重复点「加入学习」，只要版本与指纹没变就是**幂等**：不重下、不覆盖进度。
 */
object LearningPacks {
    private val gson = Gson()

    /** 资料包落盘的根目录（在 `filesDir` 下，跟听力库分开，导入失败不影响已有材料）。 */
    private fun dir(context: Context) = File(context.filesDir, "learning-packs")
    private fun indexFile(context: Context) = File(dir(context), "index.json")

    // ------------------------------------------------------------ 清单

    /**
     * 拉可学的资料包清单。
     *
     * 跟词库一致的策略：网络失败就用本机缓存的上一份清单（离线也能看到自己下过什么）。
     * `api == null`（还没初始化网络层）时只读缓存。
     */
    suspend fun catalog(context: Context, api: TongluApi?): List<LearningPack> = withContext(Dispatchers.IO) {
        if (api != null) {
            val remote = runCatching { api.learningPacks() }.getOrElse { error ->
                Log.i(TAG, "拉资料包清单失败，用本机缓存：${error.message}")
                null
            }?.mapNotNull { it.toPack() }
            if (!remote.isNullOrEmpty()) {
                saveCatalog(context, remote)
                return@withContext remote
            }
        }
        cachedCatalog(context)
    }

    private fun saveCatalog(context: Context, packs: List<LearningPack>) {
        runCatching {
            dir(context).apply { mkdirs() }
            File(dir(context), "catalog.json").writeText(gson.toJson(packs))
        }.onFailure { Log.w(TAG, "资料包清单缓存失败：${it.message}") }
    }

    private fun cachedCatalog(context: Context): List<LearningPack> {
        // 不用 runCatching 包 return：内联 lambda 里的非局部 return 会让类型推断失效
        // （Kotlin 报 Cannot infer type for type parameter 'R'），写成普通 try 更直白。
        return try {
            val file = File(dir(context), "catalog.json")
            if (!file.exists()) emptyList()
            else gson.fromJson<List<LearningPack>>(
                file.readText(), object : com.google.gson.reflect.TypeToken<List<LearningPack>>() {}.type,
            ) ?: emptyList()
        } catch (cause: Exception) {
            Log.w(TAG, "资料包清单缓存读取失败：${cause.message}")
            emptyList()
        }
    }

    // ------------------------------------------------------------ 已安装记录

    fun installed(context: Context): List<InstalledPack> {
        return try {
            val file = indexFile(context)
            if (!file.exists()) emptyList()
            else gson.fromJson<List<InstalledPack>>(
                file.readText(), object : com.google.gson.reflect.TypeToken<List<InstalledPack>>() {}.type,
            ) ?: emptyList()
        } catch (cause: Exception) {
            Log.w(TAG, "资料包记录读取失败：${cause.message}")
            emptyList()
        }
    }

    /** 这个包在本机装的是哪一版；没装过返回 null。 */
    fun installedOf(context: Context, packageId: String): InstalledPack? =
        installed(context).firstOrNull { it.packageId == packageId }

    /**
     * 界面按钮态：没下过 / 已下过可继续 / 服务器有新版。
     * `已下过` 只说明记录在，还要确认那份 `ListeningMaterial` 真的还在
     * （用户可能在学习页把材料删了）—— 删了就当没装，让他重新加入。
     *
     * **还要检查「题没补齐」**：早于 P2 的版本入库的材料里没有 `questions`
     * （老 JSON 根本没这个键），而包声明了 exercises。这种"装了但缺内容"的
     * 状态必须显示成可更新的按钮，否则用户永远点不到补题那一步，
     * 界面却一直写着「已加入」。
     */
    fun state(context: Context, library: ListeningLibrary, pack: LearningPack): PackState {
        val row = installedOf(context, pack.packageId) ?: return PackState.Available
        val material = library.list().firstOrNull { it.id == row.materialId }
        if (material == null) return PackState.Available
        if (row.version != pack.version) return PackState.Update(row.version, pack.version)
        if (pack.hasExercises && material.questionList.isEmpty()) {
            // 同版本、但本机缺题目 → 让用户点一次把内容补齐（按钮会写「补齐内容」）。
            return PackState.Update(row.version, pack.version, repair = true)
        }
        return PackState.Installed(row)
    }

    sealed interface PackState {
        /** 从没加入过（或本机那份材料被删了）—— 按钮显示「加入学习」。 */
        object Available : PackState
        /** 已经装好了 —— 按钮显示「开始学习 / 继续学习」。 */
        data class Installed(val row: InstalledPack) : PackState
        /**
         * 需要重新下载：`newVersion > installedVersion` 是版本升级；
         * 两者相等而 `repair == true` 表示同版本内容不全（早期入库缺题目），只补内容。
         */
        data class Update(val installedVersion: Int, val newVersion: Int, val repair: Boolean = false) : PackState
    }

    // ------------------------------------------------------------ 下载 + 校验 + 入库

    /**
     * 把一个资料包下载、校验并入库。
     *
     * @param onStage 阶段回调，给界面显示进度。`progress` 是 -1 表示这一阶段没有确定进度。
     * @return 入库后的 [ListeningMaterial]，界面拿它的 id 直接进学习工作区。
     * @throws IOException 任一项校验没过 —— 抛出的消息是**给用户看的**，不要塞技术细节。
     */
    suspend fun install(
        context: Context,
        api: TongluApi,
        library: ListeningLibrary,
        pack: LearningPack,
        onStage: (PackStage) -> Unit = {},
    ): ListeningMaterial = withContext(Dispatchers.IO) {
        // 幂等第一道：本机已经是这一版、材料还在 → 直接返回，一次网络请求都不发。
        // **例外**：包声明了 exercises 但本机那份材料里没有题目 —— 说明是早期版本
        // 入库时还没接题目（老材料的 JSON 根本没 questions 字段）。这种情况必须
        // 重下一遍把题目补上，否则用户永远看不到题，而界面还写着「已安装」。
        val existing = installedOf(context, pack.packageId)
        if (existing != null && existing.version == pack.version &&
            existing.manifestSha256.isNotBlank() && existing.manifestSha256 == pack.manifestSha256
        ) {
            library.list().firstOrNull { it.id == existing.materialId }?.let { cached ->
                val needsRepair = pack.hasExercises && cached.questionList.isEmpty()
                if (!needsRepair) {
                    Log.i(TAG, "资料包 ${pack.packageId}@${pack.version} 已在本机，跳过下载")
                    onStage(PackStage.Done(0, 0))
                    return@withContext cached
                }
                Log.i(TAG, "资料包 ${pack.packageId}@${pack.version} 缺题目，重下补齐")
            }
        }

        val root = dir(context).apply { mkdirs() }
        val staging = File(root, ".staging-${pack.packageId}-${System.currentTimeMillis()}")
        try {
            staging.mkdirs()

            // 1) manifest —— 先落临时文件，校验完再用它带来的 asset 列表下载
            onStage(PackStage.FetchingManifest)
            val manifestBytes = api.learningPackManifest(pack.packageId, pack.version).use { it.bytes() }
            if (manifestBytes.isEmpty()) throw IOException("这个资料包的清单是空的")
            if (manifestBytes.size > 1024 * 1024) throw IOException("资料包清单异常过大")
            val manifestRaw = manifestBytes.toString(Charsets.UTF_8)
            val manifest = runCatching { gson.fromJson(manifestRaw, PackManifest::class.java) }
                .getOrElse { throw IOException("资料包清单格式不对，无法解析") }
                ?: throw IOException("资料包清单为空")

            // 2) 校验 manifest 自身 —— 这一关不过就别浪费流量下资产
            validate(manifest, pack)

            // 3) 下载资产（音频等）到临时目录，边下边算 sha256
            val audio = manifest.assets.firstOrNull { it.kind == "audio" }
                ?: throw IOException("这个资料包没有音频，暂时不能加入学习")
            val audioFile = File(staging, "audio.${audio.path.substringAfterLast('.', "m4a")}")
            onStage(PackStage.Downloading(0, audio.bytes))
            downloadAsset(api, pack, audio, audioFile) { done, total ->
                onStage(PackStage.Downloading(done, total))
            }

            // 4) 原子入库 —— 先把音频搬进听力库目录，再写材料记录
            onStage(PackStage.Installing)
            val material = buildMaterial(context, library, manifest, audio, audioFile)
            // 关键：如果本机已经有一份同 id 的材料（之前装过、或用户在更新），
            // **必须把用户的跟读录音 / 已练标记 / 生词搬过去**，不能直接 `add` 覆盖 ——
            // 那样用户一更新就把自己的练习全清了，而界面只会显示「已更新」。
            val previous = library.list().firstOrNull { it.id == material.id }
            library.add(if (previous == null) material else mergeProgress(previous, material))

            // 5) 记账
            val row = InstalledPack(
                packageId = pack.packageId, version = pack.version,
                materialId = material.id, manifestSha256 = pack.manifestSha256,
            )
            saveIndex(context, installed(context).filter { it.packageId != pack.packageId } + row)

            onStage(PackStage.Done(audio.bytes, audio.bytes))
            Log.i(TAG, "资料包已入库 ${pack.packageId}@${pack.version}：${material.sentences.size} 句 / ${audio.bytes} 字节")
            material
        } catch (error: Exception) {
            if (error is IOException) throw error else throw IOException(
                "资料包加入失败：${error.message ?: error.javaClass.simpleName}"
            )
        } finally {
            // 不管成功失败都清掉暂存目录：成功的资产已经搬走，失败的不能留下半个包。
            runCatching { staging.deleteRecursively() }
        }
    }

    /** 边下边写 + 边算 sha256，最后对字节数与指纹。任何一项不符都不落盘。 */
    private suspend fun downloadAsset(
        api: TongluApi, pack: LearningPack, asset: PackAsset, dest: File, onProgress: (Long, Long) -> Unit,
    ) {
        if (asset.bytes <= 0) throw IOException("资料包里的 ${asset.path} 没有声明大小")
        if (asset.bytes > MAX_ASSET_BYTES) throw IOException("资源过大，已中止下载")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        var lastPercent = -1
        api.downloadLearningPackAsset(pack.packageId, asset.id, pack.version).use { body ->
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        total += read
                        if (total > MAX_ASSET_BYTES) throw IOException("资源过大，已中止下载")
                        val percent = ((total * 100) / asset.bytes).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) { lastPercent = percent; onProgress(total, asset.bytes) }
                    }
                }
            }
        }
        if (total != asset.bytes) {
            throw IOException("下载不完整：应下 ${asset.bytes} 字节，实到 $total 字节")
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (asset.sha256.isNotBlank() && actual != asset.sha256.lowercase()) {
            throw IOException("资源校验不通过，请重新下载")
        }
    }

    /**
     * 把 manifest 的句子与音频装成一份 [ListeningMaterial]。
     *
     * **时间轴直接取 manifest 的真实 startMs/endMs**（服务端逐句合成拿到的句界，44 §五），
     * 不做任何按字数均分 —— 那正是 37 §六 明令禁止的假对齐。
     * 材料 id 用 `pack-<packageId>`，同一个包重复加入不会产生第二份材料。
     */
    private fun buildMaterial(
        context: Context, library: ListeningLibrary, manifest: PackManifest, audio: PackAsset, staged: File,
    ): ListeningMaterial {
        val ext = audio.path.substringAfterLast('.', "m4a").takeIf { it.length in 1..5 } ?: "m4a"
        val audioName = "pack-${manifest.packageId}-v${manifest.version}.$ext"
        val target = library.audioFile(audioName)
        library.audioDir.mkdirs()
        // 先复制到最终位置再改名，避免目标存在时半覆盖
        val tmp = File(library.audioDir, "$audioName.tmp")
        staged.copyTo(tmp, overwrite = true)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw IOException("音频无法写入本机存储")
        if (target.length() != audio.bytes) throw IOException("音频落地后大小对不上")

        val sentences = manifest.segments.map { seg ->
            ListeningSentence(text = seg.text, startMs = seg.startMs)
        }
        // 题目一并落进材料（47 §3 P2 第一条：接通已存在的题，而不是只把句子搬进来）。
        // 只带走客户端真认识、且答案合法的题：`gradable` 为假的一律标在心里，
        // 界面只展示题面、不给作答 —— 不假装能做。
        val questions = manifest.exercises.map { ex ->
            ListeningQuestion(
                id = ex.id, type = ex.type, segmentId = ex.segmentId,
                prompt = ex.prompt, options = ex.options, answer = ex.answer,
                explanation = ex.explanation,
            )
        }
        return ListeningMaterial(
            id = "pack-${manifest.packageId}",
            title = manifest.title.ifBlank { manifest.packageId },
            audio = audioName,
            audioName = audio.audioNameLabel(),
            sentences = sentences,
            durationMs = audio.durationMs,
            words = emptyList(),
            questions = questions,
            // 来源说明原样带过来，界面照实标注（合成语音 / 公共领域），不冒充真题。
            attribution = manifest.attribution,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** 界面上「原音」那一栏显示的来源名，说明这是平台发的资料包而不是用户自己导入的文件。 */
    private fun PackAsset.audioNameLabel(): String =
        path.substringAfterLast('/').ifBlank { "audio.$mime" }

    /**
     * 更新同一份材料时，把**用户的练习**搬进新版本，其余字段以新包为准。
     *
     * 按句子文本配对而不是按下标：新版本可能增删句子，按下标配会把录音错挂到别的句子上。
     * 文本对不上的句子（新包改过措辞）就丢掉它那条录音 —— 留一条挂错句子的录音比丢掉更糟。
     */
    private fun mergeProgress(old: ListeningMaterial, fresh: ListeningMaterial): ListeningMaterial {
        val oldByText = old.sentences.associateBy { it.text }
        val merged = fresh.sentences.map { sentence ->
            val prior = oldByText[sentence.text] ?: return@map sentence
            sentence.copy(recording = prior.recording, practiced = prior.practiced)
        }
        return fresh.copy(
            sentences = merged,
            words = (old.words + fresh.words).distinct(),
            // 题目以新包为准（改版可能修错答案、加题）；用户作答记录**不在材料里**，
            // 存在 PracticeStore 里按 questionId 存，所以不受这里影响，更新不丢错题。
            questions = fresh.questionList,
            attribution = fresh.sourceNote.ifBlank { old.sourceNote },
            createdAt = minOf(old.createdAt, fresh.createdAt),
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun saveIndex(context: Context, rows: List<InstalledPack>) {
        runCatching {
            dir(context).apply { mkdirs() }
            val tmp = File(dir(context), "index.json.tmp")
            tmp.writeText(gson.toJson(rows))
            if (!tmp.renameTo(indexFile(context))) tmp.copyTo(indexFile(context), overwrite = true)
            if (tmp.exists()) tmp.delete()
        }.onFailure { Log.w(TAG, "资料包记录写入失败：${it.message}") }
    }

    // ------------------------------------------------------------ 校验

    /**
     * manifest 校验，照着 44 §六 那串来做。
     *
     * 每一条都**必须**在下载资产之前跑完：不合格就别浪费用户流量。
     * 校验失败抛的消息是给用户看的，说清楚「这个包不能用」，不要暴露内部路径。
     */
    private fun validate(manifest: PackManifest, pack: LearningPack) {
        // 版本兼容：只认 1。见到更高的版本宁可拒绝，也不要用旧解析器猜新格式。
        if (manifest.schemaVersion != SUPPORTED_SCHEMA) {
            throw IOException("资料包版本不受支持（需要 App 更新）")
        }
        if (manifest.packageId.isBlank() || manifest.packageId != pack.packageId) {
            throw IOException("资料包标识对不上，已中止")
        }
        if (manifest.version <= 0 || manifest.version != pack.version) {
            throw IOException("资料包版本对不上，已中止")
        }
        if (manifest.title.isBlank()) throw IOException("资料包缺少标题")
        if (manifest.capabilities.isEmpty()) throw IOException("资料包没有声明任何学习能力")
        if (manifest.assets.isEmpty()) throw IOException("资料包没有任何资源文件")

        // assets：路径必须合法（分段防越界）、大小为正、sha256 齐全、总量不超上限
        var total = 0L
        val audio = manifest.assets.firstOrNull { it.kind == "audio" }
            ?: throw IOException("这个资料包没有音频")
        for (asset in manifest.assets) {
            if (asset.id.isBlank()) throw IOException("资料包里有资源缺少标识")
            if (!isSafeRelativePath(asset.path)) throw IOException("资料包里的文件路径不合法")
            if (asset.bytes <= 0) throw IOException("资料包里的 ${asset.path} 没有声明大小")
            if (asset.sha256.isBlank()) throw IOException("资料包里的 ${asset.path} 缺少校验指纹")
            if (!asset.sha256.matches(Regex("^[0-9a-fA-F]{64}$"))) throw IOException("资料包里的 ${asset.path} 校验指纹格式不对")
            total += asset.bytes
            if (total > MAX_PACK_BYTES) throw IOException("资料包过大，已中止")
        }
        // 44 §六：不允许可执行/脚本类资产混进来（哪怕 manifest 声明了，类型也得是内容）
        for (asset in manifest.assets) {
            val ext = asset.path.substringAfterLast('.', "").lowercase()
            if (ext in FORBIDDEN_EXT) throw IOException("资料包里含不支持的文件类型：.$ext")
        }

        // segments：时间轴必须真实（不小于 0、结束晚于开始），且落在音频时长内
        if (manifest.capabilities.contains("listening") && manifest.segments.isEmpty()) {
            throw IOException("这个资料包声明了精听，却没有句子时间轴")
        }
        val segmentIds = HashSet<String>()
        var lastEnd = 0L
        for ((index, seg) in manifest.segments.withIndex()) {
            if (seg.id.isBlank()) throw IOException("第 ${index + 1} 句缺少标识")
            if (!segmentIds.add(seg.id)) throw IOException("句子标识重复：${seg.id}")
            if (seg.text.isBlank()) throw IOException("第 ${index + 1} 句没有文字")
            if (seg.startMs < 0 || seg.endMs <= seg.startMs) throw IOException("第 ${index + 1} 句的时间轴不合法")
            if (audio.durationMs > 0 && seg.endMs > audio.durationMs + 1000) {
                throw IOException("第 ${index + 1} 句的时间轴超出音频长度")
            }
            if (seg.startMs < lastEnd - 1) throw IOException("第 ${index + 1} 句和上一句的时间轴重叠了")
            lastEnd = seg.endMs
        }

        // exercises：选择题的答案下标必须有效，且要有解析（44 §六）
        val exerciseIds = HashSet<String>()
        for ((index, ex) in manifest.exercises.withIndex()) {
            if (ex.id.isBlank()) throw IOException("第 ${index + 1} 道题缺少标识")
            if (!exerciseIds.add(ex.id)) throw IOException("题目标识重复：${ex.id}")
            if (ex.type == "single-choice") {
                if (ex.options.size < 2) throw IOException("题目 ${ex.id} 的选项少于 2 个")
                if (ex.answer !in ex.options.indices) throw IOException("题目 ${ex.id} 的答案不在选项里")
                if (ex.explanation.isBlank()) throw IOException("题目 ${ex.id} 缺少解析")
            }
            if (ex.segmentId.isNotBlank() && ex.segmentId !in segmentIds) {
                throw IOException("题目 ${ex.id} 引用了一句不存在的原文")
            }
        }
        if (manifest.capabilities.contains("exercises") && manifest.exercises.isEmpty()) {
            throw IOException("这个资料包声明了练习，却没有题目")
        }
    }

    /**
     * 包内相对路径是否安全。
     *
     * **刻意不用 `contains("..")`**：上游内容里真出现过 `64. 求 1+2+3+...+n.md`
     * 这种正常文件名，被朴素判断误伤（项目已记过这条）。按 `/` 分段判 `.` 与 `..`。
     * 服务端已经拦过一遍，客户端再拦一遍 —— 这里是用户设备，宁可严一点。
     */
    internal fun isSafeRelativePath(path: String): Boolean {
        val cleaned = path.replace('\\', '/').trim()
        if (cleaned.isEmpty()) return false
        if (cleaned.startsWith('/') || Regex("^[a-zA-Z]:").containsMatchIn(cleaned)) return false
        return cleaned.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }

    private val FORBIDDEN_EXT = setOf(
        "sh", "bat", "cmd", "ps1", "exe", "dll", "so", "apk", "jar", "dex", "vbs", "js", "msi", "com", "scr",
    )

    // ------------------------------------------------------------ DTO

    /** 服务端一条转客户端一条。**不合法返回 null**（缺 id/版本/指纹的宁可丢掉）。 */
    private fun LearningPackDto.toPack(): LearningPack? {
        val pid = packageId?.takeIf { it.isNotBlank() } ?: return null
        val v = version?.takeIf { it > 0 } ?: return null
        val caps = capabilities?.filter { it.isNotBlank() } ?: return null
        if (caps.isEmpty()) return null
        val fingerprint = sha256?.lowercase().orEmpty()
        if (fingerprint.isBlank()) return null
        return LearningPack(
            packageId = pid, version = v,
            title = title?.takeIf { it.isNotBlank() } ?: pid,
            summary = summary.orEmpty(),
            language = language?.takeIf { it.isNotBlank() } ?: "en",
            level = level.orEmpty(),
            author = author.orEmpty(), sourceUrl = sourceUrl.orEmpty(),
            license = license.orEmpty(), licenseUrl = licenseUrl.orEmpty(),
            attribution = attribution.orEmpty(),
            capabilities = caps,
            bytes = (bytes ?: 0).toLong(),
            manifestSha256 = fingerprint,
        )
    }
}

/** 安装阶段，界面拿它显示进度文案。 */
sealed interface PackStage {
    /** 正在取清单。 */
    object FetchingManifest : PackStage
    /** 正在下载资源，`total <= 0` 时表示进度不可知。 */
    data class Downloading(val done: Long, val total: Long) : PackStage
    /** 正在写入本机。 */
    object Installing : PackStage
    /** 完成。 */
    data class Done(val bytes: Long, val total: Long) : PackStage
}
