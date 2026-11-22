package cn.tonglu.app.ui.community

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.AppViewModel
import cn.tonglu.app.data.PostDto
import cn.tonglu.app.data.TongluApi
import cn.tonglu.app.data.community.*
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OverlayDismissGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import retrofit2.HttpException

internal sealed interface BoardTool {
    data class Post(val post: PostDto, val canMuteAuthor: Boolean) : BoardTool
    data class Move(val postId: String) : BoardTool
    data class Mute(val userId: String) : BoardTool
    data class Delete(val postId: String) : BoardTool
    data class Decide(val report: BoardReportDto, val confirm: Boolean) : BoardTool
    data object Trash : BoardTool
    data object Reports : BoardTool
}

internal class BoardModeration(
    val viewModel: AppViewModel,
    val sessionId: String,
    val board: BoardDto,
    private val scope: CoroutineScope,
    private val onChanged: () -> Unit,
) {
    var canManage by mutableStateOf(false)
        private set
    var canReview by mutableStateOf(false)
        private set
    private var manageError by mutableStateOf<String?>(null)
    private var reviewError by mutableStateOf<String?>(null)
    val error get() = manageError ?: reviewError
    var attempt by mutableIntStateOf(0)
        private set
    var revision by mutableIntStateOf(0)
        private set
    var tool by mutableStateOf<BoardTool?>(null)
        private set
    var actionError by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    fun retryAccess() { attempt++ }

    suspend fun checkAccess() = coroutineScope {
        launch {
            check({ communityTrash(it, board.id) }) { allowed, failure ->
                canManage = allowed
                manageError = failure
            }
        }
        launch {
            check({ communityReports(it, board.id) }) { allowed, failure ->
                canReview = allowed
                reviewError = failure
            }
        }
    }

    private suspend fun check(request: suspend TongluApi.(String) -> Any, result: (Boolean, String?) -> Unit) {
        communityResult { viewModel.communityAuthorized(sessionId, request) }
            .onSuccess { result(true, null) }
            .onFailure { failure ->
                val error = if (failure is HttpException && failure.code() == 403) null
                    else communityErrorMessage(failure, "无法确认圈子管理权限")
                result(false, error)
            }
    }

    fun managePost(post: PostDto, userId: String?) =
        show(BoardTool.Post(post, post.author?.id?.let { it != userId } == true))
    fun openTrash() = show(BoardTool.Trash)
    fun openReports() = show(BoardTool.Reports)

    fun show(next: BoardTool?) {
        if (busy) return
        actionError = null
        if (next == null) OverlayDismissGuard.arm()
        tool = next
    }

    fun moderate(postId: String, request: ModerateBoardPostRequest, after: BoardTool? = null) =
        perform(after) { moderateCommunityPost(it, board.id, postId, request) }

    fun mute(userId: String, days: Int, reason: String) =
        perform(null) { muteCommunityMember(it, board.id, userId, BoardMuteRequest(days * 24 * 60, reason)) }

    fun decide(tool: BoardTool.Decide, reason: String) = perform(BoardTool.Reports) {
        decideCommunityReport(it, board.id, tool.report.id, BoardReportDecision(if (tool.confirm) "confirm" else "dismiss", reason))
    }

    private fun perform(after: BoardTool?, request: suspend TongluApi.(String) -> Unit) {
        if (busy) return
        busy = true
        actionError = null
        scope.launch {
            val result = communityResult { viewModel.communityAuthorized(sessionId, request) }
            busy = false
            result.onSuccess {
                revision++
                show(after)
                onChanged()
            }.onFailure {
                actionError = communityErrorMessage(it, "管理操作失败，请重试")
                if (it is HttpException && it.code() == 403) retryAccess()
            }
        }
    }
}

@Composable
internal fun rememberBoardModeration(viewModel: AppViewModel, sessionId: String, board: BoardDto, onChanged: () -> Unit): BoardModeration {
    val scope = rememberCoroutineScope()
    val latestChanged by rememberUpdatedState(onChanged)
    val state = remember(viewModel, sessionId, board.id) { BoardModeration(viewModel, sessionId, board, scope) { latestChanged() } }
    LaunchedEffect(state, state.attempt) { state.checkAccess() }
    return state
}

