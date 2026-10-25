package cn.tonglu.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Composable
internal fun LearningSearchDialog(catalog: LearningCatalog, initialQuery: String,
    articleId: String? = null, onAsk: suspend (String, List<LearningExcerpt>, String) -> LearningAnswer,
    onOpen: (LearningHit) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val index = remember(catalog.revision) { LearningSearch(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf(initialQuery.take(400)) }
    var searched by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf(emptyList<LearningHit>()) }
    var answer by remember { mutableStateOf<LearningAnswer?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    var answerAfterSearch by remember { mutableStateOf(false) }
    fun search() {
        val q = query.trim()
        if (q.length < 2) { message = "请输入至少两个字，例如：学习 Python、节省开支"; return }
        generation++; val requestGeneration = generation
        job?.cancel(); phase = 1; answer = null; hits = emptyList(); message = null
        job = scope.launch {
            try { hits = index.find(catalog, q, articleId); searched = q }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                // 原来这里只改 message，把根因整个吞掉了 —— 「AI 搜索不能用」排查时
                // 界面上只有一句「请重试」，logcat 里什么都没有。**只记异常类型与消息，不记查询内容。**
                Log.w("TongluLearnSearch", "本地检索失败：${error::class.java.simpleName}: ${error.message}")
                message = "资料暂时无法检索，请重试"
            } finally { if (generation == requestGeneration) phase = 0 }
        }
    }
    fun ask() {
        if (hits.isEmpty() || query.trim() != searched) return
        phase = 2; message = null; answer = null
        generation++; val requestGeneration = generation
        job = scope.launch {
            try {
                val strategy = if (hits.all { it.article.id == "how-to-live-better" }) "life-decision-guide" else "general"
                val result = onAsk(searched, hits.map { LearningExcerpt("${it.article.title} · ${it.heading}".take(180), it.excerpt) }, strategy)
                if (result.state == "ANSWER") answer = result
                else message = "还没有配置你自己的 AI 接口（学习 → 英语学习 → AI 设置）。配置后由你的接口生成回答、费用计入你的账户；下方原文检索不受影响。"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("TongluLearnSearch", "AI 回答失败：${error::class.java.simpleName}: ${error.message}")
                message = "AI 暂时无法回答，请查看下方原文或稍后重试"
            } finally { if (generation == requestGeneration) phase = 0 }
        }
    }
    LaunchedEffect(Unit) {
        if (query.trim().length >= 2) { answerAfterSearch = true; search() }
    }
    LaunchedEffect(phase, searched, answerAfterSearch) {
        if (answerAfterSearch && phase == 0 && searched.isNotBlank()) {
            answerAfterSearch = false
            if (hits.isNotEmpty()) ask()
        }
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    // 2026-10-09：这一层原来直接 `onDismissRequest = onClose`，关层**窗口瞬间消失、没有动画**，
    // 与其它层不一致。统一走 OverlayMotion。
    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )
    Dialog(onDismissRequest = { motion.requestClose() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { motion.requestClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "关闭 AI 搜索") }
                    Text("AI 搜索", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(if (articleId == null) "学习资料" else if (articleId == "how-to-live-better") "整本指南" else "当前文章", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp, modifier = Modifier.padding(end = 20.dp))
                }
                OutlinedTextField(value = query, onValueChange = { if (it.length <= 400) { generation++; job?.cancel(); phase = 0; query = it; answer = null; message = null; hits = emptyList(); searched = "" } },
                    placeholder = { Text("想了解什么？") }, minLines = 1, maxLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { answerAfterSearch = true; search() }),
                    trailingIcon = { IconButton(onClick = { answerAfterSearch = true; search() }, enabled = phase == 0) { Icon(Icons.Outlined.Search, "检索并回答") } })
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item {
                        Text(if (articleId == "how-to-live-better") "使用作者的 life-decision-guide：查完整条目，再按成本、收益与证据等级回答。只发送问题和公开条目。" else "先找原文，再让 AI 结合出处回答。只发送你的问题和检索到的公开摘录。",
                            fontSize = 12.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (phase != 0) item { Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(if (phase == 1) "正在检索资料…" else "正在结合原文回答…", modifier = Modifier.padding(start = 12.dp))
                        TextButton(onClick = { job?.cancel(); phase = 0 }) { Text("取消") }
                    } }
                    message?.let { value -> item { Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 23.sp) } }
                    answer?.let { result -> item {
                        Column {
                            Text("AI 回答", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
                            SelectionContainer { Text(result.answer, fontSize = 16.sp, lineHeight = 26.sp) }
                            result.citedIndices.distinct().forEach { number -> hits.getOrNull(number - 1)?.let { hit ->
                                TextButton(onClick = { onOpen(hit) }) { Text("[$number] ${hit.heading}", maxLines = 2) }
                            } }
                            Text("生成内容可能有误，请核对原文。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 10.dp))
                        }
                    } }
                    if (searched.isNotBlank() && phase != 1) item {
                        Text(if (hits.isEmpty()) "未找到相关资料，试试更具体的关键词" else "原文依据 · ${hits.size} 段",
                            fontWeight = FontWeight.SemiBold)
                    }
                    itemsIndexed(hits) { i, hit ->
                        OutlinedCard(onClick = { onOpen(hit) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("[${i + 1}] ${hit.heading}", fontWeight = FontWeight.Medium, lineHeight = 23.sp)
                                Text(hit.article.title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp))
                                Text(hit.excerpt.replace(Regex("<!--.*?-->"), "").take(260).trim() + if (hit.excerpt.length > 260) "…" else "",
                                    fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 12.dp))
                                Text("${hit.article.licenseLabel} · 打开原文段落", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
                            }
                        }
                    }
                    if (hits.isNotEmpty() && searched == query.trim()) item {
                        FilledTonalButton(onClick = { ask() }, enabled = phase == 0, modifier = Modifier.fillMaxWidth()) { Text("让 AI 结合这些资料回答") }
                    }
                }
            }
        }
    }
}
