package cn.tonglu.app.data.speech

import android.content.Context
import cn.tonglu.app.data.SpeechProblem
import cn.tonglu.app.data.TongluSpeech

/**
 * 统一声音服务（docs/plan/47 §3 P3）。
 *
 * **调用方只声明「读什么、什么语言、什么口音、谁的声音、多快、干什么用」，不关心背后是谁在发声。**
 * 这是本批的核心：把现在散着的两条链路（系统 TTS 朗读单词 / 媒体播放器播音频）收进同一套词汇，
 * 模型按清单独立下载；云端预生成音频按文本匹配并校验缓存。
 *
 * 三类声音来源（[SoundSource]）：
 * - [SoundSource.SystemTts]：系统 TTS 引擎，需要对应语言的系统语音数据。
 * - [SoundSource.BuiltinVoice]：已安装的离线模型，由服务器清单提供声线与下载信息。
 * - [SoundSource.CloudVoice]：Heart/Emma 固定英语音频，仅覆盖已生成的公开文本。
 *   不将私人文章发送到云端；未覆盖的文本明确提示，不静默换声。
 *
 * 现成音频（资料包里的真人/合成录音）**不走这里**——那是「播放一个文件」，由
 * `data/listening/ListeningAudio.kt` 的 `ListeningPlayer` 负责，保持「原音就是原音」。
 * 换声朗读是**另外生成一条音轨**并标明合成，不冒充改变原音（47 明确要求）。
 */

/** 声音来自哪里。用户能看到的每一条声音都必须说清自己的来源。 */
enum class SoundSource {
    /** 系统 TTS 引擎（各 ROM 自带，可能缺英语语音包）。 */
    SystemTts,

    /** 内置离线模型，按清单下载并校验后可用。 */
    BuiltinVoice,

    /** 云端预生成英语音频；已下载的单词使用本机缓存。 */
    CloudVoice,
}

/** 口音。**只暴露真实有对应模型/语音的组合**，缺的由 [SpeechVoice.available] 如实标 false。 */
enum class Accent(val label: String) {
    American("美式"),
    British("英式"),
    /**
     * 中文普通话。2026-10-04 加：内置离线模型是**中文**的（标贝小雅），
     * 把它塞进「美式/英式」任何一个都是骗人，所以单独一个口音位。
     */
    Chinese("中文"),
}

/** 性别。 */
enum class VoiceGender(val label: String) {
    Female("女声"),
    Male("男声"),
    Unspecified("未标"),
}

/**
 * 一条可朗读的声音。
 *
 * [available] 为 false 的组合**仍然会出现在列表里**（让用户知道「有这个位置，只是还没做」），
 * 但界面必须显示「待提供」且**不能被选中/试听**，绝不偷偷用另一条声音顶替。
 */
data class SpeechVoice(
    val id: String,
    val name: String,
    val source: SoundSource,
    val accent: Accent,
    val gender: VoiceGender,

    /** 该来源是否**真的能发声**（系统 TTS 还要看设备是否装了英语语音包）。 */
    val available: Boolean,

    /** 为什么不可用 / 缺什么。available=true 时为空。给用户看的人话。 */
    val unavailableReason: String = "",

    /** 声音文件大小（字节）。系统 TTS 为 0（不占我们的存储）。 */
    val bytes: Long = 0L,

    /** 引擎/模型版本，用于排查「为什么和上次听起来不一样」。 */
    val engineVersion: String = "",

    /** 许可证短名 + 链接，试听前就该知道能不能用。 */
    val license: String = "",
    val licenseUrl: String = "",

    /** 署名要求（CC BY 之类）。空 = 不强制。 */
    val attribution: String = "",

    /**
     * 内置离线模型：对应的模型 id（[TtsModel.id]）。系统/云语音为空。
     * **朗读时要靠它找到真正在发声的那个模型**，不能只看声线名字。
     */
    val modelId: String = "",

    /**
     * 内置离线模型：这条声线是权重里的**第几个说话人**。
     *
     * 光有 [modelId] 不够：fanchen-C 一个模型里烘着 187 个说话人，
     * 「中文·男声」和「中文·女声」是同一个模型、同一份权重，区别只在这个序号。
     * 以前没有这个字段，`BuiltinTts.speak` 永远传 0 —— 于是界面上无论列出几条声线，
     * 出声的永远是 0 号那一条（用户报的「只有一个女声，没有男声」就是这么来的）。
     */
    val speakerId: Int = 0,

    /**
     * 内置离线模型：**是否已经下载安装**。非内置语音恒为 true。
     * 没安装的声线 available=false —— 不能让用户选中一条点了没反应的。
     */
    val installed: Boolean = true,

    /** 补充说明（比如「未打包 rule.far，多音字准确率略降」）。 */
    val note: String = "",
) {
    /** 体积的人话表达，列表行直接用。 */
    val sizeLabel: String get() = if (bytes <= 0L) "不额外占用" else "%d.%d MB".format(bytes / 1024 / 1024, (bytes / 1024 / 102) % 10)
}