@Composable
internal fun BoardModerationDialogs(state: BoardModeration) {
    val tool = state.tool ?: return
    val allowed = when (tool) {
        BoardTool.Reports, is BoardTool.Decide -> state.canReview
        else -> state.canManage
    }
    if (!allowed) {
        BoardActionDialog(state, "圈子管理") {
            if (state.actionError == null) Text(state.error ?: "你没有权限执行此操作", color = AppColors.Danger)
        }
        return
    }
    key(tool) {
        when (tool) {
            is BoardTool.Post -> BoardPostActions(state, tool)
            is BoardTool.Move -> BoardMoveDialog(state, tool.postId)
            is BoardTool.Mute -> BoardReasonDialog(state, "圈内禁言", "禁言", showDays = true) { days, reason ->
                state.mute(tool.userId, days, reason)
            }
            is BoardTool.Delete -> BoardActionDialog(state, "移入回收站", "移入回收站",
                onConfirm = { state.moderate(tool.postId, ModerateBoardPostRequest("delete")) }) {
                Text("移入回收站后，圈子成员将无法查看此帖。管理者可以恢复。")
            }
            is BoardTool.Decide -> BoardReasonDialog(
                state, if (tool.confirm) "维持隐藏" else "驳回举报", "确认处理", onCancel = { state.openReports() },
            ) { _, reason -> state.decide(tool, reason) }
            BoardTool.Trash -> BoardTrashDialog(state)
            BoardTool.Reports -> BoardReportsDialog(state)
        }
    }
}

@Composable
private fun BoardPostActions(state: BoardModeration, tool: BoardTool.Post) {
    val post = tool.post
    BoardActionDialog(state, "管理帖子") {
        Column {
            val pinned = post.pinnedAt != null
            val featured = post.featuredAt != null
            TextButton(enabled = state.canManage && !state.busy, onClick = {
                state.moderate(post.id, ModerateBoardPostRequest(if (pinned) "unpin" else "pin"))
            }) { Text(if (pinned) "取消置顶" else "置顶") }
            TextButton(enabled = state.canManage && !state.busy, onClick = {
                state.moderate(post.id, ModerateBoardPostRequest(if (featured) "unfeature" else "feature"))
            }) { Text(if (featured) "取消精华" else "加精") }
            if (state.board.clubId == null && state.board.type != "CLUB") {
                TextButton(enabled = state.canManage && !state.busy, onClick = { state.show(BoardTool.Move(post.id)) }) { Text("移帖") }
            }
            if (tool.canMuteAuthor) post.author?.id?.let { userId ->
                TextButton(enabled = state.canManage && !state.busy, onClick = { state.show(BoardTool.Mute(userId)) }) { Text("圈内禁言作者") }
            }
            TextButton(enabled = state.canManage && !state.busy, onClick = { state.show(BoardTool.Delete(post.id)) }) {
                Text("移入回收站", color = AppColors.Danger)
            }
        }
    }
}

@Composable
private fun BoardMoveDialog(state: BoardModeration, postId: String) {
    val boards = rememberCommunityList(DIRECTORY_PAGE_SIZE, BoardDto::id, "目标圈子加载失败") { cursor, _ ->
        state.viewModel.apiOrNull().communityBoards(cursor)
    }
    var selected by remember { mutableStateOf<BoardDto?>(null) }
    val board = selected
    val access = if (board == null) null else key(board.id) {
        rememberCommunityResource("无法确认目标圈子的管理权限") {
            state.viewModel.communityAuthorized(state.sessionId) { communityTrash(it, board.id) }
        }
    }
    BoardActionDialog(state, "选择目标圈子", "确认移帖", enabled = state.canManage && access?.value != null,
        onConfirm = { state.moderate(postId, ModerateBoardPostRequest("move", checkNotNull(board).id)) }) {
        Column {
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(boards.items.filter { it.id != state.board.id && it.clubId == null && it.type != "CLUB" }, key = BoardDto::id) {
                    TextButton(onClick = { selected = it }, enabled = !state.busy) {
                        Text(if (board?.id == it.id) "已选 · " + it.name else it.name)
                    }
                }
                item { CommunityListStatus(boards, "暂无目标圈子") }
            }
            if (access != null) {
                CommunityStatus(busy = access.value == null && access.error == null, error = access.error) { access.attempt++ }
            }
        }
    }
}

