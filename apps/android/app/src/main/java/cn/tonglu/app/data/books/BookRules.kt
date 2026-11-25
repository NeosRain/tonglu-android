package cn.tonglu.app.data.books

import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

const val MAX_BOOK_PRICE = 100_000
const val BOOK_CAMPUS_REQUIRED = "完成校园认证后可用"

fun formatBookYuan(cents: Int): String = BigDecimal.valueOf(cents.toLong(), 2).stripTrailingZeros().toPlainString()

fun parseBookCents(input: String): Int? {
    val value = Normalizer.normalize(input.trim(), Normalizer.Form.NFKC)
    if (!value.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) return null
    return value.toBigDecimal().movePointRight(2).takeIf { it <= BigDecimal.valueOf(Int.MAX_VALUE.toLong()) }?.toInt()
}

fun normalizeBookIsbn(input: String): String? {
    val value = Normalizer.normalize(input, Normalizer.Form.NFKC)
        .filterNot { it.isWhitespace() || it == '-' }.uppercase(Locale.ROOT)
    fun checkDigit(prefix: String): Int =
        (10 - prefix.withIndex().sumOf { (i, char) -> char.digitToInt() * if (i % 2 == 0) 1 else 3 } % 10) % 10

    return when {
        value.matches(Regex("[0-9]{9}[0-9X]")) -> {
            val sum = value.withIndex().sumOf { (i, char) -> (if (char == 'X') 10 else char.digitToInt()) * (10 - i) }
            if (sum % 11 != 0) return null
            val prefix = "978" + value.take(9)
            prefix + checkDigit(prefix)
        }
        value.matches(Regex("97[89][0-9]{10}")) && value.last().digitToInt() == checkDigit(value.take(12)) -> value
        else -> null
    }
}

data class BookError(val message: String, val campusRequired: Boolean = false, val conflict: Boolean = false)

fun bookHttpError(status: Int, code: String? = null, message: String? = null): BookError {
    val detail = message?.takeIf { it.any { char -> char in '\u4e00'..'\u9fff' } }
    return when {
        status == 401 -> BookError("登录已过期，请重新登录")
        status == 403 && (code == "CAMPUS_VERIFICATION_REQUIRED" || detail == "请先完成校园认证") ->
            BookError(BOOK_CAMPUS_REQUIRED, campusRequired = true)
        status == 403 -> BookError(detail ?: "当前账号无权执行此操作")
        status == 409 -> BookError(detail ?: "交易状态已变化，请刷新后重试", conflict = true)
        status == 429 || code == "BOOK_DAILY_LIMIT" -> BookError(detail ?: "操作额度已用完，请稍后重试")
        status == 400 -> BookError(detail ?: "填写内容不符合要求，请检查后重试")
        status == 404 -> BookError(detail ?: "这条二手书记录已不可用")
        status == 413 -> BookError(detail ?: "图片超过上传大小限制")
        else -> BookError(detail ?: "二手书服务暂时不可用，请稍后重试")
    }
}

enum class BookAction { REQUEST, WITHDRAW, CANCEL, ACCEPT, DECLINE, SOLD, EDIT, OFF, REVIEW, REPORT }

fun bookDetailActions(
    status: BookListingStatus,
    seller: Boolean,
    request: BookRequestStatus? = null,
    reviewed: Boolean = false,
): Set<BookAction> = buildSet {
    val open = status == BookListingStatus.ON_SALE || status == BookListingStatus.RESERVED
    if (seller) {
        if (open) add(BookAction.OFF)
        if (status == BookListingStatus.ON_SALE) add(BookAction.EDIT)
        if (open && request == BookRequestStatus.PENDING) {
            if (status == BookListingStatus.ON_SALE) add(BookAction.ACCEPT)
            add(BookAction.DECLINE)
        }
        if (status == BookListingStatus.RESERVED && request == BookRequestStatus.ACCEPTED) {
            add(BookAction.SOLD)
            add(BookAction.CANCEL)
        }
    } else {
        if (open && request == BookRequestStatus.PENDING) add(BookAction.WITHDRAW)
        if (status == BookListingStatus.RESERVED && request == BookRequestStatus.ACCEPTED) add(BookAction.CANCEL)
        if (status == BookListingStatus.ON_SALE && request !in setOf(BookRequestStatus.PENDING, BookRequestStatus.ACCEPTED)) {
            add(BookAction.REQUEST)
        }
        if (open || status == BookListingStatus.SOLD) add(BookAction.REPORT)
    }
    if (!reviewed && status == BookListingStatus.SOLD && request == BookRequestStatus.DONE) add(BookAction.REVIEW)
}
