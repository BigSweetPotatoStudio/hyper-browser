package com.dadigua.hyperbrowser.ui.browser

import com.dadigua.hyperbrowser.browser.BrowserHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryGroupingTest {
    private val labels = HistoryDayLabels(
        today = "Today",
        yesterday = "Yesterday",
        unknownDate = "Unknown date"
    )

    @Test
    fun emptyHistoryHasNoDaySections() {
        assertTrue(groupHistoryByDay(emptyList(), labels, nowMillis = localMillis(2026, Calendar.JUNE, 30)).isEmpty())
    }

    @Test
    fun groupsHistoryByTodayYesterdayAndOlderDates() {
        val now = localMillis(2026, Calendar.JUNE, 30)
        val earlierToday = localMillis(2026, Calendar.JUNE, 30, hour = 8)
        val yesterday = localMillis(2026, Calendar.JUNE, 29)
        val older = localMillis(2026, Calendar.JUNE, 15)

        val sections = groupHistoryByDay(
            items = listOf(
                history("https://today.example", "Today page", now),
                history("https://today.example/docs", "Earlier today", earlierToday),
                history("https://yesterday.example", "Yesterday page", yesterday),
                history("https://older.example", "Older page", older)
            ),
            labels = labels,
            nowMillis = now
        )

        assertEquals(listOf("Today", "Yesterday", dayLabel(older)), sections.map { it.label })
        assertEquals(listOf("Today page", "Earlier today"), sections[0].entries.map { it.title })
        assertEquals(listOf("Yesterday page"), sections[1].entries.map { it.title })
        assertEquals(listOf("Older page"), sections[2].entries.map { it.title })
    }

    @Test
    fun invalidVisitTimeUsesUnknownDateSection() {
        val sections = groupHistoryByDay(
            items = listOf(history("https://unknown.example", "Unknown", 0L)),
            labels = labels,
            nowMillis = localMillis(2026, Calendar.JUNE, 30)
        )

        assertEquals(listOf("Unknown date"), sections.map { it.label })
        assertEquals("Unknown", sections.single().entries.single().title)
    }

    private fun history(url: String, title: String, visitedAt: Long): BrowserHistoryEntry =
        BrowserHistoryEntry(url = url, title = title, visitedAt = visitedAt)

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun dayLabel(timestamp: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
}
