package cn.tonglu.app.ui.books

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.books.*
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.launchOrigin
import cn.tonglu.app.ui.rememberLaunchSource

private enum class MineTab(val label: String) {
    LISTINGS("我发布的"), REQUESTS("我想要的"), WANTED("我的求购"),
}

@Composable
internal fun BooksMine(
    repository: BooksRepository?,
    revision: Int,
    onBack: () -> Unit,
    onDetail: (String) -> Unit,
    onWanted: () -> Unit,
    onChanged: () -> Unit,
    examples: List<BookListing>? = null,
) {
    var tab by rememberSaveable { mutableStateOf(MineTab.LISTINGS) }
    var attempt by remember(tab) { mutableIntStateOf(0) }
    var remove by remember { mutableStateOf<BookWanted?>(null) }
    val operation = bookPageOperation()
    val mutate = rememberBookMutation(operation, {})
    val listings = if (tab == MineTab.LISTINGS && repository != null) bookLoad(repository, revision, attempt) {
        repository.call { myBookListings(it) }
    } else BookLoad(value = examples.orEmpty())
    val requests = if (tab == MineTab.REQUESTS && repository != null) bookLoad(repository, revision, attempt) {
        repository.call { myBookRequests(it) }
    } else BookLoad(value = emptyList<BookRequest>())
    val wanted = if (tab == MineTab.WANTED && repository != null) bookLoad(repository, revision, attempt) {
        repository.call { myBookWanted(it) }
    } else BookLoad(value = emptyList<BookWanted>())
    val error = listings.error ?: requests.error ?: wanted.error
    val wantedOrigin = rememberLaunchSource("book-add-wanted", 20.dp)
    BooksPage("我的二手书", onBack, example = examples != null, actions = {
        if (tab == MineTab.WANTED) TextButton(onClick = { wantedOrigin.launch(onWanted) },
            modifier = Modifier.launchOrigin(wantedOrigin)) { Text("发求购") }
    }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MineTab.entries.forEach { item ->
                FilterChip(selected = tab == item, onClick = { tab = item }, label = { Text(item.label) },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) {
                item { BookErrorText(error.message) { attempt++ } }
            } else if (listings.value == null || requests.value == null || wanted.value == null) {
                item { BookLoading() }
            } else when (tab) {
                MineTab.LISTINGS -> {
                    if (listings.value.isEmpty()) item { Text("还没有发布过二手书", color = AppColors.TextSecondary) }
                    items(listings.value, key = BookListing::id) { listing ->
                        BookListingCard(listing, repository, onClick = { onDetail(listing.id) })
                    }
                }
                MineTab.REQUESTS -> {
                    if (requests.value.isEmpty()) item { Text("还没有发出购书请求", color = AppColors.TextSecondary) }
                    items(requests.value, key = BookRequest::id) { request ->
                        val listing = checkNotNull(request.listing) { "我的购书请求缺少上架记录" }
                        val canOpen = when (listing.status) {
                            BookListingStatus.ON_SALE, BookListingStatus.RESERVED -> true
                            BookListingStatus.SOLD -> request.status == BookRequestStatus.DONE
                            else -> false
                        }
                        BookListingCard(listing, repository, request.status.label,
                            onClick = if (canOpen) ({ onDetail(listing.id) }) else null)
                    }
                }
                MineTab.WANTED -> {
                    if (wanted.value.isEmpty()) item { Text("还没有求购记录", color = AppColors.TextSecondary) }
                    items(wanted.value, key = BookWanted::id) { row ->
                        BookSection {
                            row.keyword?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                            row.isbn?.let { Text("ISBN · $it", color = AppColors.TextSecondary) }
                            TextButton(enabled = !operation.busy, onClick = { remove = row }) { Text("删除求购") }
                        }
                    }
                    operation.error?.let { item { BookErrorText(it) } }
                }
            }
        }
    }
    if (remove != null) {
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("删除求购？") },
            text = { Text("删除后，这条求购将不再发送匹配通知。") },
            confirmButton = {
                TextButton(onClick = {
                    val row = checkNotNull(remove) { "删除求购需要选中一条记录" }
                    val repo = checkNotNull(repository) { "删除求购需要登录" }
                    remove = null
                    mutate({ repo.call { deleteBookWanted(it, row.id) } }, onChanged)
                }) { Text("删除", color = AppColors.Danger) }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("保留") } },
        )
    }
}

@Composable
internal fun BookWantedForm(
    repository: BooksRepository,
    seed: String,
    course: String?,
    onBack: () -> Unit,
    onCampus: () -> Unit,
    onSaved: () -> Unit,
) {
    val initialIsbn = normalizeBookIsbn(seed)
    var isbn by remember { mutableStateOf(initialIsbn.orEmpty()) }
    var keyword by remember { mutableStateOf(if (initialIsbn == null) seed.take(100) else "") }
    val operation = bookPageOperation()
    val mutate = rememberBookMutation(operation, onCampus)
    val normalized = normalizeBookIsbn(isbn)
    val enabled = keyword.isNotBlank() || normalized != null
    BooksPage("发布求购", { if (!operation.busy) onBack() }) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BookSection {
                course?.let { Text("课程 · $it", fontWeight = FontWeight.SemiBold) }
                Text("填写 ISBN 或书名关键词，匹配到新上架的书时会收到站内消息。", color = AppColors.TextSecondary)
                BookField("ISBN", isbn, { isbn = it }, 40, enabled = !operation.busy)
                if (isbn.isNotBlank() && normalized == null) BookErrorText("ISBN 格式或校验位不正确")
                BookField("书名关键词", keyword, { keyword = it }, 100, enabled = !operation.busy)
            }
            BookErrorText(operation.error)
        }
        BookActionButton(
            if (operation.busy) "提交中…" else "发布求购",
            onClick = {
                mutate({ repository.call { createBookWanted(it, CreateBookWanted(normalized, keyword.bookOptional())) } }, onSaved)
            },
            enabled = !operation.busy && enabled && (isbn.isBlank() || normalized != null),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
