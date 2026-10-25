package cn.tonglu.app.ui.learning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import cn.tonglu.app.data.english.MyReadingArticle
import cn.tonglu.app.data.ReadingLibrary
import cn.tonglu.app.ui.AppColors
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 纯排版书封，不假造书籍原封面；配色全部使用主题语义槽。 */
@Composable
internal fun LibraryCover(title: String, format: String, modifier: Modifier = Modifier) {
    Surface(modifier.width(68.dp).heightIn(min = 92.dp), shape = RoundedCornerShape(8.dp, 14.dp, 14.dp, 8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Surface(Modifier.width(5.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .15f)) {}
            Column(Modifier.weight(1f).padding(9.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(format.ifBlank { "阅读" }.take(10), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun LibrarySearch(value: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
        shape = RoundedCornerShape(18.dp), placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(Icons.Outlined.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton({ onChange("") }) { Icon(Icons.Outlined.Close, "清空书库搜索") } })
}

@Composable
internal fun LibraryEmpty(title: String, description: String, icon: ImageVector = Icons.Outlined.MenuBook) {
    Surface(shape = RoundedCornerShape(24.dp), color = learningCardColor(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}


@Composable
internal fun PersonalLibraryShelf(
    articles: List<MyReadingArticle>, owner: String, revision: Int, notice: String?, onClose: () -> Unit,
    onImport: () -> Unit, onAdd: () -> Unit, onHistory: (() -> Unit)?,
    onOpen: (MyReadingArticle) -> Unit, onDelete: (MyReadingArticle) -> Unit,
) {
    var query by rememberSaveable(owner) { mutableStateOf("") }
    var tag by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var readingOnly by rememberSaveable(owner) { mutableStateOf(false) }
    var menu by remember(owner) { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val library = remember(owner) { ReadingLibrary.of(context, owner) }
    val progress = remember(articles, owner, revision) { articles.associate { it.id to library.articleProgress(it.id)?.copy() } }
    val groups = articles.groupBy { it.documentId ?: it.id }.values
        .map { it.sortedBy { row -> row.chapterIndex } }.sortedByDescending { it.first().addedAt }
    val tags = articles.flatMap { it.tags.split("|") }.filter { it.isNotBlank() }.distinct().sorted()
    // 先组成整本书再筛选，命中某章也必须保留完整目录、页数和校对状态。
    val shown = groups.filter { rows ->
        (tag == null || rows.any { tag in it.tags.split("|") }) &&
            (query.isBlank() || rows.any { row -> listOf(row.documentTitle.orEmpty(), row.title, row.sourceNote, row.tags)
                .any { it.contains(query.trim(), ignoreCase = true) } }) &&
            (!readingOnly || rows.any { progress[it.id]?.let { p -> !p.finished } == true })
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("heading") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级") }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("我的书库", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("${groups.size} 项内容 · 留给自己的阅读空间", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                }
            }
        }
        item("import") {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("把想读的，放在一起", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("TXT · Markdown · PDF · EPUB", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onImport, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("导入文件") }
                        OutlinedButton(onAdd, modifier = Modifier.weight(1f)) { Text("粘贴文章") }
                    }
                    Text("仅存本机 · 按账号隔离", style = MaterialTheme.typography.labelSmall, color = AppColors.TextSecondary)
                }
            }
        }
        if (!notice.isNullOrBlank()) item("notice") { Text(notice, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary) }
        if (onHistory != null) item("history") {
            Surface(onClick = onHistory, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("阅读记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("续读 · 书签 · 笔记", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = AppColors.TextSecondary)
                }
            }
        }
        if (groups.isNotEmpty()) {
            item("search") { LibrarySearch(query, { query = it }, "搜索书名、章节或标签") }
            item("filters") {
                Column {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!readingOnly, { readingOnly = false }, label = { Text("全部内容") })
                        FilterChip(readingOnly, { readingOnly = true }, label = { Text("正在读") })
                    }
                    if (tags.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(tag == null, { tag = null }, label = { Text("全部标签") })
                        tags.forEach { value -> FilterChip(tag == value, { tag = if (tag == value) null else value }, label = { Text(value) }) }
                    }
                }
            }
            item("count") { Text("${shown.size} 项内容", style = MaterialTheme.typography.titleSmall, color = AppColors.TextSecondary) }
        }
        if (shown.isEmpty()) item("empty") {
            LibraryEmpty(if (groups.isEmpty()) "第一本，从喜欢的开始" else "没有符合条件的内容",
                if (groups.isEmpty()) "导入一本书，或粘贴一篇文章。阅读进度、书签和笔记，慢慢积累。" else "换个关键词、标签，或切回全部内容。")
        }
        items(shown, key = { it.first().documentId ?: it.first().id }) { rows ->
            val row = rows.first()
            val key = row.documentId ?: row.id
            val paper = rows.any { "试卷" in it.tags.split("|") }
            val started = rows.count { progress[it.id] != null }
            val complete = rows.count { progress[it.id]?.finished == true }
            val pending = rows.count { !it.textAvailable }
            Surface(onClick = { onOpen(row) }, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LibraryCover(row.documentTitle ?: row.title, if (paper) "试卷" else row.sourceFormat)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(row.documentTitle ?: row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(if (paper) "${rows.size} 页 · $pending 页待校对" else if (row.documentId != null) "${row.sourceFormat} · ${rows.size} 个章节/页面" else "文章 · ${row.sourceFormat}",
                            style = MaterialTheme.typography.labelMedium, color = AppColors.TextSecondary)
                        Text(row.sourceNote, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (complete == rows.size) "已读完 · 再读一次" else if (started > 0) "正在读 · 已读完 $complete / ${rows.size}" else "开始阅读",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Box {
                        IconButton({ menu = key }) { Icon(Icons.Outlined.MoreVert, "更多：${row.documentTitle ?: row.title}", tint = AppColors.TextSecondary) }
                        DropdownMenu(menu == key, { menu = null }) {
                            DropdownMenuItem(text = { Text("删除本机副本") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                onClick = { menu = null; onDelete(row) })
                        }
                    }
                }
            }
        }
    }
}
