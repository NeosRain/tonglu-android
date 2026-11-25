package cn.tonglu.app.data.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookRulesTest {
    @Test
    fun priceRoundTripUsesExactCentsIncludingZero() {
        val expected = mapOf(0 to "0", 5 to "0.05", 120 to "1.2", 1849 to "18.49", Int.MAX_VALUE to "21474836.47")
        for ((cents, yuan) in expected) {
            assertEquals(yuan, formatBookYuan(cents))
            assertEquals(cents, parseBookCents(yuan))
        }
    }

    @Test
    fun priceRejectsFractionalCentsNegativeValuesAndOverflow() {
        listOf("0.001", "-1", "21474836.48", "1e2", "").forEach { assertNull(parseBookCents(it)) }
    }

    @Test
    fun isbnNormalizesFullWidthAndConvertsIsbn10ToIsbn13() {
        assertEquals("9780804429573", normalizeBookIsbn(" ０－８０４４－２９５７－ｘ "))
        assertEquals("9780306406157", normalizeBookIsbn("978-0-306-40615-7"))
    }

    @Test
    fun isbnRejectsBadChecksumsAndNonBookEanCodes() {
        listOf("9780306406158", "0-8044-2957-0", "4006381333931").forEach { assertNull(normalizeBookIsbn(it)) }
    }

    @Test
    fun onlyCampusAuthorizationFailurePromptsVerification() {
        assertEquals(BookError(BOOK_CAMPUS_REQUIRED, campusRequired = true), bookHttpError(403, "CAMPUS_VERIFICATION_REQUIRED"))
        assertEquals(BookError("账号已被禁言"), bookHttpError(403, "ACCOUNT_MUTED", "账号已被禁言"))
    }

    @Test
    fun detailStateConflictRetainsServerMessageAndSignalsRefresh() {
        assertEquals(BookError("只能接受待处理请求", conflict = true), bookHttpError(409, message = "只能接受待处理请求"))
    }

    @Test
    fun contactAndQuotaErrorsKeepSpecificUserGuidance() {
        val contact = "联系方式请填在专门的栏里"
        assertEquals(BookError(contact), bookHttpError(400, message = contact))
        val quota = "今天的上架额度已用完"
        assertEquals(BookError(quota), bookHttpError(429, "BOOK_DAILY_LIMIT", quota))
    }

    @Test
    fun buyerCanRequestWithdrawOrCancelAccordingToCurrentState() {
        assertEquals(setOf(BookAction.REQUEST, BookAction.REPORT), bookDetailActions(BookListingStatus.ON_SALE, false))
        assertEquals(setOf(BookAction.WITHDRAW, BookAction.REPORT),
            bookDetailActions(BookListingStatus.RESERVED, false, BookRequestStatus.PENDING))
        assertEquals(setOf(BookAction.CANCEL, BookAction.REPORT),
            bookDetailActions(BookListingStatus.RESERVED, false, BookRequestStatus.ACCEPTED))
        assertEquals(setOf(BookAction.REPORT), bookDetailActions(BookListingStatus.RESERVED, false))
    }

    @Test
    fun sellerCanAcceptOnlyWhileOnSaleAndSoldOnlyAfterReservation() {
        assertEquals(setOf(BookAction.OFF, BookAction.EDIT, BookAction.ACCEPT, BookAction.DECLINE),
            bookDetailActions(BookListingStatus.ON_SALE, true, BookRequestStatus.PENDING))
        assertEquals(setOf(BookAction.OFF, BookAction.DECLINE),
            bookDetailActions(BookListingStatus.RESERVED, true, BookRequestStatus.PENDING))
        assertEquals(setOf(BookAction.OFF, BookAction.SOLD, BookAction.CANCEL),
            bookDetailActions(BookListingStatus.RESERVED, true, BookRequestStatus.ACCEPTED))
    }

    @Test
    fun onlyCompletedParticipantsCanReviewOnce() {
        assertEquals(setOf(BookAction.REVIEW), bookDetailActions(BookListingStatus.SOLD, true, BookRequestStatus.DONE))
        assertEquals(setOf(BookAction.REVIEW, BookAction.REPORT), bookDetailActions(BookListingStatus.SOLD, false, BookRequestStatus.DONE))
        assertEquals(emptySet<BookAction>(), bookDetailActions(BookListingStatus.SOLD, true, BookRequestStatus.DONE, reviewed = true))
        assertEquals(setOf(BookAction.REPORT), bookDetailActions(BookListingStatus.SOLD, false, BookRequestStatus.DECLINED))
        assertEquals(emptySet<BookAction>(), bookDetailActions(BookListingStatus.OFF, true, BookRequestStatus.PENDING))
    }
}
