package cn.tonglu.app.data

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 朗读为什么没有声音 —— 分类到可以动手修的那一步，而不是一句「请检查系统语音设置」。
 *
 * 真机上最常见的五种「点发音没反应」：
 * - NO_ENGINE：设备压根没装 TTS 引擎（`TextToSpeech.engines` 为空）。
 * - NO_ENGLISH_VOICE：装了引擎但没下英语语音包。此时 `speak()` 照样返回 SUCCESS，
 *   只是什么声音都没有 —— 只判断 speak 的返回码是抓不出来的，必须查 voices。
 * - MUTED：媒体音量为 0。
 * - INIT_FAILED：引擎初始化失败（默认引擎坏了，又没有别的可用）。
 * - PLAY_FAILED：speak 返回 ERROR，或引擎在 UtteranceProgressListener 里报错。
 */
enum class SpeechProblem { NO_ENGINE, NO_ENGLISH_VOICE, NO_BRITISH_VOICE, MUTED, INIT_FAILED, PLAY_FAILED }

/**
 * 系统朗读的持有者。构造不碰 TTS，`start()` 才初始化；用完必须 `shutdown()`。
 *
 * 选引擎的策略：先系统默认那台，它不行（`onInit` 失败或没有英语语音）就换下一台，
 * 直到有一台真的能读英语为止 —— 有些 ROM 默认引擎缺英语包，但另一台装了。
 */
class TongluSpeech(private val context: Context, private val onFailure: (SpeechProblem) -> Unit) {
    private val app = context.applicationContext
    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var disposed = false
    @Volatile private var lastFailure: SpeechProblem? = null
    private var candidates: List<String> = emptyList()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    /** 设备上装了哪些朗读引擎。`null` = 还没问出来（回调没到），空列表 = 确实一台都没有。 */
    private fun engines(): List<String>? {
        val current = engine ?: return null
        return try { current.engines?.mapNotNull { it.name }?.filter { it.isNotBlank() }?.distinct() }
        catch (bad: Exception) {
            android.util.Log.w(TAG, "读引擎清单失败：${bad::class.java.simpleName}: ${bad.message}")
            null
        }
    }
    /**
     * 早探：绑定有时压根不会回调（`TextToSpeech` 找不到默认引擎时既不 SUCCESS 也不 ERROR），
     * 用户看到的就是「点了没反应」。1.5 秒还没就绪，先去看看设备**有没有**引擎 ——
     * 一台都没有（国产 ROM 常见：没装 GMS 也没有语音服务）就直接说没有，别让人瞎找设置页。
     */
    private val probe = Runnable {
        if (disposed || ready) return@Runnable
        if (engines()?.isEmpty() == true) fail(SpeechProblem.NO_ENGINE)
    }
    /**
     * 兜底：有引擎但都没就绪（例如引擎坏了或语音数据缺失）→ 给初始化失败，仍然不要干等。
     */
    private val timeout = Runnable { if (!disposed && !ready) fail(lastFailure ?: SpeechProblem.INIT_FAILED) }

    /** 能不能读。没准备好时可用 `lastFailure()` 取出原因给界面。 */
    val available: Boolean get() = ready
    fun lastFailure(): SpeechProblem? = lastFailure

    fun start() {
        if (disposed || engine != null) return
        arm()
        engine = build(null) { status -> onDefault(status) }
        if (engine == null) fail(SpeechProblem.NO_ENGINE)
    }

    private fun build(packageName: String?, onInit: (Int) -> Unit): TextToSpeech? = try {
        if (packageName == null) TextToSpeech(app) { status -> onInit(status) }
        else TextToSpeech(app, { status -> onInit(status) }, packageName)
    } catch (bad: Exception) {
        // 第一次装机 / 没接过辅助设备时构造也可能炸：这台不可用就换下一台，不要吞掉。
        android.util.Log.w(TAG, "TTS 引擎不可用 $packageName：${bad::class.java.simpleName}: ${bad.message}")
        null
    }

    /** 默认引擎的结果：能用就留下，不能用就从它拿全量引擎清单挨个试。 */
    private fun onDefault(status: Int) {
        if (disposed) return
        val first = engine ?: return
        if (status == TextToSpeech.SUCCESS && adopt(first)) return
        val installed = engines().orEmpty()
        teardown()
        if (installed.isEmpty()) { fail(SpeechProblem.NO_ENGINE); return }
        candidates = installed
        next(0)
    }

