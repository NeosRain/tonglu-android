package cn.tonglu.app.ui.books

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.books.*
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.OverlayDismissGuard
import cn.tonglu.app.ui.OverlayLayerHost
import cn.tonglu.app.ui.launchOrigin
import cn.tonglu.app.ui.rememberLaunchSource
import kotlinx.coroutines.launch
import java.io.IOException

private const val MAX_BOOK_PHOTOS = 6
private const val MAX_BOOK_COURSES = 3
private data class BookDraftPhoto(val uri: Uri, val media: BookPhoto? = null, val error: BookError? = null)

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun BookPublish(
    repository: BooksRepository?,
    courses: List<String>,
    course: String?,
    onBack: () -> Unit,
    onCampus: () -> Unit,
    onSaved: (String?) -> Unit,
    onConflict: () -> Unit = {},
    editing: BookListing? = null,
    exampleBook: BookMetadata? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val edit = editing != null
    val example = exampleBook != null
    val initialBook = editing?.book ?: exampleBook
    val operation = bookPageOperation()
    var isbn by remember { mutableStateOf(initialBook?.isbn.orEmpty()) }
    var confirmedIsbn by remember { mutableStateOf(initialBook?.isbn) }
    var metadata by remember { mutableStateOf(initialBook) }
    var title by remember { mutableStateOf(initialBook?.title.orEmpty()) }
    var author by remember { mutableStateOf(initialBook?.author.orEmpty()) }
    var publisher by remember { mutableStateOf(initialBook?.publisher.orEmpty()) }
    var edition by remember { mutableStateOf(initialBook?.edition.orEmpty()) }
    var listPrice by remember { mutableStateOf(initialBook?.listPrice?.let(::formatBookYuan).orEmpty()) }
    var price by remember { mutableStateOf(editing?.price?.let(::formatBookYuan) ?: if (example) "18.5" else "") }
    var condition by remember { mutableStateOf(if (example) BookCondition.LIKE_NEW else BookCondition.GOOD) }
    var notes by remember { mutableStateOf(example) }
    var note by remember { mutableStateOf(editing?.note.orEmpty()) }
    var spot by remember { mutableStateOf(editing?.spot ?: if (example) "图书馆一楼大厅" else "") }
    var contact by remember { mutableStateOf(editing?.contact.orEmpty()) }
    var courseNames by remember { mutableStateOf(listOfNotNull(course)) }
    var customCourse by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf(emptyList<BookDraftPhoto>()) }
    var scanning by remember { mutableStateOf(false) }

    fun addPhotos(result: List<Uri>) {
        val selection = (photos + result.map { BookDraftPhoto(it) }).distinctBy(BookDraftPhoto::uri)
        photos = selection.take(MAX_BOOK_PHOTOS)
        if (selection.size > MAX_BOOK_PHOTOS) operation.error = "最多添加 6 张照片，已保留前 6 张"
    }
    val available = MAX_BOOK_PHOTOS - photos.size
    val multiPickerContract = remember(available) { ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, available)) }
    val photoPicker = rememberLauncherForActivityResult(multiPickerContract, ::addPhotos)
    val singlePhotoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { addPhotos(listOf(it)) }
    }

    fun lookup(input: String) {
        val normalized = normalizeBookIsbn(input)
        if (normalized == null) {
            operation.error = "ISBN 格式或校验位不正确"
            return
        }
        scope.launch {
            operation.run(onCampus, {}, {
                if (repository != null) repository.lookup(normalized)
                else exampleBook?.takeIf { it.isbn == normalized }
            }) { book ->
                isbn = normalized
                confirmedIsbn = normalized
                metadata = book
                title = book?.title.orEmpty()
                author = book?.author.orEmpty()
                publisher = book?.publisher.orEmpty()
                edition = book?.edition.orEmpty()
                listPrice = book?.listPrice?.let(::formatBookYuan).orEmpty()
            }
        }
    }

    val priceCents = parseBookCents(price)?.takeIf { it <= MAX_BOOK_PRICE }
    val listPriceCents = parseBookCents(listPrice)
    val ready = confirmedIsbn != null
    val enabled = !operation.busy
    val readyProblem = when {
        !edit && !ready -> "请先扫描或输入 ISBN 并查找书目"
        !edit && title.isBlank() -> "请填写书名"
        price.isBlank() && contact.isBlank() -> "请填写交易价格和联系方式"
        price.isBlank() -> "请填写交易价格"
        priceCents == null -> "请输入有效的交易价格"
        contact.isBlank() -> "请填写联系方式"
        !edit && listPrice.isNotBlank() && listPriceCents == null -> "请修正书目定价或留空"
        else -> null
    }
    BooksPage(if (edit) "编辑二手书" else "发布二手书", { if (!operation.busy) onBack() }, example = example) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!edit) BookSection {
                Text("书目信息", fontWeight = FontWeight.SemiBold)
                if (!ready) {
                    BookField("ISBN（必填）", isbn, { isbn = it; operation.error = null }, 40, enabled = enabled)
                    val scanOrigin = rememberLaunchSource("book-isbn-scan", 20.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(enabled = enabled, onClick = { scanOrigin.launch { scanning = true } },
                            modifier = Modifier.launchOrigin(scanOrigin)) { Text("扫 ISBN") }
                        BookActionButton(if (operation.busy) "查找中…" else "查找书目", { lookup(isbn) },
                            enabled = enabled && isbn.isNotBlank())
                    }
                } else {
                    Text("ISBN · $confirmedIsbn", color = AppColors.TextSecondary)
                    if (metadata != null) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        val description = listOf(author, publisher, edition).filter(String::isNotBlank).joinToString(" · ")
                        if (description.isNotEmpty()) Text(description, color = AppColors.TextSecondary)
                        if (listPrice.isNotBlank()) Text("定价 ¥$listPrice", color = AppColors.TextSecondary)
                    } else {
                        Text("未找到书目，请手动填写", color = AppColors.TextSecondary)
                        BookField("书名（必填）", title, { title = it }, 200, enabled = enabled)
                        BookField("作者", author, { author = it }, 120, enabled = enabled)
                        BookField("出版社", publisher, { publisher = it }, 120, enabled = enabled)
                        BookField("版次", edition, { edition = it }, 60, enabled = enabled)
                        BookField("定价（元）", listPrice, { listPrice = it }, 16, enabled = enabled,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        if (listPrice.isNotBlank() && listPriceCents == null) BookErrorText("定价最多填写两位小数")
                    }
                    BookSecondaryButton("更换 ISBN", { confirmedIsbn = null; operation.error = null }, enabled = enabled)
                }
            }
            if (edit || ready) {
                BookSection {
                    if (edit) Text(initialBook?.title.orEmpty(), fontWeight = FontWeight.SemiBold)
                    BookField("交易价格（元，填 0 为赠送）", price, { price = it }, 12, enabled = enabled,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    if (price.isNotBlank() && priceCents == null) BookErrorText("请输入 0–1000 元，最多两位小数")
                    if (!edit) {
                        Text("成色", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BookCondition.entries.forEach { choice ->
                                FilterChip(
                                    selected = condition == choice,
                                    onClick = { condition = choice },
                                    enabled = enabled,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    label = { Text(choice.label) },
                                    shape = RoundedCornerShape(50),
                                    colors = FilterChipDefaults.filterChipColors(
                                        labelColor = AppColors.TextPrimary,
                                        selectedContainerColor = AppColors.Accent,
                                        selectedLabelColor = AppColors.OnAccent,
                                    ),
                                    border = if (condition == choice) null else BorderStroke(1.dp, AppColors.TextSecondary),
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("有笔记", Modifier.weight(1f))
                            Switch(checked = notes, onCheckedChange = { notes = it }, enabled = enabled)
                        }
                    }
                }
                if (!edit) {
                    BookSection {
                        Text("照片（${photos.size}/$MAX_BOOK_PHOTOS）", fontWeight = FontWeight.SemiBold)
                        photos.forEachIndexed { index, photo ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                BookImage(photo.uri, "第 ${index + 1} 张照片", Modifier.size(56.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(if (photo.media != null) "已上传" else "照片 ${index + 1}", color = AppColors.TextSecondary)
                                    BookErrorText(photo.error?.message)
                                }
                                TextButton(enabled = enabled, onClick = { photos = photos.filterNot { it.uri == photo.uri } }) {
                                    Text("移除")
                                }
                            }
                        }
                        if (available > 0) {
                            Surface(
                                onClick = {
                                    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    if (available == 1) singlePhotoPicker.launch(request) else photoPicker.launch(request)
                                },
                                enabled = enabled,
                                modifier = Modifier.size(80.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = AppColors.CanvasOpaque,
                                contentColor = if (enabled) AppColors.TextPrimary else AppColors.TextSecondary,
                                border = BorderStroke(1.dp, if (enabled) AppColors.TextSecondary else AppColors.Divider),
                            ) {
                                Column(
                                    Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = null)
                                    Text("选照片", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    BookSection {
                        Text("课程（${courseNames.size}/$MAX_BOOK_COURSES）", fontWeight = FontWeight.SemiBold)
                        courseNames.forEach { name ->
                            TextButton(enabled = enabled, onClick = { courseNames = courseNames - name }) { Text("$name  ×") }
                        }
                        if (courseNames.size < MAX_BOOK_COURSES) {
                            val choices = courses.filterNot(courseNames::contains)
                            if (choices.isNotEmpty()) BookOptions(listOf("") + choices, "", { it.ifEmpty { "从我的课表选课" } }, enabled = enabled) {
                                if (it.isNotBlank()) courseNames = courseNames + it
                            }
                            BookField("也可手动填课程名", customCourse, { customCourse = it }, 100, enabled = enabled)
                            TextButton(enabled = enabled && customCourse.trim().isNotEmpty() && customCourse.trim() !in courseNames,
                                onClick = { courseNames = courseNames + customCourse.trim(); customCourse = "" }) { Text("添加课程") }
                        }
                    }
                }
                BookSection {
                    BookField("备注（500 字以内）", note, { note = it }, 500, multiline = true, enabled = enabled)
                    BookField("建议交易地点", spot, { spot = it }, 40, enabled = enabled)
                    BookField("联系方式（必填，仅预订买家可见）", contact, { contact = it }, 60, enabled = enabled)
                }
            }
            BookErrorText(operation.error)
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!operation.busy && readyProblem != null) {
                Text(readyProblem, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            BookActionButton(
                when {
                    example -> "发布（示例）"
                    operation.busy && (edit || ready) -> "提交中…"
                    edit -> "保存"
                    else -> "发布"
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !example && readyProblem == null,
                onClick = {
                    val repo = checkNotNull(repository) { "提交二手书需要登录" }
                    val cents = checkNotNull(priceCents) { "提交二手书需要有效价格" }
                    val targets = photos
                    scope.launch {
                        operation.run(onCampus, { if (edit) onConflict() }, {
                            if (editing != null) {
                                repo.call { updateBook(it, editing.id, UpdateBookListing(cents, note.trim(), spot.trim(), contact.trim())) }
                                null
                            } else {
                                var uploadFailure: BookError? = null
                                for (target in targets.filter { it.media == null }) {
                                    val updated = bookResult { repo.upload(context, target.uri) }.fold(
                                        onSuccess = { target.copy(media = it, error = null) },
                                        onFailure = {
                                            val error = it.toBookError()
                                            uploadFailure = error
                                            target.copy(error = error)
                                        },
                                    )
                                    photos = photos.map { if (it.uri == target.uri) updated else it }
                                    if (uploadFailure?.campusRequired == true) break
                                }
                                uploadFailure?.let {
                                    if (it.campusRequired) onCampus()
                                    throw IOException("有照片未上传成功，请查看照片提示后重试")
                                }
                                val input = CreateBookListing(
                                    isbn = checkNotNull(confirmedIsbn) { "发布二手书需要确认 ISBN" },
                                    title = title.trim().takeIf { metadata == null },
                                    author = author.bookOptional().takeIf { metadata == null },
                                    publisher = publisher.bookOptional().takeIf { metadata == null },
                                    edition = edition.bookOptional().takeIf { metadata == null },
                                    listPrice = listPriceCents.takeIf { metadata == null },
                                    price = cents, condition = condition, hasNotes = notes,
                                    photos = photos.mapNotNull { it.media?.id }, courseNames = courseNames,
                                    note = note.bookOptional(), spot = spot.bookOptional(), contact = contact.trim(),
                                )
                                repo.call { createBook(it, input) }.id
                            }
                        }, onSaved)
                    }
                },
            )
        }
    }

    if (scanning) {
        OverlayLayerHost(onClose = { OverlayDismissGuard.arm(); scanning = false }, dialog = true) { close ->
            BookIsbnScanner(close) { decoded ->
                isbn = decoded
                close()
                lookup(decoded)
            }
        }
    }
}
