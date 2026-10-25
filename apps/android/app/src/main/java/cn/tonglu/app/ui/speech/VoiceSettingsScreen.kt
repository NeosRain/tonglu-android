package cn.tonglu.app.ui.speech

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.tonglu.app.data.speech.SpeechResumeRefresh
import cn.tonglu.app.data.SpeechProblem
import cn.tonglu.app.data.speech.Accent
import cn.tonglu.app.data.speech.SpeechCatalog
import cn.tonglu.app.data.speech.SpeechPurpose
import cn.tonglu.app.data.speech.SpeechRequest
import cn.tonglu.app.data.speech.SpeechResult
import cn.tonglu.app.data.speech.SpeechService
import cn.tonglu.app.data.speech.SpeechPreference
import cn.tonglu.app.data.speech.SpeechVoice
import cn.tonglu.app.data.speech.SoundSource
import cn.tonglu.app.data.speech.VoiceGender
import cn.tonglu.app.data.speech.TtsDownloadMetrics
import cn.tonglu.app.data.speech.TtsDownloadSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import cn.tonglu.app.data.speechHint
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion

/**
 * 声音设置（48 §7 / 47 §3 P3）。
 *
 * 这一页要回答用户三个问题：**现在能用哪条声音、它是什么来路、我还能拿到什么**。
 *
 * 三条如实纪律：
 * 1. 口音筛选「美式 / 英式」，每个口音下只列**真实存在**的声音；缺的组合显示为
 *    「待提供」且**不可选、不可试听** —— 不设两个漂亮开关却共用同一个声音。
 * 2. 「朗读声音」（系统朗读/将来的离线模型）与「材料自带录音」**分开说**：
 *    改朗读默认**不会**替换材料里那段真人/合成录音（47 明确要求）。
 * 3. 失败分类说清（没有引擎 / 缺英语语音包 / 音频重启失败…），不统一报「没有声音」。
 */
