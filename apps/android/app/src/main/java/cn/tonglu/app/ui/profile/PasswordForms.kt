package cn.tonglu.app.ui.profile

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.tonglu.app.data.CaptchaDto
import cn.tonglu.app.data.ResetCodeResponse
import cn.tonglu.app.data.toFriendlyMessage
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.ContainerOrigin
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotionImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun PasswordResetDialog(
    initialEmail: String,
    origin: ContainerOrigin?,
    onCaptcha: suspend () -> CaptchaDto,
    onSendCode: suspend (String, String, String) -> ResetCodeResponse,
    onReset: suspend (String, String, String) -> Unit,
    onSuccess: (String) -> Unit,
    onClose: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    var sentTo by rememberSaveable { mutableStateOf("") }
    var resetStep by rememberSaveable { mutableStateOf(false) }
    var expiresInMinutes by rememberSaveable { mutableIntStateOf(5) }
    var resendAt by rememberSaveable { mutableLongStateOf(0) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var captcha by remember { mutableStateOf<CaptchaDto?>(null) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var loadingCaptcha by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showFeedback by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val scroll = rememberScrollState()
    val seconds = ((resendAt - now + 999) / 1000).coerceAtLeast(0)
    val currentEmail = email.trim()
    val waiting = seconds > 0 && sentTo.equals(currentEmail, ignoreCase = true)

    suspend fun loadCaptcha() {
        loadingCaptcha = true
        captcha = null
        bitmap = null
        answer = ""
        try {
            val challenge = onCaptcha()
            val png = Base64.decode(challenge.image.substringAfter(','), Base64.DEFAULT)
            val image = BitmapFactory.decodeByteArray(png, 0, png.size)
            checkNotNull(image) { "图形验证码图片无法读取" }
            bitmap = image.asImageBitmap()
            captcha = challenge
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "图形验证码获取失败，请重试"
        } finally {
            loadingCaptcha = false
        }
    }

    LaunchedEffect(resetStep) {
        scroll.scrollTo(0)
        if (!resetStep) loadCaptcha()
    }
    LaunchedEffect(resendAt) {
        now = System.currentTimeMillis()
        while (now < resendAt) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    PasswordFormDialog("找回密码", origin, { !busy }, onClose) { close ->
        Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (resetStep) {
                Text("如果 $sentTo 是已验证邮箱，验证码将发送至该邮箱，有效期 $expiresInMinutes 分钟。请检查收件箱和垃圾邮件。",
                    color = AppColors.TextSecondary)
                PasswordFormInput("邮箱验证码", code, !busy, KeyboardType.Number) {
                    code = it.filter { digit -> digit in '0'..'9' }.take(6)
                    error = null
                }
                PasswordFormInput("新密码（至少 8 个字符）", password, !busy, KeyboardType.Password) {
                    password = it
                    error = null
                }
                TextButton(enabled = !busy, onClick = {
                    focus.clearFocus()
                    code = ""
                    password = ""
                    error = null
                    resetStep = false
                }) { Text("修改邮箱或重新获取验证码") }
            } else {
                PasswordFormInput("已验证的邮箱", email, !busy, KeyboardType.Email) {
                    email = it
                    error = null
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        PasswordFormInput("图形验证码", answer, !busy) {
                            answer = it.take(12)
                            error = null
                        }
                    }
                    TextButton(enabled = !busy && !loadingCaptcha, onClick = {
                        error = null
                        scope.launch { loadCaptcha() }
                    }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            bitmap?.let { Image(it, "图形验证码", Modifier.width(94.dp).height(36.dp)) }
                            Text(if (loadingCaptcha) "加载中…" else if (bitmap == null) "重试" else "换一张")
                        }
                    }
                }
            }
            PasswordFormError(error)
            Text("未绑定已验证邮箱的账号无法自助找回，可通过「反馈与建议」联系我们。",
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            TextButton(enabled = !busy, onClick = { focus.clearFocus(); showFeedback = true }) { Text("反馈与建议") }
        }
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            enabled = !busy && if (resetStep) code.length == 6 && validNewPassword(password)
                else captcha != null && answer.isNotBlank() && '@' in currentEmail && !waiting,
            onClick = {
                if (!busy) {
                    busy = true
                    error = null
                    focus.clearFocus()
                    scope.launch {
                        try {
                            if (resetStep) {
                                onReset(sentTo, code, password)
                                password = ""
                                code = ""
                                onSuccess(sentTo)
                                busy = false
                                close()
                            } else {
                                val result = onSendCode(currentEmail, checkNotNull(captcha).id, answer.trim())
                                sentTo = currentEmail
                                expiresInMinutes = result.expiresInSeconds / 60
                                resendAt = System.currentTimeMillis() + result.cooldownSeconds * 1000L
                                resetStep = true
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.toFriendlyMessage()
                            if (!resetStep) loadCaptcha()
                        } finally {
                            busy = false
                        }
                    }
                }
            },
        ) { Text(when {
            busy -> "正在处理…"
            resetStep -> "重置密码"
            waiting -> "${seconds}s 后重发"
            else -> "获取邮箱验证码"
        }) }
    }
    if (showFeedback) FeedbackDialog(onClose = { OverlayDismissGuard.arm(); showFeedback = false })
}

@Composable
internal fun ChangePasswordDialog(
    origin: ContainerOrigin?,
    onChangePassword: suspend (String, String) -> Unit,
    onSuccess: () -> Unit,
    onClose: () -> Unit,
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current

    PasswordFormDialog("修改密码", origin, { !busy }, onClose) { close ->
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("修改后，其他设备需要重新登录。", color = AppColors.TextSecondary)
            PasswordFormInput("旧密码", oldPassword, !busy, KeyboardType.Password) {
                oldPassword = it
                error = null
            }
            PasswordFormInput("新密码（至少 8 个字符）", newPassword, !busy, KeyboardType.Password) {
                newPassword = it
                error = null
            }
            PasswordFormError(error)
        }
        Button(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            enabled = !busy && oldPassword.isNotEmpty() && validNewPassword(newPassword),
            onClick = {
                if (!busy) {
                    busy = true
                    error = null
                    focus.clearFocus()
                    scope.launch {
                        try {
                            onChangePassword(oldPassword, newPassword)
                            oldPassword = ""
                            newPassword = ""
                            onSuccess()
                            busy = false
                            close()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = e.toFriendlyMessage()
                        } finally {
                            busy = false
                        }
                    }
                }
            },
        ) { Text(if (busy) "保存中…" else "保存新密码") }
    }
}

