package cn.tonglu.app.ui.books

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.tonglu.app.data.books.*
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.launchOrigin
import cn.tonglu.app.ui.rememberLaunchSource
import kotlinx.coroutines.delay
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

private enum class DetailForm { REQUEST, REVIEW, REPORT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookDetail(
    repository: BooksRepository,
    id: String,
    userId: String,
    revision: Int,
    verifiedAction: (() -> Unit) -> Unit,
    onCampus: () -> Unit,
    onBack: () -> Unit,
    onEdit: (BookListing) -> Unit,
    onChanged: () -> Unit,
    contact: () -> String,
    rememberContact: (String) -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    var reviewed by rememberSaveable(id) { mutableStateOf(false) }
    var form by remember { mutableStateOf<DetailForm?>(null) }
    val drafts = remember { mutableStateMapOf<DetailForm, String>() }
    var buyerContact by remember { mutableStateOf<String?>(null) }
    var score by remember { mutableIntStateOf(5) }
    var reason by remember { mutableStateOf(BookReportReason.PIRACY) }
    var confirmation by remember { mutableStateOf<BookAction?>(null) }
    var cancellationId by remember { mutableStateOf<String?>(null) }
    var refreshedExpiry by remember { mutableStateOf<String?>(null) }
    val operation = bookPageOperation()
    fun closeForm(clearDraft: Boolean) {
        val previous = form
        form = null
        if (clearDraft && previous != null) {
            drafts.remove(previous)
            when (previous) {
                DetailForm.REQUEST -> buyerContact = null
                DetailForm.REVIEW -> score = 5
                DetailForm.REPORT -> reason = BookReportReason.PIRACY
            }
        }
    }
    val mutate = rememberBookMutation(operation, { closeForm(clearDraft = false); onCampus() }) { message ->
        if (message == "你已经评价过这笔交易") {
            reviewed = true
        }
        closeForm(clearDraft = reviewed)
        onChanged()
    }
    val detail = bookLoad(repository, id, revision, attempt) { repository.call { bookDetail(it, id) } }
    val listing = detail.value
    if (listing == null) {
        BooksPage("书籍详情", onBack) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (detail.error == null) BookLoading() else BookErrorText(detail.error.message) { attempt++ }
                BookErrorText(operation.error)
            }
        }
        return
    }
    val seller = listing.sellerId == userId
    val requests = bookLoad(repository, id, seller, revision, attempt) {
        repository.call {
            if (seller) bookRequests(it, id) else myBookRequests(it).filter { row -> row.listingId == id }
        }
    }
    val stats = bookLoad(repository, listing.sellerId, revision, attempt) {
        repository.call { bookStats(it, requireNotNull(listing.sellerId) { "二手书详情缺少卖家 ID" }) }
    }
    val buyerRequest = requests.value.orEmpty().let { rows ->
        rows.firstOrNull { it.status in setOf(BookRequestStatus.PENDING, BookRequestStatus.ACCEPTED, BookRequestStatus.DONE) }
            ?: rows.firstOrNull()
    }
    BookDetailContent(
        listing = listing,
        seller = seller,
        requests = if (seller) requests.value.orEmpty() else listOfNotNull(buyerRequest),
        requestsLoaded = requests.value != null,
        stats = stats.value,
        repository = repository,
        reviewed = reviewed,
        busy = operation.busy,
        error = operation.error ?: requests.error?.message ?: stats.error?.message,
        notice = operation.notice,
        onBack = { if (!operation.busy) onBack() },
        onRetry = { attempt++; operation.error = null },
        onRefresh = { attempt++; operation.notice = null; operation.error = null },
        onReservationExpired = { expiry ->
            if (refreshedExpiry != expiry) {
                refreshedExpiry = expiry
                attempt++
            }
        },
        onAction = { action, request ->
            val apiAction: (suspend BooksApi.(String) -> Unit)? = when (action) {
                BookAction.REQUEST -> {
                    verifiedAction {
                        if (buyerContact == null) buyerContact = contact()
                        form = DetailForm.REQUEST
                    }
                    null
                }
                BookAction.REVIEW -> { verifiedAction { form = DetailForm.REVIEW }; null }
                BookAction.REPORT -> { form = DetailForm.REPORT; null }
                BookAction.EDIT -> { onEdit(listing); null }
                BookAction.ACCEPT -> { bearer -> acceptBookRequest(bearer, checkNotNull(request).id) }
                BookAction.DECLINE -> { bearer -> declineBookRequest(bearer, checkNotNull(request).id) }
                BookAction.WITHDRAW -> { bearer -> cancelBookRequest(bearer, checkNotNull(request).id) }
                BookAction.CANCEL, BookAction.OFF, BookAction.SOLD -> {
                    cancellationId = request?.id
                    confirmation = action
                    null
                }
            }
            if (apiAction != null) mutate({ repository.call { apiAction(it) } }, onChanged)
        },
    )

    confirmation?.let { action ->
        val label = when (action) {
            BookAction.SOLD -> "确认已成交"
            BookAction.OFF -> "确认下架"
            else -> "取消预订"
        }
        AlertDialog(
            onDismissRequest = { if (!operation.busy) confirmation = null },
            title = { Text(label) },
            text = { Text(when (action) {
                BookAction.SOLD -> "确认已完成线下交易后，双方可评价本次交易。"
                BookAction.OFF -> "下架后买家将无法查看此书，正在进行的预订也会结束。"
                else -> "取消后，本次预订结束，这本书会重新上架。"
            }) },
            confirmButton = {
                TextButton(enabled = !operation.busy, onClick = {
                    confirmation = null
                    val requestId = cancellationId
                    mutate({
                        repository.call {
                            when (action) {
                                BookAction.SOLD -> markBookSold(it, id)
                                BookAction.OFF -> takeBookOff(it, id)
                                else -> cancelBookRequest(it, checkNotNull(requestId))
                            }
                        }
                    }, onChanged)
                }) { Text(label) }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("返回") } },
        )
    }

    form?.let { active ->
        val draftText = drafts[active].orEmpty()
        val fieldLimit = if (active == DetailForm.REPORT) 300 else 200
        val title = when (active) {
            DetailForm.REQUEST -> "我想要"
            DetailForm.REVIEW -> "评价这笔交易"
            DetailForm.REPORT -> "举报二手书"
        }
        val sheet = rememberModalBottomSheetState(confirmValueChange = { it != SheetValue.Hidden || !operation.busy })
        ModalBottomSheet(onDismissRequest = { if (!operation.busy) { closeForm(clearDraft = true); operation.error = null } },
            sheetState = sheet,
            containerColor = AppColors.SurfaceOpaque) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                when (active) {
                    DetailForm.REQUEST -> BookField("我的联系方式（必填，仅卖家可见）", buyerContact.orEmpty(),
                        { buyerContact = it }, 60, enabled = !operation.busy)
                    DetailForm.REVIEW -> BookOptions((1..5).toList(), score, { "$it 星" }, enabled = !operation.busy) { score = it }
                    DetailForm.REPORT -> BookOptions(BookReportReason.entries, reason, BookReportReason::label, enabled = !operation.busy) { reason = it }
                }
                BookField(when (active) {
                    DetailForm.REQUEST -> "留言（选填）"
                    DetailForm.REVIEW -> "一句话评价（必填）"
                    DetailForm.REPORT -> "补充说明（选填）"
                }, draftText, { drafts[active] = it }, fieldLimit, multiline = active != DetailForm.REVIEW, enabled = !operation.busy)
                BookErrorText(operation.error)
                BookActionButton(
                    if (operation.busy) "提交中…" else "提交",
                    onClick = {
                        val text = draftText.bookOptional()
                        val contactValue = buyerContact.orEmpty().trim()
                        val reviewScore = score
                        val reportReason = reason
                        mutate({
                            repository.call {
                                when (active) {
                                    DetailForm.REQUEST -> createBookRequest(it, id, CreateBookRequest(text, contactValue))
                                    DetailForm.REVIEW -> reviewBook(it, id, CreateBookReview(reviewScore, text))
                                    DetailForm.REPORT -> reportBook(it, id, ReportBookListing(reportReason, text))
                                }
                            }
                        }, {
                            closeForm(clearDraft = true)
                            when (active) {
                                DetailForm.REQUEST -> { rememberContact(contactValue); onChanged() }
                                DetailForm.REVIEW -> { reviewed = true; operation.notice = "评价已提交" }
                                DetailForm.REPORT -> operation.notice = "举报已提交"
                            }
                        })
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !operation.busy && when (active) {
                        DetailForm.REQUEST -> !buyerContact.isNullOrBlank()
                        DetailForm.REVIEW -> draftText.isNotBlank()
                        DetailForm.REPORT -> true
                    },
                )
            }
        }
    }
}

