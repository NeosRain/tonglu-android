package cn.tonglu.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.AppUiState
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.AvatarCropDialog
import cn.tonglu.app.ui.ContainerOrigin
import cn.tonglu.app.ui.launchOrigin
import cn.tonglu.app.ui.rememberLaunchSource
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.glass.DarkTopBarBackground
import cn.tonglu.app.ui.glass.GlassTokens
import cn.tonglu.app.ui.glass.LocalGlassNavigationEnabled
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import cn.tonglu.app.ui.launchClickable
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

@Composable
internal fun AccountSettingsScreen(
    state: AppUiState,
    onAvatar: (android.net.Uri) -> Unit,
    onSetDisplayName: (String) -> Unit,
    onOpenMyLevel: () -> Unit,
    onCampusVerification: () -> Unit,
    onOpenCampusEmail: () -> Unit,
    onChangePassword: suspend (String, String, String) -> Unit,
    onLogin: () -> Unit,
    onClose: () -> Unit,
) {
    val session = state.session

    // 换头像：与我的页同一条路（选图 → 裁剪 → onAvatar 上传）。
    var avatarCropUri by remember(session?.sessionId) { mutableStateOf<android.net.Uri?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> avatarCropUri = uri }
    avatarCropUri?.let { uri ->
        AvatarCropDialog(uri, onClose = { OverlayDismissGuard.arm(); avatarCropUri = null },
            onConfirm = { cropped -> OverlayDismissGuard.arm(); avatarCropUri = null; onAvatar(cropped) })
    }

    // 昵称草稿：进页面时以当前会话名为初值；保存成功后**以回调更新的会话为准**
    //（AppViewModel.setDisplayName 成功会同步本地会话），这里不再自己改草稿，
    // 否则「保存成功但输入框还是旧名」或反过来都会出现。
    var name by rememberSaveable(session?.sessionId, session?.displayName) { mutableStateOf(session?.displayName ?: "") }
    var submittedName by remember(session?.sessionId) { mutableStateOf<String?>(null) }
    var nameFeedback by remember(session?.sessionId) { mutableStateOf<String?>(null) }
    var showPassword by remember(session?.sessionId) { mutableStateOf(false) }
    var passwordOrigin by remember(session?.sessionId) { mutableStateOf<ContainerOrigin?>(null) }
    var passwordChanged by remember(session?.sessionId) { mutableStateOf(false) }

    // 与后端 SetDisplayNameDto 同一口径（2~24 字）：客户端先拦一道，
    // 真正的校验在后端（改一次接口都会过一遍），两边不各写一套文案。
    val nameValid = name.trim().length in 2..24 && name.trim() != (session?.displayName ?: "")
    val busy = state.busy
    LaunchedEffect(session?.displayName, state.error) {
        val submitted = submittedName ?: return@LaunchedEffect
        nameFeedback = if (session?.displayName == submitted) "昵称已更新" else state.error
        if (nameFeedback != null) submittedName = null
    }

    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
        container = true,
    )
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { motion.requestClose() },
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val background = AppColors.CanvasOpaque
        androidx.compose.material3.Surface(
            Modifier.fillMaxSize().overlayMotion(motion, fade = false),
            color = background,
            contentColor = AppColors.TextPrimary,
        ) {
            val dark = GlassTokens.DarkGlass
            val scrollState = rememberScrollState()
            val title: @Composable () -> Unit = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { motion.requestClose() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                    Text("个人资料", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
            }

            val safeTop = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
            val insets = if (dark) WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                else WindowInsets.safeDrawing
            val topInset = if (dark) safeTop.asPaddingValues().calculateTopPadding() + 60.dp else 0.dp
            val backdrop = if (dark && LocalGlassNavigationEnabled.current) rememberLayerBackdrop() else null
            Box(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
                Column(Modifier.fillMaxSize()
                    .then(if (backdrop != null) Modifier.layerBackdrop(backdrop).background(background) else Modifier)) {
                    if (!dark) title()
                    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
                        Column(
                            Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(scrollState)
                                .padding(start = 16.dp, end = 16.dp, top = topInset + 8.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            if (session == null) {
                                ProfileHeader(state, onLogin, onClose)
                            } else {
                                ProfileGroup {
                                    Column(
                                        Modifier.fillMaxWidth().clickable(enabled = !busy, role = Role.Button) {
                                            avatarPicker.launch("image/*")
                                        }.padding(vertical = 20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        ProfileAvatar(session.displayName, session.avatarUrl, 96.dp)
                                        Text(if (busy) "处理中…" else "更换头像", color = AppColors.TextPrimary,
                                            style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                                ProfileGroup {
                                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("昵称", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                        val invalidName = name.isNotBlank() && name.trim().length !in 2..24
                                        TextField(
                                            value = name, onValueChange = { name = it; nameFeedback = null },
                                            singleLine = true, enabled = !busy, isError = invalidName,
                                            shape = RoundedCornerShape(12.dp),
                                            colors = TextFieldDefaults.colors(
                                                focusedContainerColor = AppColors.ChipIdle,
                                                unfocusedContainerColor = AppColors.ChipIdle,
                                                errorContainerColor = AppColors.ChipIdle,
                                                disabledContainerColor = AppColors.ChipIdle,
                                                focusedIndicatorColor = Color.Transparent,
                                                unfocusedIndicatorColor = Color.Transparent,
                                                errorIndicatorColor = Color.Transparent,
                                                disabledIndicatorColor = Color.Transparent,
                                            ),
                                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "昵称" },
                                        )
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically) {
                                            Text("2–24 个字符", color = if (invalidName) AppColors.Danger else AppColors.TextSecondary,
                                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                            Button(enabled = nameValid && !busy, onClick = {
                                                nameFeedback = null
                                                submittedName = name.trim()
                                                onSetDisplayName(name)
                                            }) {
                                                Text(if (busy && submittedName != null) "保存中…" else "保存")
                                            }
                                        }
                                        nameFeedback?.let {
                                            Text(it, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    ProfileFieldDivider()
                                    ProfileFieldRow("用户名", "@${session.username}")
                                }
                                ProfileGroup {
                                    ProfileFieldRow("校园认证", state.campusIdentity?.summary() ?: "校园身份待查询",
                                        Modifier.launchClickable("verification", onClick = onCampusVerification), actionable = true)
                                    ProfileFieldDivider()
                                    val identity = state.campusIdentity
                                    val email = identity?.email ?: if (identity == null) "邮箱绑定状态待查询" else "未绑定邮箱"
                                    ProfileFieldRow("邮箱绑定", email,
                                        Modifier.launchClickable("campus-email", onClick = onOpenCampusEmail), actionable = true)
                                    ProfileFieldDivider()
                                    val passwordSource = rememberLaunchSource("profile-password", 16.dp, AppColors.SurfaceOpaque)
                                    ProfileFieldRow("修改密码",
                                        if (passwordChanged) "密码已修改，其他设备已退出登录" else null,
                                        Modifier.launchOrigin(passwordSource).clickable {
                                            passwordOrigin = passwordSource.capture()
                                            passwordChanged = false
                                            showPassword = true
                                        },
                                        actionable = true,
                                    )
                                }
                                state.myLevel?.let {
                                    ProfileGroup {
                                        ProfileFieldRow("我的等级", "Lv${it.level} ${it.levelName} · 学习 ${it.expLearn} · 社区 ${it.expSocial}",
                                            Modifier.launchClickable("level", onClick = onOpenMyLevel), actionable = true)
                                    }
                                }
                            }
                        }
                    }
                }
                if (dark) Box(Modifier.fillMaxWidth()) {
                    DarkTopBarBackground(backdrop, { scrollState.value.toFloat() }, Modifier.matchParentSize())
                    Box(Modifier.fillMaxWidth().windowInsetsPadding(safeTop)) { title() }
                }
            }
            if (session != null && showPassword) ChangePasswordDialog(
                origin = passwordOrigin,
                onChangePassword = { old, new -> onChangePassword(session.sessionId, old, new) },
                onSuccess = { passwordChanged = true },
                onClose = { showPassword = false; passwordOrigin = null },
            )
        }
    }
}

@Composable
private fun ProfileFieldDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = AppColors.Divider)
}

@Composable
private fun ProfileFieldRow(title: String, value: String?, modifier: Modifier = Modifier, actionable: Boolean = false) {
    Row(Modifier.fillMaxWidth().then(modifier).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            value?.let { Text(it, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
        }
        if (actionable) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(20.dp), tint = AppColors.TextSecondary)
        }
    }
}
