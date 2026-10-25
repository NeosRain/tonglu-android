package cn.tonglu.app.ui.english.intensive

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.tonglu.app.data.english.intensive.*
import cn.tonglu.app.data.speech.*
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import kotlinx.coroutines.CancellationException

@Composable
fun IntensiveReadingScreen(owner: String, onClose: () -> Unit, onLookup: (String) -> Unit) {
    val context = LocalContext.current
    val repository = remember { IntensiveRepository(context) }
    val preferences = remember(owner) { context.getSharedPreferences("intensive-" + owner, 0) }
    var catalog by remember { mutableStateOf<List<IntensiveEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var year by rememberSaveable { mutableIntStateOf(2026) }
    var exam by rememberSaveable { mutableStateOf("CET4") }
    var month by rememberSaveable { mutableIntStateOf(6) }
    var suite by rememberSaveable { mutableIntStateOf(1) }
    var openId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var article by remember { mutableStateOf<IntensiveArticle?>(null) }
    var articleError by remember { mutableStateOf<String?>(null) }
    var articleRetry by remember { mutableIntStateOf(0) }
    var lastId by remember(owner) { mutableStateOf(preferences.getString("lastArticle", null)) }
    LaunchedEffect(retry) {
        error = null
        try { catalog = repository.catalog() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "材料目录读取失败，请重试"; android.util.Log.e("Intensive", "catalog", e) }
    }
    LaunchedEffect(openId, catalog, articleRetry) {
        article = null; articleError = null
        val id = openId ?: return@LaunchedEffect
        val entries = catalog ?: return@LaunchedEffect
        if (entries.none { it.id == id }) { openId = null; return@LaunchedEffect }
        try {
            article = repository.article(entries.first { it.id == id })
            lastId = id; preferences.edit().putString("lastArticle", id).apply()
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { articleError = "文章读取或校验失败，请重试"; android.util.Log.e("Intensive", id, e) }
    }
    IntensiveWindow("考试精读", onClose) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(if (catalog?.isEmpty() == true) "继续读自己的文章" else "选一篇，就开始", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("开源版不附带未获再分发授权的真题材料；可从英语首页导入自己的文章。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (catalog?.isEmpty() == true) item {
                Text("本版本不附带尚未确认再分发授权的真题正文。个人文章精读、翻译、查词和朗读入口保留在英语首页。")
                TextButton(onClick = onClose) { Text("返回英语") }
            } else {
            catalog?.firstOrNull { it.id == lastId }?.let { last -> item {
                OutlinedCard(onClick = { openId = last.id }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("继续上次精读", color = MaterialTheme.colorScheme.primary)
                        Text(last.subtitle + "\n" + last.title)
                    }
                }
            } }
            item { Choices((2026 downTo 2021).toList(), year, { it.toString() }) { year = it; if (year == 2026) month = 6 } }
            item { Choices(listOf("CET4", "CET6"), exam, { if (it == "CET4") "大学英语四级" else "大学英语六级" }) { exam = it } }
            item { Choices(if (year == 2026) listOf(6) else listOf(6, 12), month, { it.toString() + " 月" }) { month = it } }
            item { Choices(listOf(1, 2, 3), suite, { "第 " + it + " 套" }) { suite = it } }
            if (catalog == null && error == null) item { CircularProgressIndicator() }
            error?.let { message -> item { Text(message); TextButton(onClick = { retry++ }) { Text("重试") } } }
            val rows = catalog.orEmpty().filter { it.year == year && it.exam == exam && it.month == month && it.suite == suite }
            items(listOf("cloze", "passage1", "passage2")) { kind ->
                val entry = rows.firstOrNull { it.kind == kind }
                OutlinedCard(onClick = { entry?.let { openId = it.id } }, enabled = entry != null, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(when(kind) { "cloze" -> "选词填空"; "passage1" -> "Section C · 第一篇"; else -> "Section C · 第二篇" }, fontWeight = FontWeight.SemiBold)
                        Text(if (entry == null) "开源源码不附带该真题正文" else entry.words.toString() + " 词 · " + entry.sentences + " 句 · 离线可读",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (entry != null) {
                            if (entry.qualityNotice.isNotBlank()) Text(entry.qualityNotice, style = MaterialTheme.typography.bodySmall)
                            val read = preferences.getStringSet(entry.progressKey, emptySet()).orEmpty().size
                            Text(if (read == 0) "开始精读 →" else "已读 " + read + " / " + entry.sentences + " 句 · 继续 →",
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            item { Text("翻译和语法由 GPU 预先分析，打开即用。机器结果需结合原文核对；缺失或校验未通过的材料不会显示为可读。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    if (openId != null) IntensiveWindow(article?.entry?.title ?: "正在打开文章", { openId = null }) {
        val current = article
        when {
            current != null -> IntensiveReader(current, preferences, owner, onLookup)
            articleError != null -> Column(Modifier.padding(24.dp)) {
                Text(articleError.orEmpty()); Button(onClick = { articleRetry++ }) { Text("重试") }
            }
            else -> Box(Modifier.padding(24.dp)) { CircularProgressIndicator() }
        }
    }
}

@Composable
private fun <T> Choices(values: List<T>, selected: T, label: (T) -> String, select: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value -> FilterChip(selected = selected == value, onClick = { select(value) }, label = { Text(label(value)) }) }
    }
}

@Composable
private fun IntensiveWindow(title: String, close: () -> Unit, content: @Composable () -> Unit) {
    val motion = rememberOverlayMotion(onClose = { OverlayDismissGuard.arm(); close() }, predictive = OverlayMotion.enabledState, selfDrag = true)
    Dialog(onDismissRequest = { motion.requestClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row {
                    IconButton(onClick = { motion.requestClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                    Text(title, modifier = Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold)
                }
                content()
            }
        }
    }
}
