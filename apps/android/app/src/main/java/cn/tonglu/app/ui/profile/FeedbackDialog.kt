package cn.tonglu.app.ui.profile

import kotlinx.coroutines.launch
import cn.tonglu.app.data.ApiFactory
import cn.tonglu.app.data.FeedbackSubmission
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
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
                    FeedbackInput("简短标题", title, 100, true, !submitting) {
                        title = it; receipt = null; submissionId = java.util.UUID.randomUUID().toString()
                    }
                    FeedbackInput("问题或建议", detail, 4000, false, !submitting) {
                        detail = it; receipt = null; submissionId = java.util.UUID.randomUUID().toString()
                    }
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

@Composable
private fun FeedbackInput(
    label: String,
    value: String,
    limit: Int,
    singleLine: Boolean,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = AppColors.SurfaceOpaque,
        border = BorderStroke(1.dp, if (focused) AppColors.Accent else AppColors.Divider),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = AppColors.TextSecondary)
                Text("${value.length}/$limit", style = MaterialTheme.typography.labelSmall, color = AppColors.TextMuted)
            }
            BasicTextField(
                value = value,
                onValueChange = { onChange(it.take(limit)) },
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
                    .then(if (singleLine) Modifier else Modifier.heightIn(min = 152.dp)),
                enabled = enabled,
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = AppColors.TextPrimary),
                cursorBrush = SolidColor(AppColors.Accent),
                keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
                decorationBox = { inner ->
                    Box {
                        if (value.isEmpty()) Text(
                            if (singleLine) "用一句话说明问题" else "例如：从课表点开课程，笔记在另一个时间格看不到…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = AppColors.TextMuted,
                        )
                        inner()
                    }
                },
            )
        }
    }
}
