package cn.tonglu.app.data.speech

import android.content.Context

/**
 * 声音清单：**哪几条声音存在、能不能用、许可是什么、缺的缺在哪**（47 §3 P3 / 48 §7）。
 *
 * 这里的每一条都是「如实登记」，不是「愿望清单」：
 * - 系统 TTS：能发声就 available=true；设备缺语音包就 false 并说清原因。
 * - 内置离线模型：**由服务器清单驱动**（[TtsModelStore]）。模型**不打进 APK**，
 *   所以「能发声」的判据是**已经下载安装**；没下载的照样列出来，但
 *   available=false 并写明「先下载 xx MB」，界面给下载入口 ——
 *   既不让用户选一条发不出声的，也不把他想用的功能藏起来。
 * - 云端音频：Heart/Emma 供随包精简四六级词书使用，首次下载后可缓存重播。
 *   声线可选择不代表任意文本都有预生成资源；未覆盖时播放链路明确提示。
 *
 * **只展示真实有对应模型的可选项；缺的明确待提供，不偷偷用另一口音顶替。**
 */
object SpeechCatalog {

    /**
     * 内置离线声线。**从模型清单生成**，不在这里写死 ——
     * 加一个模型只需要在服务器上发一条清单，App 不用改。
     *
     * 一个模型可以贡献**多条**声线（见 [expand]）：fanchen-C 这种权重里烘着 187 个说话人，
     * 就按清单里的 `voices` 展开成「中文·女声 / 中文·男声」等若干条。
     */
    fun builtinVoices(context: Context): List<SpeechVoice> =
        // 走**带 Context** 的重载：它会把上次成功拉到的清单从磁盘读回来，
        // 于是冷启动/断网/没进过声音设置页的页面也认得出用户下载过的声线
        // （以前只认「本进程拉过」或「APK 编死的上一版清单」，英式就是这么丢的）。
        TtsModelStore.list(context).flatMap { m ->
            expand(m, installed = TtsModelStore.isInstalled(context, m))
        }

    /**
     * 把一个模型展开成若干条声线。**抽成纯函数是为了能测**（`builtinVoices` 要 `Context`）。
     *
     * 三条规则，每条都对应一个真实踩过的坑：
     * 1. **没声明 `voices` 就退化成一条 sid=0 的默认声线** —— 老清单没有这个字段，
     *    行为必须与以前完全一致，否则升级后老用户的声音列表会凭空变空。
     * 2. **单条声线的 id 保持 `builtin-<模型id>` 不变** —— 用户偏好里存的就是这个字符串，
     *    换个 id 会让「明明选过」变成「没选过」，退回系统朗读。
     *    多条时才带 `#<sid>` 后缀。
     * 3. **`available` 看的是「模型装没装」而不是「这条声线好不好」** ——
     *    没下载就发不出声，不能让用户选中一条点了没反应的（本文件开头那条原则）。
     */
    internal fun expand(model: TtsModel, installed: Boolean): List<SpeechVoice> {
        val declared = model.voices.ifEmpty { listOf(TtsVoice(sid = 0, gender = "", name = "")) }
        return declared.map { v ->
            SpeechVoice(
                id = if (declared.size <= 1) "builtin-${model.id}" else "builtin-${model.id}#${v.sid}",
                name = when {
                    v.name.isNotBlank() -> v.name
                    declared.size <= 1 -> model.name
                    else -> "${model.name} · 说话人 ${v.sid}"
                },
                source = SoundSource.BuiltinVoice,
                accent = model.accent,
                gender = genderOf(v.gender),
                // 没下载就发不出声 —— 不能让用户选中一条点了没反应的。
                available = installed,
                unavailableReason = if (installed) "" else "还没下载模型（${model.sizeLabel}），下载后即可离线朗读",
                bytes = model.bytes,
                engineVersion = "sherpa-onnx 1.13.8 · VITS",
                license = model.license,
                licenseUrl = model.licenseUrl,
                attribution = model.attribution,
                modelId = model.id,
                // 说话人序号：朗读时要靠它选到用户点的那一条嗓音。
                speakerId = v.sid,
                installed = installed,
                note = model.note,
            )
        }
    }

    /**
     * 清单里的 `gender` 字符串 → 界面用的枚举。
     *
     * **认不出来的就是「未标」，绝不默认成女声** —— 猜错等于骗人：
     * 用户点「男声」结果出来女声，比老老实实写「未标」糟糕得多。
     * 认中文写法是因为清单是手写的，写「男」比写 "male" 更不容易写错。
     */
    internal fun genderOf(raw: String): VoiceGender = when (raw.trim().lowercase()) {
        "female", "女", "女声" -> VoiceGender.Female
        "male", "男", "男声" -> VoiceGender.Male
        else -> VoiceGender.Unspecified
    }

