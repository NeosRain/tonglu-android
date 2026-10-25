package cn.tonglu.app.ui.readingimport

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.english.EnglishRepository
import cn.tonglu.app.data.english.MyReadingArticle
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 个人导入**试卷**的阅读/校对页（2026-10-18 任务B）。
 *
 * 设计原则（用户反馈直接决定）：
 * - 试卷是**一份完整的试题册**，不是 24 个章节：入口只有一个，打开就是原版 PDF，左右滑动翻页；
 * - 原版 PDF 保留字体/版式，作为唯一可信的核对底稿；
 * - OCR 只生成**页级文字草稿**，必须由用户对照原图校对后才标为「已校对」；
 * - **不自动识别题目结构、不生成题目/答案、不承诺复刻字体版式** —— 识别不可靠就停在待校对。
 * - 只存本机（Room + app 私有 PDF 副本），不上传。
 */
@Composable
fun PaperReaderScreen(owner: String, documentId: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(owner) { EnglishRepository(context) }
    val scope = rememberCoroutineScope()
    val articles by repository.observeMyShelf(owner).collectAsState(initial = emptyList())
    val pages = articles.filter { it.documentId == documentId }.sortedBy { it.chapterIndex }
    val book = pages.firstOrNull()
    var notice by remember(owner, documentId) { mutableStateOf<String?>(null) }
    var currentPage by rememberSaveable(owner, documentId) { mutableIntStateOf(1) }
    val pending = pages.count { !it.textAvailable }
    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )
    fun saveCorrected(row: MyReadingArticle, verified: String) {
        scope.launch {
            try {
                val body = cn.tonglu.app.data.readingimport.ReadingImportParser.plainMarkdown(verified.trim())
                require(body.isNotBlank()) { "校对后的文字为空，未保存" }
                // 按 owner 复核这一页还在：不拿 upsert 把已删文档「复活」。
                val current = repository.myArticle(row.id, owner)
                    ?: error("这一页已不存在，请返回重新导入")
                repository.dao.upsertMyArticle(current.copy(body = body, textAvailable = true))
                notice = "第 ${row.sourcePage ?: "?"} 页已标为「已校对」（只保存在本机）。"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                notice = cause.message ?: "校对文字保存失败，请重试"
            }
        }
    }

    // OCR 全册草稿：只对未校对页重跑本机 OCR，写回页级草稿（textAvailable 保持 false），
    // 界面显示进度并可取消；已校对页不动，避免覆盖用户校对。
    var bulkJob by remember(owner, documentId) { mutableStateOf<Job?>(null) }
    var bulkText by remember(owner, documentId) { mutableStateOf<String?>(null) }
    fun recognizeWholePaper() {
        val sourceFile = book?.sourceFile ?: return
        if (bulkJob?.isActive == true) {
            bulkJob?.cancel()
            return
        }
        bulkJob = scope.launch {
            var ok = 0
            var failed = 0
            val ordered = pages.sortedBy { it.chapterIndex }
            try {
                for ((index, row) in ordered.withIndex()) {
                    if (row.textAvailable) continue
                    val pageNumber = row.sourcePage ?: continue
                    bulkText = "本机 OCR 识别中：第 $pageNumber 页（${index + 1}/${ordered.size}）"
                    try {
                        val layout = cn.tonglu.app.data.readingimport.PdfPageOcr.recognizePage(
                            context, owner, sourceFile, pageNumber,
                        )
                        if (layout.isBlank()) {
                            failed += 1
                        } else {
                            val current = repository.myArticle(row.id, owner)
                            if (current != null) {
                                repository.dao.upsertMyArticle(current.copy(body = layout))
                                ok += 1
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (cause: Exception) {
                        failed += 1
                    }
                }
                notice = if (failed == 0) {
                    "本机 OCR 完成：$ok 页已生成待校对草稿（拉丁+中文双模型，未上传图片）；草稿仍可能有错字，请逐页对照原版校对。"
                } else {
                    "本机 OCR 完成：$ok 页有草稿，$failed 页没有识别出文字（封面/纯图可能）；请逐页校对。"
                }
            } finally {
                bulkText = null
                bulkJob = null
            }
        }
    }

    Dialog(
        onDismissRequest = { motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false, onBack = motion::requestClose),
            color = AppColors.Canvas) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { motion.requestClose() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("试卷原版 · 待校对", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(book?.documentTitle ?: "个人导入试卷",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (bulkText == null) {
                        TextButton(onClick = { recognizeWholePaper() }, enabled = book?.sourceFile != null) {
                            Text("OCR 全册")
                        }
                    } else {
                        TextButton(onClick = { recognizeWholePaper() }) { Text("取消") }
                    }
                }
                bulkText?.let { progress ->
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Text(progress, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                }
                val currentRow = pages.firstOrNull { it.sourcePage == currentPage }
                Text(
                    "第 $currentPage 页 · " + (if (currentRow?.textAvailable == true) "已校对" else "待校对") +
                        " · 共 ${pages.size} 页 / 待校对 $pending 页。原版保留字体/版式，是核对底稿；" +
                        "OCR 只生成页级文字草稿，不自动识别题目结构、不生成题目与答案。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                notice?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                }
                if (book?.sourceFile == null || pages.isEmpty()) {
                    Text("试卷文件不存在或已被删除，请回到「我的文章与书籍」重新导入。",
                        Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                } else {
                    ImportedPdfView(
                        owner = owner,
                        filename = book.sourceFile,
                        initialPage = currentPage,
                        pageCount = pages.size,
                        hasText = { page -> pages.firstOrNull { it.sourcePage == page }?.textAvailable == true },
                        onReadText = {},
                        chapterForPage = { page -> pages.firstOrNull { it.sourcePage == page } },
                        onSaveOcrText = { row, verified -> saveCorrected(row, verified) },
                        onPageChanged = { currentPage = it },
                        showReadText = false,
                    )
                }
            }
        }
    }
}