/** 朗读一段文本要什么。用途决定「该不该换声」——比如单词发音与整句朗读可以选不同默认。 */
data class SpeechRequest(
    val text: String,
    val locale: String = "en",
    val accent: Accent = Accent.American,
    val voiceId: String? = null,
    /** 0.5~2.0，1.0 = 原速。系统 TTS 走 setSpeechRate。 */
    val rate: Float = 1.0f,
    /** 这条朗读是干什么用的：单词 / 例句 / 试听 / 通用。用于日志与将来分用途默认。 */
    val purpose: SpeechPurpose = SpeechPurpose.General,
    /** 稳定标识，用于播放回调与「同一条再点一次」判定。 */
    val utteranceId: String = "",
)

enum class SpeechPurpose(val label: String) {
    Word("单词"),
    Sentence("例句"),
    Preview("试听"),
    General("朗读"),
}

/** 一次朗读请求的结果。失败要能说清是哪一类，界面才好给对应的补救。 */
sealed interface SpeechResult {
    data object Spoken : SpeechResult
    data class Failed(val problem: SpeechProblem, val hint: String) : SpeechResult
    data class Unavailable(val reason: String) : SpeechResult

    /**
     * **发出声了，但不是用户选的那条声线**（典型：拿中文离线声线去读英文）。
     *
     * 单独开一类是为了守住「不静默顶替」：既不能假装就是你要的音色（那会骗人），
     * 也不该一声不吭（那会让人以为坏了）。界面必须把这个原因显示出来。
     */
    data class Degraded(val reason: String) : SpeechResult
}

/**
 * 统一声音服务的门面。
 *
 * 共用声音偏好与停止入口。各来源按实际安装/覆盖情况工作，失败明确提示。
 */
