package com.cloudin.reader

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class ReadingStatsActivity : AppCompatActivity() {
    private enum class Page(val title: String) {
        OVERVIEW("阅读统计"), DAILY("每日阅读"), MONTHLY("月度阅读"), BOOKS("已读书籍")
    }

    private val ink = 0xFF332C26.toInt()
    private val muted = 0xFF786D64.toInt()
    private val paper = 0xFFFAF7F1.toInt()
    private val warm = 0xFFF7EEE6.toInt()
    private val stroke = 0xFFE9DED4.toInt()
    private val accent = 0xFFC96950.toInt()
    private val lightDay = 0xFFF5E5DC.toInt()
    private val mediumDay = 0xFFF0BFAE.toInt()
    private val darkDay = 0xFFE99A7E.toInt()
    private val dateLabel = DateTimeFormatter.ofPattern("M.d")
    private var page = Page.OVERVIEW
    private lateinit var root: LinearLayout
    private lateinit var header: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.stats_enter, R.anim.stats_under_exit)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.stats_under_enter, R.anim.stats_exit)
        }
        page = savedInstanceState?.getString("stats_page")?.let { Page.valueOf(it) } ?: Page.OVERVIEW
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        root = column()
        val toolbar = row().apply { gravity = Gravity.CENTER_VERTICAL }
        val back = ImageButton(this).apply {
            contentDescription = "返回"
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(ink)
            val attr = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, attr, true)
            setBackgroundResource(attr.resourceId)
            setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header = label("", 17f, ink).apply { setTypeface(typeface, Typeface.BOLD) }
        toolbar.addView(header)
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(View(this).apply { setBackgroundColor(stroke) }, LinearLayout.LayoutParams(-1, dp(1)))
        scroll = ScrollView(this).apply { isFillViewport = true }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (page == Page.OVERVIEW) finish() else show(Page.OVERVIEW, animate = true)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        show(page)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("stats_page", page.name)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.stats_under_enter, R.anim.stats_exit)
    }

    private fun show(destination: Page, animate: Boolean = false) {
        page = destination
        val timeline = ReadingStats.read(this)
        val today = LocalDate.now()
        header.text = page.title
        root.setBackgroundColor(if (page == Page.OVERVIEW) warm else paper)
        scroll.removeAllViews()
        val content = when (page) {
            Page.OVERVIEW -> overview(timeline, today)
            Page.DAILY -> daily(timeline, today)
            Page.MONTHLY -> monthly(timeline, today)
            Page.BOOKS -> readBooks()
        }
        scroll.addView(content, FrameLayout.LayoutParams(-1, -2))
        scroll.scrollTo(0, 0)
        if (animate) content.animatePageEntry(forward = destination != Page.OVERVIEW)
    }

    private fun overview(data: ReadingTimeline, today: LocalDate): LinearLayout = column().apply {
        setPadding(dp(16), dp(12), dp(16), dp(16))
        val books = BookStore.load(this@ReadingStatsActivity)
        val completed = books.count { it.progress >= 0.999f }
        val bookCard = card { show(Page.BOOKS, animate = true) }
        bookCard.addView(label("已读书籍", 13f, muted))
        bookCard.addView(metricRow("已读  $completed / ${books.size} 本书", true), gap(24))
        val progressTrack = FrameLayout(this@ReadingStatsActivity).apply { setBackgroundColor(lightDay) }
        if (books.isNotEmpty() && completed > 0) {
            progressTrack.addView(View(this@ReadingStatsActivity).apply { setBackgroundColor(accent) }, FrameLayout.LayoutParams(0, -1))
            progressTrack.addOnLayoutChangeListener { view, l, _, r, _, oldL, _, oldR, _ ->
                if (r - l != oldR - oldL) (view as FrameLayout).getChildAt(0).layoutParams =
                    FrameLayout.LayoutParams((view.width.toLong() * completed / books.size).toInt(), -1)
            }
        }
        bookCard.addView(progressTrack, LinearLayout.LayoutParams(-1, dp(9)).apply { topMargin = dp(10) })
        addView(bookCard)

        val month = YearMonth.from(today)
        val monthDays = data.month(month)
        var accumulated = 0L
        val monthly = LongArray(monthDays.size) { index ->
            accumulated += data.on(monthDays[index])
            accumulated
        }
        val pair = row()
        val dayCard = chartCard("今日阅读", hourLabel(data.on(today)),
            ReadingChartView(this@ReadingStatsActivity, data.hoursOn(today), mapOf(0 to "00:00", 23 to "23:59"), true, true), Page.DAILY)
        val monthCard = chartCard("本月阅读", hourLabel(monthly.last()),
            ReadingChartView(this@ReadingStatsActivity, monthly,
                mapOf(0 to shortDate(monthDays.first()), monthDays.lastIndex to shortDate(monthDays.last())), false, true, today.dayOfMonth), Page.MONTHLY)
        pair.addView(dayCard, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(8) })
        pair.addView(monthCard, LinearLayout.LayoutParams(0, -1, 1f))
        addView(pair, gap(12))
        addView(statRow("阅读天数", "${data.daysRead} 天"), gap(12))
        addView(statRow("持续阅读", "${data.streak(today)} 天"), gap(12))
    }

    private fun chartCard(name: String, value: String, chart: View, destination: Page): LinearLayout = card { show(destination, animate = true) }.apply {
        addView(label(name, 13f, muted))
        addView(metricRow(value, true), gap(16))
        addView(chart, LinearLayout.LayoutParams(-1, dp(37)))
    }

    private fun statRow(name: String, value: String): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        background = panel()
        setPadding(dp(15), dp(9), dp(15), dp(9))
        minimumHeight = dp(54)
        addView(label(name, 13f, muted), LinearLayout.LayoutParams(0, -2, 1f))
        addView(metric(value))
    }

    private fun daily(data: ReadingTimeline, today: LocalDate): LinearLayout = column().apply {
        setPadding(dp(24), dp(15), dp(24), dp(24))
        val week = data.week(today)
        val month = data.month(YearMonth.from(today))
        addView(chartSection("今日", data.hoursOn(today), mapOf(4 to "04:00", 12 to "12:00", 20 to "20:00"), true))
        addView(chartSection("本周", week.map(data::on).toLongArray(), week.mapIndexed { i, date -> i to date.format(dateLabel) }.toMap(), false), gap(34))
        addView(chartSection("本月", month.map(data::on).toLongArray(),
            listOf(0, 14, month.lastIndex).associateWith { month[it].format(dateLabel) }, false), gap(34))
    }

    private fun chartSection(name: String, values: LongArray, labels: Map<Int, String>, bars: Boolean): View = column().apply {
        val heading = row().apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(name, 18f, ink).apply { setTypeface(typeface, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(label(if (bars) "每小时阅读时长（分钟）" else "每日阅读时长（分钟）", 12f, muted))
        addView(heading)
        val chart = ReadingChartView(this@ReadingStatsActivity, values, labels, bars, false).apply {
            contentDescription = "$name，${durationLabel(values.sum())}"
        }
        addView(chart, LinearLayout.LayoutParams(-1, dp(144)).apply { topMargin = dp(12) })
    }

    private fun monthly(data: ReadingTimeline, today: LocalDate): LinearLayout = column().apply {
        setPadding(dp(16), dp(12), dp(16), dp(16))
        val current = YearMonth.from(today)
        val earliest = minOf(current.minusMonths(2), data.earliestDay?.let(YearMonth::from) ?: current)
        var month = earliest
        while (!month.isAfter(current)) {
            addView(calendarMonth(month, data), if (month == earliest) gap(0) else gap(28))
            month = month.plusMonths(1)
        }
        post {
            if (page == Page.MONTHLY && parent === scroll) scroll.scrollTo(0, (height - scroll.height).coerceAtLeast(0))
        }
    }

    private fun calendarMonth(month: YearMonth, data: ReadingTimeline): View = column().apply {
        addView(label("${month.year}年${month.monthValue}月", 18f, ink).apply { setTypeface(typeface, Typeface.BOLD) })
        val weekdays = row()
        for (day in listOf("日", "一", "二", "三", "四", "五", "六")) {
            weekdays.addView(label(day, 12f, muted).apply { gravity = Gravity.CENTER }, cell(36))
        }
        addView(weekdays, gap(10))
        val offset = month.atDay(1).dayOfWeek.value % 7
        val rows = (offset + month.lengthOfMonth() + 6) / 7
        for (week in 0 until rows) {
            val days = row()
            for (weekday in 0..6) {
                val day = week * 7 + weekday - offset + 1
                val container = FrameLayout(this@ReadingStatsActivity)
                if (day in 1..month.lengthOfMonth()) {
                    val date = month.atDay(day)
                    val time = data.on(date)
                    val dayLabel = label(day.toString(), 15f, ink).apply {
                        gravity = Gravity.CENTER
                        val fill = when {
                            time == 0L -> Color.TRANSPARENT
                            time < 1_800_000L -> lightDay
                            time < 10_800_000L -> mediumDay
                            else -> darkDay
                        }
                        background = rounded(fill, 7)
                        contentDescription = "${month.monthValue}月${day}日，${durationLabel(time)}"
                        setOnClickListener {
                            AlertDialog.Builder(this@ReadingStatsActivity)
                                .setTitle("${month.monthValue}月${day}日")
                                .setMessage("阅读时长：${durationLabel(time)}")
                                .setPositiveButton(R.string.ok, null).show()
                        }
                    }
                    container.addView(dayLabel, FrameLayout.LayoutParams(dp(36), dp(34), Gravity.CENTER))
                }
                days.addView(container, cell(48))
            }
            addView(days)
        }
        val legend = row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        listOf(lightDay to "少于30分钟", mediumDay to "30分钟至3小时", darkDay to "3小时及以上").forEach { (color, name) ->
            legend.addView(View(this@ReadingStatsActivity).apply { background = rounded(color, 9) },
                LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginStart = dp(8); marginEnd = dp(4) })
            legend.addView(label(name, 10f, muted))
        }
        addView(legend, gap(8))
    }

    private fun readBooks(): View = column().apply {
        setPadding(dp(16), dp(12), dp(16), dp(16))
        val finished = BookStore.load(this@ReadingStatsActivity).filter { it.progress >= 0.999f }.sortedByDescending { it.lastRead }
        if (finished.isEmpty()) addView(label("还没有读完的书", 15f, muted).apply { setPadding(dp(12), dp(16), dp(12), 0) })
        finished.forEach { book ->
            addView(card {
                startActivity(Intent(this@ReadingStatsActivity, ReaderActivity::class.java).putExtra("bookId", book.id))
            }.apply { addView(label(book.title, 16f, ink)) }, gap(8))
        }
    }

    private fun card(action: () -> Unit): LinearLayout = column().apply {
        setPadding(dp(15), dp(14), dp(15), dp(14))
        background = RippleDrawable(ColorStateList.valueOf(0x14000000), panel(), null)
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun metricRow(value: String, arrow: Boolean): View = row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(metric(value), LinearLayout.LayoutParams(0, -2, 1f))
        if (arrow) addView(label("›", 19f, muted).apply { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(19), dp(22)))
    }

    private fun metric(value: String) = label(value, 23f, ink).apply { typeface = Typeface.SERIF }
    private fun panel() = rounded(paper, 5).apply { setStroke(dp(1), stroke) }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun hourLabel(millis: Long): String = when {
        millis <= 0L -> "0 分钟"
        millis < 360_000L -> "${(millis / 60_000f).toInt().coerceAtLeast(1)} 分钟"
        else -> String.format(java.util.Locale.getDefault(), "%.1f 小时", millis / 3_600_000.0)
    }
    private fun durationLabel(millis: Long): String = if (millis == 0L) "0 分钟"
        else if (millis < 60_000L) "不足 1 分钟"
        else if (millis < 3_600_000L) "${millis / 60_000} 分钟"
        else "${millis / 3_600_000} 小时 ${millis / 60_000 % 60} 分钟"
    private fun shortDate(date: LocalDate) = "%02d/%02d".format(date.monthValue, date.dayOfMonth)
    private fun label(text: String, size: Float, color: Int) = TextView(this).apply { this.text = text; textSize = size; setTextColor(color) }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
    private fun gap(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    private fun cell(height: Int) = LinearLayout.LayoutParams(0, dp(height), 1f)
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
