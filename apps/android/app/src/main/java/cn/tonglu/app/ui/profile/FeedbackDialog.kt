package cn.tonglu.app.ui.profile

import kotlinx.coroutines.launch
import cn.tonglu.app.data.ApiFactory
import cn.tonglu.app.data.FeedbackSubmission
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.BuildConfig
import cn.tonglu.app.data.AppBrand
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion

/** Anonymous in-app delivery; a receipt is shown only after server acknowledgement. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FeedbackDialog(onClose: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var detail by rememberSaveable { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var submitting by remember { mutableStateOf(false) }
    var receipt by rememberSaveable { mutableStateOf<String?>(null) }
    var submissionId by rememberSaveable { mutableStateOf(java.util.UUID.randomUUID().toString()) }
    val api = remember { ApiFactory.create() }
    val clipboard = LocalClipboardManager.current
    val motion = rememberOverlayMotion(onClose = onClose, predictive = OverlayMotion.enabledState, selfDrag = true)
    val version = "${AppBrand.CHINESE_NAME} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE}"
    val body = "## 问题或建议\n${detail.trim()}\n\n## 版本\n$version"
    val ready = title.trim().length >= 2 && detail.trim().length >= 4
    Dialog(onDismissRequest = { motion.requestClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false), color = AppColors.CanvasOpaque, contentColor = AppColors.TextPrimary) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("反馈与建议", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { motion.requestClose() }) { Icon(Icons.Outlined.Close, "关闭反馈") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("哪里不顺手？告诉我们。", style = MaterialTheme.typography.headlineSmall)
                    Text("写下操作步骤、遇到的情况和你期待的结果，也欢迎提出新想法。", color = AppColors.TextSecondary)
                    OutlinedTextField(title, { title = it.take(100); receipt = null; submissionId = java.util.UUID.randomUUID().toString() }, label = { Text("简短标题") }, singleLine = true, enabled = !submitting, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(detail, { detail = it.take(4000); receipt = null; submissionId = java.util.UUID.randomUUID().toString() }, label = { Text("问题或建议") }, minLines = 6, enabled = !submitting, modifier = Modifier.fillMaxWidth())
                    Text(version, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    Text("直接发送给开发者，无需登录应用或 GitHub。反馈不会公开展示。请勿填写密码、密钥、学号等个人信息。", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    Text("只附上以上应用和系统版本；不会自动上传日志、账号或学习数据。", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    notice?.let { Text(it, color = AppColors.TextPrimary) }
                }
                FlowRow(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = ready, onClick = {
                        clipboard.setText(AnnotatedString(title.trim() + "\n\n" + body))
                        notice = "已复制，可粘贴到你选择的反馈渠道。"
                    }) { Text("复制反馈") }
                    Button(enabled = ready && !submitting && receipt == null, onClick = {
                        submitting = true
                        scope.launch {
                            try {
                                val result = api.submitFeedback(FeedbackSubmission(submissionId, title.trim(), detail.trim(), version))
                                require(result.receiptId.isNotBlank()) { "服务器未返回收件编号" }
                                receipt = result.receiptId
                                notice = "反馈已收到，编号：${result.receiptId}"
                            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (cause: Exception) {
                                notice = "提交未确认，请重试。内容已保留，也可以复制反馈。"
                            } finally { submitting = false }
                        }
                    }) { Text(if (submitting) "正在提交…" else if (receipt != null) "已提交" else "提交反馈") }
                }
            }
        }
    }
}
