package cn.tonglu.app.ui.speech

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.tonglu.app.data.speech.AudioPackPlayer
import cn.tonglu.app.data.speech.AudioPackStore
import cn.tonglu.app.data.speech.EnglishAudioPack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/** Fixed English candidate pack; does not change the global voice preference. */
@Composable
internal fun AudioPackPreview(onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val store = remember(context) { AudioPackStore(File(context.cacheDir, "english_audio_candidates"),
        "https://dl.useall.top/tts/previews/heart-emma-20261020-v1/") }
    var pack by remember { mutableStateOf<EnglishAudioPack?>(null) }
    var voice by remember { mutableStateOf("kokoro-v1-af-heart") }
    var message by remember { mutableStateOf("正在读取试听清单…") }
    var active by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var downloading by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf<Job?>(null) }
    var downloadGeneration by remember { mutableLongStateOf(0L) }
    val player = remember(store, scope) { AudioPackPlayer(context, store, scope) { text, id -> message = text; active = id } }
    fun stop() { downloadGeneration++; download?.cancel(); download = null; downloading = false; player.stop() }
    fun close() { stop(); onClose() }
    BackHandler { close() }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stop() }
    }
    LaunchedEffect(retry) {
        try {
            store.cached()?.let { local ->
                pack = local
                message = "已加载本地试听清单，可以离线播放"
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = "本地清单读取失败，正在重新加载：${e.message}" }
        try {
            val loaded = store.load(); pack = loaded.pack
            if (active == null && !downloading) {
                message = if (loaded.cached) "已使用离线清单（${loaded.warning}）" else "选一条试听，下载后可离线播放"
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (active == null && !downloading) message = "读取失败：${e.message}，可点重试" }
    }
    val selected = pack?.items?.filter { it.voiceId == voice }.orEmpty()
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { close() }) { Text("返回") }
                    TextButton(onClick = { stop() }) { Text("停止") }
                }
                Text("Heart / Emma 试听", style = MaterialTheme.typography.headlineSmall)
                Text("20 条英语词句 · 候选试听\n下载后可离线播放，暂不替换默认朗读。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("kokoro-v1-af-heart" to "美式 Heart", "kokoro-v1-bf-emma" to "英式 Emma").forEach { (id, name) ->
                        FilterChip(selected = voice == id, onClick = { stop(); voice = id }, label = { Text(name) })
                    }
                }
                Text(message, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { stop(); retry++ }) { Text("重试清单") }
                    TextButton(enabled = selected.isNotEmpty() && !downloading, onClick = {
                        stop(); downloading = true
                        val generation = downloadGeneration
                        download = scope.launch {
                            var failures = 0
                            var lastError: String? = null
                            try {
                                selected.forEachIndexed { index, item ->
                                    try { store.file(item) }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { failures++; lastError = "${item.text}：${e.message}" }
                                    if (generation == downloadGeneration) message = "已处理 ${index + 1}/${selected.size}，失败 $failures 条"
                                }
                                if (generation == downloadGeneration) message = if (failures == 0) "下载完成，可以离线试听" else "$failures 条下载失败，可重新下载。$lastError"
                            } finally { if (generation == downloadGeneration) downloading = false }
                        }
                    }) { Text(if (downloading) "下载中…" else "下载此声音") }
                }
                TextButton(enabled = selected.isNotEmpty() && !downloading,
                    onClick = { player.play(selected.filter { it.purpose == "reading" }) }) { Text("连续朗读精读短文") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(selected, key = { it.key }) { item ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(item.text)
                                TextButton(enabled = !downloading, onClick = { player.play(listOf(item)) }) {
                                    Text(if (active == item.id) "重新播放" else "试听")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
