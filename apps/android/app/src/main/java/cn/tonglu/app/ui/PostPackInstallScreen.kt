package cn.tonglu.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import cn.tonglu.app.data.LinkedPackDto
import cn.tonglu.app.data.TongluApi
import cn.tonglu.app.data.listening.LearningPack
import cn.tonglu.app.data.listening.LearningPacks
import cn.tonglu.app.data.listening.ListeningLibrary
import cn.tonglu.app.data.listening.PackStage

/**
 * 从广场/详情里点开一张「贴附的资料」卡片 → 预览 → 加入学习 → 直接打开（47 §3 P4）。
 *
 * 这个页面只做**编排**：真正下载/校验/入库走的是资料库那页同一个
 * [LearningPacks.install]，不另写一套下载逻辑 —— 那样迟早两边行为不一致
 * （比如一处校验 sha256、一处忘了）。
 *
 * 关键点：按 `packageId@version` 拉清单里**对应版本**的那条。不能只按 packageId
 * 找「最新版」，否则帖子说 v1、用户下到 v2，练的内容和作者说的对不上。
 *
 * 「加入学习」完成后直接 `onOpenMaterial(materialId)` 跳进精听工作区 —— 这才是
 * 47 §3 P4 要的「预览 → 加入学习 → 下载并校验 → 直接打开」闭环。
 */
