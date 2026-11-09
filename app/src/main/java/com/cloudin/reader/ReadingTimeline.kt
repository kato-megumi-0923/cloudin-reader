package com.cloudin.reader

import android.content.Context
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

internal class ReadingTimeline(private val hours: Map<LocalDate, LongArray>) {
    val daysRead: Int get() = hours.size
    val earliestDay: LocalDate? get() = hours.keys.minOrNull()

    fun hoursOn(date: LocalDate): LongArray = hours[date] ?: LongArray(24)
    fun on(date: LocalDate): Long = hours[date]?.sum() ?: 0L

    fun week(today: LocalDate): List<LocalDate> {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return List(7) { monday.plusDays(it.toLong()) }
    }

    fun month(month: YearMonth): List<LocalDate> = List(month.lengthOfMonth()) { month.atDay(it + 1) }

    fun streak(today: LocalDate): Int {
        var date = if (on(today) > 0) today else today.minusDays(1)
        var days = 0
        while (on(date) > 0) {
            days++
            date = date.minusDays(1)
        }
        return days
    }
}

internal object ReadingStats {
    private const val PREFS = "reading_stats"
    private const val TOTAL = "totalMillis"
    private const val HOUR = "hour:"
    private const val BOOK = "book:"

    fun total(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(TOTAL, 0L)

    fun bookTimes(context: Context): Map<String, Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (key, value) ->
            if (key.startsWith(BOOK) && value is Long) key.removePrefix(BOOK) to value else null
        }.toMap()

    @Synchronized
    fun record(context: Context, endMillis: Long, durationMillis: Long, zone: ZoneId = ZoneId.systemDefault(), bookId: String? = null) {
        if (durationMillis <= 0) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit().putLong(TOTAL, prefs.getLong(TOTAL, 0L) + durationMillis)
        if (bookId != null) {
            val key = "$BOOK$bookId"
            editor.putLong(key, prefs.getLong(key, 0L) + durationMillis)
        }
        val increments = mutableMapOf<String, Long>()
        var cursor = endMillis - durationMillis
        while (cursor < endMillis) {
            val instant = Instant.ofEpochMilli(cursor)
            val local = instant.atZone(zone)
            val nextLocalHour = local.toLocalDateTime().truncatedTo(ChronoUnit.HOURS).plusHours(1)
            val nextHour = ZonedDateTime.ofLocal(nextLocalHour, zone, local.offset).toInstant().toEpochMilli()
            val nextTransition = zone.rules.nextTransition(instant)?.instant?.toEpochMilli() ?: Long.MAX_VALUE
            val boundary = minOf(endMillis, nextHour, nextTransition)
            // 防御：夏令时/异常时区下 boundary 可能等于 cursor，会让循环原地打转
            if (boundary <= cursor) break
            val key = "$HOUR${local.toLocalDate()}:${local.hour}"
            increments[key] = (increments[key] ?: 0L) + boundary - cursor
            cursor = boundary
        }
        increments.forEach { (key, delta) -> editor.putLong(key, prefs.getLong(key, 0L) + delta) }
        editor.apply()
    }

    fun read(context: Context): ReadingTimeline {
        val result = mutableMapOf<LocalDate, LongArray>()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (key, value) ->
            if (!key.startsWith(HOUR) || value !is Long || value <= 0L) return@forEach
            val suffix = key.removePrefix(HOUR)
            val hour = suffix.substringAfterLast(':').toIntOrNull() ?: return@forEach
            if (hour !in 0..23) return@forEach
            val day = runCatching { LocalDate.parse(suffix.substringBeforeLast(':')) }.getOrNull() ?: return@forEach
            result.getOrPut(day) { LongArray(24) }[hour] = value
        }
        return ReadingTimeline(result)
    }
}