    private fun next(index: Int) {
        if (disposed) return
        if (index >= candidates.size) { fail(lastFailure ?: SpeechProblem.NO_ENGLISH_VOICE); return }
        val packageName = candidates[index]
        arm()
        val candidate = build(packageName) { status -> onCandidate(status, packageName, index) }
        if (candidate == null) next(index + 1) else engine = candidate
    }

    private fun arm() {
        handler.removeCallbacks(probe); handler.removeCallbacks(timeout)
        handler.postDelayed(probe, 1_500L)
        handler.postDelayed(timeout, 6_000L)
    }

    private fun onCandidate(status: Int, packageName: String, index: Int) {
        if (disposed) return
        val candidate = engine ?: return
        if (status == TextToSpeech.SUCCESS && adopt(candidate, packageName)) return
        teardown()
        next(index + 1)
    }

    /** 绑定成功后校验：有没有英语语音可真正发声，然后接上播放回调。 */
    private fun adopt(tts: TextToSpeech, packageName: String? = null): Boolean {
        if (!hasEnglishVoice(tts)) return false
        tts.setLanguage(Locale.US)
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {}
            // 播放回调里的错误是「这一条没念出来」，不是引擎坏了 —— 走 report，别把就绪状态废掉。
            @Deprecated("legacy") override fun onError(utteranceId: String?) { report(SpeechProblem.PLAY_FAILED) }
            override fun onError(utteranceId: String?, errorCode: Int) { report(SpeechProblem.PLAY_FAILED) }
        })
        lastFailure = null
        ready = true
        handler.removeCallbacks(probe); handler.removeCallbacks(timeout)
        android.util.Log.i(TAG, "朗读就绪：${packageName ?: "系统默认引擎"}")
        return true
    }

    /**
     * 有没有英语语音可真正发声。
     * 先看 voices（只看语言不看国家：部分引擎只有 en-GB），再看 setLanguage 的返回码。
     */
    private fun hasEnglishVoice(tts: TextToSpeech): Boolean {
        val installed = try { tts.setLanguage(Locale.US) } catch (_: Exception) { TextToSpeech.LANG_MISSING_DATA }
        val voices = try { tts.voices } catch (_: Exception) { null }
        if (voices.isNullOrEmpty()) return installed >= 0
        val english = voices.any { voice ->
            voice.locale?.language == Locale.ENGLISH.language &&
                voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        }
        return english && installed >= 0
    }

    /**
     * 这台设备的引擎有没有**能发这个语言/地区**的声线。
     *
     * 只看语言不够：[hasEnglishVoice] 只要求 `en`（有的 ROM 只有 en-GB，有的只有 en-US），
     * 而用户选「英式」要的是**英国**声线。以前界面拿「有没有英语」当选「能不能读英式」用，
     * 于是「系统朗读」在英式那一列被标成可用/使用中，点下去才弹「系统没有可用的英式声音」
     * —— 用户 2026-10-15 的截图正是这一幕。
     *
     * 判据与 [applyLanguage] 对齐：**只有英式按国家严格判**（它对 en-GB 强制要求一条英国声线，
     * 且不接受需要联网的声线）；别的地区它只要求同语言，引擎自己会退到该语言里能用的那条，
     * 所以这里也只按语言判 —— 免得界面说「本机不可用」而其实读得出来（那是反向的谎）。
     *
     * 引擎不报声线清单（`voices` 为空）时不判死，留给 [applyLanguage] 现场判：
     * 宁可让用户点一次拿到真实分类原因，也不凭空宣称不可用。
     */
    fun supports(localeTag: String): Boolean {
        if (!ready) return false
        val target = runCatching { Locale.forLanguageTag(localeTag) }
            .getOrNull()?.takeIf { it.language.isNotBlank() } ?: return true
        val tts = engine ?: return false
        val voices = try { tts.voices } catch (_: Exception) { null }
        val strictBritish = target.country.equals("GB", ignoreCase = true)
        if (voices.isNullOrEmpty()) {
            // 引擎报不出声线清单：非英式交给 setLanguage 现场判（别凭空宣称不可用）；
            // 英式**必须**有一条 GB 声线（[applyLanguage] 就是这么判的），报不出来就等于发不出。
            return !strictBritish
        }
        val sameLanguage = voices.filter {
            it.locale?.language == target.language &&
                it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        }
        if (sameLanguage.isEmpty()) return false
        if (!strictBritish) return true
        return sameLanguage.any {
            it.locale?.country.equals("GB", ignoreCase = true) && !it.isNetworkConnectionRequired
        }
    }

    /** 媒体音量为 0 时 speak 也会返回 SUCCESS 但什么都听不见。 */
    private fun muted(): Boolean = try {
        val manager = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        manager.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0
    } catch (_: Exception) { false }

    /**
     * 朗读。
     *
     * @param rate 语速，1.0 = 原速。系统 TTS 走 `setSpeechRate` ——
     *   之前这一项被丢在调用方外面，界面上的语速条对系统朗读**完全无效**（离线朗读倒是生效了，
     *   于是同一个滑块两条链路表现不一致）。
     * @param localeTag BCP-47 语言标签（`en` / `en-GB` / `zh-CN` …）。之前固定 `Locale.US`，
     *   用户在设置里选英式，系统朗读照样念美式。
     */
    fun speak(text: String, utteranceId: String, rate: Float = 1.0f, localeTag: String = "en"): Boolean {
        if (disposed) { fail(SpeechProblem.NO_ENGINE); return false }
        if (!ready) { fail(lastFailure ?: SpeechProblem.INIT_FAILED); return false }
        // 音量为 0 是「这一次没出声」，不是「引擎坏了」—— 调高音量就该能再念，所以走 report 不走 fail。
        if (muted()) { report(SpeechProblem.MUTED); return false }
        val tts = engine ?: run { fail(SpeechProblem.INIT_FAILED); return false }
        applyRate(tts, rate)
        if (!applyLanguage(tts, localeTag)) {
            report(if (localeTag.equals("en-GB", true)) SpeechProblem.NO_BRITISH_VOICE else SpeechProblem.NO_ENGLISH_VOICE)
            return false
        }
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
        val code = try { tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId) }
        catch (bad: Exception) {
            android.util.Log.w(TAG, "朗读失败：${bad::class.java.simpleName}: ${bad.message}")
            TextToSpeech.ERROR
        }
        if (code != TextToSpeech.SUCCESS) { report(SpeechProblem.PLAY_FAILED); return false }
        return true
    }

    private fun applyRate(tts: TextToSpeech, rate: Float) {
        try { tts.setSpeechRate(rate.coerceIn(0.2f, 4.0f)) }
        catch (bad: Exception) { android.util.Log.w(TAG, "设置语速失败：${bad::class.java.simpleName}") }
    }

    /**
     * 按请求语言选声音。普通语言保留同语言回退；英式必须匹配英国声线，缺失时明确失败。
     */
    private fun applyLanguage(tts: TextToSpeech, localeTag: String): Boolean {
        val target = runCatching { Locale.forLanguageTag(localeTag) }
            .getOrNull()?.takeIf { it.language.isNotBlank() } ?: Locale.US
        try {
            val code = tts.setLanguage(target)
            // 英式声音必须确实匹配英国，不允许系统悄悄退到美式或无地区英语。
            if (target == Locale.UK) {
                val voice = tts.voices?.firstOrNull {
                    it.locale.language == "en" && it.locale.country == "GB" && !it.isNetworkConnectionRequired
                }
                if (code < TextToSpeech.LANG_COUNTRY_AVAILABLE || voice == null) {
                    android.util.Log.w(TAG, "系统未提供可用的英式语音，拒绝用其他口音顶替")
                    return false
                }
                if (tts.setVoice(voice) != TextToSpeech.SUCCESS) {
                    android.util.Log.w(TAG, "设置系统英式声线失败")
                    return false
                }
            }
            if (code < 0) {
                val fallback = tts.setLanguage(Locale(target.language))
                android.util.Log.w(TAG, "引擎没有 ${target.toLanguageTag()}（code=$code），退回同语言结果 $fallback")
            }
        } catch (bad: Exception) {
            android.util.Log.w(TAG, "设置语言失败：${bad::class.java.simpleName}: ${bad.message}")
            return false
        }
        return true
    }

    /**
     * 能力重探测：**装完语音数据回到 App 必须能用**。
     *
     * `start()` 见 `engine != null` 就直接 return，而那个实例正是「当时没有英语语音」的那个 ——
     * 于是用户照提示装完语言包回来，还是发不出声，只能杀进程重开。
     * 这里释放旧实例、把候选引擎重新走一遍。
     */
    fun refresh() {
        if (disposed) return
        android.util.Log.i(TAG, "重新探测朗读能力")
        teardown()
        lastFailure = null
        start()
    }

    fun stop() { try { engine?.stop() } catch (_: Exception) {} }

    fun shutdown() { disposed = true; handler.removeCallbacks(probe); handler.removeCallbacks(timeout); teardown() }

    private fun teardown() {
        ready = false
        val old = engine; engine = null
        try { old?.stop(); old?.shutdown() } catch (_: Exception) {}
    }

    /**
     * 打开系统的朗读设置。各 ROM 入口不统一（也有 `Settings` 里没有对应常量的情况），
     * 所以按「厂商设置页 → 语音数据安装 → 无障碍设置」依次试，能跳开哪个算哪个。
     */
    fun openSettings(): Boolean = listOf(
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    ).any { launch(it) }

    /** 让默认引擎拉起自己的语音包下载。 */
    fun installVoiceData(): Boolean = launch(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))

    private fun launch(intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(app.packageManager) == null) false else { app.startActivity(intent); true }
    } catch (bad: Exception) {
        android.util.Log.w(TAG, "打开语音设置失败：${bad::class.java.simpleName}: ${bad.message}"); false
    }

    /**
     * 引擎级失败：这台设备/这个引擎**真的不能用**，就绪状态作废，必须重新初始化。
     * 只用于 NO_ENGINE / INIT_FAILED / NO_ENGLISH_VOICE。
     */
    private fun fail(problem: SpeechProblem) {
        android.util.Log.w(TAG, "朗读不可用：" + problem.name)
        lastFailure = problem; ready = false; onFailure(problem)
    }

    /**
     * 单次播放级失败：**不废掉就绪状态**。
     *
     * 媒体音量为 0、某一次合成报错，都不是「引擎坏了」—— 调高音量或再点一次就该出声。
     * 之前这两类也走 `fail()`，`ready` 被置 false，而 `start()` 又因 `engine != null` 直接 return，
     * 结果是**调高音量也永远恢复不了，只能杀 App**（plan/56 §4.2 第 6 项）。
     */
    private fun report(problem: SpeechProblem) {
        android.util.Log.w(TAG, "这一次朗读没发出声：" + problem.name)
        lastFailure = problem; onFailure(problem)
    }

    private companion object { const val TAG = "TongluSpeech" }
}