class SpeechService(
    private val context: Context,
    private val onProblem: (SpeechProblem) -> Unit = {},
) {
    private var systemRequested = false
    private val system = TongluSpeech(context) { problem -> if (systemRequested) onProblem(problem) }
    private var started = false
    private var cloud: CloudEnglishPlayback? = null

    /** 当前生效的声音。默认系统 TTS 的美式——与旧行为一致，不因本批改变默认音色。 */
    var current: SpeechVoice = SpeechCatalog.systemDefault()
        private set

    fun start() {
        if (started) return
        started = true
        BuiltinTts.attach()
        system.start()
        // 清单不该只有声音设置页在拉：单词练习 / 每日例句 / 词卡都要认得出用户下载过的声线，
        // 否则它们看到的是上一版 App 编死的清单（英式就是这么「发不出声音」的）。
        // 单飞 + 10 分钟节流，绝不阻塞调用方（见 TtsModelStore.refreshInBackground）。
        TtsModelStore.refreshInBackground(context)
    }

    /**
     * 重新探测系统朗读能力：装完语音数据、从系统设置返回时调。
     *
     * 不调的话，当前实例还是「当时没有英语语音」那一个，用户照提示装完回来照样发不出声，
     * 只能杀进程重开（plan/56 §4.2 第 5 项）。
     */
    fun refreshSystem() = system.refresh()

    fun shutdown() {
        cloud?.close(); cloud = null
        system.shutdown()
        if (started) { started = false; BuiltinTts.detach() }
    }
    fun stop() { systemRequested = false; system.stop(); BuiltinTts.stop(); cloud?.stop() }

    /** 失败原因，交给界面决定怎么提示。 */
    fun lastProblem(): SpeechProblem? = system.lastFailure()

    /**
     * 打开系统「文字转语音」设置（只有 [SpeechProblem] 需要动手修时才该给这个入口）。
     * 各 ROM 入口不统一，内部按「厂商设置页 → 语音数据安装 → 无障碍」依次试。
     */
    fun openSystemSettings(): Boolean = system.openSettings()

    /** 让系统默认引擎拉起自己的语音包下载。 */
    fun installVoiceData(): Boolean = system.installVoiceData()

    /** 系统朗读是否真的就绪（设备装了引擎 + 有英语语音包）。**不分口音**，见 [systemSupports]。 */
    val systemReady: Boolean get() = system.available

    /**
     * 系统朗读在这台设备上能不能发**这个口音**。
     *
     * 以前只有一个总开关（有没有英语语音），于是选「英式」时「系统朗读（本机自带）」
     * 照样被标成可用/使用中，点下去才弹「系统没有可用的英式声音」（用户截图那一幕）。
     * 英式必须真的有一条英国声线才算数（判据与 [TongluSpeech.applyLanguage] 一致）。
     */
    fun systemSupports(accent: Accent): Boolean = system.supports(speechLocale(accent))

    /**
     * 该口音下**这台设备本次真的会用来朗读的那条声线**（以及「为什么不是系统朗读」的说明）。
     *
     * 声音设置页用它显示「使用中」，也用它决定切换口音之后选中哪条；
     * [speak] 用同一条路径解析，**界面显示与实际发声不会再是两套逻辑**
     * （以前界面是「当前选中那条」，发音是「resolve 出来的那条」，于是屏幕上写着
     * 「系统朗读 使用中」而点下去弹「发不出声音」）。
     *
     * @param requestedVoiceId 偏好里存下的声线 id（没有就传 null）。
     */
    fun voiceFor(accent: Accent, requestedVoiceId: String? = null): VoiceDecision {
        CloudEnglishVoices.find(requestedVoiceId)?.let { voice ->
            return VoiceDecision(if (voice.accent == accent) voice else voice.copy(available = false,
                unavailableReason = "这条声音是${voice.accent.label}，请在声音设置中选择${accent.label}声音"))
        }
        val builtin = SpeechCatalog.builtinVoices(context)
        val requested = requestedVoiceId
            ?.takeIf { it.isNotBlank() }
            ?.let { id -> builtin.firstOrNull { v -> v.id == id } }
        // An explicit downloaded voice must stay explicit even if its files become unavailable.
        // Reporting the missing voice is preferable to unexpectedly speaking with system TTS.
        if (requestedVoiceId?.startsWith("builtin-") == true && requestedVoiceId != requested?.id) {
            return VoiceDecision(SpeechVoice(requestedVoiceId, "已选离线声音", SoundSource.BuiltinVoice,
                accent, VoiceGender.Unspecified, available = false,
                unavailableReason = "已选离线声音暂时不可用，请在声音设置中重新选择或下载"))
        }
        if (requested != null && requested.accent == accent && !requested.available) {
            return VoiceDecision(requested)
        }
        val systemOk = systemSupports(accent)
        return resolveSpokenVoice(
            accent = accent,
            requested = requested,
            system = SpeechCatalog.systemVoice(systemOk, accent),
            builtin = builtin,
            systemCanSpeakAccent = systemOk,
        )
    }

    /** 换一条声音。不可用的声音**拒绝切换**并返回 false，不静默顶替。 */
    fun select(voice: SpeechVoice): Boolean {
        if (!voice.available) return false
        if (voice.source == SoundSource.CloudVoice && CloudEnglishVoices.find(voice.id) == null) return false
        stop()
        current = voice
        if (voice.source == SoundSource.BuiltinVoice) modelOf(voice.modelId)?.let { BuiltinTts.warm(context, it) }
        return true
    }

    /** 内置离线模型能不能用（模型在不在、引擎加载没报错）。 */
    val builtinReady: Boolean get() = BuiltinTts.ready
    val builtinError: String? get() = BuiltinTts.lastError

    /** 已安装的离线模型 id 集合。界面用它决定「哪些能试听」。 */
    fun installedModelIds(): Set<String> =
        TtsModelStore.list(context).filter { TtsModelStore.isInstalled(context, it) }.map { it.id }.toSet()

    /**
     * 按 id 找模型。找不到返回 null（清单里确实没有这一条）。
     *
     * 走 [TtsModelStore.list] 的**带 Context 重载**：它会把上次成功拉到的清单从磁盘读回来，
     * 所以冷启动/断网时也认得出用户已经下载过的模型（不是只看 APK 里编死的那份旧配置）。
     */
    fun modelOf(id: String): TtsModel? =
        TtsModelStore.list(context).firstOrNull { it.id == id }

    /**
     * 联网刷新模型清单。**必须在后台线程调**（走网络）。
     * 界面拉到之后要自己刷新列表，否则显示的还是兜底那份。
     */
    fun refreshModels(): List<TtsModel> = TtsModelStore.refresh(context)

    /** 清单上次为什么没拉到（null = 拉到了）。 */
    val manifestError: String? get() = TtsModelStore.manifestError

    /** 上次拉到清单时清掉了哪些孤儿目录（换模型后老目录就靠这一步消失）。 */
    val prunedModels: List<String> get() = TtsModelStore.lastPruned

    /**
     * 下载并安装一个离线模型。**必须在后台线程调**（走网络 + 解压）。
     *
     * @return null = 装好了；非 null = 失败原因（给人看的）。
     */
    fun downloadModel(model: TtsModel, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): String? =
        TtsModelStore.download(context, model, onProgress)

    /** 删掉一个已安装的模型（腾空间）。 */
    fun removeModel(model: TtsModel) = TtsModelStore.remove(context, model)

    /**
     * 离线语音自检：合成一句并落成 wav。
     *
     * 这是给用户和走查用的**硬证据**——「有没有真的出声音」不能靠"点了没报错"来判断，
     * 落一个 wav、看它有多少字节，才算数。
     */
    fun selfTestBuiltin(modelId: String, outFile: java.io.File): BuiltinTts.Result {
        val model = modelOf(modelId)
            ?: return BuiltinTts.Result(false, outFile, 0, 0, "清单里没有这个模型：$modelId")
        return BuiltinTts.selfTest(context, model, outFile)
    }

    /**
     * 朗读。这是唯一入口，调用方不再直接碰 [TongluSpeech]。
     *
     * 英式请求按 [resolveSpokenVoice] 的优先级选声音：**该口音已下载的离线声线优先于发不出声的系统朗读**；
     * 一个都发不出来时如实失败并给出分类原因（缺英国英语数据 / 缺引擎），绝不静默用美音顶替。
     */
    fun speak(request: SpeechRequest): SpeechResult {
        systemRequested = false
        // Every request cancels the previous download/playback, including a
        // switch back from cloud audio to system/builtin speech.
        CloudEnglishPlayback.stopActive()
        // 试听必须试到「用户点进去的那一条」，而不是当前默认那条：
        // 请求里带了 voiceId 就要按它解析声线，否则点「小雅」试听时
        // 实际发声的是系统朗读 —— 界面上看不出差别，但试听的是错的音色。
        //
        // 🔴 声线解析**必须看全量清单 + 该口音的系统能力**，不能只看「当前选中那条」：
        // 2026-10-15 用户手机上报「英式发不出声音」，真因就是这里 ——
        // 系统朗读发不出英式，而已下载的英式离线声线根本没被考虑到，
        // 于是退回一条必然失败的系统朗读（弹「系统没有可用的英式声音」）。
        // 偏好里存的那条可能已经不可用了：系统 TTS 当初没就绪、或者离线模型被删了。
        // `select()` 本来就拒绝不可用的声线，但**恢复路径不走 select**，以前直接拿来就用 ——
        // 表现为「明明选过，点发音却没反应」。这里补上同一道闸（plan/56 §4.2 第 7 项）。
        val decision = voiceFor(request.accent, request.voiceId)
        val target = decision.voice
        if (target.source == SoundSource.CloudVoice) {
            systemRequested = false
            if (!target.available) return SpeechResult.Unavailable(target.unavailableReason)
            system.stop(); BuiltinTts.stop()
            current = target
            return (cloud ?: CloudEnglishPlayback(context).also { cloud = it }).play(request.copy(voiceId = target.id))
        }
        if (!target.available && target.source == SoundSource.BuiltinVoice) {
            return SpeechResult.Unavailable(target.unavailableReason.ifBlank { "已选离线声音暂时不可用，请检查声音设置" })
        }
        // 系统发不出这个口音、改用离线声线时，日志里留一句真因（界面用同一个函数自己解释）。
        decision.note?.let { android.util.Log.i("TongluSpeechService", it) }
        if (needsBritishSystem(request.accent, target.source, target.accent)) {
            systemRequested = true
            val ok = system.speak(request.text, request.utteranceId.ifBlank { request.text.take(32) }, request.rate, "en-GB")
            return if (ok) SpeechResult.Degraded("英式离线声音·待提供，本次使用系统英式朗读")
            else SpeechResult.Failed(system.lastFailure() ?: SpeechProblem.PLAY_FAILED, "系统英式朗读不可用，请安装英式语音数据")
        }
        val locale = if (request.locale.startsWith("en", true)) speechLocale(request.accent) else request.locale
        when (target.source) {
            SoundSource.SystemTts -> {
                systemRequested = true
                if (request.text.isBlank()) return SpeechResult.Unavailable("没有可朗读的文本")
                val ok = system.speak(
                    request.text,
                    request.utteranceId.ifBlank { request.text.take(32) },
                    request.rate,
                    locale,
                )
                return if (ok) SpeechResult.Spoken
                else SpeechResult.Failed(system.lastFailure() ?: SpeechProblem.PLAY_FAILED,
                    if (request.accent == Accent.British)
                        "系统英式朗读不可用。两条出路：到系统「文字转语音」里下载英国英语（English UK）语音数据，" +
                            "或到英语 → 声音设置里下载「英式离线声线」（不依赖系统语音包，30 MB 左右）。"
                    else "系统朗读没能发声")
            }
            SoundSource.BuiltinVoice -> {
                if (request.text.isBlank()) return SpeechResult.Unavailable("没有可朗读的文本")
                // 声线 → 真正发声的那个模型。名字不能当凭据，模型 id 才行。
                val model = target.modelId.takeIf { it.isNotBlank() }?.let { modelOf(it) }
                    ?: return SpeechResult.Unavailable("「${target.name}」没有对应的离线模型")
                if (!TtsModelStore.isInstalled(context, model)) {
                    return SpeechResult.Unavailable("「${model.name}」还没下载，先下载 ${model.sizeLabel} 才能离线朗读")
                }
                // 声线语言和内容语言对不上时（拿中文声线念英文、或拿英文声线念中文），
                // 念出来是一串怪音。这里既不假装成功、也不一声不吭：
                // 交给系统朗读真的读出来，再用 Degraded 如实告诉界面「这不是你选的那条声线」。
                val wantZh = request.locale.startsWith("zh", ignoreCase = true)
                val modelZh = model.lang.startsWith("zh", ignoreCase = true)
                if (wantZh != modelZh) {
                    systemRequested = true
                    val ok = system.speak(
                        request.text,
                        request.utteranceId.ifBlank { request.text.take(32) },
                        request.rate,
                        locale,
                    )
                    return if (ok) {
                        SpeechResult.Degraded(
                            "「${target.name}」是${if (modelZh) "中文" else "英文"}声线，" +
                                "这段是${if (wantZh) "中文" else "英文"}，已改用系统朗读；" +
                                "要听这条声线请朗读${if (modelZh) "中文" else "英文"}内容"
                        )
                    } else {
                        SpeechResult.Failed(system.lastFailure() ?: SpeechProblem.PLAY_FAILED, "系统朗读没能发声")
                    }
                }
                // ⚠️ 必须把**这条声线自己的说话人序号**传下去。少传一个参数，
                // 多说话人模型（fanchen-C 有 187 个）就会永远念 0 号 ——
                // 界面上看不出任何异常，只是「选了男声还是女声」。
                val ok = BuiltinTts.speak(context, model, request.text, request.rate, target.speakerId)
                return if (ok) SpeechResult.Spoken
                else SpeechResult.Unavailable(BuiltinTts.lastError ?: "离线语音还没准备好")
            }
            SoundSource.CloudVoice ->
                return SpeechResult.Unavailable("这条云端声音暂未支持，请在声音设置中重新选择")
        }
    }
}