@Composable
internal fun PostPackInstallScreen(
    pack: LinkedPackDto,
    owner: String,
    api: TongluApi?,
    onOpenMaterial: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val library = remember(owner) { ListeningLibrary.of(context, owner) }
    var busy by remember { mutableStateOf(false) }
    var stageText by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(-1f) }
    var error by remember { mutableStateOf<String?>(null) }
    var installedMaterialId by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 进页面先看本机是不是已经装过这一版：装过就直接给「继续学习」，不必再点一次。
    LaunchedEffect(pack.packageId, pack.version) {
        val pid = pack.packageId ?: return@LaunchedEffect
        val row = LearningPacks.installedOf(context, pid)
        if (row != null && row.version == pack.version &&
            library.list().any { it.id == row.materialId }) {
            installedMaterialId = row.materialId
        }
    }

    // 内联层：能吃到系统返回**进度**，所以走 selfDrag = false —— 真正的「左边缘往右拖跟手」。
    // 返回键也归它管（系统手势与返回键都进同一个 flow，只是后者没有中间进度），
    // 所以这里**不再自己挂 BackHandler**：两条路都注册在 OnBackPressedDispatcher 上会打架。
    // busy（正在下载安装）时不接管返回，免得半截操作被打断。
    val motion = rememberOverlayMotion(
        onClose = onClose,
        predictive = OverlayMotion.enabledState,
        selfDrag = false,
        backEnabled = !busy,
    )

    Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { motion.requestClose() }, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                Text("学习资料", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(pack.title?.takeIf { it.isNotBlank() } ?: pack.packageId.orEmpty(),
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (!pack.summary.isNullOrBlank()) {
                            Text(pack.summary, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val meta = buildList {
                            pack.capabilities?.mapNotNull { CAPABILITY_LABEL[it] }?.forEach { add(it) }
                            pack.bytes?.let { add(sizeLabel(it)) }
                            pack.version?.let { add("v$it") }
                            pack.author?.takeIf { it.isNotBlank() }?.let { add(it) }
                            pack.level?.takeIf { it.isNotBlank() }?.let { add(it) }
                        }
                        if (meta.isNotEmpty()) {
                            Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // 许可来源如实展示（和资料库页一致）：内容不是我们原创的，用户有权知道。
                        if (!pack.attribution.isNullOrBlank()) {
                            Text(pack.attribution, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4)
                        }
                    }
                }

                if (pack.available == false) {
                    Text("这份资料已被下架，暂时不能加入学习。", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    return@Column
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                if (busy) {
                    Text(stageText.ifBlank { "准备中…" }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { busy = false; error = "已取消" }) { Text("取消") }
                } else if (installedMaterialId != null) {
                    Button(onClick = { onOpenMaterial(installedMaterialId!!) }, modifier = Modifier.fillMaxWidth()) {
                        Text("继续学习")
                    }
                } else {
                    Button(
                        onClick = {
                            val pid = pack.packageId ?: return@Button
                            busy = true; error = null; stageText = "正在取清单…"; progress = -1f
                            scope.launch {
                                try {
                                    // 必须拿到**对应版本**那条：只有 packageId 会取到最新版，
                                    // 那样帖子说 v1、用户却下到 v2，内容对不上。
                                    val target = LearningPacks.catalog(context, api)
                                        .firstOrNull { it.packageId == pid && it.version == pack.version }
                                    if (target == null) {
                                        busy = false
                                        error = "这份资料在本机暂时取不到（可能已下架），稍后再试"
                                        return@launch
                                    }
                                    if (api == null) {
                                        busy = false; error = "还没连上服务器，稍后再试"; return@launch
                                    }
                                    val material = LearningPacks.install(context, api, library, target) { stage ->
                                        when (stage) {
                                            PackStage.FetchingManifest -> { stageText = "正在取清单…"; progress = -1f }
                                            is PackStage.Downloading -> {
                                                stageText = "下载中…"
                                                progress = if (stage.total > 0) stage.done.toFloat() / stage.total else -1f
                                            }
                                            PackStage.Installing -> { stageText = "正在入库…"; progress = -1f }
                                            is PackStage.Done -> { stageText = "完成" }
                                        }
                                    }
                                    busy = false
                                    installedMaterialId = material.id
                                    // 「下载并校验 → 直接打开」：装完立刻进工作区，不用再找一次入口。
                                    onOpenMaterial(material.id)
                                } catch (cause: Exception) {
                                    busy = false
                                    error = cause.message ?: "加入失败，请重试"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("加入学习") }
                }

                Text(
                    "加入后会下载到本机，之后完全离线可用；同一份资料重复加入不会重复占空间，" +
                        "更新版本也不会覆盖你的录音、笔记与成绩。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 发帖时挑一份要贴附的资料（47 §3 P4）。
 *
 * 只列「现在能下载的」（`LearningPacks.catalog` 已经是服务端去重后的在架清单），
 * 选一条就把 `packageId@version` 交回去 —— **不在这里下载**，下载发生在读者点开卡片时。
 * 作者贴的是「内容地址」，不是文件本身，这样内容下架/换版都不会让帖子变坏链接。
 */
@Composable
internal fun PostPackPickerScreen(
    api: TongluApi?,
    onPick: (LinkedPackDto) -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var packs by remember { mutableStateOf<List<LearningPack>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        packs = runCatching { LearningPacks.catalog(context, api) }.getOrElse { error = "没能取到资料清单"; null }
    }

    // 内联层：能吃到系统返回**进度**，selfDrag = false 才是真正的跟手。
    // 删掉那条裸 `BackHandler` —— 与外壳同时注册会一次返回退两层。
    val motion = rememberOverlayMotion(
        onClose = onClose,
        predictive = OverlayMotion.enabledState,
        selfDrag = false,
    )

    Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                Text("选一份资料", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            }
            when {
                packs == null && error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    val rows = packs.orEmpty()
                    if (rows.isEmpty()) {
                        Text(error ?: "资料库还是空的，先去学习页的资料库看看。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 18.dp))
                    } else {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(horizontal = 18.dp).padding(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            rows.forEach { pack ->
                                OutlinedCard(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        onPick(
                                            LinkedPackDto(
                                                packageId = pack.packageId, version = pack.version,
                                                title = pack.title, summary = pack.summary,
                                                language = pack.language, level = pack.level,
                                                author = pack.author, license = pack.license,
                                                attribution = pack.attribution,
                                                capabilities = pack.capabilities, bytes = pack.bytes,
                                                available = true,
                                            )
                                        )
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                ) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(pack.title, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            listOfNotNull(
                                                pack.capabilityLabel.takeIf { it.isNotBlank() },
                                                pack.sizeLabel,
                                                "v${pack.version}",
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
