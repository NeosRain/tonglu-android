package cn.tonglu.app.ui.english.books

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.english.books.BookArticleRecord
import cn.tonglu.app.data.english.books.PersonalBookWord
import cn.tonglu.app.data.english.books.PersonalBooksSnapshot
import cn.tonglu.app.data.english.books.PersonalWordBook
import cn.tonglu.app.data.english.books.PersonalWordBookStore
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayMotion
import cn.tonglu.app.ui.overlayMotion
import cn.tonglu.app.ui.rememberOverlayMotion
import kotlinx.coroutines.launch

/**
 * 个人词书管理（EF04）。
 *
 * 🔴 保存范围如实明示：**仅保存在本机**（按账号哈希隔离的本地文件），
 * 不上传云端、卸载/清除数据后不恢复。有云同步接口之前不承诺同步。
 *
 * 只依赖 [PersonalWordBookStore] 的真实 API；不伪造任何成功状态。
 * 精读点词收藏到书的入口由 EnglishStudyScreen（AI1）按 work/workbuddy-B/HANDOFF.md 接线。
 */
@Composable
fun PersonalBooksScreen(owner: String, onClose: () -> Unit,
    /** EF10：生成并保存的文章从这里回到既有阅读器；null = 只管理词书（不显示不可用的入口）。 */
    onOpenArticle: ((BookArticleRecord) -> Unit)? = null) {
    val context = LocalContext.current
    val store = remember(owner) { PersonalWordBookStore(context, owner) }
    val scope = rememberCoroutineScope()
    var snapshot by remember(owner) { mutableStateOf<PersonalBooksSnapshot?>(null) }
    var openBookId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var creating by rememberSaveable(owner) { mutableStateOf(false) }
    var createName by rememberSaveable(owner) { mutableStateOf("") }
    var notice by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    var selectedKeys by remember(owner) { mutableStateOf<Set<String>>(emptySet()) }
    var selectedBookIds by remember(owner) { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDeleteBooks by rememberSaveable(owner) { mutableStateOf(false) }
    var confirmRemoveWords by rememberSaveable(owner) { mutableStateOf(false) }
    var importOpen by rememberSaveable(owner) { mutableStateOf(false) }
    var photoOpen by rememberSaveable(owner) { mutableStateOf(false) }
    var articleBookId by rememberSaveable(owner) { mutableStateOf<String?>(null) }
    val motion = rememberOverlayMotion(
        onClose = { OverlayDismissGuard.arm(); onClose() },
        predictive = OverlayMotion.enabledState, selfDrag = true,
    )
    val books = snapshot?.books.orEmpty()
    val openBook = books.firstOrNull { it.id == openBookId }

    fun reload() {
        scope.launch {
            try { snapshot = store.snapshot() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (cause: Exception) { notice = cause.message ?: "词书读取失败" }
        }
    }
    LaunchedEffect(owner) { reload() }

    fun back() {
        when {
            creating -> { creating = false }
            openBookId != null -> { openBookId = null; selectedKeys = emptySet() }
            selectedBookIds.isNotEmpty() -> selectedBookIds = emptySet()
            else -> motion.requestClose()
        }
    }

    if (importOpen) {
        PersonalBookImportScreen(owner = owner, onClose = { importOpen = false })
        return
    }
    if (photoOpen) {
        PhotoWordScreen(owner = owner, onClose = { photoOpen = false })
        return
    }
    articleBookId?.let { bookId ->
        val articleBook = snapshot?.books?.firstOrNull { it.id == bookId }
        if (articleBook == null) {
            articleBookId = null
        } else {
            BookArticleScreen(owner = owner, book = articleBook, onClose = { articleBookId = null },
                onOpenArticle = onOpenArticle)
            return
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = { back() },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(Modifier.fillMaxSize().overlayMotion(motion, fade = false, onBack = { back() }), color = AppColors.Canvas) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一级") }
                    Text(
                        when {
                            creating -> "新建词书"
                            openBook != null -> openBook.name
                            selectedBookIds.isNotEmpty() -> "已选 ${selectedBookIds.size} 本"
                            else -> "个人词书"
                        },
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2,
                    )
                    when {
                        creating -> TextButton(onClick = {
                            scope.launch {
                                try {
                                    store.create(createName)
                                    creating = false; createName = ""; notice = null; reload()
                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                catch (cause: Exception) { notice = cause.message ?: "创建失败" }
                            }
                        }, enabled = createName.isNotBlank()) { Text("保存") }
                        openBook != null -> {
                            TextButton(onClick = { articleBookId = openBook.id }) { Text("生成文章") }
                            if (selectedKeys.isNotEmpty()) TextButton(onClick = { confirmRemoveWords = true }) {
                                Text("移除 ${selectedKeys.size}")
                            }
                        }
                        else -> {
                            if (selectedBookIds.isNotEmpty()) TextButton(onClick = { confirmDeleteBooks = true }) {
                                Text("删除 ${selectedBookIds.size}")
                            }
                            TextButton(onClick = { importOpen = true }) { Text("导入") }
                            TextButton(onClick = { photoOpen = true }) { Text("拍照识词") }
                            TextButton(onClick = { creating = true; notice = null }, enabled = books.size < PersonalWordBookStore.MAX_BOOKS) {
                                Text("＋ 新建")
                            }
                        }
                    }
                }
                notice?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                }
                when {
                    creating -> CreateBookBody(createName, books.size, onNameChange = { createName = it })
                    openBook != null -> WordListBody(openBook, selectedKeys,
                        onToggle = { key -> selectedKeys = if (key in selectedKeys) selectedKeys - key else selectedKeys + key },
                        onSelectAll = { selectedKeys = if (selectedKeys.size == openBook.words.size) emptySet() else openBook.words.map { it.key }.toSet() })
                    else -> BooksBody(books, selectedBookIds,
                        onToggle = { id -> selectedBookIds = if (id in selectedBookIds) selectedBookIds - id else selectedBookIds + id },
                        onOpen = { openBookId = it; selectedKeys = emptySet(); notice = null },
                        onLongPress = { id -> selectedBookIds = if (id in selectedBookIds) selectedBookIds - id else selectedBookIds + id },
                        atLimit = books.size >= PersonalWordBookStore.MAX_BOOKS)
                }
            }
        }
    }

    if (confirmDeleteBooks) {
        AlertDialog(onDismissRequest = { confirmDeleteBooks = false }, title = { Text("删除词书") },
            text = { Text("删除已选的 ${selectedBookIds.size} 本词书及其全部词条，本机立即生效且无法恢复。其他词书中的同词不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteBooks = false
                    scope.launch {
                        try {
                            val result = store.deleteBooks(selectedBookIds)
                            selectedBookIds = emptySet(); reload()
                            notice = if (result.failed.isEmpty()) null
                            else "部分删除失败：${result.failed.values.distinct().joinToString()}"
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (cause: Exception) { notice = cause.message ?: "删除失败" }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteBooks = false }) { Text("取消") } })
    }
    if (confirmRemoveWords && openBook != null) {
        AlertDialog(onDismissRequest = { confirmRemoveWords = false }, title = { Text("移除词条") },
            text = { Text("从《${openBook.name}》移除已选 ${selectedKeys.size} 个词条。这些词若在其他词书或词典中，不会受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveWords = false
                    val bookId = openBook.id
                    scope.launch {
                        try {
                            val result = store.removeWords(bookId, selectedKeys)
                            selectedKeys = emptySet(); reload()
                            notice = if (result.failed.isEmpty()) null
                            else "部分移除失败：${result.failed.values.distinct().joinToString()}"
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (cause: Exception) { notice = cause.message ?: "移除失败" }
                    }
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveWords = false }) { Text("取消") } })
    }
}

@Composable
private fun CreateBookBody(name: String, existingCount: Int, onNameChange: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("已创建 $existingCount / ${PersonalWordBookStore.MAX_BOOKS} 本", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = name, onValueChange = { if (it.length <= 40) onNameChange(it) },
            label = { Text("词书名（1–40 字）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("保存范围：个人词书仅保存在本机（按账号隔离），不上传服务器；卸载应用或清除数据后将无法恢复。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BooksBody(books: List<PersonalWordBook>, selected: Set<String>,
    onToggle: (String) -> Unit, onOpen: (String) -> Unit, onLongPress: (String) -> Unit, atLimit: Boolean) {
    if (books.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("还没有个人词书。精读时点词收藏，或在这里新建一本。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(if (atLimit) "已达 ${PersonalWordBookStore.MAX_BOOKS} 本上限，删除一本后才能再建。"
            else "长按可多选删除；点击书名进入词条。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(books, key = { it.id }) { book ->
            val isSelected = book.id in selected
            Row(Modifier.fillMaxWidth()
                .clickable { if (selected.isNotEmpty()) onToggle(book.id) else onOpen(book.id) }
                .padding(vertical = 10.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isSelected, onCheckedChange = { onToggle(book.id) })
                Column(Modifier.weight(1f)) {
                    Text(book.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${book.words.size} 词", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun WordListBody(book: PersonalWordBook, selected: Set<String>,
    onToggle: (String) -> Unit, onSelectAll: () -> Unit) {
    if (book.words.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("这本词书还是空的。在精读里点词收藏到这本书即可加入。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSelectAll) {
                Text(if (selected.size == book.words.size) "取消全选" else "全选")
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            items(book.words, key = { it.key }) { word ->
                WordRow(word, checked = word.key in selected, onToggle = { onToggle(word.key) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun WordRow(word: PersonalBookWord, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(word.surface, fontWeight = FontWeight.Medium)
            if (word.lemma.isNotBlank() && !word.lemma.equals(word.surface, true)) {
                Text("原型 ${word.lemma}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (word.meaning.isNotBlank()) Text(word.meaning, style = MaterialTheme.typography.bodySmall)
            if (word.sentence.isNotBlank()) Text("原句：${word.sentence}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
