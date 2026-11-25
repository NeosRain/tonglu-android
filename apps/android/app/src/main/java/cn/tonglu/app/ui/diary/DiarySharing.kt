package cn.tonglu.app.ui.diary

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import cn.tonglu.app.data.diary.DiaryData
import cn.tonglu.app.data.diary.DiaryDates
import cn.tonglu.app.data.diary.IdeaReview
import cn.tonglu.app.data.diary.ProhibitionStats
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale

internal data class DiaryShareFile(val file: File, val mimeType: String, val title: String)
internal data class DiaryShareColors(val canvas: Int, val surface: Int, val text: Int, val secondary: Int, val accent: Int)

@Composable
internal fun rememberDiaryShare(): (() -> DiaryShareFile) -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return { build ->
        scope.launch {
            try {
                val share = withContext(Dispatchers.IO) { build() }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", share.file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = share.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri(share.title, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(intent, share.title))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(context, "没有可以接收分享的应用", Toast.LENGTH_LONG).show()
            } catch (_: IOException) {
                Toast.makeText(context, "生成分享文件失败，请重试", Toast.LENGTH_LONG).show()
            }
        }
    }
}

internal fun exportDiary(context: Context, data: DiaryData, json: Boolean): DiaryShareFile {
    val ideas = data.ideas.filter { it.review != IdeaReview.DISCARD }.sortedByDescending { it.recordedAt }
    val summaries = data.summaries.sortedByDescending { it.recordedAt }
    val file = diaryShareFile(context, "diary", if (json) ".json" else ".txt")
    if (json) {
        val exported = mapOf(
            "version" to 1,
            "example" to data.example,
            "ideas" to ideas.map { idea ->
                mapOf("text" to idea.text, "recordedAt" to idea.recordedAt, "tags" to idea.tags, "review" to idea.review?.name)
            },
            "summaries" to summaries.map { summary ->
                mapOf(
                    "recordedAt" to summary.recordedAt,
                    "fromDate" to DiaryDates.date(summary.fromDay),
                    "throughDate" to DiaryDates.date(summary.throughDay),
                    "text" to summary.text,
                    "completedPlans" to summary.plans.filter { it.done }.map { it.text },
                    "unfinishedPlans" to summary.plans.filterNot { it.done }.map { it.text },
                    "ideas" to summary.ideas.map { mapOf("text" to it.text, "review" to it.review.name) },
                )
            },
        )
        file.writeText(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(exported))
    } else {
        file.writeText(buildString {
            appendLine(if (data.example) "灵感与小结（示例）" else "灵感与小结")
            appendLine()
            appendLine("灵感")
            ideas.forEach {
                appendLine("${DiaryDates.timestamp(it.recordedAt)}  ${it.text}")
                if (it.tags.isNotEmpty()) appendLine("标签：${it.tags.joinToString("、")}")
                if (it.review == IdeaReview.PLAN) appendLine("已转成计划")
                appendLine()
            }
            appendLine("小结")
            summaries.forEach { summary ->
                appendLine(summaryRange(summary.fromDay, summary.throughDay))
                if (summary.text.isNotBlank()) appendLine(summary.text)
                summary.plans.forEach { appendLine("${if (it.done) "已完成" else "未完成"}：${it.text}") }
                summary.ideas.forEach { appendLine("${it.review.label}：${it.text}") }
                appendLine()
            }
        })
    }
    return DiaryShareFile(file, if (json) "application/json" else "text/plain", "分享灵感与小结")
}

internal fun shareRank(
    context: Context,
    rank: ProhibitionStats.Rank,
    example: Boolean,
    colors: DiaryShareColors,
): DiaryShareFile {
    val width = 840
    val margin = 72
    val textWidth = width - margin * 2
    fun layout(text: String, size: Float, color: Int, bold: Boolean, lines: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.color = color
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(8f, 1f)
            .setMaxLines(lines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }
    val header = if (example) "完不成的事 · 示例" else "完不成的事"
    val parts = listOf(
        layout("$header  /  第 ${rank.position} 名", 32f, colors.accent, true, 2),
        layout(rank.item.text, 48f, colors.text, true, 12),
        layout(rankEstimate(rank.stats.estimate), 29f, colors.secondary, false, 2),
        layout(rankStatus(rank), 29f, colors.secondary, false, 2),
    )
    val spacing = 22
    val height = parts.sumOf { it.height } + spacing * (parts.size - 1) + margin * 2
    val file = diaryShareFile(context, "rank", ".png")
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(colors.canvas)
        canvas.drawRoundRect(24f, 24f, width - 24f, height - 24f, 34f, 34f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colors.surface
        })
        canvas.translate(margin.toFloat(), margin.toFloat())
        parts.forEach {
            it.draw(canvas)
            canvas.translate(0f, (it.height + spacing).toFloat())
        }
        file.outputStream().use {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("分享图片未写入")
        }
    } finally {
        bitmap.recycle()
    }
    return DiaryShareFile(file, "image/png", "分享这一条")
}

private fun diaryShareFile(context: Context, prefix: String, extension: String): File {
    val directory = File(context.cacheDir, "diary-share")
    directory.mkdirs()
    return File.createTempFile(prefix, extension, directory)
}

internal fun rankEstimate(estimate: Double) = String.format(Locale.ROOT, "估计每周 %.1f 次", estimate)

internal fun rankStatus(rank: ProhibitionStats.Rank): String {
    val previous = rank.previousPosition
    val change = when {
        previous == null -> "新上榜"
        previous > rank.position -> "↑ ${previous - rank.position}"
        previous < rank.position -> "↓ ${rank.position - previous}"
        else -> "名次持平"
    }
    return "$change · 连续守住 ${rank.stats.zeroStreak} 周"
}
