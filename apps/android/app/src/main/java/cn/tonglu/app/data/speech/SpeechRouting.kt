package cn.tonglu.app.data.speech

/** 英式请求不得进入美式离线模型；系统声线用语言标签确定口音。 */
internal fun needsBritishSystem(accent: Accent, source: SoundSource, voiceAccent: Accent): Boolean =
    accent == Accent.British && source != SoundSource.SystemTts && voiceAccent != Accent.British

/**
 * `save(owner, voiceId = null, accent)` 时，已存的那条声线该不该保留。
 *
 * 抽成纯函数是为了能被单测钉住：单词页的「英音/美音」两个 chip 走的就是 `save(null, accent)`，
 * 用户点「英音」只是想换口音，**不该把刚在声音设置里选好的英式离线声线清掉** ——
 * 清掉之后再进声音设置就显示「系统朗读（使用中）」，点发音弹「发不出声音」（bug 1）。
 * 规则：口音真的变了才清（旧声线属于别的口音，留着会串音）；口音没变就保留。
 */
internal fun keepVoiceOnAccentSave(
    storedVoiceId: String?,
    storedAccent: Accent,
    incomingAccent: Accent,
): String? = when {
    storedVoiceId == null -> null
    storedAccent == incomingAccent -> storedVoiceId
    else -> null
}

internal fun speechLocale(accent: Accent): String = when (accent) {
    Accent.British -> "en-GB"
    Accent.American -> "en-US"
    Accent.Chinese -> "zh-CN"
}

/**
 * 这一次朗读该用哪条声音。
 *
 * 抽成**纯函数**是为了能被 JVM 单测穷举：这里承载的正是那条出过 bug 的规则 ——
 * 「系统朗读发不出这个口音时，用该口音**已下载的离线声线**顶上，
 * 而不是退回一条必然发不出声的系统朗读」。2026-10-15 用户手机上
 * 「英式点了没声音 / 弹『系统没有可用的英式声音』」就是这条规则缺失的后果
 * （英式离线声线明明已经下载好了，只因为系统那一关过不去就整条朗读失败）。
 *
 * @param note 系统朗读发不出这个口音、改用离线声线时如实写清原因；null = 没有要解释的。
 */
/** 用 public 而不是 internal：[SpeechService.voiceFor] 是公开 API（界面要用它显示「使用中」）。 */
data class VoiceDecision(val voice: SpeechVoice, val note: String? = null)

/**
 * 决定顺序（每一条都对应一个真实场景，顺序本身就是判据）：
 *
 * 1. **[requested]**：请求点名的那条（试听按钮、或声音设置里存下来的偏好 voiceId）。
 *    必须 `available` 且口音与请求一致 —— 否则等于用户点「英音」却放美音。
 * 2. **系统朗读**：这台设备**真的发得出这个口音**时用它。保持既有默认
 *    （「系统朗读（本机自带）」），不因为装了离线模型就悄悄换掉用户听到的声音。
 * 3. **该口音已下载的离线声线**：系统发不出这个口音时的唯一出路（英式的修复点）。
 *    此时返回 [VoiceDecision.note]，界面据此说明「为什么不是系统朗读」。
 * 4. 都发不出来 → 返回系统那一条（`available=false`），由调用方给出**分类失败原因**
 *    并提示去哪修 —— **绝不静默换一个口音顶替**。
 *
 * ⚠️ 不用「当前选中那条」参与判断：`SpeechService.current` 在各页面是新实例的默认值，
 * 拿它当依据会让「同一句话有时走系统、有时走离线」—— 音色与音质忽变正是用户报的现象之一。
 */
internal fun resolveSpokenVoice(
    accent: Accent,
    requested: SpeechVoice?,
    system: SpeechVoice,
    builtin: List<SpeechVoice>,
    systemCanSpeakAccent: Boolean,
): VoiceDecision {
    if (requested != null && requested.available && requested.accent == accent) {
        return VoiceDecision(requested)
    }
    if (systemCanSpeakAccent) return VoiceDecision(system)

    val installed = builtin.firstOrNull { it.accent == accent && it.available }
    if (installed != null) {
        return VoiceDecision(
            installed,
            "这台设备的系统朗读没有${accent.label}语音，朗读改用已下载的「${installed.name}」（离线，不联网）。",
        )
    }
    // 一个都发不出来：返回系统那一条（不可用），让调用方给出分类原因并告诉用户去哪装。
    return VoiceDecision(system)
}