@Composable
internal fun VoiceSettingsScreen(
    owner: String,
    onClose: () -> Unit,
    onLookupWord: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val resumeRefresh = remember { SpeechResumeRefresh() }
    var engineRevision by remember { mutableStateOf(0) }
    // 旧实例 shutdown 后不可再次 start，返回系统设置必须创建新实例。
    val service = remember(context, engineRevision) { SpeechService(context) }
    val preference = remember(owner) { SpeechPreference(context) }
    var accent by remember(owner) { mutableStateOf(preference.accent(owner)) }
    var systemReady by remember(service) { mutableStateOf(false) }
    /**
     * 系统朗读能不能发**当前口音**。英式与美式必须分开判 ——
     * 只有一个总开关时，「系统朗读（本机自带）」在英式那一列会被标成可用/使用中，
     * 点下去才弹「发不出声音：系统没有可用的英式声音」（2026-10-15 用户截图那一幕）。
     */
    val systemSpeaksAccent = systemReady && service.systemSupports(accent)
    /** 系统发不出这个口音、改用已下载的离线声线时的一句说明（行内显示，不拿弹窗盖页面）。 */
    var accentNote by remember { mutableStateOf<String?>(null) }
    var current by remember(service, owner) { mutableStateOf(service.current) }
    var problem by remember { mutableStateOf<SpeechProblem?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var audioPreview by remember { mutableStateOf(false) }
    if (audioPreview) AudioPackPreview(onClose = { audioPreview = false })

    /** 正在下载的模型 id。非空时那一行显示进度条，不再显示「下载」按钮。 */
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var downloadSnapshot by remember { mutableStateOf(TtsDownloadSnapshot(0L, 0L, 0L, null)) }
    /**
     * 装/删之后 +1，用来让声音列表**重新算**。
     * 少了这一步，下载完界面还显示「未下载」，用户会以为下载没生效。
     */
    var voiceVersion by remember { mutableStateOf(0) }

    // 自检在 IO 线程跑（加载神经网络 + 合成都是秒级的重活），跑完把结果弹给用户看。
    LaunchedEffect(testing) {
        if (!testing) return@LaunchedEffect
        val modelId = service.installedModelIds().firstOrNull()
        if (modelId == null) {
            testing = false
            notice = "还没有装好的离线模型，先下载一条再自检。"
            return@LaunchedEffect
        }
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            service.selfTestBuiltin(modelId, java.io.File(context.filesDir, "tts_selftest_$modelId.wav"))
        }
        testing = false
        notice = if (result.ok) {
            "自检通过（$modelId）：已合成 ${result.bytes} 字节的 wav（采样率 ${result.sampleRate} Hz）。" +
                "\n文件：${result.file.absolutePath}"
        } else {
            "自检失败（$modelId）：${result.message}"
        }
    }

    // 模型清单要联网拉一次。**必须在 IO 线程**：Compose 的 remember/L mutableState
    // 都在主线程，直接在那里联网会撞 NetworkOnMainThreadException，
    // 表现是「列表永远只有兜底那两条，新加的模型看不见」。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            service.refreshModels()
        }
        voiceVersion++
    }

    // 下载在 IO 线程跑，进度回主线程刷进度条。
    LaunchedEffect(downloadingId) {
        val id = downloadingId ?: return@LaunchedEffect
        val model = service.modelOf(id)
        if (model == null) {
            downloadingId = null
            notice = "清单里没有这个模型：$id"
            return@LaunchedEffect
        }
        downloadSnapshot = TtsDownloadSnapshot(0L, model.bytes, 0L, null)
        // IO 只投递最新快照，Compose 状态统一在主线程修改。
        val updates = Channel<TtsDownloadSnapshot>(Channel.CONFLATED)
        val collector = launch { for (snapshot in updates) downloadSnapshot = snapshot }
        val deferred = async {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val metrics = TtsDownloadMetrics(android.os.SystemClock.elapsedRealtime())
                val downloadContext = coroutineContext
                service.downloadModel(model) { done, total ->
                    downloadContext.ensureActive()
                    metrics.sample(done, total, android.os.SystemClock.elapsedRealtime())?.let { updates.trySend(it) }
                }
            }
        }
        var cancelledByUser = false
        val error = try {
            deferred.await()
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            // 按钮只掐网络：保留忙状态直到旧IO与清理结束，避免立即重试覆盖临时文件。
            cancelledByUser = true
            null
        } finally {
            updates.close()
            collector.join()
        }
        downloadingId = null
        voiceVersion++
        notice = if (cancelledByUser) "已取消下载，可随时重新点「下载」。"
        else if (error == null) "「${model.name}」下载完成（${model.sizeLabel}），可以离线朗读了。"
        else "「${model.name}」下载失败：$error（可在列表里再点「下载」重试）"
    }

    // 🔴 取消及时性（S103 遗留）：LaunchedEffect 的协程取消只能等阻塞 read 到下一块
    // 或 60s readTimeout。这里在 effect 销毁那一刻（离开页面 / 切换下载目标）直接
    // cancelDownload 掐断 HTTP 连接；用户点「取消」的路径在按钮回调里同样调它。
    // 下载正常结束时也会触发一次（id 已注销、动作为空、标记由下轮 clearCancel 清），
    // requestCancel 幂等，无副作用。
    DisposableEffect(downloadingId) {
        val startedId = downloadingId
        onDispose {
            startedId?.let { cn.tonglu.app.data.speech.TtsModelStore.cancelDownload(it) }
        }
    }

    // 恢复上次选的声音（若还存在且可用）；解析不出来时**按口音挑一条真的发得出声的**，
    // 而不是一律退回系统朗读 —— 系统朗读发不出英式时，那条路是死的（弹「发不出声音」）。
    // 解析与发音共用 SpeechService.voiceFor，界面显示和实际发声不会再是两套逻辑。
    LaunchedEffect(owner, service, systemReady, voiceVersion, accent) {
        val decision = service.voiceFor(accent, preference.voiceId(owner))
        current = if (service.select(decision.voice)) service.current else decision.voice
        accentNote = decision.note
    }

    DisposableEffect(service) {
        service.start()
        onDispose { service.shutdown() }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> resumeRefresh.onPause()
                Lifecycle.Event.ON_RESUME -> if (resumeRefresh.onResume()) engineRevision++
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // 引擎就绪与否只在真问过之后才知道：起手按「未就绪」渲染，就绪后 UI 自己刷新。
    //
    // **注意：不要把 start() 探到的失败原因直接弹成对话框** —— 这台设备没有 TTS 引擎，
    // 页面本身（口音筛选、离线候选、"这个能力到哪一步了"的说明）照样有意义，
    // 一进来就糊一个「发不出声音」会把整页盖住（P3 走查踩过）。失败提示只在
    // **用户真的点「试听」**那一刻出现，由那次 speak 的结果触发（见 onPreview）。
    LaunchedEffect(service) {
        var waited = 0
        while (waited < 6_500 && !service.systemReady) {
            kotlinx.coroutines.delay(250); waited += 250
        }
        systemReady = service.systemReady
        // 就绪变化统一交给恢复偏好的 effect，避免两个协程互相覆盖口音与声线。
    }

    val voices = remember(accent, systemReady, voiceVersion, engineRevision) {
        SpeechCatalog.voices(accent, systemSpeaksAccent, context)
    }

    // 弹层自识别左边缘拖动（见 EnglishHubScreen 里的同一段说明）。
    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )
    Dialog(
        onDismissRequest = { motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { motion.requestClose() }) {   // 走 motion：与返回键同一条退场动画
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回英语")
                    }
                    Text("声音设置", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }

                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        OutlinedButton(onClick = { service.stop(); audioPreview = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("Heart / Emma 试听样本")
                        }
                    }
                    item {
                        Text(
                            "Heart / Emma 已接入随包四、六级精简词书，选为默认后点击单词发音即可。首次联网下载，缓存后可离线；任意例句与文章尚未覆盖。\n\n这里选的是「朗读用什么声音」。资料材料里自带的录音（真人或已合成好的音轨）" +
                                "不受这里影响 —— 换声音不会替换它，也不会假装改变了它。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // —— 口音筛选（48 §7：美式 / 英式）——
                    item {
                        Text("口音", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Accent.entries.forEach { option ->
                                FilterChip(
                                    selected = accent == option,
                                    onClick = {
                                        // 只做两件事：换口音、记偏好。选哪条声线交给上面的恢复 effect
                                        // 统一解析（键里有 accent）—— 这里是「一个口音一条出路」，
                                        // 以前那句「if (current.accent != option) 就退回系统朗读」，
                                        // 在系统发不出该口音的机器上等于把用户送进死路。
                                        // 偏好：只有口音真的变了才清旧声线（见 SpeechPreference.save）。
                                        preference.save(owner, null, option)
                                        accent = option
                                    },
                                    label = { Text(option.label) },
                                )
                            }
                        }
                        // 系统朗读发不出这个口音时，把「实际会用哪条声音、为什么」写在页面上。
                        // 用行内文字而不是弹窗：进页面就糊一个对话框会把整页盖住（P3 走查踩过）。
                        accentNote?.let { note ->
                            Text(
                                note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        }
                    }

                    // —— 声音行 ——
                    items(voices, key = { it.id }) { voice ->
                        VoiceRow(
                            voice = voice,
                            selected = voice.id == current.id,
                            downloading = downloadingId == voice.modelId && voice.modelId.isNotBlank(),
                            progress = downloadSnapshot.progress,
                            downloadLabel = if (downloadSnapshot.total > 0L && downloadSnapshot.done >= downloadSnapshot.total)
                                "下载完成，正在校验并安装…" else downloadSnapshot.label,
                            downloadEnabled = downloadingId == null,
                            onDownloadCancel = {
                                // 先掐连接再清状态：downloadingId 置空会销毁下载 effect，
                                // DisposableEffect 的 onDispose 兜底再 cancel 一次（幂等）。
                                cn.tonglu.app.data.speech.TtsModelStore.cancelDownload(voice.modelId)
                                notice = "正在取消下载，清理完成后可重试…"
                            },
                            onDownload = {
                                val id = voice.modelId
                                if (id.isBlank()) {
                                    notice = "这条声音没有对应的离线模型，暂时无法下载。"
                                } else {
                                    // 一个界面同时只下载一个模型，避免双击重启及多声线共用模型竞争。
                                    if (downloadingId == null) {
                                        cn.tonglu.app.data.speech.TtsModelStore.prepareDownload(id)
                                        downloadingId = id
                                    }
                                }
                            },
                            onPreview = {
                                // 离线模型第一次要加载几十兆的神经网络，给个明确交代，
                                // 否则用户点了没声音会以为坏了。
                                if (voice.source == SoundSource.BuiltinVoice && !service.builtinReady) {
                                    notice = "正在加载离线语音模型（首次约几秒），加载完就会朗读。"
                                }
                                when (val result = service.speak(
                                    SpeechRequest(
                                        text = if (voice.source == SoundSource.CloudVoice) "absorb" else previewText(voice), accent = accent,
                                        // locale 必须跟预览文本的语言一致：默认是 en，
                                        // 不传的话试听中文声线会被当成「拿中文声线读英文」而降级到系统朗读。
                                        locale = when (voice.accent) { Accent.Chinese -> "zh-CN"; Accent.British -> "en-GB"; Accent.American -> "en-US" },
                                        voiceId = voice.id, purpose = SpeechPurpose.Preview,
                                        utteranceId = "preview-${voice.id}",
                                    ),
                                )) {
                                    is SpeechResult.Failed -> problem = result.problem
                                    is SpeechResult.Unavailable -> notice = result.reason
                                    is SpeechResult.Degraded -> notice = result.reason
                                    SpeechResult.Spoken -> Unit
                                }
                            },
                            onSelect = {
                                if (service.select(voice)) {
                                    current = service.current
                                    preference.save(owner, voice.id, accent)
                                    notice = "已把「${voice.name}」设为朗读声音。"
                                } else {
                                    notice = "这条声音还不能用：${voice.unavailableReason.ifBlank { "尚未提供" }}"
                                }
                            },
                        )
                    }

                    // —— 离线语音进度说明（如实）——
                    item {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                                Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    SpeechCatalog.offlineNote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    // —— 自检：不靠"点了没报错"判断有没有声音，落一个 wav 才算数 ——
                    item {
                        TextButton(
                            onClick = { if (!testing) testing = true },
                            enabled = !testing,
                        ) {
                            Text(if (testing) "正在自检…（首次要加载模型，约几秒）" else "自检离线语音（合成并保存一段 wav）")
                        }
                    }

                    // —— 材料自带录音，单独一行说清 ——
                    item {
                        Text("材料自带录音", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    item {
                        Text(
                            "听说材料里的原音是随材料一起下发的（可能是真人录音，也可能是预先合成好的音轨，材料页会写明来源）。" +
                                "它不经过这里，也不会因为换了朗读声音而改变。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    notice?.let { message ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { notice = null },
            title = { Text("提示") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { notice = null }) { Text("知道了") } },
        )
    }

    problem?.let { issue ->
        val (hint, openable) = speechHint(issue)
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surface,
            onDismissRequest = { problem = null },
            title = { Text("发不出声音") }, text = { Text(hint) },
            confirmButton = { TextButton(onClick = { problem = null }) { Text("知道了") } },
            dismissButton = {
                if (openable) TextButton(onClick = {
                    if (!service.installVoiceData() && !service.openSystemSettings()) {
                        notice = "这台设备没有可跳转的语音设置页，请到系统设置里搜索「文字转语音」。"
                    }
                    problem = null
                }) { Text("去系统设置") }
            },
        )
    }
}

/**
 * 试听文本：同一口音内用**同一句、同语速**，方便同条件比声音（48 §7）。
 *
 * 但**不能用英文句子去试中文模型** —— 中文 VITS 遇到英文会念成一串怪音，
 * 那样试听出来「声音很怪」是试听方式的错，不是声音的错。
 */
private fun previewText(voice: SpeechVoice): String =
    if (voice.accent == Accent.Chinese) "这是离线中文语音的试听，今天天气不错。"
    else "The quick brown fox jumps over the lazy dog."

@Composable
private fun VoiceRow(
    voice: SpeechVoice,
    selected: Boolean,
    onPreview: () -> Unit,
    onSelect: () -> Unit,
    /** 正在下载这条声线对应的模型。 */
    downloading: Boolean = false,
    /** 下载进度 0~1。 */
    progress: Float = 0f,
    downloadLabel: String = "",
    downloadEnabled: Boolean = true,
    /** 点「取消」：下载中才有意义。掐断连接并回到可重试状态。 */
    onDownloadCancel: () -> Unit = {},
    /** 点「下载」。离线声线没装模型时才有意义。 */
    onDownload: () -> Unit = {},
) {
    // 状态文案要分清三种「不可用」，混成一句会让用户误会是谁的问题：
    // - 离线声线**没下载模型** → 「未下载模型」（点一下就能有，是用户的选择）
    // - 离线声线**没有模型可选** → 「待提供」（是我们还没做）
    // - 系统朗读在没引擎的机器上 → 「本机不可用」（是这台设备的系统缺引擎）
    //
    // 还有一条：**被选中但不可用时不能说「使用中」** —— 这台设备没引擎却显示「使用中」，
    // 是在骗用户。改成如实说「已选 · 本机不可用」。
    val status = when {
        downloading -> "下载中"
        selected && voice.available -> "使用中"
        selected -> "已选 · 本机不可用"
        voice.available -> "可用"
        voice.source == SoundSource.SystemTts -> "本机不可用"
        voice.modelId.isNotBlank() -> "未下载模型"
        else -> "待提供"
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 2.dp else 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(voice.name, style = MaterialTheme.typography.bodyLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tag(voice.gender.label)
                    Spacer(Modifier.width(4.dp))
                    Tag(voice.accent.label)
                    Spacer(Modifier.width(4.dp))
                    Tag(when (voice.source) {
                        SoundSource.SystemTts -> "系统"
                        SoundSource.BuiltinVoice -> "离线"
                        SoundSource.CloudVoice -> "云端音频"
                    })
                }
                Text(
                    buildString {
                        append(status)
                        append(" · ")
                        append(if (voice.source == SoundSource.CloudVoice) "按需下载 · 缓存占用空间" else voice.sizeLabel)
                        if (voice.engineVersion.isNotBlank()) { append(" · "); append(voice.engineVersion) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!voice.available && voice.unavailableReason.isNotBlank()) {
                    Text(voice.unavailableReason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                if (voice.license.isNotBlank()) {
                    Text("许可：${voice.license}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (voice.note.isNotBlank()) Text(voice.note, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (downloading) {
                // 下载几十兆要给看得见的进度，不然用户以为卡住了；取消必须真掐断（S103 遗留）。
                Column(horizontalAlignment = Alignment.End) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.width(84.dp),
                    )
                    Text("${(progress.coerceIn(0f, 1f) * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onDownloadCancel) { Text("取消") }
            } else if (!voice.available && voice.modelId.isNotBlank()) {
                // 没装模型就先给「下载」，**不给「试听」** —— 摆一个点了没反应的按钮是骗人的。
                TextButton(onClick = onDownload, enabled = downloadEnabled) { Text("下载") }
            } else {
                // 不可用的组合不能试听也不能设默认 —— 不摆点了没反应的按钮。
                TextButton(onClick = onPreview, enabled = voice.available) { Text("试听") }
                if (selected) {
                    Icon(Icons.Outlined.Check, "使用中", tint = MaterialTheme.colorScheme.primary)
                } else {
                    TextButton(onClick = onSelect, enabled = voice.available) { Text("设为默认") }
                }
            }
            }
            if (downloading) Text(downloadLabel, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
