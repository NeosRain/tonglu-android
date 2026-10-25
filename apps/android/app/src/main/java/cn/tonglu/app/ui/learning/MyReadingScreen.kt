package cn.tonglu.app.ui.learning

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.LearningArticle
import cn.tonglu.app.data.english.EnglishRepository
import cn.tonglu.app.data.english.MyReadingArticle
import cn.tonglu.app.data.readingimport.ReadingFileImport
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.readingimport.ImportedPdfView
import cn.tonglu.app.ui.readingimport.PaperReaderScreen
import cn.tonglu.app.ui.rememberOverlayMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 个人阅读书架：一本书一个入口，章节/页码在书内选择，复用现有正文精读。
 *
 * 🔴 2026-10-18 任务B：书架 / 导入 / 添加表单 / 书内章节 / PDF 原版各自是**独立 Dialog 窗口**。
 * 原来五六步都塞在同一个窗口里，返回手势拖动时系统移动的是这**一个**窗口，露出的永远是它下面的
 * 英语 Hub —— 用户看到的「最上一级背景」。拆成窗口后，返回手势露出的就是上一页窗口的真背景。
 *
 * 只捕获「文章/书」这条链路；PDF 原版窗口里再开 OCR 校对（`PdfOcrProofreadDialog`）时也天然是上一层。
 */
