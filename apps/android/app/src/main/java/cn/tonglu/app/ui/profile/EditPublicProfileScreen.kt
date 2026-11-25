package cn.tonglu.app.ui.profile

import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.AppViewModel
import cn.tonglu.app.data.PublicProfileDto
import cn.tonglu.app.data.UpdatePublicProfileRequest
import cn.tonglu.app.data.community.communityErrorMessage
import cn.tonglu.app.data.community.communityResult
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.AvatarCropDialog
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.community.CommunityField
import cn.tonglu.app.ui.community.CommunityPageHeader
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

@Composable
internal fun EditPublicProfileScreen(
    profile: PublicProfileDto, cover: ImageBitmap?, sessionId: String, viewModel: AppViewModel,
    back: () -> Unit, onSaved: (PublicProfileDto) -> Unit,
) {
    val privacy = requireNotNull(profile.privacy) { "编辑资料必须由本人打开" }
    var displayName by remember { mutableStateOf(profile.displayName) }
    var bio by remember { mutableStateOf(profile.bio.orEmpty()) }
    val tags = remember { mutableStateListOf(*List(3) { profile.profileTags.getOrNull(it).orEmpty() }.toTypedArray()) }
    val filledTags = tags.map { it.trim() }.filter { it.isNotEmpty() }
    var settings by remember { mutableStateOf(privacy) }
    var current by remember { mutableStateOf(profile) }
    var avatarDraft by remember { mutableStateOf<Uri?>(null) }
    var coverDraft by remember { mutableStateOf<Uri?>(null) }
    var removeCover by remember { mutableStateOf(false) }
    var crop by remember { mutableStateOf<Uri?>(null) }
    var pickingCover by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> crop = uri }
    BackHandler(enabled = busy) { /* 等待已发出的保存请求回执。 */ }
    DeleteCropOnDispose(avatarDraft)
    DeleteCropOnDispose(coverDraft)
    val preview = current.copy(displayName = displayName.trim(), bio = bio.trim(), profileTags = filledTags.distinct(),
        level = current.ownGrowthLevel.takeIf { settings.showLevel }, followedBoardsVisible = settings.showBoards,
        stats = current.stats.copy(likes = current.ownStats?.likes.takeIf { settings.showLikes },
            following = current.ownStats?.following.takeIf { settings.showFollows },
            followers = current.ownStats?.followers.takeIf { settings.showFollows }))

    Column(Modifier.fillMaxSize().background(AppColors.CanvasOpaque).safeDrawingPadding().imePadding()) {
        CommunityPageHeader("编辑资料", onBack = { if (!busy) back() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ProfileCover(preview, cover.takeIf { !removeCover && current.coverUrl == profile.coverUrl }, coverDraft)
            ProfileIdentity(preview, avatarDraft)
            ProfileStatistics(preview.stats)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("帖子", color = AppColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
                if (preview.followedBoardsVisible) Text("关注的圈子", color = AppColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !busy, onClick = { pickingCover = false; pick.launch("image/*") }) { Text("换头像") }
                    if (profile.canSetCover == true) {
                        TextButton(enabled = !busy, onClick = { pickingCover = true; pick.launch("image/*") }) { Text("换背景") }
                    }
                    if (!removeCover && (current.coverUrl != null || coverDraft != null)) {
                        TextButton(enabled = !busy, onClick = { coverDraft = null; removeCover = current.coverUrl != null }) {
                            Text("移除背景", color = AppColors.Danger)
                        }
                    }
                }
                if (profile.canSetCover != true) Text("信任等级达到 TL1 后可设置背景图。", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                CommunityField(displayName, { displayName = it }, "昵称（2–24 字）", 24, !busy)
                CommunityField(bio, { bio = limitProfileText(it, 60) }, "个性签名（60 字，最多换行一次）", 120, !busy, lines = 2)
                Text("标签（最多 3 个，每个 8 字）", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                tags.indices.forEach { index ->
                    CommunityField(tags[index], { tags[index] = limitProfileText(it, 8) }, "标签 ${index + 1}", 16, !busy)
                }
                HorizontalDivider(color = AppColors.Divider, modifier = Modifier.padding(vertical = 4.dp))
                Text("主页可见范围", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                Box {
                    FilledTonalButton(onClick = { expanded = true }, enabled = !busy) { Text(visibilityLabel(settings.profileVisibility)) }
                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                        listOf("PUBLIC", "AUTHENTICATED", "PRIVATE").forEach { value ->
                            DropdownMenuItem(text = { Text(visibilityLabel(value)) }, onClick = {
                                settings = settings.copy(profileVisibility = value)
                                expanded = false
                            })
                        }
                    }
                }
                ProfilePrivacySwitch("显示成长等级", settings.showLevel, !busy) { settings = settings.copy(showLevel = it) }
                ProfilePrivacySwitch("显示获赞数", settings.showLikes, !busy) { settings = settings.copy(showLikes = it) }
                ProfilePrivacySwitch("显示关注和粉丝列表", settings.showFollows, !busy) { settings = settings.copy(showFollows = it) }
                ProfilePrivacySwitch("显示关注的圈子", settings.showBoards, !busy) { settings = settings.copy(showBoards = it) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalAlignment = Alignment.End) {
            error?.let { Text(it, color = AppColors.Danger, modifier = Modifier.fillMaxWidth()) }
            ProfileAction(if (busy) "正在保存…" else "保存资料", enabled = !busy && crop == null) {
                val name = displayName.trim()
                val signature = bio.trim()
                error = when {
                    name.length < 2 -> "昵称需要 2–24 字"
                    signature.count { it == '\n' } > 1 -> "签名最多换行一次"
                    filledTags.size != filledTags.toSet().size -> "标签不能重复"
                    else -> null
                }
                if (error == null) {
                    busy = true
                    scope.launch {
                        var textSaved = false
                        communityResult {
                            val input = UpdatePublicProfileRequest(name, signature, filledTags, settings.profileVisibility,
                                settings.showLevel, settings.showLikes, settings.showFollows, settings.showBoards)
                            current = viewModel.updatePublicProfile(sessionId, input)
                            textSaved = true
                            avatarDraft?.let { uri ->
                                val avatar = viewModel.uploadPublicProfileAvatar(sessionId, uri)
                                current = current.copy(avatarUrl = avatar.avatarUrl)
                                avatarDraft = null
                            }
                            val coverFile = coverDraft
                            if (coverFile != null) {
                                val file = File(requireNotNull(coverFile.path) { "裁剪文件需要本地路径" })
                                val part = MultipartBody.Part.createFormData("cover", "cover.jpg", file.asRequestBody("image/jpeg".toMediaType()))
                                val url = viewModel.communityAuthorized(sessionId) { uploadProfileCover(it, part) }.coverUrl
                                current = current.copy(coverUrl = url)
                                coverDraft = null
                            } else if (removeCover) {
                                viewModel.communityAuthorized(sessionId) { removeProfileCover(it) }
                                current = current.copy(coverUrl = null)
                                removeCover = false
                            }
                            viewModel.readPublicProfile(sessionId) { publicProfile(profile.id, it) }
                        }.onSuccess(onSaved).onFailure {
                            val failure = communityErrorMessage(it, "资料保存失败")
                            error = if (textSaved) "文字与隐私已保存。$failure" else failure
                        }
                        busy = false
                    }
                }
            }
        }
    }
    crop?.let { uri -> key(uri) {
        AvatarCropDialog(uri, onClose = { OverlayDismissGuard.arm(); crop = null }, onConfirm = { selected ->
            if (pickingCover) { coverDraft = selected; removeCover = false } else avatarDraft = selected
            crop = null
        }, cover = pickingCover, uniqueFile = true)
    } }
}

@Composable
private fun DeleteCropOnDispose(uri: Uri?) {
    DisposableEffect(uri) {
        onDispose {
            uri?.path?.let { path ->
                val file = File(path)
                if (file.exists() && !file.delete()) Log.w("EditPublicProfile", "裁剪文件清理失败")
            }
        }
    }
}

@Composable
private fun ProfilePrivacySwitch(label: String, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun visibilityLabel(value: String) = when (value) {
    "PRIVATE" -> "仅自己"
    "AUTHENTICATED" -> "仅登录用户"
    else -> "所有人"
}

internal fun limitProfileText(value: String, limit: Int): String =
    value.substring(0, value.offsetByCodePoints(0, minOf(limit, value.codePointCount(0, value.length))))