@Composable
private fun BoardTrashDialog(state: BoardModeration) {
    val list = key(state.revision) {
        rememberCommunityList(DIRECTORY_PAGE_SIZE, BoardTrashDto::id, "回收站加载失败") { cursor, _ ->
            state.viewModel.communityAuthorized(state.sessionId) { communityTrash(it, state.board.id, cursor) }
        }
    }
    BoardActionDialog(state, "回收站") {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(list.items, key = BoardTrashDto::id) { post ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(post.title ?: "无标题帖子", modifier = Modifier.weight(1f).padding(vertical = 12.dp))
                    TextButton(enabled = state.canManage && !state.busy, onClick = {
                        state.moderate(post.id, ModerateBoardPostRequest("restore"), BoardTool.Trash)
                    }) { Text("恢复") }
                }
                HorizontalDivider(color = AppColors.Divider)
            }
            item { CommunityListStatus(list, "回收站为空") }
        }
    }
}

@Composable
private fun BoardReportsDialog(state: BoardModeration) {
    val list = key(state.revision) {
        rememberCommunityList(POST_PAGE_SIZE, BoardReportDto::id, "待处理举报加载失败") { cursor, _ ->
            state.viewModel.communityAuthorized(state.sessionId) { communityReports(it, state.board.id, cursor) }
        }
    }
    BoardActionDialog(state, "处理举报") {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list.items, key = BoardReportDto::id) { item ->
                Text(item.title ?: "无标题帖子", color = AppColors.TextPrimary)
                Text(item.body, color = AppColors.TextSecondary, fontSize = 13.sp, maxLines = 4)
                item.reports.forEach { report ->
                    val label = PostReportCategory.entries.firstOrNull { it.name == report.category }?.label ?: report.reason
                    Text(listOfNotNull(label, report.details).joinToString(" · "), color = AppColors.TextSecondary, fontSize = 13.sp)
                }
                Row {
                    TextButton(enabled = state.canReview, onClick = { state.show(BoardTool.Decide(item, true)) }) { Text("维持隐藏") }
                    TextButton(enabled = state.canReview, onClick = { state.show(BoardTool.Decide(item, false)) }) { Text("驳回举报") }
                }
                HorizontalDivider(color = AppColors.Divider)
            }
            item { CommunityListStatus(list, "暂无待处理举报") }
        }
    }
}

@Composable
private fun BoardReasonDialog(
    state: BoardModeration, title: String, action: String, showDays: Boolean = false,
    onCancel: () -> Unit = { state.show(null) }, onConfirm: (Int, String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    var days by remember { mutableIntStateOf(1) }
    BoardActionDialog(
        state, title, action, enabled = reason.isNotBlank() && (if (showDays) state.canManage else state.canReview),
        onCancel = onCancel, onConfirm = { onConfirm(days, reason.trim()) },
    ) {
        Column {
            if (showDays) {
                Text("禁言天数", color = AppColors.TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { days-- }, enabled = !state.busy && days > 1) { Text("减一天") }
                    Text("$days 天", modifier = Modifier.padding(top = 12.dp))
                    TextButton(onClick = { days++ }, enabled = !state.busy && days < 7) { Text("加一天") }
                }
            }
            CommunityField(reason, { reason = it }, "说明（必填，最多 300 字）", 300, !state.busy, lines = 2)
        }
    }
}

@Composable
private fun BoardActionDialog(
    state: BoardModeration, title: String, confirm: String? = null, enabled: Boolean = true,
    onCancel: () -> Unit = { state.show(null) }, onConfirm: () -> Unit = {}, content: @Composable () -> Unit,
) {
    AlertDialog(
        containerColor = AppColors.SurfaceOpaque,
        onDismissRequest = { if (!state.busy) onCancel() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                content()
                state.actionError?.let { Text(it, color = AppColors.Danger) }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AppColors.Accent)
            }
        },
        confirmButton = {
            if (confirm != null) TextButton(enabled = enabled && !state.busy, onClick = onConfirm) { Text(confirm) }
        },
        dismissButton = { TextButton(enabled = !state.busy, onClick = onCancel) { Text(if (confirm == null) "关闭" else "取消") } },
    )
}