@Composable
internal fun BookDetailContent(
    listing: BookListing,
    seller: Boolean,
    requests: List<BookRequest>,
    requestsLoaded: Boolean,
    stats: BookStats?,
    repository: BooksRepository? = null,
    reviewed: Boolean = false,
    busy: Boolean = false,
    error: String? = null,
    notice: String? = null,
    example: Boolean = false,
    onBack: () -> Unit,
    onRetry: () -> Unit = {},
    onRefresh: (() -> Unit)? = null,
    onReservationExpired: (String) -> Unit = {},
    onAction: (BookAction, BookRequest?) -> Unit = { _, _ -> },
) {
    val request = requests.firstOrNull()
    val accepted = requests.firstOrNull { it.status == BookRequestStatus.ACCEPTED }
    val done = requests.firstOrNull { it.status == BookRequestStatus.DONE }
    val actionStatus = if (seller) (accepted ?: done)?.status else request?.status
    val actions = bookDetailActions(listing.status, seller, actionStatus, reviewed)
    val expiresAt = remember(listing.reservedUntil) { listing.reservedUntil?.let(::bookExpiryMillis) }
    var remaining by remember(expiresAt) { mutableLongStateOf(expiresAt?.let { it - System.currentTimeMillis() } ?: 0L) }
    val refreshExpired by rememberUpdatedState(onReservationExpired)
    LaunchedEffect(expiresAt) {
        if (expiresAt == null) return@LaunchedEffect
        while (true) {
            remaining = expiresAt - System.currentTimeMillis()
            if (remaining <= 0L) break
            delay(minOf(remaining, 1_000L))
        }
        listing.reservedUntil?.let(refreshExpired)
    }
    val reservationActive = listing.status == BookListingStatus.RESERVED && remaining > 0
    val book = listing.book
    val editOrigin = rememberLaunchSource("book-edit", 20.dp)
    BooksPage("书籍详情", onBack, example = example, actions = {
        if (onRefresh != null) IconButton(onClick = onRefresh, enabled = !busy) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新书籍详情")
        }
        if (BookAction.EDIT in actions) TextButton(
            enabled = !busy,
            onClick = { editOrigin.launch { onAction(BookAction.EDIT, null) } },
            modifier = Modifier.launchOrigin(editOrigin),
        ) { Text("编辑") }
        if (BookAction.REPORT in actions) TextButton(enabled = !busy, onClick = { onAction(BookAction.REPORT, null) }) { Text("举报") }
        if (reservationActive && BookAction.OFF in actions) {
            TextButton(enabled = !busy, onClick = { onAction(BookAction.OFF, null) }) { Text("下架") }
        }
    }) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (reservationActive && (seller || accepted != null)) {
                BookReservationCard(
                    title = if (seller) {
                        accepted?.buyer?.displayName?.let { "已预订给 $it" } ?: "已预订"
                    } else "已预订给你",
                    remaining = remaining,
                    sellerContact = listing.contact.takeUnless { seller },
                )
            }
            val photos = listing.photos.orEmpty()
            val imageCount = photos.size + if (book?.coverUrl != null) 1 else 0
            if (imageCount > 0) {
                val pager = rememberPagerState { imageCount }
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { index ->
                    val cover = book?.coverUrl
                    val mediaId = photos.getOrNull(index - if (cover == null) 0 else 1)
                    val photo = if (mediaId == null || repository == null) null else bookLoad(repository, mediaId) { repository.photoUrl(mediaId) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BookImage(photo?.value ?: cover.takeIf { index == 0 }, "书籍图片 ${index + 1}",
                            Modifier.fillMaxWidth().height(230.dp), ContentScale.Fit)
                        BookErrorText(photo?.error?.message)
                    }
                }
                if (imageCount > 1) Text("${pager.currentPage + 1} / $imageCount", Modifier.align(Alignment.CenterHorizontally),
                    color = AppColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
            }
            BookSection {
                Text(book?.title ?: "已移除的二手书", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("${bookPriceLabel(listing.price)}  ·  ${listing.status.label}", color = AppColors.Accent,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (book != null) {
                    BookDetailLine("作者", book.author)
                    BookDetailLine("出版社", book.publisher)
                    BookDetailLine("版次", book.edition)
                    BookDetailLine("ISBN", book.isbn)
                    BookDetailLine("定价", book.listPrice?.let { "¥${formatBookYuan(it)}" })
                    BookDetailLine("课程", book.courseTags.orEmpty().joinToString(" · ") { it.courseName })
                }
                BookDetailLine("成色", listing.condition?.label)
                Text(if (listing.hasNotes) "有笔记" else "无笔记", color = AppColors.TextSecondary)
                BookDetailLine("备注", listing.note)
                BookDetailLine("建议交易地点", listing.spot)
            }
            listing.seller?.let { owner ->
                BookSection {
                    Text("卖家 · ${owner.displayName}", fontWeight = FontWeight.SemiBold)
                    if (stats != null) {
                        val average = stats.averageScore?.let { String.format(Locale.CHINA, "%.1f", it) } ?: "暂无"
                        Text("成交 ${stats.tradeCount} 笔  ·  评分 $average", color = AppColors.TextSecondary)
                        Text("爽约 ${stats.noShows} 次  ·  反悔 ${stats.sellerBackouts} 次", color = AppColors.TextSecondary)
                    }
                    if (seller) listing.contact?.let { BookContactLine("我的联系方式", it) }
                }
            }
            if (seller) {
                BookSection {
                    Text("买家请求", fontWeight = FontWeight.SemiBold)
                    if (requestsLoaded && requests.isEmpty()) Text("暂无买家请求", color = AppColors.TextSecondary)
                    if (!requestsLoaded && error == null) BookLoading()
                }
                requests.forEach { row ->
                    val rowActions = bookDetailActions(listing.status, true, row.status)
                    BookSection {
                        Text(row.buyer?.displayName ?: "买家", fontWeight = FontWeight.SemiBold)
                        Text(row.status.label, color = AppColors.TextSecondary)
                        BookDetailLine("留言", row.message)
                        row.buyerContact?.let { BookContactLine("买家联系方式", it) }
                        if (BookAction.ACCEPT in rowActions || BookAction.DECLINE in rowActions) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (BookAction.ACCEPT in rowActions) TextButton(enabled = !busy, onClick = { onAction(BookAction.ACCEPT, row) }) { Text("接受") }
                                if (BookAction.DECLINE in rowActions) TextButton(enabled = !busy, onClick = { onAction(BookAction.DECLINE, row) }) { Text("拒绝") }
                            }
                        }
                    }
                }
            } else if (request != null) {
                BookSection {
                    Text("我的请求 · ${request.status.label}", fontWeight = FontWeight.SemiBold)
                    BookDetailLine("留言", request.message)
                }
            }
            BookErrorText(error, onRetry)
            notice?.let { Text(it, color = AppColors.TextSecondary) }
            if (reviewed) Text("已评价", color = AppColors.TextSecondary)
        }
        val primary = when {
            BookAction.SOLD in actions && reservationActive -> BookAction.SOLD
            BookAction.REVIEW in actions -> BookAction.REVIEW
            BookAction.REQUEST in actions && requestsLoaded -> BookAction.REQUEST
            else -> null
        }
        val canCancel = reservationActive && BookAction.CANCEL in actions
        val offInFooter = !reservationActive && BookAction.OFF in actions
        if (primary != null || canCancel || BookAction.WITHDRAW in actions || offInFooter) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (offInFooter) TextButton(enabled = !busy, onClick = { onAction(BookAction.OFF, null) }) { Text("下架") }
                if (BookAction.WITHDRAW in actions) TextButton(enabled = !busy, onClick = { onAction(BookAction.WITHDRAW, request) }) { Text("撤回") }
                if (canCancel) BookSecondaryButton("取消预订", { onAction(BookAction.CANCEL, accepted) }, enabled = !busy)
                primary?.let { action ->
                    val label = when (action) {
                        BookAction.SOLD -> "已成交"
                        BookAction.REVIEW -> "评价"
                        else -> "我想要"
                    }
                    BookActionButton(if (busy) "处理中…" else label, { onAction(action, null) }, enabled = !busy)
                }
            }
        }
    }
}

@Composable
private fun BookReservationCard(title: String, remaining: Long, sellerContact: String?) {
    val minutes = (remaining + 59_999) / 60_000
    val time = if (minutes >= 60) "剩 ${minutes / 60} 小时 ${minutes % 60} 分钟" else "剩 $minutes 分钟"
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
            Text("$title · $time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            sellerContact?.let { BookContactLine("卖家联系方式", it) }
        }
    }
}

@Composable
private fun BookDetailLine(label: String, text: String?) {
    if (!text.isNullOrBlank()) Text("$label · $text", color = AppColors.TextSecondary)
}

@Composable
private fun BookContactLine(label: String, text: String) {
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label · $text", Modifier.weight(1f), color = AppColors.TextPrimary)
        TextButton(
            onClick = { clipboard.setText(AnnotatedString(text)) },
            colors = ButtonDefaults.textButtonColors(contentColor = AppColors.TextPrimary),
        ) { Text("复制") }
    }
}

private fun bookExpiryMillis(value: String): Long? = try {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }.parse(value)?.time
} catch (error: ParseException) {
    Log.w("TongluBooks", "无法读取预订时间（${error.javaClass.simpleName}）")
    null
}
