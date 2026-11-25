package cn.tonglu.app.data.diary

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object DiaryDates {
    private const val DAY_MS = 86_400_000L
    private const val FIRST_MONDAY = 4L
    private val utc = TimeZone.getTimeZone("UTC")
    val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun dayAt(millis: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        Math.floorDiv(millis + zone.getOffset(millis), DAY_MS)

    fun week(day: Long): Long = Math.floorDiv(day - FIRST_MONDAY, 7L)
    fun weekStart(week: Long): Long = FIRST_MONDAY + week * 7

    fun atTime(day: Long, hour: Int, minute: Int, zone: TimeZone = TimeZone.getDefault()): Long {
        val date = Calendar.getInstance(utc).apply { timeInMillis = day * DAY_MS }
        return Calendar.getInstance(zone).apply {
            clear()
            set(date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH), hour, minute)
        }.timeInMillis
    }

    fun date(day: Long): String = format("yyyy-MM-dd", utc).format(Date(day * DAY_MS))
    fun groupLabel(day: Long, today: Long): String = when (day) {
        today -> "今天"
        today - 1 -> "昨天"
        else -> {
            val date = Date(day * DAY_MS)
            val year = format("yyyy", utc)
            val pattern = if (year.format(date) == year.format(Date(today * DAY_MS))) "M月d日" else "yyyy年M月d日"
            format(pattern, utc).format(date)
        }
    }
    fun time(millis: Long): String = format("HH:mm", TimeZone.getDefault()).format(Date(millis))
    fun timestamp(millis: Long): String = format("yyyy-MM-dd HH:mm", TimeZone.getDefault()).format(Date(millis))

    private fun format(pattern: String, zone: TimeZone) =
        SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = zone }

    fun surveyAt(settings: DiarySettings, week: Long, zone: TimeZone = TimeZone.getDefault()): Long =
        atTime(weekStart(week) + settings.surveyWeekday - 1, settings.surveyHour, settings.surveyMinute, zone)
}
