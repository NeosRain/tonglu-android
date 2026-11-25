package cn.tonglu.app.ui.books

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cn.tonglu.app.R
import cn.tonglu.app.data.books.*
import cn.tonglu.app.ui.AppColors
import cn.tonglu.app.ui.AppTheme
import cn.tonglu.app.ui.ThemeSystemBars
import cn.tonglu.app.ui.appMaterialColors
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 走查使用独立进程与本地示例，不提交网络请求。 */
class BooksPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dark = intent.getStringExtra("theme") == "dark"
        val page = intent.getStringExtra("page") ?: "home"
        val previewContext = object : ContextWrapper(this) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("qa_books_$name", mode)
        }
        AppTheme.setEnabled(previewContext, dark)
        val course = "概率论与数理统计"
        val metadata = BookMetadata(
            "9787302511854", "示例 · 概率论与数理统计",
            author = "示例作者", publisher = "示例大学出版社", edition = "第 3 版", listPrice = 4800,
            coverUrl = "android.resource://$packageName/${R.drawable.qa_books_cover}",
            courseTags = listOf(BookCourseTag(course)),
        )
        val listing = BookListing(
            "example-listing", BookListingStatus.ON_SALE, "example-seller", metadata,
            seller = BookUser("example-seller", "松针同学"), price = 1850,
            condition = BookCondition.LIKE_NEW, hasNotes = true,
            note = "书页干净，第二章有少量铅笔笔记。", spot = "图书馆一楼大厅", contact = "示例联系方式",
        )
        val reservation = listing.copy(
            status = BookListingStatus.RESERVED,
            reservedUntil = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(System.currentTimeMillis() + 47 * 60 * 60_000L)),
        )
        val buyer = BookUser("example-buyer", "苔米同学")
        val pending = BookRequest("example-request", listing.id, BookRequestStatus.PENDING,
            message = "你好，请问明天中午可以在图书馆见面吗？", buyerContact = "示例买家联系方式", buyer = buyer)
        val accepted = pending.copy(id = "example-accepted", status = BookRequestStatus.ACCEPTED)
        val stats = BookStats(8, 4.8, 0, 1)
        val titles = listOf(
            listing,
            reservation.copy(id = "example-reserved", book = metadata.copy(title = "示例 · 高等数学（上册）", edition = "第 7 版",
                courseTags = listOf(BookCourseTag("高等数学"))), price = 1200, condition = BookCondition.GOOD),
            listing.copy(id = "example-free", book = metadata.copy(title = "示例 · 大学英语阅读", edition = "第 2 版",
                courseTags = listOf(BookCourseTag("大学英语"))), price = 0, condition = BookCondition.WORN),
        )
        setContent {
            val controller = remember(dark) { ThemeController(if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light) }
            MiuixTheme(colors = controller.currentColors()) {
                MaterialTheme(colorScheme = appMaterialColors()) {
                    CompositionLocalProvider(LocalContentColor provides AppColors.TextPrimary, LocalContext provides previewContext) {
                        ThemeSystemBars()
                        when (page) {
                            "detail-buyer", "详情-买家" -> BookDetailContent(listing.copy(contact = null), false, emptyList(),
                                true, stats, example = true, onBack = ::finish)
                            "detail-accepted", "详情-被接受买家" -> BookDetailContent(reservation, false,
                                listOf(accepted.copy(buyerContact = null, buyer = null)),
                                true, stats, example = true, onBack = ::finish)
                            "detail-seller", "详情-卖家" -> BookDetailContent(reservation, true, listOf(accepted),
                                true, stats, example = true, onBack = ::finish)
                            "publish", "发布" -> BookPublish(null, listOf(course, "大学英语"), course,
                                onBack = ::finish, onCampus = {}, onSaved = {}, exampleBook = metadata)
                            "mine", "我的" -> BooksMine(null, 0, ::finish, {}, {}, {}, examples = titles)
                            else -> BooksHome(null, null, 0, ::finish, {}, {}, {}, {}, {}, examples = titles)
                        }
                    }
                }
            }
        }
    }
}