/** 把失败原因翻译成人话。`second` = 是否值得给「去设置」的入口。 */
fun speechHint(problem: SpeechProblem): Pair<String, Boolean> = when (problem) {
    SpeechProblem.NO_ENGINE -> "这台设备没有可用的朗读引擎。请在系统「文字转语音(TTS)」设置里选择或安装引擎（如在应用商店安装「Google 语音服务」），回来就能发音。" to true
    SpeechProblem.NO_ENGLISH_VOICE -> "朗读引擎已就绪，但缺少英语语音包。请在系统设置里下载 English 语音数据后再试。" to true
    SpeechProblem.NO_BRITISH_VOICE -> "系统没有可用的英式声音。可以：①到系统「文字转语音」里下载英国英语（English UK）语音数据；" +
        "②到英语 → 声音设置 → 口音「英式」里下载英式离线声线（不依赖系统语音包）。" +
        "在拿到英式声音之前，这里的朗读不会用美音代替 —— 要听美音请在单词页把口音切成「美音」。" to true
    SpeechProblem.MUTED -> "媒体音量为 0，调大音量后再听。" to false
    SpeechProblem.INIT_FAILED -> "朗读引擎没能初始化，请到系统「文字转语音(TTS)」设置页试听排错。" to true
    SpeechProblem.PLAY_FAILED -> "朗读失败，请到系统「文字转语音(TTS)」设置页试听排错。" to true
}
