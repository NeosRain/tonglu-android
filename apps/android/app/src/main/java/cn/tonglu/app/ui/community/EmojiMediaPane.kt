package cn.tonglu.app.ui.community

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.community.*
import cn.tonglu.app.ui.AppColors
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.launch

/** 嵌入评论面板，不再套Dialog；不会增加一层返回或让玻璃采样输入文字。 */
@Composable
internal fun EmojiMediaPane(gateway: EmojiGateway?, sessionId: String?, source: EmojiSource,
    enabled: Boolean, localPreview: Boolean = false, onInsert: (String) -> Boolean) {
    if (gateway == null || sessionId == null) {
        Text("图片表情需要登录应用账号", color = AppColors.TextSecondary)
        return
    }
    val controller = remember(gateway, sessionId, source) { EmojiPickerController(gateway, sessionId) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAttribution by remember(source) { mutableStateOf(false) }
    var pendingPickerOwner by remember { mutableStateOf<String?>(null) }
    var deleting by remember(sessionId) { mutableStateOf<EmojiAsset?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (pendingPickerOwner != sessionId) controller.message("账号发生变化，请重新选择图片")
            else scope.launch { controller.upload { readEmojiFile(context, uri) } }
        }
        pendingPickerOwner = null
    }
    LaunchedEffect(controller, source) { controller.load(source) }
    Column(Modifier.fillMaxWidth().heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (source == EmojiSource.FAVORITES) {
                if (state.favoritesLoaded) "收藏 ${state.favorites.size}/20" else "收藏数量暂不可用"
            } else if (source == EmojiSource.MINE) "我的图片表情" else "官方表情包", color = AppColors.TextPrimary)
            Spacer(Modifier.weight(1f))
            TextButton(enabled = enabled && !state.busy && !state.loading, onClick = { scope.launch { controller.load(source) } }) { Text("重试") }
            if (source == EmojiSource.MINE) TextButton(enabled = enabled && state.capabilities?.canUploadImage == true && !state.busy && !state.loading,
                onClick = { pendingPickerOwner = sessionId; picker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/gif")) }) { Text("上传") }
        }
        if (state.capabilities?.canUploadImage == false) Text(state.capabilities?.reason ?: "等级超过3级或学生认证后可发图片", color = AppColors.TextSecondary, fontSize = 11.sp)
        state.error?.let { Text(it, color = AppColors.Danger, fontSize = 11.sp) }
        state.notice?.let { Text(it, color = AppColors.TextSecondary, fontSize = 11.sp) }
        state.pack?.let { pack ->
            TextButton(onClick = { showAttribution = !showAttribution }) { Text(if (showAttribution) "收起出处与许可" else "出处与许可 · ${pack.license}") }
            if (showAttribution) {
                Text(pack.attribution, color = AppColors.TextSecondary, fontSize = 10.sp)
                Text("${pack.license} · ${pack.licenseUrl}", color = AppColors.TextMuted, fontSize = 10.sp)
                Text(pack.source, color = AppColors.TextMuted, fontSize = 10.sp)
            }
        }
        if (!state.loading && state.assets.isEmpty() && state.error == null) Text(
            when (source) {
                EmojiSource.OFFICIAL -> "官方表情尚未上架，可先使用文字表情。"
                EmojiSource.FAVORITES -> "还没有收藏的表情。"
                EmojiSource.MINE -> "还没有上传的表情。"
            }, color = AppColors.TextMuted, fontSize = 12.sp)
        LazyVerticalGrid(GridCells.Adaptive(76.dp), Modifier.fillMaxWidth().heightIn(max = 224.dp)) {
            items(state.assets, key = { it.id }) { asset ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    var failed by remember(asset.id) { mutableStateOf(false) }
                    TextButton(enabled = enabled && !state.busy && asset.sendable && state.capabilities?.canUploadImage == true,
                        contentPadding = PaddingValues(0.dp), onClick = {
                            try {
                                if (!onInsert(emojiMarkdown(asset))) controller.message("评论最多2000字，请先缩短内容")
                            } catch (e: IllegalArgumentException) { controller.message(e.message ?: "表情链接无效") }
                        }) {
                        if (localPreview) Text(asset.glyph ?: "示例", fontSize = 26.sp, modifier = Modifier.size(52.dp))
                        else if (failed) Text("加载失败", fontSize = 10.sp, color = AppColors.TextSecondary, modifier = Modifier.size(52.dp))
                        else AsyncImage(model = remember(context, asset.publicUrl) { ImageRequest.Builder(context).data(asset.publicUrl).size(128).build() },
                            contentDescription = "插入图片表情 ${asset.glyph ?: asset.id}", modifier = Modifier.size(52.dp),
                            onError = { failed = true })
                    }
                    if (!asset.sendable) Text(if (asset.status == "PENDING") "未完成上传" else "待审核", color = AppColors.TextMuted, fontSize = 10.sp)
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(modifier = Modifier.weight(1f), enabled = enabled && state.favoritesLoaded && !state.busy && !state.loading && asset.status == "READY",
                            contentPadding = PaddingValues(horizontal = 2.dp), onClick = { scope.launch { controller.favorite(asset) } }) {
                            Text(if (asset.id in state.favorites) "取消收藏" else "收藏", fontSize = 10.sp)
                        }
                        if (source == EmojiSource.MINE) TextButton(modifier = Modifier.weight(1f), enabled = enabled && !state.busy,
                            contentPadding = PaddingValues(horizontal = 2.dp), onClick = { deleting = asset }) { Text("删除", color = AppColors.Danger, fontSize = 10.sp) }
                    }
                }
            }
        }
        deleting?.let { asset ->
            // 行内确认，不增加全屏弹层；账号作用域变更自动丢弃。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("删除后无法再引用此表情", Modifier.weight(1f), color = AppColors.TextSecondary, fontSize = 11.sp)
                TextButton(onClick = { deleting = null }) { Text("取消") }
                TextButton(enabled = !state.busy, onClick = { scope.launch { controller.remove(asset); deleting = null } }) { Text("确认删除", color = AppColors.Danger) }
            }
        }
    }
}
