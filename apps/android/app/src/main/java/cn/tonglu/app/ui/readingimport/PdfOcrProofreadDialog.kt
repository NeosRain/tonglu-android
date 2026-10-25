package cn.tonglu.app.ui.readingimport

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.readingimport.PdfPageOcr
import cn.tonglu.app.data.readingimport.ReadingTextQuality
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PDF 页面 OCR 校对（本机进行，不上传图片）。
 *
 * 用户样本（2025 年 6 月四级试题册）页面视觉清楚、PDF 文字层却是乱码，
 * 因此「导入即精读」不能只靠文字层：这里把页面渲染出来、用随应用的离线 OCR 识别，
 * 再让用户**对照原图逐字校对**后才保存 —— 保存的是用户校对的文字，不是 OCR 原文。
 *
 * 🔴 OCR 模型是拉丁文字模型，主要识别英文；中文、公式、页眉页脚可能识别不全，
 * 界面必须如实说明，不能把识别结果说成「已还原原文」。
 */
@Composable
fun PdfOcrProofreadDialog(
    owner: String,
    sourceFile: String,
    page: Int,
    title: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val context = LocalContext.current
    var bitmap by remember(owner, sourceFile, page) { mutableStateOf<Bitmap?>(null) }
    var text by remember(owner, sourceFile, page) { mutableStateOf("") }
    var busy by remember(owner, sourceFile, page) { mutableStateOf(true) }
    var error by remember(owner, sourceFile, page) { mutableStateOf<String?>(null) }
    var attempt by remember(owner, sourceFile, page) { mutableIntStateOf(0) }
    var metrics by remember(owner, sourceFile, page) { mutableStateOf<String?>(null) }
    var warnings by remember(owner, sourceFile, page) { mutableStateOf("") }

    LaunchedEffect(owner, sourceFile, page, attempt) {
        busy = true
        error = null
        try {
            // 预览与 OCR 共用一张 2200px 位图（不再重复渲染）；中英文双模型并行识别。
            val rendered = withContext(Dispatchers.IO) {
                PdfPageOcr.renderPage(context, owner, sourceFile, page, maxWidthPx = 2200)
            }
            bitmap = rendered
            val scan = PdfPageOcr.recognizeScan(rendered)
            text = PdfPageOcr.layoutText(scan.page).trim()
            metrics = "本机识别 ${scan.elapsedMs} ms · 拉丁 ${scan.latinLines} 行 / 中文 ${scan.chineseLines} 行 / 合并 ${scan.mergedLines} 行"
            warnings = scan.warnings.joinToString("；")
            if (text.isBlank()) {
                error = "这一页没有识别到文字（纯图/过暗/空白页都可能；若为中文封面，中文模型也可能只识别到少量字）。可重试，或直接在下面手动输入后再保存。"
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (cause: Exception) {
            error = "OCR 识别失败：${cause.message ?: "请重试"}"
        } finally {
            busy = false
        }
    }

    // 全屏层的退场动画走统一 motion（audit_overlay_structure C11）：只有 onDismissRequest
    // 的层关窗时是「瞬间消失」，和阅读器/材料页的动画不一致。
    // 保存时先播退场动画，再回调父层写库（避免「窗口先没了、保存还在跑」）。
    var saveAfterExit by remember(owner, sourceFile, page) { mutableStateOf<String?>(null) }
    val motion = rememberOverlayMotion(
        onClose = {
            OverlayDismissGuard.arm()
            val verified = saveAfterExit
            saveAfterExit = null
            if (verified == null) onDismiss() else onSave(verified)
        },
        predictive = OverlayMotion.enabledState, selfDrag = true,
    )
    fun close() {
        motion.requestClose()
    }

    Dialog(onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false, onBack = { close() }),
            color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { close() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回 PDF 原版")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("OCR 校对 · 第 $page 页", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(title, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                Text(
                    "识别在本机离线进行（拉丁 + 中文两个模型），图片不上传、不调用云端。中文封面/说明用中文模型，英文用拉丁模型；" +
                        "公式/水印/页边仍可能识别错。这里只生成页级文字草稿，不自动识别题目结构、不生成题目与答案、不承诺复刻字体版式；" +
                        "请对照下面原图校对后再保存，你改过的文字才记为「已校对」。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                metrics?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                }
                warnings.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp))
                }
                error?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp))
                }
                bitmap?.let { image ->
                    Image(
                        image.asImageBitmap(),
                        contentDescription = "PDF 第 $page 页原图",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 8.dp),
                    )
                }
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("正在本机识别第 $page 页…", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("识别文字（请对照原图校对）") },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp),
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { attempt++ }, enabled = !busy) { Text("重新识别") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { close() }) { Text("取消") }
                    Button(onClick = {
                        val verified = text.trim()
                        if (verified.isNotEmpty()) {
                            saveAfterExit = verified
                            close()
                        }
                    }, enabled = !busy && text.isNotBlank()) { Text("保存校对文字") }
                }
            }
        }
    }
}
