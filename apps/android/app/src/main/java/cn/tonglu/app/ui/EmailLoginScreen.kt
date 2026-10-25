package cn.tonglu.app.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cn.tonglu.app.R
import cn.tonglu.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 提交给 ViewModel 的表单快照。
 *
 * 为什么用 data class 而不是继续堆位置参数：原来 `onSubmit` 已经有 6 个 `String`/`Boolean`，
 * 再加一个用户名就是 7 个 —— 位置传参一旦错位（把昵称传进用户名的位置）**编译器不会报错**，
 * 只会静默地把数据写错地方。这项目已经因为「位置解构」翻过车，这里直接消灭它。
 */
internal data class EmailAuthInput(
    val identifier: String,
    val password: String,
    val displayName: String,
    val register: Boolean,
    val acceptedTerms: Boolean,
    val emailCode: String,
    val newUsername: String,
)

@Composable
internal fun EmailLoginScreen(
    state: AppUiState,
    onSubmit: (EmailAuthInput) -> Unit,
    onCheckUsername: suspend (String) -> UsernameAvailableDto,
    onPreview: () -> Unit,
    onCaptcha: suspend () -> CaptchaDto,
    onSendCode: suspend (String, String, String) -> EmailCodeResponse,
    /**
     * 按返回键：退回主界面。**刻意没有默认值** ——
     * 注册了 `BackHandler` 却回调成空实现会把返回键变成「点了没反应」，比退桌面更糟（人会被困住）。
     * 每个调用方都必须明确说清楚返回去哪里。
     */
    onBack: () -> Unit,
) {
    var register by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var newUsername by rememberSaveable { mutableStateOf("") }
    var agreed by rememberSaveable { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    var captcha by remember { mutableStateOf<CaptchaDto?>(null) }
    var loadingCaptcha by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var waitUntil by rememberSaveable { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var message by remember { mutableStateOf<String?>(null) }
    var passwordVisible by remember { mutableStateOf(false) }
    var showNickname by rememberSaveable { mutableStateOf(false) }
    var errorDismissed by remember(state.error) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberScrollState()
    fun edited() { message = null; errorDismissed = true }
    fun back() { focus.clearFocus(); if (register) register = false else onBack() }
    fun switchMode() {
        focus.clearFocus()
        register = !register
        edited()
        password = ""
        passwordVisible = false
        code = ""
    }
    suspend fun reloadCaptcha() {
        answer = ""
        captcha = null
        loadingCaptcha = true
        try { captcha = onCaptcha() } catch (e: Exception) {
            if (e is CancellationException) throw e
            message = "图形验证码获取失败，请检查网络并重试"
        } finally { loadingCaptcha = false }
    }
    LaunchedEffect(register) {
        scroll.scrollTo(0)
        if (register) reloadCaptcha()
    }
    LaunchedEffect(waitUntil) { while (System.currentTimeMillis() < waitUntil) { now = System.currentTimeMillis(); delay(1000) }; now = System.currentTimeMillis() }
    val seconds = ((waitUntil - now + 999) / 1000).coerceAtLeast(0)

    // 🔴 返回键必须「退回上一屏」，不能让系统把整个 App 退到桌面（BUGS S57）。
    //
    // 为什么默认行为是错的：这一屏不是 Navigation 的目的地，而是 `TongluApp` 在
    // `session == null && !previewMode` 时 early return 出来的**整棵树** ——
    // 主界面（底栏 / 我的页）那时一个 composable 都没组合，系统返回栈里没有东西可弹，
    // BACK 于是直接 finish 掉 MainActivity。而到这屏只有一条路（主界面 →「注册或登录」），
    // 所以 BACK 的语义就是「回到主界面」，由 `onBack` 补齐。
    //
    // 分两级：表单停在「注册」态时先退回「登录」态，再按一次才回主界面 ——
    // 注册表单只是同一屏里的一个开关、不是独立页面，一次返回全走掉会让人以为填的东西丢了。
    //
    // ⚠️ 放在状态声明**之后**：Kotlin 里 `@Composable fun` 的函数体是按书写顺序解析的，
    // 写在 `var register` 前面会直接 Unresolved reference（本次构建就撞到了）。
    BackHandler { back() }

    // —— 用户名的本地即时校验 + 防抖联网查重 ——
    //
    // 分两段的意义：
    //  ① 本地纯规则（长度/字符集/保留字）**立刻**出结论，不发请求 —— 用户敲错字马上能看到；
    //  ② 只有格式合法时才去查「是否被占用」，而且要等用户停手 450ms。
    //     这个口后端额度是 20 次/分，每字符一次会瞬间打爆，用户看到的会是
    //     「服务暂时不可用」——而他只是在取名字。
    val localCode = remember(newUsername) { UsernamePolicy.checkFormat(newUsername) }
    var usernameTip by remember { mutableStateOf<String?>(null) }
    var usernameAvailable by remember { mutableStateOf<Boolean?>(null) }
    var checkingUsername by remember { mutableStateOf(false) }
    LaunchedEffect(register, newUsername) {
        usernameAvailable = null
        checkingUsername = false
        if (!register || newUsername.isBlank()) { usernameTip = null; return@LaunchedEffect }
        if (localCode != UsernamePolicy.CODE_OK) {
            usernameTip = UsernamePolicy.messageFor(localCode)
            return@LaunchedEffect
        }
        usernameTip = null
        checkingUsername = true
        try {
            delay(450) // 等待也算检查中，避免提交上一输入的查重结论。
            val r = onCheckUsername(newUsername)
            usernameAvailable = r.available
            usernameTip = r.message
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 网络未知与已占用分开；最终由注册接口校验，不让离线查重卡死表单。
            usernameTip = "暂时无法检查用户名，提交时会再次验证"
        } finally { checkingUsername = false }
    }

    val canSubmit = !state.busy && !sending && email.isNotBlank() && password.length >= 8 &&
        (!register || (agreed && code.length == 6 && (name.isBlank() || name.trim().length >= 2) &&
            localCode == UsernamePolicy.CODE_OK && !checkingUsername && usernameAvailable != false))
    fun submit() {
        if (!canSubmit) return
        message = null
        errorDismissed = false
        focus.clearFocus()
        keyboard?.hide()
        onSubmit(EmailAuthInput(
            identifier = email, password = password, displayName = name.trim(),
            register = register, acceptedTerms = agreed, emailCode = code,
            newUsername = newUsername.trim(),
        ))
    }

    val bitmap = remember(captcha?.image) { runCatching {
        val bytes = Base64.decode(captcha?.image?.substringAfter(',') ?: "", Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull() }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(AppColors.CanvasOpaque).safeDrawingPadding().imePadding(),
    ) {
        val viewportHeight = maxHeight
        Column(
            Modifier.align(Alignment.TopCenter).widthIn(max = 440.dp).fillMaxWidth()
                .verticalScroll(scroll).heightIn(min = viewportHeight).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { back() }, enabled = !state.busy && !sending) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, if (register) "返回登录" else "返回")
                }
                TextButton(onClick = onPreview, enabled = !state.busy && !sending) { Text("暂不登录") }
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                Box(
                    Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(AppColors.SurfaceOpaque),
                    contentAlignment = Alignment.Center,
                ) {
                    // 启动器前景自带安全区；放大已有素材，避免在页面里显示成很小的头像。
                    Image(painterResource(R.drawable.ic_launcher_portrait), contentDescription = null,
                        modifier = Modifier.requiredSize(112.dp))
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    if (register) "创建账号" else "欢迎回来",
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (register) "普通邮箱即可，校园认证可稍后完成" else "让学习和生活，轻松一点",
                    style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary,
                )
                Spacer(Modifier.height(28.dp))
                Surface(shape = RoundedCornerShape(28.dp), color = AppColors.SurfaceOpaque) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val fieldShape = RoundedCornerShape(16.dp)
                        OutlinedTextField(
                            email, { email = it.trim(); edited() },
                            label = { Text(if (register) "邮箱" else "账号") },
                            placeholder = { Text(if (register) "输入常用邮箱" else "用户名 / 邮箱 / ID") },
                            singleLine = true, enabled = !state.busy && !sending,
                            shape = fieldShape, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                                keyboardType = if (register) KeyboardType.Email else KeyboardType.Text,
                                imeAction = ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) }),
                        )
                        if (register) {
                            OutlinedTextField(
                                newUsername, { newUsername = it.take(UsernamePolicy.MAX); edited() },
                                label = { Text("用户名") },
                                placeholder = { Text("${UsernamePolicy.MIN}–${UsernamePolicy.MAX} 位字母、数字或下划线") },
                                singleLine = true, enabled = !state.busy && !sending, shape = fieldShape,
                                isError = localCode != UsernamePolicy.CODE_OK && newUsername.isNotBlank() || usernameAvailable == false,
                                supportingText = {
                                    Text(if (checkingUsername) "正在检查用户名…" else usernameTip ?: "以后可用它登录")
                                },
                                trailingIcon = if (usernameAvailable == true) {
                                    { Icon(Icons.Outlined.Check, "用户名可用", tint = AppColors.TextSecondary) }
                                } else null,
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        OutlinedTextField(
                            password, { password = it; edited() },
                            label = { Text(if (register) "密码（至少 8 位）" else "密码") },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                        if (passwordVisible) "隐藏密码" else "显示密码")
                                }
                            },
                            singleLine = true, enabled = !state.busy && !sending, shape = fieldShape,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = if (register) ImeAction.Next else ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) },
                                onDone = { submit() },
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (register) {
                            TextButton(onClick = {
                                if (showNickname) name = ""
                                showNickname = !showNickname
                                edited()
                            }, enabled = !state.busy && !sending) {
                                Text(if (showNickname) "不设置昵称" else "设置昵称（选填）")
                            }
                            if (showNickname) OutlinedTextField(
                                name, { name = it.take(24); edited() }, label = { Text("昵称（2–24 字）") },
                                singleLine = true, enabled = !state.busy && !sending, shape = fieldShape,
                                isError = name.isNotBlank() && name.trim().length < 2,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            Text("验证邮箱", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    answer, { answer = it.take(4); edited() }, label = { Text("图形验证码") },
                                    singleLine = true, enabled = !state.busy && !sending, shape = fieldShape,
                                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(androidx.compose.ui.focus.FocusDirection.Next) }),
                                    modifier = Modifier.weight(1f),
                                )
                                Column(
                                    Modifier.width(100.dp).heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
                                        .clickable(enabled = !state.busy && !sending && !loadingCaptcha, role = Role.Button,
                                            onClickLabel = "换一张图形验证码") { scope.launch { reloadCaptcha() } }
                                        .padding(4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (bitmap != null) {
                                        Image(bitmap, "图形验证码", Modifier.fillMaxWidth().height(36.dp))
                                        Text("换一张", style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                                    } else if (loadingCaptcha) {
                                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                    } else Text("点击重试", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    code, { code = it.filter(Char::isDigit).take(6); edited() }, label = { Text("邮箱验证码") },
                                    singleLine = true, enabled = !state.busy && !sending, shape = fieldShape,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = { submit() }),
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    enabled = !state.busy && !sending && seconds == 0L && email.contains('@') && answer.length == 4 && captcha != null,
                                    onClick = {
                                        val challenge = captcha ?: return@TextButton
                                        sending = true
                                        focus.clearFocus()
                                        scope.launch {
                                            try {
                                                onSendCode(email, challenge.id, answer)
                                                waitUntil = System.currentTimeMillis() + 60_000
                                                message = "验证码已发送，请检查收件箱和垃圾邮件"
                                            } catch (e: Exception) {
                                                if (e is CancellationException) throw e
                                                message = if (e is retrofit2.HttpException) {
                                                    runCatching { org.json.JSONObject(e.response()?.errorBody()?.string().orEmpty()).optString("message") }
                                                        .getOrNull()?.takeIf { it.isNotBlank() } ?: "发送失败，请稍后重试"
                                                } else "无法连接服务器，请检查网络"
                                            } finally { sending = false; reloadCaptcha() }
                                        }
                                    },
                                ) { Text(if (sending) "发送中…" else if (seconds > 0) "${seconds}s 后重发" else "获取验证码") }
                            }
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .toggleable(agreed, enabled = !state.busy && !sending, role = Role.Checkbox) { agreed = it; edited() }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(agreed, onCheckedChange = null)
                                Text("我已阅读并同意用户协议与隐私政策", style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        val serverError = state.error?.takeUnless { errorDismissed }
                        (serverError ?: message)?.let {
                            Text(it, color = if (serverError != null) MaterialTheme.colorScheme.error else AppColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        Button(
                            onClick = { submit() }, enabled = canSubmit,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        ) {
                            if (state.busy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(if (state.busy) "正在处理…" else if (register) "注册并登录" else "登录",
                                style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                TextButton(
                    onClick = { switchMode() }, enabled = !state.busy && !sending,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                ) { Text(if (register) "已有账号？登录" else "没有账号？注册") }
            }
            Text(
                "非官方校园应用",
                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp),
            )
        }
    }
}

/**
 * 老用户登录后的**改名引导**（用户名还是自动派生时出现）。
 *
 * 三条刻意的设计：
 * - **不阻塞**：弹窗出现时人已经进主界面了，关掉它照样能用；
 * - 可以「保留当前用户名」：明确放弃后服务端记一笔，之后不再打扰 ——
 *   否则每次登录都弹，而用户可能真的不想改名；
 * - 原 ID 仍然能登录，所以文案里要说清楚，别让用户以为改了就登不回来了。
 */
@Composable
internal fun UsernamePromptDialog(
    state: UsernamePromptState,
    onCheck: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onKeep: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(state.suggested) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给自己起个用户名") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "你现在用的 @${state.legacy ?: "?"} 是注册时自动生成的，不太好记。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    input,
                    { input = it.take(UsernamePolicy.MAX); onCheck(it) },
                    label = { Text("用户名") },
                    singleLine = true,
                    isError = state.error != null,
                    supportingText = {
                        Text(
                            state.error ?: if (state.checking) "检查中…"
                            else if (state.available == true) UsernamePolicy.MSG_OK else "只能用字母、数字和下划线",
                            color = when {
                                state.error != null -> MaterialTheme.colorScheme.error
                                state.available == true -> AppColors.Accent
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "改完之后原来的 @${state.legacy ?: "?"} 仍然可以登录，不会失效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.busy && state.available == true,
                onClick = { onSubmit(input) },
            ) { Text(if (state.busy) "保存中…" else "就用这个") }
        },
        dismissButton = {
            TextButton(onClick = onKeep) { Text("保留当前用户名") }
        },
    )
}
