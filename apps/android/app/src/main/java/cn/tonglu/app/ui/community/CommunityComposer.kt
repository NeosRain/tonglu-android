package cn.tonglu.app.ui.community

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.AppViewModel
import cn.tonglu.app.data.LinkedPackDto
import cn.tonglu.app.data.community.*
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OriginAlertDialog
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.PostComposerDialog
import cn.tonglu.app.ui.PostPackPickerScreen
import kotlinx.coroutines.launch
import retrofit2.HttpException

internal data class CommunityDraft(
    val category: String? = null,
    val title: String = "",
    val body: String = "",
    val board: BoardDto? = null,
    val topics: List<TopicDto> = emptyList(),
    val linkedPack: LinkedPackDto? = null,
)

@Composable
internal fun CommunityComposer(
    viewModel: AppViewModel, sessionId: String, target: CommunityComposeTarget, savedDraft: CommunityDraft?,
    legacy: Boolean,
    onDismiss: (CommunityDraft) -> Unit, onPublished: () -> Unit,
) {
    val draft = savedDraft ?: CommunityDraft(board = target.board, topics = listOfNotNull(target.topic))
    var board by remember { mutableStateOf(draft.board) }
    var topics by remember { mutableStateOf(draft.topics) }
    var pickingBoard by remember { mutableStateOf(false) }
    var pickingTopic by remember { mutableStateOf(false) }
    var pickingPack by remember { mutableStateOf(false) }
    var linkedPack by remember { mutableStateOf(draft.linkedPack) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(legacy) {
        val category = draft.category
        if (!legacy && board == null && category != null) {
            communityResult { viewModel.apiOrNull().communityBoard("official:$category") }
                .onSuccess { found ->
                    if (board == null) board = officialBoardForCategory(category, listOf(found))
                }.onFailure {
                    // 历史分类对应的官方圈子可能已关闭，此时请用户重新选圈子。
                    if (it !is HttpException || it.code() != 404) {
                        error = communityErrorMessage(it, "无法加载草稿对应的圈子，请重新选择")
                    }
                }
        }
    }
    fun chooseTopic(topic: TopicDto) {
        val ids = topics.map(TopicDto::id)
        if (addPostTopic(ids, topic.id) != ids) topics = topics + topic
        pickingTopic = false
    }
    PostComposerDialog(
        heading = "发布内容",
        initialCategory = draft.category ?: "WALL",
        initialTitle = draft.title,
        initialBody = draft.body,
        linkedPack = linkedPack,
        onPickPack = { pickingPack = true },
        onClearPack = { linkedPack = null },
        overlay = if (pickingPack) ({
            PostPackPickerScreen(api = viewModel.apiOrNull(),
                onPick = { OverlayDismissGuard.arm(); linkedPack = it; pickingPack = false },
                onClose = { OverlayDismissGuard.arm(); pickingPack = false },
            )
        }) else null,
        onDismiss = {},
        onSaveDraft = { category, title, body ->
            onDismiss(CommunityDraft(if (legacy) category else board?.defaultCategory ?: draft.category, title, body, board, topics, linkedPack))
        },
        publishEnabled = legacy || board != null,
        publishError = error,
        communityFields = if (legacy) null else { enabled ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                TextButton(onClick = { pickingBoard = true }, enabled = enabled) {
                    Text(board?.let { "圈子 · " + it.name } ?: "选择圈子（必选）")
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    topics.forEach { topic ->
                        TextButton(enabled = enabled, onClick = { topics = topics.filterNot { it.id == topic.id } }) {
                            Text("#" + topic.name + "  ×")
                        }
                    }
                    if (topics.size < MAX_POST_TOPICS) {
                        TextButton(enabled = enabled, onClick = { pickingTopic = true }) { Text("添加话题（最多 3 个）") }
                    } else Text("已选 3 个话题", color = AppColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                }
            }
        },
        onPublish = { category, title, body, done ->
            error = null
            val pack = linkedPack?.let { metadata -> metadata.packageId?.let { id -> metadata.version?.let { id to it } } }
            viewModel.createPost(
                category, body, title, pack,
                boardId = if (legacy) null else checkNotNull(board).id,
                topics = if (legacy) null else topics.map(TopicDto::id),
                onCommunityError = { error = it },
            ) { ok ->
                done()
                if (ok) onPublished()
            }
        },
    )
    if (pickingBoard) BoardPicker(viewModel, onDismiss = { pickingBoard = false }) {
        board = it
        error = null
        pickingBoard = false
    }
    if (pickingTopic) TopicPicker(viewModel, sessionId, topics, onDismiss = { pickingTopic = false }, onPick = ::chooseTopic)
}

@Composable
private fun BoardPicker(viewModel: AppViewModel, onDismiss: () -> Unit, onPick: (BoardDto) -> Unit) {
    val boards = rememberCommunityList(DIRECTORY_PAGE_SIZE, BoardDto::id, "圈子列表加载失败") { cursor, _ ->
        viewModel.apiOrNull().communityBoards(cursor)
    }
    OriginAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.SurfaceOpaque,
        title = { Text("选择圈子") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                items(boards.items.filter { it.status == "ACTIVE" && it.clubId == null && it.type != "CLUB" }, key = BoardDto::id) {
                    TextButton(onClick = { closeWith { onPick(it) } }) { Text(it.name) }
                }
                item { CommunityListStatus(boards, "暂无可发帖的圈子") }
            }
        },
        confirmButton = { TextButton(onClick = this::dismiss) { Text("取消") } },
    )
}

@Composable
private fun TopicPicker(
    viewModel: AppViewModel, sessionId: String, selected: List<TopicDto>, onDismiss: () -> Unit, onPick: (TopicDto) -> Unit,
) {
    val topics = rememberCommunityList(DIRECTORY_PAGE_SIZE, TopicDto::id, "话题列表加载失败") { cursor, _ ->
        viewModel.apiOrNull().communityTopics(cursor)
    }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    OriginAlertDialog(
        onDismissRequest = { onDismiss() }, dismissEnabled = !busy,
        containerColor = AppColors.SurfaceOpaque,
        title = { Text("选择或创建话题") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                item { CommunityField(name, { name = it }, "话题名称（最多 40 字）", 40, !busy) }
                error?.let { item { Text(it, color = AppColors.Danger) } }
                items(topics.items.filter { it.name.contains(name.trim(), true) }, key = TopicDto::id) { topic ->
                    TextButton(enabled = !busy && selected.none { it.id == topic.id }, onClick = { closeWith { onPick(topic) } }) {
                        Text("#" + topic.name)
                    }
                }
                item { CommunityListStatus(topics, "暂无话题") }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                busy = true
                error = null
                scope.launch {
                    communityResult {
                        viewModel.communityAuthorized(sessionId) { createCommunityTopic(it, CreateTopicRequest(name.trim())) }
                    }.onSuccess { closeWith { onPick(it) } }.onFailure { error = communityErrorMessage(it, "话题创建失败") }
                    busy = false
                }
            }) { Text(if (busy) "创建中…" else "创建并添加") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = this::dismiss) { Text("取消") } },
    )
}
