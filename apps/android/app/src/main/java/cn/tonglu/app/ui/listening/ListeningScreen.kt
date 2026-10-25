package cn.tonglu.app.ui.listening

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.ApiFactory
import cn.tonglu.app.data.listening.LearningPack
import cn.tonglu.app.data.listening.LearningPacks
import cn.tonglu.app.data.listening.ListeningLibrary
import cn.tonglu.app.data.listening.ListeningMaterial
import cn.tonglu.app.data.listening.ListeningPlayer
import cn.tonglu.app.data.listening.ListeningRecorder
import cn.tonglu.app.data.listening.PackStage
import cn.tonglu.app.data.listening.PracticeStore
import cn.tonglu.app.data.listening.candidateWords
import cn.tonglu.app.data.listening.splitSentences
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

private fun clock(ms: Int): String {
    val total = (ms.coerceAtLeast(0)) / 100
    val m = total / 600
    val s = (total % 600) / 10
    val d = total % 10
    return String.format(Locale.US, "%d:%02d.%d", m, s, d)
}

/**
 * 精听跟读工作区（docs/plan/37 §六）。
 *
 * 四步闭环：**先听 → 看句子 → 跟读 → 生词留作下次任务**。
 * 三条硬边界，界面上处处体现：
 * 1. **没有时间轴就老实说没有** —— 不能按字数均分音频假装逐句对齐；
 * 2. **不打分** —— 只有"过 / 再练"两个手动自评，ASR 对不代表发音准；
 * 3. **材料是私人导入，只在本机、只对本账号可见**，不进目录、不同步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListeningScreen(
    owner: String,
    onClose: () -> Unit,
    onLookupWord: (String) -> Unit,
    /**
     * 进来就打开哪份材料（47 §3 P4：从广场帖子的资料卡「加入学习」后**直接打开**）。
     * 只认第一帧：设一次 studioId 就够，之后用户在里面切材料/返回都由本页自己管，
     * 不能每次重组都把它拉回同一份。
     */
    initialMaterialId: String? = null,
) {
    val context = LocalContext.current
    val library = remember(owner) { ListeningLibrary.of(context, owner) }
    val player = remember { ListeningPlayer() }
    val preview = remember { ListeningPlayer() }
    val recorder = remember(owner) { ListeningRecorder(context, library.recordDir) }

    var materials by remember(owner) { mutableStateOf(library.list()) }
    var studioId by remember { mutableStateOf<String?>(initialMaterialId) }
    // initialMaterialId 变化时才重置（Compose 里用 key 语义最直白）：
    LaunchedEffect(initialMaterialId) {
        if (initialMaterialId != null) {
            materials = library.list()
            studioId = initialMaterialId
        }
    }
    var importing by remember { mutableStateOf(false) }
    /** 「资料库」：服务器上的可下载学习包。null = 没进这个页面。 */
    var libraryOpen by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    DisposableEffect(player, preview) { onDispose { player.release(); preview.release() } }
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    fun toast(text: String) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }

    // 🔴 与 EnglishStudyScreen 同一个病、同一套改法（**先判断、后动画**）。
    //  听说页有三层内部状态（导入中 / 资料库 / 精听工作区），只做内部返回时**层不该关**；
    //  老写法 `onClose = { back() }` 配 `onDismissRequest = { motion.requestClose() }`，
    //  而 requestClose() 先播退场动画再调 back() → 层被动画到 alpha=0 却没关：
    //  看不见、还吃触摸，且 closing=true 之后再也关不掉（只能杀进程）。
    //  motion 必须声明在 back() **之前**（局部函数不能引用后面的变量）。
    val motion = rememberOverlayMotion(
        onClose = {
            player.release(); preview.release(); recorder.cancel()
            OverlayDismissGuard.arm(); onClose()
        },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
        // 有内部层级时不接管返回：交给 onDismissRequest → back() 做内部返回。
        backEnabled = !importing && !libraryOpen && studioId == null,
    )
    fun back() {
        when {
            importing -> importing = false
            libraryOpen -> libraryOpen = false
            studioId != null -> { player.pause(); preview.pause(); studioId = null }
            else -> motion.requestClose()
        }
    }

    // 弹层自识别左边缘拖动（Dialog 收不到系统进度，见 EnglishHubScreen 里的说明）。
    Dialog(onDismissRequest = { back() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false, onBack = { back() }, backEnabled = !importing), color = MaterialTheme.colorScheme.background) {
            val material = materials.firstOrNull { it.id == studioId }
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack,
                            if (studioId != null || importing || libraryOpen) "返回" else "关闭")
                    }
                    Text(
                        when {
                            libraryOpen -> "资料库 · 一键加入"
                            material != null -> "精听跟读"
                            else -> "精听跟读 · 我的材料"
                        },
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (material == null && !importing && !libraryOpen) IconButton(onClick = { importing = true }) {
                        Icon(Icons.Outlined.Add, "导入材料")
                    }
                }
                notice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
                when {
                    libraryOpen -> PackLibraryPane(library = library, onDone = { materialId ->
                        materials = library.list()
                        libraryOpen = false
                        studioId = materialId
                    }, onCancel = { libraryOpen = false }, onNotice = { notice = it })
                    importing -> ImportPane(onCancel = { importing = false }) { title, uri, name, text ->
                        try {
                            val audio = library.importAudio(uri, name)
                            val sentences = splitSentences(text).map { cn.tonglu.app.data.listening.ListeningSentence(it) }
                            val created = ListeningMaterial(id = UUID.randomUUID().toString(), title = title,
                                audio = audio, audioName = name, sentences = sentences)
                            library.add(created)
                            materials = library.list()
                            importing = false
                            studioId = created.id
                        } catch (cause: Exception) {
                            android.util.Log.e("ListeningStore", "导入材料失败", cause)
                            notice = "导入失败：${cause.message ?: "请换一个文件试试"}"
                        }
                    }
                    material != null -> StudioPane(material = material, library = library, player = player,
                        preview = preview, recorder = recorder, tick = tick, owner = owner,
                        onTick = { tick++ }, onChanged = { materials = library.list() },
                        onNotice = { notice = it }, onToast = ::toast, onLookupWord = onLookupWord)
                    else -> MaterialPane(materials = materials,
                        onOpen = { studioId = it },
                        onDelete = { confirmDelete = it },
                        onImport = { importing = true },
                        onBrowseLibrary = { libraryOpen = true })
                }
            }
        }
    }

    confirmDelete?.let { id ->
        val target = materials.firstOrNull { it.id == id } ?: return@let
        Dialog(onDismissRequest = { confirmDelete = null }) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("删除这份材料？", fontWeight = FontWeight.SemiBold)
                    Text("会一起删掉它的音频和跟读录音。词库、笔记和别的材料不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { confirmDelete = null }) { Text("取消") }
                        TextButton(onClick = {
                            library.delete(id); materials = library.list(); confirmDelete = null
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaterialPane(materials: List<ListeningMaterial>, onOpen: (String) -> Unit,
                         onDelete: (String) -> Unit, onImport: () -> Unit, onBrowseLibrary: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "starter-listening") { StarterListeningCard() }
        item {
            // 资料库入口放在最上面：44 §九 明确「不要继续要求普通用户手选音频、粘文本和手动打点」，
            // 所以「从资料库一键加入」是主路径，手工导入收在下面当高级入口。
            OutlinedCard(onClick = onBrowseLibrary, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("资料库", fontWeight = FontWeight.SemiBold)
                        Text("选一份现成的，点一下加入就能听、跟读、做题",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.Add, "打开资料库", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Text("也可以导入自己的音频和文本，按「听 → 打点 → 逐句跟读」练。\n" +
                "自己导入的材料只存在这台手机的私有目录里，不上传、不同步、不进公开目录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (materials.isEmpty()) item {
            OutlinedCard(onClick = onImport, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("还没有材料", fontWeight = FontWeight.SemiBold)
                    Text("点这里导入一段音频 + 对应文本", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        itemsIndexed(materials, key = { _, it -> it.id }) { _, m ->
            OutlinedCard(onClick = { onOpen(m.id) }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(m.title, fontWeight = FontWeight.SemiBold)
                        Text("${m.sentences.size} 句 · 已对齐 ${m.aligned}/${m.sentences.size}" +
                            (if (m.words.isNotEmpty()) " · 生词 ${m.words.size}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("原音：${m.audioName}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onDelete(m.id) }) { Icon(Icons.Outlined.Delete, "删除材料") }
                }
            }
        }
    }
}

/**
 * 资料库（docs/plan/44 §七 A）：服务器上的学习包，点一次就下载、校验、入库。
 *
 * 三条界面纪律：
 * 1. **按钮态必须反映真实状态** —— 没下过是「加入学习」，装好了是「继续学习」，
 *    服务器有新版本才显示「更新」。不给用户一个永远写着「加入学习」的按钮。
 * 2. **下载全程可取消** —— 弱网下最怕点了没反应又退不出去。
 * 3. **失败要说人话** —— 校验不过 / 断网 / 存储满了分得清，不糊成一句「网络问题」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackLibraryPane(library: ListeningLibrary, onDone: (String) -> Unit,
                            onCancel: () -> Unit, onNotice: (String) -> Unit) {
    val context = LocalContext.current
    val api = remember { ApiFactory.create(readTimeoutSeconds = 90L) }
    val scope = rememberCoroutineScope()

    var packs by remember { mutableStateOf<List<LearningPack>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    /** 正在安装的包 id → 进度文案。同一时刻只允许一个在装，避免并发写同一个库。 */
    var busyId by remember { mutableStateOf<String?>(null) }
    var busyText by remember { mutableStateOf("") }
    /** 装完后刷新按钮态用；每次状态变化 +1 触发重组。 */
    var revision by remember { mutableIntStateOf(0) }

    // 进来就拉清单。失败不清空已有列表 —— 离线时还能看到上次的目录。
    LaunchedEffect(Unit) {
        loading = true
        val rows = LearningPacks.catalog(context, api)
        packs = rows
        error = if (rows.isEmpty()) "没能取到资料库清单，检查网络后再试" else null
        loading = false
    }

    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("这些是平台已核权的现成材料，点一下就会下载到本机，之后完全离线可用。\n" +
                "不需要 AI 配置，也不需要别的语音引擎。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) item {
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalArrangement = Arrangement.Center) { Text("正在取清单…") }
        }
        error?.let { message -> if (packs.isEmpty()) item {
            OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        scope.launch {
                            loading = true; error = null
                            val rows = LearningPacks.catalog(context, api)
                            packs = rows
                            error = if (rows.isEmpty()) "还是没取到，稍后再试" else null
                            loading = false
                        }
                    }) { Text("重试") }
                }
            }
        } }
        if (!loading && packs.isEmpty() && error == null) item {
            OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("资料库还是空的", fontWeight = FontWeight.SemiBold)
                    Text("管理员还没发布内容，先导一份自己的音频练着。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        itemsIndexed(packs, key = { _, it -> it.packageId }) { _, pack ->
            // 每次 revision 变化都重算按钮态：装完立刻从「加入学习」变成「继续学习」。
            val state = remember(pack.packageId, revision) { LearningPacks.state(context, library, pack) }
            PackCard(
                pack = pack, state = state, busy = busyId == pack.packageId, busyText = busyText,
                onAction = {
                    if (busyId != null) return@PackCard
                    when (state) {
                        is LearningPacks.PackState.Installed -> onDone(state.row.materialId)
                        else -> scope.launch {
                            busyId = pack.packageId
                            busyText = "准备中…"
                            try {
                                val material = LearningPacks.install(context, api, library, pack) { stage ->
                                    busyText = when (stage) {
                                        PackStage.FetchingManifest -> "正在取清单…"
                                        is PackStage.Downloading -> if (stage.total > 0)
                                            "下载中 ${stage.done * 100 / stage.total}%"
                                        else "下载中…"
                                        PackStage.Installing -> "正在入库…"
                                        is PackStage.Done -> "完成"
                                    }
                                }
                                busyId = null; revision++
                                onDone(material.id)
                            } catch (cause: Exception) {
                                android.util.Log.e("LearningPacks", "加入资料包失败", cause)
                                busyId = null; revision++
                                onNotice(cause.message ?: "加入失败，请重试")
                            }
                        }
                    }
                },
                onCancelBusy = { busyId = null; onNotice("已取消下载") },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackCard(pack: LearningPack, state: LearningPacks.PackState, busy: Boolean, busyText: String,
                     onAction: () -> Unit, onCancelBusy: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(pack.title, fontWeight = FontWeight.SemiBold)
            if (pack.summary.isNotBlank()) {
                Text(pack.summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val meta = buildList {
                if (pack.capabilityLabel.isNotBlank()) add(pack.capabilityLabel)
                add(pack.sizeLabel)
                if (pack.author.isNotBlank()) add(pack.author)
                if (pack.level.isNotBlank()) add(pack.level)
            }
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // 许可来源如实展示：内容不是我们原创的，用户有权知道能不能再分发。
            if (pack.attribution.isNotBlank()) {
                Text(pack.attribution, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    Text(busyText, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onCancelBusy) { Text("取消") }
                } else {
                    when (state) {
                        is LearningPacks.PackState.Installed -> {
                            Text("已加入 · v${state.row.version}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                            Button(onClick = onAction) { Text("继续学习") }
                        }
                        is LearningPacks.PackState.Update -> {
                            Text(
                                if (state.repair) "本机缺题目 · v${state.installedVersion}"
                                else "本机 v${state.installedVersion}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f))
                            Button(onClick = onAction) {
                                Text(if (state.repair) "补齐内容" else "更新到 v${state.newVersion}")
                            }
                        }
                        LearningPacks.PackState.Available -> {
                            OutlinedButton(onClick = onAction, modifier = Modifier.weight(1f)) { Text("加入学习") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportPane(onCancel: () -> Unit,
                       onSave: (title: String, uri: android.net.Uri, name: String, text: String) -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var audioUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var audioName by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        }.getOrNull() ?: "audio"
        audioUri = uri; audioName = name
        if (title.isBlank()) title = name.substringBeforeLast('.')
    }
    val pickText = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val loaded = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (loaded.isNullOrBlank()) error = "这个文本文件是空的"
        else { text = loaded; error = null }
    }

    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("材料只在你这台手机上：音频会被复制进 App 私有目录，删材料时一起清掉。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedTextField(value = title, onValueChange = { title = it },
                label = { Text("材料名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickAudio.launch(arrayOf("audio/*")) }) { Text("选择音频") }
                Text(if (audioName.isBlank()) "还没选" else audioName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { pickText.launch(arrayOf("text/*")) }) { Text("从 txt 导入文本") }
                Text("也可以直接在下面粘贴", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
        item {
            OutlinedTextField(value = text, onValueChange = { text = it },
                label = { Text("文本（按句末标点或换行分句）") },
                modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 12)
        }
        error?.let { item { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val uri = audioUri
                    when {
                        uri == null -> error = "先选一个音频文件"
                        text.isBlank() -> error = "文本不能为空"
                        title.isBlank() -> error = "给材料起个名字"
                        else -> onSave(title.trim(), uri, audioName.ifBlank { "audio" }, text)
                    }
                }) { Text("保存并开始") }
                TextButton(onClick = onCancel) { Text("取消") }
            }
        }
    }
}

/**
 * 句子训练的显式状态（47 §3 P1.3）：
 *
 * `Idle`（没在练）→ `Playing`（正在播本句）→ `AwaitingMic`（等麦克风授权）
 * → `Recording`（正在录）→ `Review`（录好了，可回听/重录）→ 回 `Idle`。
 *
 * 为什么要有状态机而不是两个独立按钮：用户抱怨的是「点播放听一句、还得再去点录一遍」。
 * 把两个动作合成一个主按钮，就必须有一个明确的「我现在在哪一步」，
 * 否则切句、退层、来电打断时不知道该怎么收拾 —— 那才会串句、丢录音。
 */
internal enum class SentenceStage { Idle, Playing, AwaitingMic, Recording, Review }

/** 工作区的两条主线：句子训练（听/录）与做题（47 §3 P2）。 */
private enum class StudioTab { Sentence, Question }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioPane(material: ListeningMaterial, library: ListeningLibrary, player: ListeningPlayer,
                       preview: ListeningPlayer, recorder: ListeningRecorder, tick: Int, onTick: () -> Unit,
                       onChanged: () -> Unit, onNotice: (String?) -> Unit, onToast: (String) -> Unit,
                       onLookupWord: (String) -> Unit, owner: String) {
    val context = LocalContext.current
    var loaded by remember(material.id) { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1.0f) }
    var loop by remember { mutableStateOf(true) }
    var range by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var expanded by remember { mutableIntStateOf(-1) }
    var recording by remember { mutableStateOf(false) }
    var pendingRecord by remember { mutableIntStateOf(-1) }
    // 跟读模式：默认「先听后录」（播完自动开录），可选「同步跟读」（边播边录）。
    var syncMode by remember { mutableStateOf(false) }
    // 显式状态机的当前句子（-1 = 没有进行中的句子）。四种状态见 SentenceStage。
    var stage by remember { mutableStateOf(SentenceStage.Idle) }
    var activeIndex by remember { mutableIntStateOf(-1) }
    // 有题目时默认停在「句子训练」，做题是并排的第二条线，不抢占主流程。
    var tab by remember(material.id) { mutableStateOf(StudioTab.Sentence) }


    /** 真正开始录一句。授权通过后由 requestMic 回调调用。 */
    fun beginRecording(index: Int) {
        if (index < 0) return
        try {
            recorder.start("${material.id}-$index.m4a")
            recording = true
            activeIndex = index
            stage = SentenceStage.Recording
            expanded = index
        } catch (cause: Exception) {
            android.util.Log.e("ListeningStore", "开始录音失败", cause)
            onNotice("录音启动失败：${cause.message ?: "请检查麦克风权限"}")
            stage = if (activeIndex >= 0) SentenceStage.Review else SentenceStage.Idle
        }
    }

    val requestMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val index = pendingRecord
        pendingRecord = -1
        if (!granted) {
            // 拒绝权限不该把整句训练卡死：仍然能听，只是录不了。明确说清原因。
            onNotice("没给麦克风权限，这一句只能听、不能录。想要跟读请在系统设置里允许录音。")
            stage = SentenceStage.Idle
            activeIndex = -1
            return@rememberLauncherForActivityResult
        }
        beginRecording(index)
    }

    /** 结束当前录音并落到句子记录上；返回是否真的录到了文件。 */
    fun finishRecording(): Boolean {
        val index = activeIndex
        val file = recorder.stop()
        recording = false
        if (index < 0) return false
        return if (file != null) {
            library.setRecording(material.id, index, file.name, false)
            onChanged()
            stage = SentenceStage.Review
            true
        } else {
            onNotice("没录到声音，再试一次")
            stage = SentenceStage.Idle
            activeIndex = -1
            false
        }
    }

    /** 安全停止一切：切句、退层、被打断时统一走这里，避免串句或丢录音。 */
    fun stopAll() {
        if (recording) finishRecording() else { player.pause(); preview.pause() }
        playing = false
        range = null
        recording = false
        stage = SentenceStage.Idle
        activeIndex = -1
    }

    // 载入音频：只有原音，没有"合成音"这一路 —— 合成朗读要等内置语音放行后才接
    LaunchedEffect(material.id) {
        try {
            player.load(library.audioFile(material.audio))
            loaded = true
        } catch (cause: Exception) {
            android.util.Log.e("ListeningStore", "音频载入失败", cause)
            onNotice("音频载入失败：${cause.message ?: "这个文件可能已经不在了"}")
        }
    }
    // 整轨自然播完 → 若是「先听后录」，自动进入录音（这就是把「播放 + 录一遍」合成一个动作的关键）。
    DisposableEffect(player) {
        player.onCompleted = {
            if (stage == SentenceStage.Playing && !syncMode && activeIndex >= 0) {
                if (recorder.isRecording) { /* 已在录，忽略 */ }
                else { stage = SentenceStage.AwaitingMic; pendingRecord = activeIndex; requestMic.launch(android.Manifest.permission.RECORD_AUDIO) }
            }
        }
        onDispose { player.onCompleted = null }
    }
    // 播放位置每 100ms 刷一次；进度条与循环区间都靠它
    LaunchedEffect(material.id, loaded) {
        if (!loaded) return@LaunchedEffect
        while (true) { delay(100); onTick() }
    }
    LaunchedEffect(range, loop) {
        val r = range ?: return@LaunchedEffect
        while (true) {
            delay(120)
            if (!player.isPlaying) break
            if (player.positionMs >= r.second) {
                if (loop) player.seek(r.first) else { player.pause(); break }
            }
        }
    }
    // 单句区间播完 → 「先听后录」的自动开录触发点（区间结束不是轨尾，收不到 onCompleted）。
    LaunchedEffect(range, stage, activeIndex, syncMode) {
        val r = range ?: return@LaunchedEffect
        if (stage != SentenceStage.Playing || syncMode) return@LaunchedEffect
        while (true) {
            delay(100)
            if (stage != SentenceStage.Playing) break
            if (player.positionMs >= r.second - 30) {
                player.pause(); playing = false
                if (activeIndex >= 0 && !recorder.isRecording) {
                    stage = SentenceStage.AwaitingMic
                    pendingRecord = activeIndex
                    requestMic.launch(android.Manifest.permission.RECORD_AUDIO)
                }
                break
            }
        }
    }
    // 离开这一屏（切材料/退层）时安全清理，别把录音丢了。
    DisposableEffect(material.id) { onDispose { if (recorder.isRecording) recorder.stop() } }

    fun play() { if (!loaded) return; player.play(); playing = true }
    fun pause() { player.pause(); playing = false }
    fun playSentence(index: Int) {
        val start = material.sentences.getOrNull(index)?.startMs ?: run {
            onNotice("这一句还没打点，先整段听或在下面标记它的起点"); return
        }
        val end = material.sentences.getOrNull(index + 1)?.startMs?.toInt()
            ?: player.durationMs.takeIf { it > 0 } ?: start.toInt() + 5000
        if (end <= start) { onNotice("这一句的时间轴不对，重打一次点吧"); return }
        range = start.toInt() to end
        player.seek(start.toInt()); play()
    }

    /**
     * 主按钮：把「听本句」和「录这一句」合成一个动作（用户抱怨点两下的那件事）。
     *
     * - Idle → 播本句；先听后录模式下播完自动进录音。
     * - Playing → 暂停（不往下走）。
     * - Recording → 停止并保存，进 Review。
     * - Review → 重练这一句（清掉旧的进新的一轮）。
     */
    fun runSentence(index: Int) {
        if (!loaded) return
        expanded = index
        when {
            stage == SentenceStage.Recording && activeIndex == index -> { finishRecording() }
            stage == SentenceStage.Playing && activeIndex == index -> { player.pause(); playing = false }
            else -> {
                if (recorder.isRecording) recorder.stop()
                recording = false
                activeIndex = index
                preview.pause()
                playSentence(index)
                if (syncMode) {
                    // 同步跟读：边播边录。界面已提示推荐耳机，不承诺扬声器下干净录音。
                    stage = SentenceStage.Playing
                    pendingRecord = index
                    requestMic.launch(android.Manifest.permission.RECORD_AUDIO)
                } else {
                    stage = SentenceStage.Playing
                }
            }
        }
    }

    val position = if (tick >= 0) player.positionMs else 0
    val duration = if (loaded) player.durationMs else 0
    val aligned = material.aligned
    // 做题存储按 owner 分桶；和听力库同一个 owner，换号不串。
    val practice = remember(material.id, owner) { PracticeStore.of(context, owner) }

    // 有题目才出现「做题」这条线；私人导入的材料没有题，不摆一个空的第二 tab。
    val hasQuestions = material.questionList.isNotEmpty()
    if (hasQuestions) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == StudioTab.Sentence,
                onClick = { if (tab != StudioTab.Sentence) { stopAll(); tab = StudioTab.Sentence } },
                label = { Text("句子训练") })
            FilterChip(selected = tab == StudioTab.Question,
                onClick = { if (tab != StudioTab.Question) { stopAll(); tab = StudioTab.Question } },
                label = { Text("做题（${material.questionList.size}）") })
        }
    }

    if (tab == StudioTab.Question && hasQuestions) {
        QuestionPane(
            material = material,
            store = practice,
            onPlaySentence = { index ->
                // 从解析回跳原文：切回句子训练并直接播这一句，用户不用自己去找。
                tab = StudioTab.Sentence
                expanded = index
                playSentence(index)
                stage = SentenceStage.Playing
                activeIndex = index
            },
            onNotice = { onNotice(it) },
        )
        return
    }

    LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(material.title, fontWeight = FontWeight.SemiBold)
                Text("原音：${material.audioName} · " + if (material.fromPack) 
                        "来自资料库的合成语音（已在卡片注明来源与许可），不是你自己的录音" 
                    else 
                        "自己导入的录音，保留说话人原本的口音，不会被转写成合成音", 
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconButton(onClick = { if (player.isPlaying) pause() else { range = null; play() } },
                            enabled = loaded) {
                            Icon(if (player.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                if (player.isPlaying) "暂停" else "播放")
                        }
                        Text("${clock(position)} / ${clock(duration)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { player.seek(0) }, enabled = loaded) { Text("回到开头") }
                    }
                    Slider(value = (if (duration > 0) position.toFloat() / duration else 0f).coerceIn(0f, 1f),
                        onValueChange = { v -> if (duration > 0) player.seek((v * duration).toInt()) },
                        enabled = loaded, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.6f, 0.8f, 1.0f, 1.25f).forEach { v ->
                            FilterChip(selected = speed == v, onClick = { speed = v; player.speed(v) },
                                label = { Text(if (v == 1.0f) "原速" else "${v}x") })
                        }
                    }
                    Text("变速只改快慢，不改音高 —— 不能用升降调冒充口音。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("时间轴：已对齐 $aligned/${material.sentences.size}", fontWeight = FontWeight.SemiBold)
                    Text("没有字幕时只能边听边打点：听到一句结束就按下面这个按钮，" +
                        "记录的是你按下那一刻的真实播放位置。我们不会按字数均分时间假装对齐。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val nextIndex = material.sentences.indexOfFirst { it.startMs == null }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = {
                            val index = nextIndex
                            if (index < 0) return@Button
                            library.markStart(material.id, index, player.positionMs.toLong())
                            onChanged(); onNotice("第 ${index + 1} 句起点已标记为 ${clock(player.positionMs)}")
                        }, enabled = loaded && nextIndex >= 0) {
                            Text(if (nextIndex >= 0) "把当前位置标为第 ${nextIndex + 1} 句起点" else "全部句子已打点")
                        }
                        OutlinedButton(onClick = {
                            range = null; pause()
                            library.clearTimeline(material.id)
                            onChanged(); onNotice("时间轴已清空，可以重打")
                        }, enabled = aligned > 0) { Text("清空重打") }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = loop, onClick = { loop = !loop }, label = { Text("单句循环") })
                    }
                }
            }
        }
        if (material.words.isNotEmpty()) item {
            OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本次生词 ${material.words.size}", fontWeight = FontWeight.SemiBold)
                    Text("点一个词去英语页查它的释义（走你自己的词库，没有 API 也能查）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        material.words.forEach { w ->
                            AssistChip(onClick = { onLookupWord(w) }, label = { Text(w) })
                        }
                    }
                }
            }
        }
        // 跟读模式：显式二选一，不把两个意思混成一个开关（47 §3 P1.4 / 48 §4）。
        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("跟读方式", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !syncMode, onClick = {
                            if (syncMode) { stopAll() }; syncMode = false
                        }, label = { Text("先听后录") })
                        FilterChip(selected = syncMode, onClick = {
                            if (!syncMode) { stopAll() }; syncMode = true
                        }, label = { Text("同步跟读") })
                    }
                    Text(
                        if (syncMode) "同步跟读：本句一播就开始录，边听边说。建议戴耳机 —— 外放会把喇叭里的声音一起录进去，也不做发音评分。"
                        else "先听后录：本句播完自动开始录，你说完点「停止」即可。点一下主按钮就走完「听 → 录」，不用分两次。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        itemsIndexed(material.sentences, key = { _, s -> s.text }) { index, sentence ->
            val open = expanded == index
            val mine = stage != SentenceStage.Idle && activeIndex == index
            OutlinedCard(onClick = { expanded = if (open) -1 else index }, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(sentence.text, modifier = Modifier.weight(1f))
                        Text(if (sentence.startMs == null) "未对齐" else clock(sentence.startMs!!.toInt()),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (sentence.startMs == null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { expanded = if (open) -1 else index }) {
                            Text(if (open) "收起" else "跟读")
                        }
                        if (sentence.recording != null) TextButton(onClick = {
                            if (preview.isPlaying) preview.pause()
                            else try {
                                preview.load(library.recordFile(sentence.recording!!)); preview.play()
                            } catch (cause: Exception) {
                                android.util.Log.e("ListeningStore", "回放失败", cause)
                                onNotice("这条录音放不出来了，重新录一遍吧")
                            }
                        }) { Text(if (preview.isPlaying) "停回放" else "回放我的") }
                        if (sentence.practiced) Icon(Icons.Outlined.Check, "练过了",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                    if (open) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 一个主按钮走完「听本句 → 录这一句」，按钮文案随状态变。
                            if (sentence.startMs == null) {
                                Text("这一句还没有时间轴，先在下面打点，或整段听。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Button(onClick = { runSentence(index) }, enabled = loaded) {
                                    val (icon, label) = when {
                                        mine && stage == SentenceStage.Recording -> Icons.Outlined.Stop to "停止并保存"
                                        mine && stage == SentenceStage.Playing ->
                                            Icons.Outlined.Pause to (if (syncMode) "停止（正在同步跟读）" else "暂停")
                                        mine && stage == SentenceStage.Review -> Icons.Outlined.Refresh to "重练这一句"
                                        else -> Icons.Outlined.PlayArrow to
                                            (if (syncMode) "边听边录" else "听本句 → 自动录音")
                                    }
                                    Icon(icon, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(label)
                                }
                            }
                            if (mine) {
                                val status = when (stage) {
                                    SentenceStage.Playing -> if (syncMode) "正在同步跟读（边说边录）" else "正在播放本句…"
                                    SentenceStage.AwaitingMic -> "等待麦克风授权…"
                                    SentenceStage.Recording -> "● 正在录音，说完点「停止并保存」"
                                    SentenceStage.Review -> "录好了，可先听示范、再听自己的"
                                    SentenceStage.Idle -> ""
                                }
                                if (status.isNotBlank()) Text(status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (stage == SentenceStage.Recording) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    library.setRecording(material.id, index, sentence.recording,
                                        !sentence.practiced)
                                    onChanged()
                                }, enabled = sentence.recording != null) {
                                    Text(if (sentence.practiced) "改回再练" else "标记：过了")
                                }
                                if (sentence.recording != null) IconButton(onClick = {
                                    library.setRecording(material.id, index, null, false)
                                    onChanged()
                                }) { Icon(Icons.Outlined.Refresh, "删掉这条录音") }
                            }
                            Text("只做回放对比，不给发音打分 —— 识别对不等于说得准。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val words = remember(sentence.text) { candidateWords(sentence.text) }
                            if (words.isNotEmpty()) {
                                Text("点词加入本次生词：", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    words.take(6).forEach { w ->
                                        AssistChip(onClick = {
                                            library.addWord(material.id, w)
                                            onChanged(); onToast("已加入本次生词：$w")
                                        }, label = { Text(w) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Text("共 ${material.sentences.size} 句 · 练过的句子会标上对勾",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)) }
    }
}