@Composable
private fun PasswordFormDialog(
    title: String,
    origin: ContainerOrigin?,
    canClose: () -> Boolean,
    onClose: () -> Unit,
    content: @Composable ColumnScope.(() -> Unit) -> Unit,
) {
    val motion = rememberOverlayMotionImpl(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState, selfDrag = true, origin = origin,
        takePending = false, beforeClose = canClose,
    )
    Dialog(onDismissRequest = { motion.requestClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false, containerColor = AppColors.CanvasOpaque),
            color = AppColors.CanvasOpaque, contentColor = AppColors.TextPrimary) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 440.dp).fillMaxWidth().fillMaxHeight().padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { motion.requestClose() }, enabled = canClose()) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                        }
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                    }
                    content { motion.requestClose() }
                }
            }
        }
    }
}

@Composable
private fun PasswordFormInput(
    label: String,
    value: String,
    enabled: Boolean,
    type: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        TextField(value, onChange, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = AppColors.SurfaceOpaque,
                unfocusedContainerColor = AppColors.SurfaceOpaque,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = type, autoCorrectEnabled = false),
            visualTransformation = if (type == KeyboardType.Password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
    }
}

@Composable
private fun PasswordFormError(error: String?) {
    if (error != null) Text(error, color = AppColors.Danger, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
}

private val passwordPresentationSequence = Regex("[^\uFE0F\uFE0E][\uFE0F\uFE0E]")

private fun validNewPassword(value: String): Boolean {
    // 与注册 DTO 的 MinLength 一致，变体选择符算在前一个字符内。
    val password = value.replace(passwordPresentationSequence) { it.value.dropLast(1) }
    return Character.codePointCount(password, 0, password.length) >= 8
}
