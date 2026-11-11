package com.cloudin.reader

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

class ReadingHistoryActivity : AppCompatActivity() {
    private enum class Filter(val title: Int, val empty: Int) {
        IN_PROGRESS(R.string.history_in_progress, R.string.history_no_in_progress),
        COMPLETED(R.string.history_completed, R.string.history_no_completed)
    }

    private var filter = Filter.IN_PROGRESS
    private val ink get() = getColor(R.color.shelf_title)
    private val muted get() = getColor(R.color.shelf_secondary_ink)
    private lateinit var count: TextView
    private lateinit var empty: TextView
    private lateinit var list: RecyclerView
    private val adapter = HistoryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        filter = if (savedInstanceState?.getBoolean("completed") == true) Filter.COMPLETED else Filter.IN_PROGRESS
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.stats_enter, R.anim.stats_under_exit)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.stats_under_enter, R.anim.stats_exit)
        }
        window.useAuroraSystemBars()
        val backdrop = View(this).apply { setBackgroundResource(R.drawable.bg_aurora) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = glassBackButton(ink, backdrop) { finish() }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            marginStart = dp(10)
            marginEnd = dp(8)
        })
        toolbar.addView(label(getString(R.string.history_title), 17f, ink).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(View(this).apply { setBackgroundColor(getColor(R.color.shelf_separator)) },
            LinearLayout.LayoutParams(-1, dp(1)))

        val segments = readerSegments(
            Filter.entries.map { getString(it.title) }, filter.ordinal, ReaderPrefs.THEME_PURE_WHITE,
            backdrop, getString(R.string.history_title), trackColor = getColor(R.color.shelf_track)
        ) { index ->
            filter = Filter.entries[index]
            refresh(resetScroll = true)
        }
        root.addView(segments, LinearLayout.LayoutParams(-1, dp(44)).apply {
            setMargins(dp(16), dp(14), dp(16), 0)
        })
        count = label("", 12f, muted).apply { setPadding(dp(16), dp(11), dp(16), dp(10)) }
        root.addView(count)
        val body = FrameLayout(this)
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ReadingHistoryActivity)
            adapter = this@ReadingHistoryActivity.adapter
            addItemDecoration(DividerItemDecoration(this@ReadingHistoryActivity, DividerItemDecoration.VERTICAL))
        }
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty = label("", 14f, muted).apply { gravity = Gravity.CENTER }
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(FrameLayout(this).apply {
            addView(backdrop, FrameLayout.LayoutParams(-1, -1))
            addView(root, FrameLayout.LayoutParams(-1, -1))
        })
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("completed", filter == Filter.COMPLETED)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.stats_under_enter, R.anim.stats_exit)
    }

    private fun refresh(resetScroll: Boolean = false) {
        val times = ReadingStats.bookTimes(this)
        val books = BookStore.load(this).filter {
            (it.lastRead > 0L || it.progress > 0f || it.id in times) &&
                (it.progress >= 0.999f) == (filter == Filter.COMPLETED)
        }.sortedByDescending { it.lastRead }
        count.text = getString(R.string.history_book_count, books.size)
        empty.setText(filter.empty)
        empty.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        adapter.submit(books, times)
        if (resetScroll && books.isNotEmpty()) list.scrollToPosition(0)
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<HistoryHolder>() {
        private var books = emptyList<Book>()
        private var times = emptyMap<String, Long>()

        fun submit(items: List<Book>, durations: Map<String, Long>) {
            books = items
            times = durations
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = books.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryHolder = HistoryHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_reading_history, parent, false)
        )

        override fun onBindViewHolder(holder: HistoryHolder, position: Int) {
            val book = books[position]
            val millis = times[book.id] ?: 0L
            holder.duration.text = when {
                millis <= 0L -> getString(R.string.history_no_duration)
                millis < 3000L -> getString(R.string.history_under_tenth_minute)
                else -> getString(R.string.history_duration_minutes, millis / 60_000.0)
            }
            holder.title.text = book.title
            val percent = if (book.progress >= 0.999f) 100 else (book.progress * 100).roundToInt().coerceIn(0, 99)
            holder.percent.text = "$percent%"
            holder.progress.progress = percent
            holder.cover.bind(book)
            holder.itemView.setOnClickListener {
                startActivity(Intent(this@ReadingHistoryActivity, ReaderActivity::class.java).putExtra("bookId", book.id))
            }
        }
    }

    private class HistoryHolder(view: View) : RecyclerView.ViewHolder(view) {
        val duration: TextView = view.findViewById(R.id.historyDuration)
        val title: TextView = view.findViewById(R.id.historyBookTitle)
        val percent: TextView = view.findViewById(R.id.historyPercent)
        val progress: ProgressBar = view.findViewById(R.id.historyProgress)
        val cover: BookCoverView = view.findViewById(R.id.historyCover)
    }

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
