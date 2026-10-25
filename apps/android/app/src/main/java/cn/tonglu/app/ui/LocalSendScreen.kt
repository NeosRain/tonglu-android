package cn.tonglu.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.transfer.*
import java.util.Locale

private fun fileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0)
    else -> "$bytes B"
}

@Composable
internal fun LocalSendScreen(onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val controller = remember { LocalSendController(context) }
    val state by controller.ui.collectAsState()
    DisposableEffect(controller) { onDispose { controller.close() } }
    var tab by remember { mutableIntStateOf(0) }
    var showManual by remember { mutableStateOf(false) }
    var manualIp by remember { mutableStateOf("") }
    var manualPort by remember { mutableStateOf("53317") }
    var exitConfirm by remember { mutableStateOf(false) }
    var savingFile by remember { mutableStateOf<ReceivedFile?>(null) }
    var showLicense by remember { mutableStateOf(false) }
    val license = remember { context.assets.open("licenses/localsend.txt").bufferedReader().use { it.readText() } }
    val exportLicenses = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) controller.exportLicenses(uri)
    }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) controller.choose(uris) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = savingFile; savingFile = null
        if (uri != null && file != null) controller.saveTo(uri, file)
    }
    // 2026-10-09：这一层原来只有 `onDismissRequest`，关层时**窗口瞬间消失、一点动画都没有**
    // （用户报的「主页功能没有返回动画」）。统一走 OverlayMotion：进场/退场各一条 tween，
    // 并且能吃到预测式返回的进度（Dialog 是独立 Window，系统进度得挂到它**自己的窗口**上才收得到）。
    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
        // 「传输中」时返回另有含义（先弹确认），这几条路不能让 motion 接管 ——
        // 否则一按返回就直接关层、把正在传的传输掐断。此时返回落到下面的 onDismissRequest。
        backEnabled = !(state.busy || state.offer != null),
    )
    val requestClose = {
        if (state.busy || state.offer != null) exitConfirm = true else motion.requestClose()
    }
    Dialog(onDismissRequest = requestClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = requestClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回首页") }
                    Column(Modifier.weight(1f)) {
                        Text("文件互传", style = MaterialTheme.typography.titleLarge)
                        Text("LocalSend 内置核心 · 加密局域网", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(tab == 0, onClick = { tab = 0 }, label = { Text("发送") })
                    FilterChip(tab == 1, onClick = { tab = 1 }, label = { Text("接收") })
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(state.status, style = MaterialTheme.typography.bodyMedium)
                                if (state.starting) LinearProgressIndicator(Modifier.fillMaxWidth())
                                if (state.busy) {
                                    if (state.total > 0) LinearProgressIndicator(progress = { (state.bytes.toFloat() / state.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                    Text("${fileSize(state.bytes)} / ${fileSize(state.total)} · 当前文件", style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = controller::cancel) { Text("取消本次传输") }
                                }
                                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Text("双方打开此页或 LocalSend，并连接同一 Wi-Fi/热点。退出本页会停止发现与接收。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                state.addresses.filterNot { it.startsWith("0.0.0.0") || it.startsWith("[::]") }.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                    if (tab == 0) {
                        item {
                            Button(onClick = { choose.launch(arrayOf("*/*")) }, enabled = state.running && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("选择文件") }
                        }
                        items(state.selected, key = { it.uri.toString() }) { file ->
                            ListItem(headlineContent = { Text(file.name) }, supportingContent = { Text(fileSize(file.size)) })
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("附近设备", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = controller::scan, enabled = state.running && !state.busy) { Text(if (state.discovering) "发现中…" else "重新发现") }
                                TextButton(onClick = { showManual = true }, enabled = state.running && !state.busy) { Text("输入 IP") }
                            }
                            // 收到组播但还没验完 TLS 的那段时间以前是「什么都不显示」，
                            // 用户看到空白就以为对方没开页面 —— 这里明确说在验证谁。
                            if (state.probing > 0) {
                                Text(
                                    "正在与 ${state.probing} 台设备验证身份（证书指纹），验证通过后才会出现在下面。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp),
                                )
                            }
                            if (state.devices.isEmpty()) Text(
                                if (state.discovering) "正在持续广播发现…同一网络里打开本页或 LocalSend 的设备会出现。" 
                                else "尚未发现设备。校园网络可能隔离设备，可以尝试手机热点；广播被禁用时输入对方 IP。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        items(state.devices, key = { it.fingerprint }) { device ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(device.alias, style = MaterialTheme.typography.titleMedium)
                                    Text("${device.ip}:${device.port} · 指纹 ${device.fingerprint.takeLast(12)}", style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { controller.send(device) }, enabled = state.selected.isNotEmpty() && !state.busy) { Text("发送所选文件") }
                                }
                            }
                        }
                    } else {
                        item {
                            Text("已接收文件", style = MaterialTheme.typography.titleMedium)
                            Text("文件先保存在应用私有空间；保存后可在系统文件中找到。卸载会删除未另存的文件。", style = MaterialTheme.typography.bodySmall)
                            if (state.received.isEmpty()) Text("暂未收到文件。保持此页打开，接收前会询问你。", modifier = Modifier.padding(top = 16.dp))
                        }
                        items(state.received, key = { it.path }) { file ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(file.name)
                                    Text(fileSize(file.size), style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { savingFile = file; save.launch(file.name) }) { Text("保存到系统文件") }
                                }
                            }
                        }
                    }
                    item {
                        Text("远距离传递与网盘将独立接入；本页当前只提供局域网传输，不经过应用服务器。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { showLicense = true }) { Text("LocalSend 开源许可") }
                    }
                }
            }
        }
        state.offer?.let { offer -> AlertDialog(onDismissRequest = { controller.decide(false) },
            title = { Text("${offer.alias} 想发送文件") },
            text = { Column { Text("${offer.files.size} 个文件 · ${fileSize(offer.files.sumOf { it.second })}\n设备指纹 ${offer.fingerprint.takeLast(12)}")
                offer.files.take(6).forEach { (name, size) -> Text("$name · ${fileSize(size)}", style = MaterialTheme.typography.bodySmall) }
                Text("只接受你确认的设备。", modifier = Modifier.padding(top = 12.dp)) } },
            confirmButton = { TextButton(onClick = { controller.decide(true); tab = 1 }) { Text("接受") } },
            dismissButton = { TextButton(onClick = { controller.decide(false) }) { Text("拒绝") } }) }
        if (showManual) AlertDialog(onDismissRequest = { showManual = false }, title = { Text("连接局域网设备") },
            text = { Column { OutlinedTextField(manualIp, { manualIp = it.filter { c -> c in '0'..'9' || c == '.' }.take(15) }, label = { Text("IPv4 地址") }, singleLine = true)
                OutlinedTextField(manualPort, { if (it.all(Char::isDigit) && it.length <= 5) manualPort = it }, label = { Text("端口") }, singleLine = true) } },
            confirmButton = { TextButton(onClick = { showManual = false; controller.probe(manualIp, manualPort.toIntOrNull() ?: 53317) }) { Text("连接") } },
            dismissButton = { TextButton(onClick = { showManual = false }) { Text("取消") } })
        if (exitConfirm) AlertDialog(onDismissRequest = { exitConfirm = false }, title = { Text("停止传输并返回？") },
            text = { Text("退出会中断当前传输，已完整接收的文件会保留。") },
            confirmButton = { TextButton(onClick = { exitConfirm = false; motion.requestClose() }) { Text("停止并返回") } },
            dismissButton = { TextButton(onClick = { exitConfirm = false }) { Text("继续传输") } })
        if (showLicense) AlertDialog(onDismissRequest = { showLicense = false }, title = { Text("LocalSend · Apache 2.0") },
            text = { Text("LocalSend v1.18.2\nCopyright 2022–2026 Tien Do Nam and contributors\nhttps://github.com/localsend/localsend\n\n$license", modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { exportLicenses.launch("localsend-dependencies.zip") }) { Text("保存依赖许可证") } },
            dismissButton = { TextButton(onClick = { showLicense = false }) { Text("关闭") } })
    }
}