@Composable
fun MyReadingScreen(owner: String, onClose: () -> Unit, onOpen: (LearningArticle) -> Unit,
    reader: (@Composable (LearningArticle, () -> Unit) -> Unit)? = null,
    /** 从考试精读进来时预置分类（「四级」/「六级」）：导入的文章会归入该考试，在考试列表里可精读。 */
    initialTags: String = "",
    /** 直接打开某本书/试卷（考试列表点「个人导入试卷」用）。 */
    initialDocumentId: String? = null,
    /** 学习页的个人书库可直接查看同账号的进度、书签与笔记。 */
    onReadingHistory: (() -> Unit)? = null) {
    val context = LocalContext.current
    val repository = remember(owner) { EnglishRepository(context) }
    val importer = remember(owner) { ReadingFileImport(context, repository) }
    val scope = rememberCoroutineScope()
    var readingRevision by remember(owner) { mutableIntStateOf(0) }
    // IME 的「返回先收键盘」由每个窗口的 MyReadingLevelWindow 统一处理（见该 helper）。
    var activeReading by remember(owner) { mutableStateOf<LearningArticle?>(null) }
    val articles by repository.observeMyShelf(owner).collectAsState(initial = emptyList())
    var formOpen by rememberSaveable(owner) { mutableStateOf(false) }
    var notice by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var documentId by rememberSaveable(owner) { mutableStateOf(initialDocumentId) }
    // 试卷视图：整份文档一个入口（原版翻页 + 逐页 OCR 校对），不再按页做成章节。
    var paperDocumentId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    // 从考试精读进来默认按试卷导入（那是用户自己的卷子）；普通入口默认按文章。
    var importAsPaper by rememberSaveable(owner) { mutableStateOf(initialTags.isNotBlank()) }
    var previewFile by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var previewPage by rememberSaveable(owner) { mutableIntStateOf(1) }
    // 与来源/版权草稿一起恢复；只保存 URI 字符串，不把文件正文放进实例状态。
    var pendingUris by rememberSaveable(owner, stateSaver = listSaver(
        save = { uris: List<android.net.Uri> -> uris.map { it.toString() } },
        restore = { values -> values.map { android.net.Uri.parse(it) } },
    )) { mutableStateOf(emptyList()) }
    var source by rememberSaveable(owner) { mutableStateOf("") }
    var license by rememberSaveable(owner) { mutableStateOf("") }
    var progress by remember(owner) { mutableStateOf<String?>(null) }
    var importJob by remember(owner) { mutableStateOf<Job?>(null) }
    var deleting by remember(owner) { mutableStateOf<MyReadingArticle?>(null) }
    var showLicense by remember(owner) { mutableStateOf(false) }
    val importing = progress != null
    val chapters = articles.filter { it.documentId == documentId && documentId != null }.sortedBy { it.chapterIndex }
    val book = chapters.firstOrNull()
    val chapterProgress = remember(owner, chapters, readingRevision) {
        val library = cn.tonglu.app.data.ReadingLibrary.of(context, owner)
        chapters.associate { it.id to library.articleProgress(it.id)?.copy() }
    }

    fun openArticle(row: MyReadingArticle) {
        scope.launch {
            try {
                val article = repository.myArticle(row.id, owner) ?: error("文章已不存在，请重新导入")
                val converted = repository.toLearningArticle(article)
                if (reader == null) onOpen(converted) else {
                    cn.tonglu.app.data.ReadingStore(context, owner).apply {
                        recordOpen(converted); lastArticle = converted.id
                    }
                    activeReading = converted
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (cause: Exception) { notice = cause.message ?: "文章无法打开" }
        }
    }

    val importFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { pendingUris = uris; source = ""; license = ""; notice = null }
    }

    // ---------- 层 1：书架 ----------
    MyReadingLevelWindow(imeAwareBack = true, onResume = { readingRevision++ }, onClose = { OverlayDismissGuard.arm(); onClose() }) { requestClose ->
        PersonalLibraryShelf(
            articles = articles, owner = owner, revision = readingRevision, notice = notice, onClose = requestClose,
            onImport = { importFiles.launch(arrayOf("text/plain", "text/markdown", "text/x-markdown", "application/pdf", "application/epub+zip")) },
            onAdd = { formOpen = true }, onHistory = onReadingHistory,
            onOpen = { row ->
                if (row.documentId != null) {
                    if (row.tags.split("|").contains("试卷")) paperDocumentId = row.documentId else documentId = row.documentId
                } else openArticle(row)
            },
            onDelete = { deleting = it },
        )
    }

    // ---------- 层 2：导入（选文件 → 填来源/版权 → 导入） ----------
    if (pendingUris.isNotEmpty() || importing) {
        MyReadingLevelWindow(imeAwareBack = true, onClose = {
            if (importing) { importJob?.cancel(); notice = "导入已停止，已完成的文件保留。" }
            pendingUris = emptyList()
        }) { requestClose ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { requestClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级") }
                Text(if (importing) "正在导入" else "导入阅读文件", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            MyReadingNotice(notice)
            if (importing) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(progress.orEmpty())
                    TextButton({ importJob?.cancel(); notice = "导入已停止，已完成的文件保留。" }) { Text("停止导入（保留已完成文件）") }
                }
            } else {
                Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("已选 ${pendingUris.size} 个文件 · TXT / Markdown / PDF / EPUB")
                    Text("导入为", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!importAsPaper, { importAsPaper = false }, label = { Text("阅读文章") })
                        FilterChip(importAsPaper, { importAsPaper = true }, label = { Text("试卷（原版+逐页校对）") })
                    }
                    Text(if (importAsPaper) "试卷：整份 PDF 一个入口，原版左右翻页核对；每页先标记「待校对」，OCR 只生成页级草稿，你对照原图改完保存才转为「已校对」。不自动识别题目结构、不生成题目与答案。" else "文章：按章节/全文进入阅读器精读（PDF 有文字层时可读，乱码页仍可 OCR 校对）。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("来源和版权备注适用于本次选择的文件，文件名自动记录。只保存本机，按账号隔离，不上传原文。",
                        style = MaterialTheme.typography.bodySmall)
                    if (initialTags.isNotBlank()) Text(
                        "本次导入会归入「$initialTags」分类，可在考试精读里看到；仍只存本机，不上传、不公开发布。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(source, { source = it }, label = { Text("来源 *") }, supportingText = { Text("例如：自己的笔记、购买的电子书") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(license, { license = it }, label = { Text("版权备注 *") }, supportingText = { Text("例如：本人原创、已购仅供个人阅读") }, modifier = Modifier.fillMaxWidth())
                    Text("单文件最多 50 MB。EPUB 导入文字与章节；PDF 可看原版，有文字的页面支持精读。" +
                        "扫描页或文字层乱码的页面，可在「查看 PDF 原版」里 OCR 识别并对照原图校对后精读；加密或 DRM 正文暂不支持。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = source.isNotBlank() && license.isNotBlank(), onClick = {
                        val selectedUris = pendingUris.toList()
                        val sourceNote = source
                        val licenseNote = license
                        progress = "准备导入…"
                        // 工作协程持有本次快照。开始后不再恢复为待提交选择，避免重建后重复导入。
                        pendingUris = emptyList()
                        importJob = scope.launch {
                            val results = mutableListOf<String>()
                            var succeeded = 0
                            try {
                                for ((index, uri) in selectedUris.withIndex()) {
                                    try {
                                        val importTags = cn.tonglu.app.data.english.MyReadingRules.normalizeTags(
                                            listOf(initialTags, if (importAsPaper) "试卷" else "").filter { it.isNotBlank() }.joinToString("|"))
                                        val result = importer.import(owner, uri, sourceNote, licenseNote,
                                            tags = importTags, paper = importAsPaper) {
                                            progress = "${index + 1}/${selectedUris.size} · $it"
                                        }
                                        succeeded++
                                        results += "✓ ${result.title}：${result.parts} 页/章节" +
                                            (if (result.paper) "（试卷：全部待校对）" else "") +
                                            result.warnings.joinToString("；", prefix = if (result.warnings.isEmpty()) "" else "\n")
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (cause: Exception) { results += "第 ${index + 1} 个文件导入失败：${cause.message ?: "无法读取，请检查格式"}" }
                                }
                                notice = "导入完成：$succeeded/${selectedUris.size} 个文件成功。\n" + results.joinToString("\n")
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                notice = "导入已停止，已完成的文件保留。\n" + results.joinToString("\n")
                                throw cancelled
                            } finally { progress = null; pendingUris = emptyList(); importJob = null }
                        }
                    }) { Text("开始导入") }
                    TextButton({ showLicense = true }) { Text("PDF 解析组件与开源许可") }
                }
            }
        }
    }

    // ---------- 层 2：添加文章表单 ----------
    if (formOpen) {
        MyReadingLevelWindow(imeAwareBack = true, onClose = { formOpen = false }) { requestClose ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { requestClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级") }
                Text("添加文章", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            MyArticleForm(owner, null, initialTags) { _, message -> formOpen = false; notice = message }
        }
    }

    // ---------- 层 3：书内章节 ----------
    if (documentId != null && previewFile == null) {
        MyReadingLevelWindow(onResume = { readingRevision++ }, onClose = { documentId = null }) { requestClose ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { requestClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级") }
                Text(book?.documentTitle ?: "章节目录", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
            }
            MyReadingNotice(notice)
            if (book == null) Text("书籍已不存在，请返回书架。", Modifier.padding(24.dp))
            else {
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item("book-summary") {
                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    LibraryCover(book.documentTitle ?: book.title, book.sourceFormat)
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(book.documentTitle ?: book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        Text("${chapters.size} 个章节/页面 · ${book.sourceFormat}", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                    }
                                }
                                val resume = chapters.filter { it.textAvailable }.maxByOrNull {
                                    chapterProgress[it.id]?.updatedAt ?: 0L
                                }
                                resume?.let { row ->
                                    val seen = chapterProgress[row.id]
                                    Button(onClick = { openArticle(row) }, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (seen == null) "开始阅读" else if (seen.finished) "回看这一章" else "继续阅读")
                                    }
                                }
                                Text(book.sourceNote + "\n" + book.licenseNote, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                book.sourceFile?.let { file -> TextButton({ previewPage = 1; previewFile = file }) { Text("查看 PDF 原版") } }
                            }
                        }
                    }
                    item("book-tools") {
                        val bookIsPaper = book.tags.split("|").contains("试卷")
                        if (!bookIsPaper && book.sourceFormat == "PDF") {
                            // 旧版本导入的 PDF 已经按页建了章节：允许原地转成试卷视图，不要求用户重新导入。
                            TextButton({
                                val docId = book.documentId ?: return@TextButton
                                scope.launch {
                                    try {
                                        val pageCount = repository.markDocumentAsPaper(owner, docId)
                                        notice = "已按试卷整理：$pageCount 页全部标记为待校对（原版可左右翻页核对）。"
                                        paperDocumentId = docId
                                        documentId = null
                                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                    catch (cause: Exception) { notice = cause.message ?: "按试卷整理失败，请重试" }
                                }
                            }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("按试卷整理（原版翻页+逐页校对）") }
                        }
                    }
                    item("chapters-heading") { Text("章节目录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                    items(chapters, key = { it.id }) { row ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(18.dp), onClick = {
                            if (row.textAvailable) openArticle(row)
                            else { previewPage = row.sourcePage ?: 1; previewFile = row.sourceFile }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text((chapters.indexOf(row) + 1).toString().padStart(2, '0'),
                                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(row.title, fontWeight = FontWeight.SemiBold)
                                    val record = chapterProgress[row.id]
                                    Text(if (!row.textAvailable) "待校对 · 查看原版" else if (record?.finished == true) "已读完 · 再读一次"
                                        else if (record != null) "继续阅读 · ${(record.score * 100).toInt()}%" else "开始阅读与精读",
                                        style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                                }
                                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = AppColors.TextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- 层 4：PDF 原版（左右滑动翻页） ----------
    if (previewFile != null && book != null) {
        MyReadingLevelWindow(onClose = { previewFile = null }) { requestClose ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { requestClose() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级") }
                Text("PDF 原版", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("左右滑动翻页", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MyReadingNotice(notice)
            ImportedPdfView(owner, previewFile!!, previewPage,
                chapters.mapNotNull { it.sourcePage }.maxOrNull() ?: 1,
                hasText = { page -> chapters.any { it.sourcePage == page && it.textAvailable } },
                onReadText = { page -> chapters.firstOrNull { it.sourcePage == page && it.textAvailable }?.let { openArticle(it) } },
                chapterForPage = { page -> chapters.firstOrNull { it.sourcePage == page } },
                onSaveOcrText = { row, verified ->
                    scope.launch {
                        try {
                            val body = cn.tonglu.app.data.readingimport.ReadingImportParser.plainMarkdown(verified)
                            require(body.isNotBlank()) { "校对后的文字为空，未保存" }
                            // 先按 owner 确认这一页还在：别用 upsert 把已经删掉的文章「复活」成孤儿行。
                            val current = repository.myArticle(row.id, owner)
                                ?: error("这一页的文章已不存在，请重新导入")
                            repository.dao.upsertMyArticle(current.copy(body = body, textAvailable = true))
                            val pageLabel = row.sourcePage?.toString() ?: "?"
                            notice = "第 $pageLabel 页的文字已按你的校对保存；返回章节列表即可打开精读。"
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (cause: Exception) { notice = cause.message ?: "校对文字保存失败，请重试" }
                    }
                },
                onPageChanged = { previewPage = it },
            )
        }
    }

    // ---------- 层 5：试卷视图（原版左右翻页 + 逐页 OCR 校对） ----------
    paperDocumentId?.let { doc ->
        PaperReaderScreen(owner = owner, documentId = doc, onClose = { paperDocumentId = null })
    }

    deleting?.let { row -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除本机副本？") },
        text = { Text("删除「${row.documentTitle ?: row.title}」${if (row.documentId != null) "及全部章节" else ""}。原始文件不会改变。") },
        confirmButton = { TextButton({
            deleting = null
            scope.launch {
                try {
                    val ok = if (row.documentId != null) repository.deleteMyDocument(row.documentId, owner) else repository.deleteMyArticle(row.id, owner)
                    notice = if (ok) "已删除本机阅读副本" else "没有找到该文件，请刷新后重试"
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (cause: Exception) { notice = cause.message ?: "删除失败，请重试" }
            }
        }) { Text("删除") } }, dismissButton = { TextButton({ deleting = null }) { Text("取消") } }) }
    if (showLicense) AlertDialog(onDismissRequest = { showLicense = false }, title = { Text("PdfBox-Android · Apache-2.0") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text("https://github.com/TomRoush/PdfBox-Android\n版本 2.0.27.0\n")
            val licenseText = remember { listOf("PdfBox-Android.txt", "PdfBox-Android-NOTICE.txt").joinToString("\n\n") { name ->
                context.assets.open("licenses/$name").bufferedReader().use { it.readText() }
            } }
            Text(licenseText, style = MaterialTheme.typography.bodySmall)
        } }, confirmButton = { TextButton({ showLicense = false }) { Text("关闭") } })
    // 与考试选材页相同的子阅读器入口；书架/书页窗口保持组合，关闭正文回到原书/原页码列表。
    activeReading?.let { current -> reader?.invoke(current) { OverlayDismissGuard.arm(); activeReading = null } }
}

/**
 * 一层阅读/导入页的独立窗口：自带进出场动画与跟手返回（返回露出的就是下面那层窗口）。
 *
 * [imeAwareBack]：导入表单的窗口在键盘弹起时，返回先收键盘、不关窗口 —— 旧版单窗口的
 * `back()` 有这条，拆成多窗口后必须由窗口自己接回来（否则输入来源/版权时一按返回就把导入丢了）。
 */
@Composable
private fun MyReadingLevelWindow(
    imeAwareBack: Boolean = false,
    onResume: () -> Unit = {},
    onClose: () -> Unit,
    content: @Composable (requestClose: () -> Unit) -> Unit,
) {
    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )
    var dialogBack by remember { mutableStateOf<() -> Unit>({ motion.requestClose() }) }
    Dialog(
        onDismissRequest = { dialogBack() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // 控制器、焦点与 Insets 必须在 Dialog 内获取，宿主窗口不能控制此窗口的 IME。
        val view = androidx.compose.ui.platform.LocalView.current
        val resume by rememberUpdatedState(onResume)
        DisposableEffect(view) {
            val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) resume() }
            view.viewTreeObserver.addOnWindowFocusChangeListener(listener)
            onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(listener) }
        }
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val back: () -> Unit = {
            if (imeAwareBack && imeVisible) { focus.clearFocus(); keyboard?.hide() }
            else motion.requestClose()
        }
        SideEffect { dialogBack = back }
        // 全屏 Dialog 用不透明底色：AppColors.Canvas 在有壁纸时是半透明的，会让上一级
        // 窗口（Hub / 考试列表）透出来，看起来像「导入页夹在两层中间」。壁纸只留在根层。
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false, onBack = { back() }), color = AppColors.CanvasOpaque) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) { content(back) }
        }
    }
}

@Composable
private fun MyReadingNotice(notice: String?) {
    notice?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp))
    }
}

/**
 * 添加 / 编辑表单。四项必填：标题、正文、来源、版权 —— 与仓储层同一判据
 * （`addMyArticle` 的 require），按钮先禁用、保存失败再 Toast，两层都挡。
 */
@Composable
private fun MyArticleForm(
    owner: String, origin: MyReadingArticle?, initialTags: String = "", onDone: (Boolean, String) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(owner) { EnglishRepository(context) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var title by remember { mutableStateOf(origin?.title ?: "") }
    var body by remember { mutableStateOf(origin?.body ?: "") }
    var tags by remember { mutableStateOf(origin?.tags?.replace("|", " ") ?: initialTags) }
    var sourceNote by remember { mutableStateOf(origin?.sourceNote ?: "") }
    var licenseNote by remember { mutableStateOf(origin?.licenseNote ?: "") }
    var busy by remember { mutableStateOf(false) }

    val ready = cn.tonglu.app.data.english.MyReadingRules.canSave(title, body, sourceNote, licenseNote)
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(if (origin == null) "放进一篇想读的文章" else "编辑文章", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("粘贴正文，留好来源，就能随时回来读。", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
        OutlinedTextField(title, { title = it }, label = { Text("标题 *") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it }, label = { Text("正文 *（支持 Markdown）") },
            minLines = 8, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(tags, { tags = it }, label = { Text("分类标签") }, supportingText = { Text("用空格分隔，例如：四级 笔记") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sourceNote, { sourceNote = it },
            label = { Text("来源 *") }, supportingText = { Text("例如：自己的笔记、书名或原文出处") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(licenseNote, { licenseNote = it },
            label = { Text("版权备注 *") }, supportingText = { Text("例如：本人原创、已购仅供个人阅读") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(
            "文章只存本机、按账号隔离，不上传不分享；来源与版权为自己填写的学习备注。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { onDone(false, "") }, enabled = !busy) { Text("取消") }
            Button(
                enabled = ready && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            repository.addMyArticle(owner, origin?.id, title, body, tags, sourceNote, licenseNote)
                            onDone(true, if (origin == null) "已添加「${title.trim()}」" else "已保存")
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (cause: Exception) {
                            android.widget.Toast.makeText(
                                context, cause.message ?: "保存失败",
                                android.widget.Toast.LENGTH_SHORT).show()
                        } finally { busy = false }
                    }
                },
            ) { Text(if (busy) "保存中…" else "保存") }
            if (!ready) {
                Text(
                    "带 * 的四项都要填",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
        }
    }
}
