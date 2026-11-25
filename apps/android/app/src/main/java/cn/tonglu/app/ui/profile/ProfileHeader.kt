package cn.tonglu.app.ui.profile

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.AppUiState
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.UserAvatar
import cn.tonglu.app.ui.launchClickable
import cn.tonglu.app.ui.launchOrigin
import cn.tonglu.app.ui.rememberLaunchSource

@Composable
internal fun ProfileHeader(state: AppUiState, onLogin: () -> Unit, onEdit: () -> Unit) {
    val session = state.session
    val openProfile = LocalOpenPublicProfile.current
    ProfileGroup {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ProfileAvatar(session?.displayName, session?.avatarUrl, 72.dp,
                    Modifier.semantics {
                        contentDescription = if (session == null) "注册或登录" else "查看个人主页"
                    }.launchClickable(session?.userId, 36.dp, enabled = !state.busy,
                        onClick = if (session == null) onLogin else {
                            { if (openProfile != null) openProfile(session.userId, false) else onEdit() }
                        }))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(session?.displayName ?: "你好，同学", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (session == null) "本机模式" else "@${session.username}",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (session == null) {
                    Button(onClick = onLogin, enabled = !state.busy,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppColors.Accent, contentColor = AppColors.OnAccent,
                        )) { Text("注册或登录") }
                } else {
                    val origin = rememberLaunchSource("edit-profile", 20.dp)
                    FilledTonalButton(onClick = { origin.launch {
                        if (openProfile != null) openProfile(session.userId, true) else onEdit()
                    } }, enabled = !state.busy,
                        modifier = Modifier.launchOrigin(origin),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = AppColors.CanvasOpaque, contentColor = AppColors.TextPrimary,
                        )) { Text("编辑资料") }
                }
            }
            if (session == null) {
                Text("登录后可使用校园认证、成长等级和云同步。", color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall)
            } else {
                HorizontalDivider(color = AppColors.Divider)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    ProfileStatus(state.campusIdentity?.summary()?.substringAfter(" · ") ?: "校园身份待查询")
                    // me/level 的信任字段仅在自己的「我的」页使用。
                    state.myLevel?.trustName?.takeIf { it.isNotBlank() }?.let { ProfileStatus("信任 · $it") }
                    state.myLevel?.let { ProfileStatus("Lv${it.level}") }
                }
            }
        }
    }
}

@Composable
private fun ProfileStatus(label: String) {
    Surface(shape = CircleShape, color = AppColors.ChipIdle, contentColor = AppColors.TextPrimary) {
        Text(label, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
internal fun ProfileAvatar(name: String?, url: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(Modifier.size(size).clip(CircleShape).then(modifier)
        .border(1.dp, AppColors.Accent.copy(alpha = .45f), CircleShape).padding(4.dp),
        contentAlignment = Alignment.Center) {
        if (name == null) {
            Surface(Modifier.fillMaxSize(), shape = CircleShape, color = AppColors.ChipIdle) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PersonOutline, null, Modifier.size(30.dp), tint = AppColors.Accent)
                }
            }
        } else {
            ProvideTextStyle(MaterialTheme.typography.headlineSmall) {
                UserAvatar(name, url, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
internal fun ProfileGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        color = AppColors.SurfaceOpaque, contentColor = AppColors.TextPrimary) {
        Column(content = content)
    }
}
