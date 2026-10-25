package cn.tonglu.app.ui.readingimport

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.english.MyReadingArticle
import cn.tonglu.app.data.readingimport.ReadingImportFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PDF 原版阅读器：**左右滑动翻页**（[HorizontalPager] 自带跟手与翻页动画），
 * 仍保留上一页/下一页按钮、页码跳转与缩放；缩放 > 1 时暂停翻页手势，改成在原图里平移，
 * 避免「想放大看细节却一直翻页」。
 *
 * 只绘制当前页附近的页面，切页即释放 renderer/file descriptor；不把整本 PDF 预先转成图片。
 *
 * [chapterForPage] / [onSaveOcrText]：文字层取不出或疑似乱码的页面，可按「OCR 识别并校对」，
 * 本机识别 + 用户对照原图校对后写回该页正文（不覆盖 PDF 原件）。null = 不提供 OCR 入口。
 * [onPageChanged] 把当前页码告诉宿主（试卷页面状态、校对进度都靠它同步）。
 */
@Composable
fun ImportedPdfView(owner: String, filename: String, initialPage: Int, pageCount: Int,
    hasText: (Int) -> Boolean, onReadText: (Int) -> Unit,
    chapterForPage: (Int) -> MyReadingArticle? = { null },
    onSaveOcrText: ((MyReadingArticle, String) -> Unit)? = null,
    onPageChanged: ((Int) -> Unit)? = null,
    /** 试卷模式不需要「阅读文字」主按钮（校对稿以原版为准），宿主可关掉。 */
    showReadText: Boolean = true) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val startPage = initialPage.coerceIn(1, pageCount)
    val pager = rememberPagerState(initialPage = startPage - 1, pageCount = { pageCount })
    val scope = rememberCoroutineScope()
    var jump by rememberSaveable(owner, filename) { mutableStateOf(startPage.toString()) }
    var zoom by rememberSaveable(owner, filename) { mutableFloatStateOf(1f) }
    var ocrOpen by rememberSaveable(owner, filename) { mutableStateOf(false) }
    val page = pager.currentPage + 1
    val zoomed = zoom > 1.01f
    // 宿主从「第 N 页」打开（例如章节列表点了第 5 页）时定位过去；同一窗口重新打开也能生效。
    LaunchedEffect(initialPage) {
        val target = initialPage.coerceIn(1, pageCount) - 1
        if (pager.currentPage != target) pager.scrollToPage(target)
    }

    LaunchedEffect(pager.currentPage) {
        jump = (pager.currentPage + 1).toString()
        onPageChanged?.invoke(pager.currentPage + 1)
    }
    fun goTo(target: Int) {
        val clamped = target.coerceIn(1, pageCount)
        if (clamped - 1 == pager.currentPage) return
        scope.launch { pager.animateScrollToPage(clamped - 1) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ goTo(page - 1) }, enabled = page > 1) { Text("上一页") }
            Text("第 $page / $pageCount 页", style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            TextButton({ goTo(page + 1) }, enabled = page < pageCount) { Text("下一页") }
            OutlinedTextField(jump, { jump = it.filter(Char::isDigit).take(4) }, singleLine = true,
                modifier = Modifier.width(76.dp), label = { Text("页码") })
            TextButton({ goTo(jump.toIntOrNull() ?: page) }) { Text("跳转") }
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("缩放", style = MaterialTheme.typography.labelMedium)
            Slider(zoom, { zoom = it }, valueRange = 1f..2.5f,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp), steps = 5)
            Text(if (zoomed) "放大中 · 翻页已暂停" else "左右滑动翻页",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showReadText) {
                TextButton({ onReadText(page) }, enabled = hasText(page)) { Text("阅读文字") }
            }
            if (onSaveOcrText != null && chapterForPage(page) != null) {
                TextButton({ ocrOpen = true }) {
                    Text(if (hasText(page)) "OCR 校对" else "OCR 识别并校对")
                }
            }
        }
        if (!hasText(page)) Text(
            "本页没有可提取文字或文字疑似乱码。可点「OCR 识别并校对」：识别在本机离线进行，保存前请对照原图逐字校对；" +
                "校对后的文字才会作为可阅读/可校对内容。",
            modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            // 放大后把水平手势让给页面内的平移，不然「看细节」永远被翻页抢走。
            userScrollEnabled = !zoomed,
            pageSpacing = 8.dp,
        ) { index ->
            PdfPageImage(owner = owner, filename = filename, pageIndex = index, zoom = zoom,
                modifier = Modifier.fillMaxSize())
        }
    }
    if (ocrOpen) {
        val chapter = chapterForPage(page)
        if (chapter == null) {
            ocrOpen = false
        } else {
            PdfOcrProofreadDialog(
                owner = owner, sourceFile = filename, page = page, title = chapter.title,
                onDismiss = { ocrOpen = false },
                onSave = { verified ->
                    ocrOpen = false
                    onSaveOcrText?.invoke(chapter, verified)
                },
            )
        }
    }
}

/** 单页渲染：懒加载当前页位图；缩放 > 1 时在页面内双向平移，不把整本 PDF 读进内存。 */
@Composable
private fun PdfPageImage(owner: String, filename: String, pageIndex: Int, zoom: Float, modifier: Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val targetWidth = with(density) { (maxWidth * zoom).roundToPx() }.coerceIn(1, 2400)
        var bitmap by remember(owner, filename, pageIndex, targetWidth) { mutableStateOf<Bitmap?>(null) }
        var error by remember(owner, filename, pageIndex, targetWidth) { mutableStateOf<String?>(null) }
        LaunchedEffect(owner, filename, pageIndex, targetWidth) {
            try {
                bitmap = withContext(Dispatchers.IO) {
                    val file = ReadingImportFiles.pdf(context, owner, filename)
                    require(file.isFile) { "PDF 副本不存在，请重新导入" }
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                        PdfRenderer(fd).use { renderer ->
                            val pageNumber = pageIndex + 1
                            require(pageNumber in 1..renderer.pageCount) { "PDF 页码超出范围" }
                            renderer.openPage(pageNumber - 1).use { pdfPage ->
                                val scale = min(targetWidth.toFloat() / pdfPage.width, 3200f / pdfPage.height)
                                Bitmap.createBitmap((pdfPage.width * scale).roundToInt().coerceAtLeast(1),
                                    (pdfPage.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                    .also { image ->
                                        // PDF 原版纸张颜色，不是应用页面颜色槽。
                                        image.eraseColor(android.graphics.Color.WHITE)
                                        pdfPage.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    }
                            }
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                error = "PDF 页面无法打开：${cause.message ?: "请重新导入"}"
            }
        }
        when {
            error != null -> Text(error!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
            bitmap == null -> CircularProgressIndicator(Modifier.padding(32.dp))
            else -> {
                val image = bitmap!!
                val scrollModifier = if (zoom > 1.01f) {
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                } else Modifier.fillMaxSize()
                Box(scrollModifier) {
                    Image(image.asImageBitmap(), "PDF 第 ${pageIndex + 1} 页",
                        modifier = Modifier.requiredSize(with(density) { image.width.toDp() },
                            with(density) { image.height.toDp() }))
                }
            }
        }
    }
}