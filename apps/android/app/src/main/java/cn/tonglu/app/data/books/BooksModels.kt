package cn.tonglu.app.data.books

enum class BookCondition(val label: String) {
    NEW("全新"), LIKE_NEW("近全新"), GOOD("良好"), WORN("有磨损"),
}

enum class BookListingStatus(val label: String) {
    ON_SALE("在售"), RESERVED("已预订"), SOLD("已成交"), OFF("已下架"), REMOVED("已移除"),
}

enum class BookRequestStatus(val label: String) {
    PENDING("等待卖家处理"), ACCEPTED("已接受"), DECLINED("已拒绝"),
    CANCELLED("已取消"), EXPIRED("已过期"), DONE("已成交"),
}

enum class BookReportReason(val label: String) {
    PIRACY("盗版或复印件"), DIGITAL_COPY("电子版"), NON_BOOK("非书籍"), DIVERSION("导流"),
}

data class BookMetadata(
    val isbn: String,
    val title: String,
    val author: String? = null,
    val publisher: String? = null,
    val edition: String? = null,
    val listPrice: Int? = null,
    val coverUrl: String? = null,
    val courseTags: List<BookCourseTag>? = null,
)

data class BookCourseTag(val courseName: String)
data class BookUser(val id: String, val displayName: String)

data class BookListing(
    val id: String,
    val status: BookListingStatus,
    // “我想要的”里的已移除书目只返回 id 和 status。
    val sellerId: String? = null,
    val book: BookMetadata? = null,
    val seller: BookUser? = null,
    val price: Int = 0,
    val condition: BookCondition? = null,
    val hasNotes: Boolean = false,
    val photos: List<String>? = null,
    val note: String? = null,
    val spot: String? = null,
    val contact: String? = null,
    val reservedUntil: String? = null,
)

data class BookPage(val items: List<BookListing>, val nextCursor: String?)

data class BookRequest(
    val id: String,
    val listingId: String,
    val status: BookRequestStatus,
    val message: String? = null,
    val buyerContact: String? = null,
    val buyer: BookUser? = null,
    val listing: BookListing? = null,
)

data class BookStats(val tradeCount: Int, val averageScore: Double?, val noShows: Int, val sellerBackouts: Int)
data class BookWanted(val id: String, val isbn: String?, val keyword: String?)

data class CreateBookListing(
    val isbn: String,
    val title: String?,
    val author: String?,
    val publisher: String?,
    val edition: String?,
    val listPrice: Int?,
    val price: Int,
    val condition: BookCondition,
    val hasNotes: Boolean,
    val photos: List<String>,
    val courseNames: List<String>,
    val note: String?,
    val spot: String?,
    val contact: String,
)

data class UpdateBookListing(val price: Int, val note: String, val spot: String, val contact: String)
data class CreateBookRequest(val message: String?, val buyerContact: String)
data class CreateBookWanted(val isbn: String?, val keyword: String?)
data class CreateBookReview(val score: Int, val text: String?)
data class ReportBookListing(val reason: BookReportReason, val details: String?)
data class BeginBookPhoto(val size: Int, val contentType: String)
data class BookPhotoTicket(val id: String, val uploadUrl: String, val method: String, val contentType: String)
data class BookPhoto(val id: String, val status: String, val publicUrl: String)
