package cn.tonglu.app.ui.books

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.books.*
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.SearchField
import cn.tonglu.app.ui.launchOrigin
import cn.tonglu.app.ui.rememberLaunchSource
import kotlinx.coroutines.delay

@Composable
internal fun BooksHome(
    repository: BooksRepository?,
    course: String?,
    revision: Int,
    onBack: () -> Unit,
    onLogin: () -> Unit,
    onPublish: () -> Unit,
    onMine: () -> Unit,
    onDetail: (String) -> Unit,
    onWanted: (String) -> Unit,
    examples: List<BookListing>? = null,
) {
    var mine by rememberSaveable(course) { mutableStateOf(course == null) }
    var activeCourse by rememberSaveable(course) { mutableStateOf(course) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf("new") }
    var condition by rememberSaveable { mutableStateOf<BookCondition?>(null) }
    var attempt by remember(mine, activeCourse, query, condition, sort, revision) { mutableIntStateOf(0) }
    var cursor by remember(mine, activeCourse, query, condition, sort, revision) { mutableStateOf<String?>(null) }
    var rows by remember(mine, activeCourse, query, condition, sort, revision) { mutableStateOf(examples.orEmpty()) }
    var nextCursor by remember(mine, activeCourse, query, condition, sort, revision) { mutableStateOf<String?>(null) }
    val load = if (repository == null) null else bookLoad(repository, mine, activeCourse, query, sort, condition, revision, cursor, attempt) {
        if (cursor == null && query.isNotBlank()) delay(300)
        repository.list(mine, activeCourse, query, condition, sort, cursor)
    }
    LaunchedEffect(load?.value) {
        load?.value?.let { page ->
            rows = if (cursor == null) page.items else (rows + page.items).distinctBy(BookListing::id)
            nextCursor = page.nextCursor
        }
    }

    val mineOrigin = rememberLaunchSource("books-mine", 20.dp)
    BooksPage("二手书", onBack, example = examples != null, actions = {
        if (repository != null || examples != null) {
            TextButton(onClick = { mineOrigin.launch(onMine) }, modifier = Modifier.launchOrigin(mineOrigin)) { Text("我的") }
        }
    }) {
        if (repository == null && examples == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("登录苔米林账号后可浏览校内二手书", color = AppColors.TextSecondary)
                BookActionButton("去登录", onLogin)
            }
            return@BooksPage
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 6.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchField(query, { if (it.length <= 200) query = it }, hint = "搜索书名、作者或 ISBN")
            BooksFilters(mine, { mine = it }, condition, { condition = it }, sort, { sort = it })
            activeCourse?.let { name ->
                FilterChip(selected = true, onClick = { activeCourse = null }, label = { Text("$name  ×", maxLines = 1) },
                    modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rows, key = BookListing::id) { listing ->
                    BookListingCard(listing, repository) { onDetail(listing.id) }
                }
                when {
                    load?.error != null -> item { BookErrorText(load.error.message) { attempt++ } }
                    load != null && load.value == null -> item { BookLoading() }
                    rows.isEmpty() -> item {
                        val wantedSeed = query.trim()
                        val origin = rememberLaunchSource("books-empty-wanted", 20.dp)
                        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("暂时没有符合条件的二手书", color = AppColors.TextSecondary)
                            TextButton(onClick = { origin.launch { onWanted(wantedSeed) } }, modifier = Modifier.launchOrigin(origin)) {
                                Text(if (activeCourse != null && query.isBlank()) "求购这门课的书" else "求购这本书")
                            }
                        }
                    }
                    nextCursor != null -> item {
                        TextButton(onClick = { cursor = nextCursor }, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
                    }
                }
            }
            val publishOrigin = rememberLaunchSource("books-publish", 18.dp, AppColors.Accent)
            ExtendedFloatingActionButton(
                onClick = { publishOrigin.launch(onPublish) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).launchOrigin(publishOrigin),
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("发布") },
                containerColor = AppColors.Accent,
                contentColor = AppColors.OnAccent,
            )
        }
    }
}

@Composable
private fun BooksFilters(
    mine: Boolean, onMine: (Boolean) -> Unit,
    condition: BookCondition?, onCondition: (BookCondition?) -> Unit,
    sort: String, onSort: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Box(Modifier.matchParentSize().padding(vertical = 8.dp).background(AppColors.SurfaceOpaque, CircleShape))
            Row(Modifier.selectableGroup()) {
                for (choice in listOf(true, false)) {
                    val selected = mine == choice
                    Box(
                        Modifier.heightIn(min = 48.dp).clip(CircleShape)
                            .selectable(selected, role = Role.RadioButton, onClick = { onMine(choice) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        val fill = if (selected) Modifier.background(AppColors.Accent, CircleShape) else Modifier
                        Text(
                            if (choice) "我的课程" else "全部",
                            fill.padding(horizontal = 9.dp, vertical = 8.dp),
                            color = if (selected) AppColors.OnAccent else AppColors.TextPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        BookFilterMenu(condition?.label ?: "全部成色", listOf(null) + BookCondition.entries,
            { it?.label ?: "全部成色" }, onCondition)
        BookFilterMenu(if (sort == "new") "最新上架" else "价格优先", listOf("new", "price"),
            { if (it == "new") "最新上架" else "价格从低到高" }, onSort)
    }
}

@Composable
private fun <T> BookFilterMenu(selectedLabel: String, options: List<T>, label: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(role = Role.Button) { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier.background(AppColors.SurfaceOpaque, CircleShape).padding(start = 8.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selectedLabel, color = AppColors.TextPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(16.dp), tint = AppColors.TextSecondary)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { item ->
                DropdownMenuItem(text = { Text(label(item)) }, onClick = { expanded = false; onSelect(item) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookListingCard(
    listing: BookListing,
    repository: BooksRepository?,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val book = listing.book
    val photo = listing.photos.orEmpty().firstOrNull().takeIf { book?.coverUrl == null }
    val loadedPhoto = if (photo == null || repository == null) null else bookLoad(repository, photo) { repository.photoUrl(photo) }
    val origin = rememberLaunchSource(listing.id, 16.dp, AppColors.Surface)
    val click = if (onClick == null) Modifier else Modifier.launchOrigin(origin).clickable { origin.launch(onClick) }
    Box(click.fillMaxWidth()) {
        BookSection {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BookImage(book?.coverUrl ?: loadedPhoto?.value, null, Modifier.size(width = 64.dp, height = 84.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(book?.title ?: "已移除的二手书", fontWeight = FontWeight.SemiBold, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                    if (book != null) {
                        val metadata = listOfNotNull(book.edition, listing.condition?.label).joinToString(" · ")
                        if (metadata.isNotEmpty()) Text(metadata, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listing.seller?.let {
                                Text(it.displayName, Modifier.align(Alignment.CenterVertically),
                                    color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            book.courseTags.orEmpty().forEach { tag ->
                                Text(
                                    tag.courseName,
                                    Modifier.align(Alignment.CenterVertically).background(AppColors.CanvasOpaque, CircleShape)
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    color = AppColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (book != null) Text(bookPriceLabel(listing.price), color = AppColors.Accent,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (listing.status != BookListingStatus.ON_SALE) {
                        Surface(color = AppColors.ChipIdle, shape = MaterialTheme.shapes.small) {
                            Text(listing.status.label, Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                color = AppColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            supporting?.let { Text(it, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

internal fun bookPriceLabel(price: Int): String = if (price == 0) "赠送" else "¥${formatBookYuan(price)}"
