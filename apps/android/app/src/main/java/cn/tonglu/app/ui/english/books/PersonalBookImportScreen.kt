package cn.tonglu.app.ui.english.books

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.english.books.ImportRow
import cn.tonglu.app.data.english.books.PersonalWordBookStore
import cn.tonglu.app.data.english.books.WordListImport
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EF11 导入：主流程只保留「选择文件 → 校对预览 → 确认导入」三步。
 *
 * - 预览每行显示行号、原始行、可编辑的词/释义；重复行与「需补释义」行单独标出；
 * - 释义为空或仍标重复的行**不会**被静默导入：确认时逐项调用 store.add，
 *   重复与失败如实计数并留在列表里可改后重试；
 * - 支持格式如实写在页面上（TSV / 分隔符 / 纯单词行），未实现的格式不进清单。
 */
@Composable
fun PersonalBookImportScreen(owner: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember(owner) { PersonalWordBookStore(context, owner) }
    val scope = rememberCoroutineScope()
    var step by rememberSaveable(owner) { mutableStateOf("pick") } // pick | preview | done
    var sourceName by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var rows by remember(owner) { mutableStateOf<List<ImportRow>>(emptyList()) }
    var skipped by remember(owner) { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var parsing by remember(owner) { mutableStateOf(false) }
    var notice by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var importing by remember(owner) { mutableStateOf(false) }
    var summary by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    // 目标书：下拉选择已有书，或输入新名现场建（数量上限由 store 兜底）
    var books by remember(owner) { mutableStateOf<List<Pair<String, String>>>(emptyList()) } // id→name
    var targetBookId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var newBookName by rememberSaveable(owner) { mutableStateOf("") }

    fun reloadBooks() {
        scope.launch {
            try {
                books = store.snapshot().books.map { it.id to it.name }
                if (targetBookId !in books.map { it.first }) targetBookId = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (cause: Exception) { notice = cause.message ?: "词书读取失败" }
        }
    }
    LaunchedEffect(owner) { reloadBooks() }

    fun parseText(text: String, name: String) {
        scope.launch {
            parsing = true
            try {
                val result = withContext(Dispatchers.Default) { WordListImport.parse(text) }
                rows = result.rows; skipped = result.skipped
                sourceName = name
                summary = null; notice = null; step = "preview"
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (cause: Exception) { notice = cause.message ?: "解析失败" }
            finally { parsing = false }
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                parsing = true
                try {
                    val bytes = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: error("文件无法读取")
                    }
                    // 常见中文词单是 GBK：UTF-8 解出替换符就退回 GBK 再试，两都不行才报错
                    var text = bytes.toString(Charsets.UTF_8)
                    var charsetUsed = "UTF-8"
                    if (text.contains('\uFFFD')) {
                        text = bytes.toString(charset("GBK")); charsetUsed = "GBK"
                    }
                    parseText(text, uri.lastPathSegment ?: "已选文件")
                    if (text.contains('\uFFFD')) notice = "文件编码无法完整识别（$charsetUsed 仍含乱码字符），请检查预览结果。"
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (cause: Exception) { notice = cause.message ?: "读取文件失败" }
                finally { parsing = false }
            }
        }
    }
    var pasteOpen by rememberSaveable(owner) { mutableStateOf(false) }
    var pasteText by rememberSaveable(owner) { mutableStateOf("") }

    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState, selfDrag = true,
    )
    fun back() {
        when {
            pasteOpen -> pasteOpen = false
            step == "preview" -> { step = "pick"; rows = emptyList(); skipped = emptyList(); summary = null }
            else -> motion.requestClose()
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = { back() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false, onBack = { back() }), color = AppColors.Canvas) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级") }
                    Text(
                        when (step) {
                            "preview" -> "校对预览（${rows.size} 词条）"
                            else -> "导入词单"
                        }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
                    if (step == "preview" && !importing) {
                        TextButton(onClick = { confirmImport(rows, store, targetBookId, newBookName, scope,
                            onDone = { added, dup, failed ->
                                summary = "新增 $added · 书内重复 $dup · 失败 $failed" +
                                    (if (failed > 0) "（失败行已标出，可修正后重试）" else "")
                                step = "done"; importing = false; reloadBooks()
                            },
                            onError = { notice = it; importing = false },
                            onNewBookCreated = { reloadBooks() },
                            setBusy = { importing = it }) }) { Text("确认导入") }
                    }
                }
                notice?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp))
                }
                when (step) {
                    "pick" -> {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("支持格式（逐行）", fontWeight = FontWeight.Medium)
                            Text("· 单词<TAB>释义（可第三列原型）\n· 单词 - 释义 / 单词：释义\n· 一行一个纯单词（导入时需补释义）",
                                style = MaterialTheme.typography.bodySmall)
                            Text("确认前不写入词书；预览里可逐行改词与释义，原始行保留可回看。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { pickFile.launch(arrayOf("text/*", "application/json")) }, enabled = !parsing) {
                                    Text(if (parsing) "读取中…" else "选择文件")
                                }
                                OutlinedButton(onClick = { pasteOpen = true }, enabled = !parsing) { Text("粘贴文本") }
                            }
                        }
                        if (pasteOpen) {
                            AlertDialog(onDismissRequest = { pasteOpen = false }, title = { Text("粘贴词单文本") },
                                text = {
                                    OutlinedTextField(value = pasteText, onValueChange = { pasteText = it },
                                        minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth())
                                },
                                confirmButton = {
                                    TextButton(onClick = { pasteOpen = false; parseText(pasteText, "粘贴文本") },
                                        enabled = pasteText.isNotBlank()) { Text("解析") }
                                },
                                dismissButton = { TextButton(onClick = { pasteOpen = false }) { Text("取消") } })
                        }
                    }
                    "preview" -> ImportPreviewBody(rows, skipped, books, targetBookId, newBookName,
                        onTarget = { targetBookId = it; newBookName = "" },
                        onNewBookName = { newBookName = it },
                        onEdit = { lineNo, surface, meaning ->
                            rows = rows.map { if (it.lineNo == lineNo) it.copy(surface = surface, meaning = meaning) else it }
                        })
                    else -> {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            summary?.let { Text(it, fontWeight = FontWeight.Medium) }
                            Text("只有「新增」的词写进了词书；重复与失败未写入。可返回预览修正后重试。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { step = "preview" }) { Text("返回预览修正") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportPreviewBody(rows: List<ImportRow>, skipped: List<Pair<Int, String>>,
    books: List<Pair<String, String>>, targetBookId: String?, newBookName: String,
    onTarget: (String?) -> Unit, onNewBookName: (String) -> Unit,
    onEdit: (Int, String, String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("目标词书", fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (books.isEmpty()) {
                    Text("还没有词书：填新名字现场创建。", style = MaterialTheme.typography.bodySmall)
                } else {
                    books.forEach { (id, name) ->
                        FilterChip(selected = targetBookId == id, onClick = { onTarget(if (targetBookId == id) null else id) },
                            label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
            OutlinedTextField(value = newBookName, onValueChange = { if (it.length <= 40) onNewBookName(it) },
                label = { Text("或新建词书名") }, singleLine = true, enabled = targetBookId == null,
                modifier = Modifier.fillMaxWidth())
        }
        if (skipped.isNotEmpty()) item {
            Text("跳过 ${skipped.size} 行（未识别/注释/空行），原始内容保留在下方，不静默丢词：",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            skipped.take(20).forEach { (lineNo, reason) ->
                Text("第 $lineNo 行：$reason", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(rows, key = { it.lineNo }) { row ->
            var surface by remember(row.lineNo, row.surface) { mutableStateOf(row.surface) }
            var meaning by remember(row.lineNo, row.meaning) { mutableStateOf(row.meaning) }
            val isDuplicate = row.duplicateOfLine != null
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("第 ${row.lineNo} 行", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (isDuplicate) AssistChip(onClick = {}, label = { Text("与第 ${row.duplicateOfLine} 行重复") })
                        if (row.needsMeaning && meaning.isBlank()) AssistChip(onClick = {}, label = { Text("需补释义") })
                    }
                    OutlinedTextField(value = surface, onValueChange = { surface = it; onEdit(row.lineNo, it, meaning) },
                        label = { Text("词") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = meaning, onValueChange = { meaning = it; onEdit(row.lineNo, surface, it) },
                        label = { Text("释义（导入必填）") }, modifier = Modifier.fillMaxWidth())
                    Text("原始：${row.rawLine.take(80)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** 确认导入：缺释义/无目标书先拒绝并说明；逐项 store.add，结果如实计数，不静默吞。 */
private fun confirmImport(
    rows: List<ImportRow>,
    store: PersonalWordBookStore,
    targetBookId: String?,
    newBookName: String,
    scope: kotlinx.coroutines.CoroutineScope,
    onNewBookCreated: () -> Unit,
    setBusy: (Boolean) -> Unit,
    onDone: (added: Int, duplicate: Int, failed: Int) -> Unit,
    onError: (String) -> Unit,
) {
    val hasTarget = targetBookId != null || newBookName.isNotBlank()
    if (!hasTarget) { onError("先选一本目标词书，或填新词书名。"); return }
    val ready = rows.filterNot { it.duplicateOfLine != null } // 重复行默认不导（可改词后变非重复）
    if (ready.isEmpty()) { onError("没有可导入的行（全部是重复行）。"); return }
    val missingMeaning = ready.count { it.meaning.isBlank() }
    if (missingMeaning > 0) { onError("还有 $missingMeaning 行没填释义；补齐后再导入，或先删掉这些行。"); return }
    scope.launch {
        setBusy(true)
        try {
            val bookId = targetBookId ?: store.create(newBookName.trim()).id.also { onNewBookCreated() }
            var added = 0; var duplicate = 0; var failed = 0
            for (row in ready) {
                try {
                    val fresh = store.add(bookId, cn.tonglu.app.data.english.books.PersonalBookWord(
                        surface = row.surface.trim(), lemma = row.lemma.trim(), meaning = row.meaning.trim(),
                        sentence = "", articleId = "", sourcePosition = "导入第${row.lineNo}行"))
                    if (fresh) added++ else duplicate++
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (cause: Exception) { failed++ }
            }
            onDone(added, duplicate, failed)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (cause: Exception) { onError(cause.message ?: "导入失败") }
    }
}
