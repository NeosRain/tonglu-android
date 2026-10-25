package cn.tonglu.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.AppUiState
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.AvatarCropDialog
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.UserAvatar
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

private val Accent get() = AppColors.Accent
private val Muted get() = AppColors.TextMuted

/**
 * 账号设置页：点头像/「账号设置」进来。
 *
 * 用户要求（2026-10-13）：点头像不该直接弹换头像，而是进一个**账号设置页** ——
 * 除了换头像，还能改昵称；等级改不了但要**看得见**，校园身份也一样。
 *
 * 内容（自上而下）：头像（点换）→ 昵称（可改，2~24 字）→ 用户名（只读，
 * 有自己的改名引导流程）→ 等级（只读，入口跳等级页）→ 校园身份（只读，入口跳认证）。
 *
 * 昵称走 `PATCH auth/display-name`（后端新增），成功后本地会话同步新名字，
 * 我的页头部/帖子作者名立即生效。
 */
@Composable
internal fun AccountSettingsScreen(
    state: AppUiState,
    onAvatar: (android.net.Uri) -> Unit,
    onSetDisplayName: (String) -> Unit,
    onOpenMyLevel: () -> Unit,
    onCampusVerification: () -> Unit,
    onLogin: () -> Unit,
    onClose: () -> Unit,
) {
    val session = state.session
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // 换头像：与我的页同一条路（选图 → 裁剪 → onAvatar 上传）。
    var avatarCropUri by remember { mutableStateOf<android.net.Uri?>(null) }
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
    var name by rememberSaveable(session?.displayName) { mutableStateOf(session?.displayName ?: "") }
    var localNotice by remember { mutableStateOf<String?>(null) }

    // 与后端 SetDisplayNameDto 同一口径（2~24 字）：客户端先拦一道，
    // 真正的校验在后端（改一次接口都会过一遍），两边不各写一套文案。
    val nameValid = name.trim().length in 2..24 && name.trim() != (session?.displayName ?: "")
    val busy = state.busy

    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState,
        selfDrag = true,
    )
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { motion.requestClose() },
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        androidx.compose.material3.Surface(
            Modifier.fillMaxSize().overlayMotion(motion, fade = false),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { motion.requestClose() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                    Text("个人资料", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (session == null) {
                        // 理论上到不了（入口都要求登录），但别让空会话画出一片空白。
                        Column(
                            Modifier.fillMaxWidth().padding(top = 60.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("还没有登录应用账号")
                            Button(onClick = onLogin) { Text("注册或登录") }
                        }
                        return@Column
                    }

                    // ---- 头像 ----
                    MiuixCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            UserAvatar(
                                session.displayName, session.avatarUrl,
                                Modifier.size(64.dp).clickable(enabled = !busy) {
                                    avatarPicker.launch("image/*")
                                },
                            )
                            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                                Text("头像", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (busy) "处理中…" else "点头像更换，支持裁剪",
                                    color = Muted, style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }

                    // ---- 昵称 ----
                    MiuixCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Text("昵称", fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            name, { name = it },
                            singleLine = true,
                            label = { Text("2~24 个字符") },
                            isError = name.trim().isNotEmpty() && name.trim().length !in 2..24,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            if (localNotice != null) {
                                Text(
                                    localNotice!!, color = Muted,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
                                )
                            }
                            Button(
                                enabled = nameValid && !busy,
                                onClick = {
                                    onSetDisplayName(name)
                                    // 真正的成功/失败由上层 notice（state.notice）反馈；
                                    // 这里把「点了但没反应」的疑虑就地消掉。
                                    scope.launch { localNotice = "已提交，保存后自动生效" }
                                },
                            ) { Text(if (busy) "保存中…" else "保存") }
                        }
                    }

                    // ---- 用户名（只读）：改名有独立引导流程，这里不放编辑 ----
                    MiuixCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Text("用户名", fontWeight = FontWeight.SemiBold)
                        Text(
                            "@${session.username}",
                            color = Muted, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    // ---- 等级（只读）：改不了，但看得见 ----
                    if (state.myLevel != null) {
                        MiuixCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth().clickable(onClick = onOpenMyLevel),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("我的等级", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        state.myLevel?.let {
                                            "Lv${it.level} ${it.levelName} · 学习 ${it.expLearn} · 社区 ${it.expSocial}"
                                        } ?: "",
                                        color = Muted, style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                    Text(
                                        "等级由经验自动成长，不能手动改",
                                        color = Muted, style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                                Text("查看", color = Accent, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    // ---- 校园身份（只读入口）----
                    MiuixCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = onCampusVerification),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("校园身份", fontWeight = FontWeight.SemiBold)
                                Text(
                                    state.campusIdentity?.summary() ?: "校园身份待查询",
                                    color = Muted, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            Text("查看", color = Accent, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
