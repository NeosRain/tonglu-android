package cn.tonglu.app.ui.community

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.tonglu.app.data.community.communityErrorMessage
import cn.tonglu.app.data.community.communityResult
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.square.CategoryTab
import kotlinx.coroutines.launch

internal const val POST_PAGE_SIZE = 20
internal const val DIRECTORY_PAGE_SIZE = 50

internal class CommunityList<T>(
    private val pageSize: Int,
    private val id: (T) -> String,
    private val failureMessage: String,
    private val request: suspend (cursor: String?, page: Int) -> List<T>,
) {
    var items by mutableStateOf(emptyList<T>())
        private set
    var busy by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasMore by mutableStateOf(false)
        private set
    private var inFlight = false
    private var page = 0
    private var cursor: String? = null
    private var retryReset = false

    suspend fun load(reset: Boolean = false) {
        if (inFlight) return
        inFlight = true
        busy = true
        error = null
        val replacing = reset || retryReset
        try {
            val next = request(if (replacing) null else cursor, if (replacing) 0 else page)
            items = ((if (replacing) emptyList() else items) + next).distinctBy(id)
            hasMore = next.size == pageSize
            cursor = next.lastOrNull()?.let(id)
            page = if (replacing) 1 else page + 1
            retryReset = false
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            retryReset = replacing
            error = communityErrorMessage(failure, failureMessage)
        } finally {
            inFlight = false
            busy = false
        }
    }
}

@Composable
internal fun <T> rememberCommunityList(
    pageSize: Int,
    id: (T) -> String,
    failureMessage: String,
    request: suspend (String?, Int) -> List<T>,
): CommunityList<T> {
    val latestRequest by rememberUpdatedState(request)
    val list = remember { CommunityList(pageSize, id, failureMessage) { cursor, page -> latestRequest(cursor, page) } }
    LaunchedEffect(list) { list.load() }
    return list
}

internal class CommunityResource<T> {
    var value by mutableStateOf<T?>(null)
    var error by mutableStateOf<String?>(null)
    var attempt by mutableIntStateOf(0)
}

@Composable
internal fun <T> rememberCommunityResource(failureMessage: String, request: suspend () -> T): CommunityResource<T> {
    val result = remember { CommunityResource<T>() }
    LaunchedEffect(result.attempt) {
        result.error = null
        communityResult { request() }
            .onSuccess { result.value = it }
            .onFailure { result.error = communityErrorMessage(it, failureMessage) }
    }
    return result
}

@Composable
internal fun CommunityStatus(
    busy: Boolean = false,
    error: String? = null,
    empty: String? = null,
    more: Boolean = false,
    onNext: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            busy -> CircularProgressIndicator(color = AppColors.Accent, modifier = Modifier.size(24.dp))
            error != null -> {
                Text(error, color = AppColors.Danger, fontSize = 14.sp)
                TextButton(onClick = onNext) { Text("重试") }
            }
            more -> TextButton(onClick = onNext) { Text("加载更多") }
            empty != null -> Text(empty, color = AppColors.TextSecondary, fontSize = 14.sp)
        }
    }
}

@Composable
internal fun <T> CommunityListStatus(list: CommunityList<T>, empty: String) {
    val scope = rememberCoroutineScope()
    CommunityStatus(list.busy, list.error, empty.takeIf { list.items.isEmpty() }, list.hasMore) {
        scope.launch { list.load() }
    }
}

@Composable
internal fun CommunityGrid(
    bottom: PaddingValues = PaddingValues(bottom = 16.dp),
    content: LazyGridScope.() -> Unit,
) {
    val window = LocalConfiguration.current
    val wide = window.screenWidthDp >= 600 && window.screenHeightDp >= 480
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (wide && maxWidth >= 520.dp) 2 else 1
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 14.dp, end = 14.dp, top = 8.dp, bottom = bottom.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

internal fun LazyGridScope.communityFullRow(content: @Composable () -> Unit) {
    // 一行里可能放多块（入口、话题、状态），用 Column 纵向排，避免叠在一起。
    item(span = { GridItemSpan(maxLineSpan) }) { Column(Modifier.fillMaxWidth()) { content() } }
}

@Composable
internal fun <T> CommunityTabs(items: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        items.forEach { (value, label) ->
            CategoryTab(label, value == selected, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { onSelect(value) }
        }
    }
    HorizontalDivider(color = AppColors.Divider)
}

@Composable
internal fun CommunityField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    limit: Int,
    enabled: Boolean = true,
    lines: Int = 1,
) {
    Text(label, color = AppColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
    TextField(
        value, { onChange(it.take(limit)) }, enabled = enabled, singleLine = lines == 1, minLines = lines,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = AppColors.SurfaceOpaque, unfocusedContainerColor = AppColors.SurfaceOpaque,
            disabledContainerColor = AppColors.SurfaceOpaque, focusedIndicatorColor = AppColors.SurfaceOpaque,
            unfocusedIndicatorColor = AppColors.SurfaceOpaque, disabledIndicatorColor = AppColors.SurfaceOpaque,
        ),
    )
}

@Composable
internal fun CommunityDescription(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, color = AppColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        if (body.isNotBlank()) Text(body, color = AppColors.TextSecondary, fontSize = 14.sp, lineHeight = 21.sp)
    }
}