    /**
     * 系统 TTS 那一条。设备有没有语音包，只有真问过引擎才知道——
     * [ready] 由调用方用 **[SpeechService.systemSupports]（按口音）** 填入，
     * 不是一个笼统的「有没有英语语音」：只有 en-US 的手机发不出英式，
     * 拿总开关当选「英式可用」，界面就会显示「使用中」然后一点就失败。
     */
    fun systemVoice(ready: Boolean = true, accent: Accent = Accent.American): SpeechVoice = SpeechVoice(
        id = "system-tts", name = "系统朗读（本机自带）",
        source = SoundSource.SystemTts, accent = accent, gender = VoiceGender.Unspecified,
        available = ready,
        unavailableReason = if (ready) "" else when (accent) {
            // 英式与美式的失败原因不同（一个是缺英国语音数据，一个是压根没引擎/没英语包），
            // 混成一句话会让用户去改错地方 —— 照 A34 的结论如实分开写。
            Accent.British -> "这台设备的系统朗读没有英式（English UK）语音：可在系统「文字转语音」里下载，" +
                "或选下面已下载的英式离线声线（不依赖系统语音包）。"
            Accent.Chinese -> "这台设备的系统朗读没有中文语音包，请到系统「文字转语音」设置里检查"
            Accent.American -> "这台设备没有可用的朗读引擎或缺少英语语音包，请到系统「文字转语音」设置里检查"
        },
        bytes = 0L, engineVersion = "系统 TTS",
        license = "随系统 ROM", licenseUrl = "",
    )

    /** 旧行为默认 = 系统朗读（美式），本批不改默认音色。 */
    fun systemDefault(): SpeechVoice = systemVoice(ready = true)

    /**
     * 某个口音**还没有离线模型**时的占位条目。
     *
     * 为什么必须有它：以前 `voices()` 用 `filter { it.accent == accent }` 把不匹配的模型
     * 直接滤掉，于是选「英式」时整个列表只剩「系统朗读（本机自带）」一条 ——
     * 用户看到的是**什么都没有**：既不知道英式离线声音到底有没有，也找不到任何下载入口。
     * 2026-10-08 用户原话就是这么报的：「英式英语的声音压根无法下载模型，也没有模型可下载」。
     *
     * 这里**如实登记一条 available=false、modelId 留空**的条目。界面会渲染成
     * 「待提供」+ 一行原因（`VoiceSettingsScreen` 的 `status` 里本来就有这条分支，
     * 只是以前永远走不到），试听 / 设为默认自动禁用，也不会出现点不动的「下载」按钮。
     * 这正是本文件开头写的原则：**缺的明确待提供，不偷偷用另一口音顶替**。
     */
    fun pending(accent: Accent): SpeechVoice = SpeechVoice(
        id = "pending-${accent.name.lowercase()}",
        name = "${accent.label}离线声音",
        source = SoundSource.BuiltinVoice,
        accent = accent, gender = VoiceGender.Unspecified,
        available = false,
        // `modelId` 必须留空：界面靠它区分「没下载」和「还没提供」（见 VoiceRow 的 status）。
        modelId = "",
        unavailableReason = "这个口音还没有离线模型，暂时只能用它上面的「系统朗读」；" +
            "等有了会先在服务器清单里上架，App 里就会出现下载入口。",
        bytes = 0L, engineVersion = "sherpa-onnx 1.13.8 · VITS",
        license = "待定（上架时会写明）", licenseUrl = "",
    )

    /**
     * 把「系统朗读」和该口音的内置声线合并成最终清单。
     *
     * **抽成纯函数是为了能测**：`voices()` 要 `Context`（要查模型装没装），JVM 单测给不了，
     * 而这里承载的正是那条**出过 bug 的规则** —— 「某个口音一条离线声线都没有时，
     * 也要给一条『待提供』」。删掉 `ifEmpty`，`SpeechCatalogTest` 必须变红
     * （只写注释锁不住东西，见 `docs/BUGS.md` 里那条「修法写进代码 ≠ 修法被固定住」）。
     */
    internal fun merge(
        accent: Accent,
        system: SpeechVoice,
        builtin: List<SpeechVoice>,
    ): List<SpeechVoice> =
        listOf(system) + builtin.filter { it.accent == accent }.ifEmpty { listOf(pending(accent)) }

    /**
     * 按口音取声音清单。
     *
     * @param systemReady **「系统朗读能不能发 [accent] 这个口音」**（用 [SpeechService.systemSupports] 取），
     *   不是笼统的「有没有英语语音」—— 后者会把英式那一行标成可用（A34 与 2026-10-15 截图）。
     */
    fun voices(accent: Accent, systemReady: Boolean, context: Context): List<SpeechVoice> =
        CloudEnglishVoices.voices.filter { it.accent == accent } +
            merge(accent, systemVoice(ready = systemReady, accent = accent), builtinVoices(context))

    /** 全部声音（不分口音），给「管理」视图用。[systemReady] 含义同 [voices]。 */
    fun all(systemReady: Boolean, context: Context): List<SpeechVoice> =
        CloudEnglishVoices.voices + listOf(systemVoice(ready = systemReady)) + builtinVoices(context)

    /**
     * 离线语音整体的状态说明——一句话告诉用户「这个能力到哪一步了、边界在哪」。
     * 界面直接显示，不藏着。
     */
    val offlineNote: String
        get() = "离线语音由内置引擎（sherpa-onnx，Apache-2.0）合成，不联网也能朗读。" +
            "模型不随 App 安装，选中哪条才下载哪条，用多少下多少。" +
            "中文模型 16000 Hz、187 个说话人，男女声都有；" +
            "它自带的词典能读的字比英文那条多，但**多音字**仍可能读错。" +
            "英文模型由 LJSpeech（public domain）训练而成，音素化走自带的 12.4 万词词典，" +
            "不含 GPL 组件 —— 代价是词典外的词读不出来。"
}
