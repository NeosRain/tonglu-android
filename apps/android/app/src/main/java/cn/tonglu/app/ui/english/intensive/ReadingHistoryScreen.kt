package cn.tonglu.app.ui.english.intensive

import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import cn.tonglu.app.ui.glass.GlassTopBar
import cn.tonglu.app.ui.glass.GlassTokens
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
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
    var favoritesTab by rememberSaveable(owner, articleKey, sentenceId) { mutableStateOf(initialFavorites) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var loading by remember(owner) { mutableStateOf(true) }
    var readError by remember(owner) { mutableStateOf<String?>(null) }
    var readRevision by remember(owner) { mutableIntStateOf(0) }
    fun reload() { readRevision++ }
    val changes by IntensiveStudyStore.changes.collectAsState()
    LaunchedEffect(owner, articleKey, sentenceId, changes, readRevision) {
        loading = true; readError = null
        try {
            val loadedHistory = store.history().filter { (articleKey == null || it.articleKey == articleKey) && (sentenceId == null || it.sentenceId == sentenceId) }
            val loadedFavorites = store.favorites().filter { articleKey == null || it.articleKey == articleKey }
            history = loadedHistory; favorites = loadedFavorites
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { readError = "历史读取失败，请重试" }
        finally { loading = false }
    }
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
            val backdrop = rememberLayerBackdrop()
            val headerInset = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding() +
                GlassTokens.TopBarHeightDp.dp + GlassTokens.TopBarVerticalMarginDp.dp
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
                Box(Modifier.fillMaxSize().layerBackdrop(backdrop)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)), contentAlignment = Alignment.TopCenter) {
                    Column(Modifier.widthIn(max = 880.dp).fillMaxSize().padding(top = headerInset + 12.dp)) {
                        FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(false to "历史", true to "收藏的句子").forEach { (tab, title) ->
                                FilterChip(selected = favoritesTab == tab, onClick = { favoritesTab = tab }, label = { Text(title) },
                                    border = null, shape = RoundedCornerShape(20.dp), colors = FilterChipDefaults.filterChipColors(
                                        containerColor = AppColors.ChipIdle, labelColor = AppColors.TextSecondary,
                                        selectedContainerColor = AppColors.Accent, selectedLabelColor = AppColors.OnAccent))
                            }
                        }
                        if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        readError?.let { message ->
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(message, color = AppColors.TextSecondary)
                                TextButton(onClick = { reload() }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.TextPrimary)) { Text("重试读取") }
                            }
                        }
                        error?.let { Text(it, Modifier.padding(20.dp), color = AppColors.Danger) }
                        AnimatedContent(targetState = favoritesTab, transitionSpec = { fadeIn(tween(160, delayMillis = 120)) togetherWith fadeOut(tween(120)) },
                            modifier = Modifier.weight(1f), label = "reading-history-tab") { tab ->
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (!tab) {
                                    if (!loading && readError == null && history.isEmpty()) item("history-empty") {
                                        Text("尚无历史。保存自己的译文或请求 AI 后，会按版本保存在本机。", color = AppColors.TextSecondary)
                                    }
                                    items(history, key = { it.id }) { row ->
                                        Surface(modifier = Modifier.animateItem(), color = AppColors.SurfaceOpaque, shape = RoundedCornerShape(20.dp)) {
                                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Text(readingHistoryKind(row.kind) + " · " + SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date(row.createdAt)), style = MaterialTheme.typography.labelLarge)
                                                Text(row.articleTitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                                SelectionContainer { Text(row.sourceText) }
                                                if (row.inputText.isNotBlank()) { Text("当时的译文", style = MaterialTheme.typography.labelMedium); SelectionContainer { Text(row.inputText) } }
                                                SelectionContainer { Text(row.text) }
                                                if (row.model.isNotBlank()) Text("AI · ${row.model} · 已保存，重复请求复用", style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                                                if (row.kind == "manual") TextButton(onClick = { if (tab == favoritesTab) review(row) },
                                                    enabled = !busy && tab == favoritesTab, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.TextPrimary)) { Text("AI 点评这版译文") }
                                            }
                                        }
                                    }
                                } else {
                                    if (!loading && readError == null && favorites.isEmpty()) item("favorites-empty") {
                                        Text("还没有收藏句子。在逐句精读中点收藏即可保存。", color = AppColors.TextSecondary)
                                    }
                                    items(favorites, key = { it.articleKey + it.sentenceId }) { row ->
                                        Surface(modifier = Modifier.animateItem(), color = AppColors.SurfaceOpaque, shape = RoundedCornerShape(20.dp)) {
                                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(row.articleTitle, color = AppColors.TextSecondary)
                                                SelectionContainer { Text(row.sourceText) }
                                                TextButton(enabled = tab == favoritesTab && !loading, onClick = {
                                                    if (tab == favoritesTab) scope.launch {
                                                        try { store.favorite(row.articleKey, row.articleTitle, row.sentenceId, row.sourceText, false); reload() }
                                                        catch (e: CancellationException) { throw e }
                                                        catch (e: Exception) { error = "未能取消收藏，请重试" }
                                                    }
                                                }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.TextPrimary)) { Text("取消收藏") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                GlassTopBar(backdrop, Modifier.align(Alignment.TopCenter)) {
                    IconButton(onClick = { motion.requestClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回精读", tint = AppColors.TextPrimary) }
                    Text("精读记录", Modifier.weight(1f), color = AppColors.TextPrimary)
                    TextButton(onClick = { aiSettings = true }, colors = ButtonDefaults.textButtonColors(contentColor = AppColors.TextPrimary)) { Text("AI 设置") }
                }
            }
        }
    }
    if (aiSettings) EnglishAiSettingsDialog(aiStore, config, onSaved = { config = it }, onDismiss = { aiSettings = false })
}
