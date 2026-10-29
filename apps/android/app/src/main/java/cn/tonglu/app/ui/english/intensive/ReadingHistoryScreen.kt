package cn.tonglu.app.ui.english.intensive

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
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
import cn.tonglu.app.data.english.*
import cn.tonglu.app.data.english.intensive.IntensiveStudyStore
import cn.tonglu.app.data.english.reading.StoredReadingHistory
import cn.tonglu.app.data.english.reading.StoredReadingFavorite
import cn.tonglu.app.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun readingHistoryKind(kind: String) = when (kind) {
    "manual" -> "我的译文"; "translation" -> "AI 译文"; "review" -> "AI 译文点评"; else -> "AI 精读分析"
}

@Composable
internal fun ReadingHistoryScreen(owner: String, onClose: () -> Unit, articleKey: String? = null,
    sentenceId: String? = null, initialFavorites: Boolean = false) {
    val context = LocalContext.current
    val store = remember(owner) { IntensiveStudyStore(context, owner) }
    val aiStore = remember { EnglishAiStore(context) }
    var config by remember { mutableStateOf(aiStore.load()) }
    var aiSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf<List<StoredReadingHistory>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<StoredReadingFavorite>>(emptyList()) }
    var favoritesTab by remember { mutableStateOf(initialFavorites) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun reload() { scope.launch {
        try {
            history = store.history().filter { (articleKey == null || it.articleKey == articleKey) && (sentenceId == null || it.sentenceId == sentenceId) }
            favorites = store.favorites().filter { articleKey == null || it.articleKey == articleKey }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "历史读取失败，请重试" }
    } }
    val changes by IntensiveStudyStore.changes.collectAsState()
    LaunchedEffect(owner, changes) { reload() }
    fun review(row: StoredReadingHistory) {
        if (!config.ready()) { aiSettings = true; return }
        if (busy) return
        scope.launch {
            busy = true; error = null
            try {
                store.generate(row.articleKey, row.articleTitle, row.sentenceId, row.sourceText, "review", config, row.text)
                reload()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "AI 点评未完成，请重试" }
            finally { busy = false }
        }
    }
    val motion = rememberOverlayMotion(onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState, selfDrag = true)
    Dialog(onDismissRequest = { motion.requestClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = AppColors.CanvasOpaque) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { motion.requestClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回精读") }
                    Text("精读记录", Modifier.weight(1f))
                    TextButton(onClick = { aiSettings = true }) { Text("AI 设置") }
                }
                Row(Modifier.padding(horizontal = 16.dp)) {
                    TextButton(onClick = { favoritesTab = false }) { Text(if (!favoritesTab) "✓ 历史" else "历史") }
                    TextButton(onClick = { favoritesTab = true }) { Text(if (favoritesTab) "✓ 收藏的句子" else "收藏的句子") }
                }
                error?.let { Text(it, Modifier.padding(16.dp), color = AppColors.Danger) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (!favoritesTab) {
                        if (history.isEmpty()) item { Text("尚无历史。保存自己的译文或请求 AI 后，会按版本保存在本机。") }
                        items(history, key = { it.id }) { row ->
                            Surface(color = AppColors.SurfaceOpaque, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(readingHistoryKind(row.kind) + " · " + SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date(row.createdAt)), style = MaterialTheme.typography.labelLarge)
                                    Text(row.articleTitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                    SelectionContainer { Text(row.sourceText) }
                                    if (row.inputText.isNotBlank()) { Text("当时的译文", style = MaterialTheme.typography.labelMedium); SelectionContainer { Text(row.inputText) } }
                                    SelectionContainer { Text(row.text) }
                                    if (row.model.isNotBlank()) Text("AI · ${row.model} · 已保存，重复请求复用", style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                                    if (row.kind == "manual") TextButton(onClick = { review(row) }, enabled = !busy) { Text("AI 点评这版译文") }
                                }
                            }
                        }
                    } else {
                        if (favorites.isEmpty()) item { Text("还没有收藏句子。在逐句精读中点收藏即可保存。") }
                        items(favorites, key = { it.articleKey + it.sentenceId }) { row ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(row.articleTitle, color = AppColors.TextSecondary)
                                SelectionContainer { Text(row.sourceText) }
                                TextButton(onClick = { scope.launch {
                                    try { store.favorite(row.articleKey, row.articleTitle, row.sentenceId, row.sourceText, false); reload() }
                                    catch (e: CancellationException) { throw e }
                                    catch (e: Exception) { error = "未能取消收藏，请重试" }
                                } }) { Text("取消收藏") }
                                HorizontalDivider(color = AppColors.Divider)
                            }
                        }
                    }
                }
            }
        }
    }
    if (aiSettings) EnglishAiSettingsDialog(aiStore, config, onSaved = { config = it }, onDismiss = { aiSettings = false })
}
