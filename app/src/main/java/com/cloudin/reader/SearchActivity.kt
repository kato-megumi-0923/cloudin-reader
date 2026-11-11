package com.cloudin.reader

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray

/** 搜索书架：按书名/作者过滤，保留最近搜索记录。 */
class SearchActivity : AppCompatActivity() {
    private val paper = 0xFFFAF7F1.toInt()
    private val ink = 0xFF332C26.toInt()
    private val muted = 0xFF786D64.toInt()
    private val accent = 0xFFA94F3B.toInt()
    private val chipBg = 0xFFF0EBE5.toInt()

    private lateinit var input: EditText
    private lateinit var historyRow: LinearLayout
    private lateinit var historyBlock: LinearLayout
    private lateinit var empty: TextView
    private lateinit var list: RecyclerView
    private val adapter = ResultAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var books: List<Book> = emptyList()
    private var submittedQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.useAuroraSystemBars()
        books = BookStore.load(this).sortedByDescending { it.lastRead }

        val backdrop = View(this).apply { setBackgroundResource(R.drawable.bg_aurora) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = glassBackButton(ink, backdrop, paper) { finish() }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(10) })

        input = EditText(this).apply {
            hint = "搜索书名 / 作者"
            textSize = 16f
            setTextColor(ink)
            setHintTextColor(muted)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = rounded(chipBg, 20)
            setPadding(dp(16), 0, dp(16), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    submitQuery(text.toString())
                    hideKeyboard()
                    true
                } else false
            }
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                pending?.let { handler.removeCallbacks(it) }
                val q = s?.toString().orEmpty()
                pending = Runnable { applyQuery(q, record = false) }.also {
                    handler.postDelayed(it, 160)
                }
            }
        })
        toolbar.addView(input, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(4)
            marginEnd = dp(16)
        })
        root.addView(toolbar, LinearLayout.LayoutParams(-1, dp(56)))

        // 搜索记录
        historyBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val historyHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        historyHead.addView(label("最近搜索", 12f, muted), LinearLayout.LayoutParams(0, -2, 1f))
        val clear = label("清除记录", 12f, accent).apply {
            setOnClickListener {
                SearchHistory.clear(this@SearchActivity)
                updateHistory()
            }
        }
        historyHead.addView(clear)
        historyBlock.addView(historyHead)
        val scroll = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        historyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), dp(8))
        }
        scroll.addView(historyRow)
        historyBlock.addView(scroll)
        root.addView(historyBlock)

        empty = label("输入关键词开始搜索", 14f, muted).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        list = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@SearchActivity, gridSpanCount())
            adapter = this@SearchActivity.adapter
            clipToPadding = false
            setPadding(dp(10), dp(4), dp(10), dp(16))
            visibility = View.GONE
        }
        val body = FrameLayout(this)
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(FrameLayout(this).apply {
            addView(backdrop, FrameLayout.LayoutParams(-1, -1))
            addView(root, FrameLayout.LayoutParams(-1, -1))
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val preset = intent.getStringExtra(EXTRA_QUERY)
        if (!preset.isNullOrBlank()) {
            input.setText(preset)
            input.setSelection(preset.length)
            applyQuery(preset, record = true)
        } else {
            updateHistory()
            input.post {
                input.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun applyQuery(query: String, record: Boolean) {
        val q = query.trim()
        val showHistory = q.isEmpty()
        historyBlock.visibility = if (showHistory) View.VISIBLE else View.GONE
        if (showHistory) {
            updateHistory()
            list.visibility = View.GONE
            empty.visibility = View.GONE
            adapter.submit(emptyList())
            return
        }
        val result = books.filter {
            it.title.contains(q, ignoreCase = true) || it.author.contains(q, ignoreCase = true)
        }.sortedWith(
            compareByDescending<Book> { it.title.startsWith(q, ignoreCase = true) }
                .thenByDescending { it.lastRead }
        )
        adapter.submit(result)
        list.visibility = if (result.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (result.isEmpty()) "没有找到「$q」相关的书" else ""
        if (record && result.isNotEmpty()) {
            submittedQuery = q
            SearchHistory.add(this, q)
            updateHistory()
        }
    }

    private fun submitQuery(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        SearchHistory.add(this, q)
        submittedQuery = q
        applyQuery(q, record = false)
    }

    private fun updateHistory() {
        val items = SearchHistory.load(this)
        historyRow.removeAllViews()
        historyBlock.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        items.forEach { term ->
            historyRow.addView(
                label(term, 13f, ink).apply {
                    background = rounded(chipBg, 16)
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    setOnClickListener {
                        input.setText(term)
                        input.setSelection(term.length)
                        submitQuery(term)
                    }
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
            )
        }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    override fun onPause() {
        super.onPause()
        val q = input.text?.toString()?.trim().orEmpty()
        if (q.isNotEmpty() && submittedQuery != q) SearchHistory.add(this, q)
    }

    private inner class ResultAdapter : RecyclerView.Adapter<ResultHolder>() {
        private var items = emptyList<Book>()

        fun submit(next: List<Book>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultHolder {
            val cell = LinearLayout(this@SearchActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            val cover = BookCoverView(this@SearchActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }
            val title = TextView(this@SearchActivity).apply {
                textSize = 13f
                setTextColor(ink)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(2), dp(6), dp(2), 0)
            }
            cell.addView(cover)
            cell.addView(title)
            return ResultHolder(cell, cover, title)
        }

        override fun onBindViewHolder(holder: ResultHolder, position: Int) {
            val book = items[position]
            holder.cover.bind(book)
            holder.title.text = book.title
            holder.itemView.setOnClickListener {
                SearchHistory.add(this@SearchActivity, input.text?.toString()?.trim().orEmpty())
                startActivity(
                    Intent(this@SearchActivity, ReaderActivity::class.java)
                        .putExtra("bookId", book.id)
                )
            }
        }
    }

    private class ResultHolder(view: View, val cover: BookCoverView, val title: TextView) :
        RecyclerView.ViewHolder(view)

    private fun label(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    /** 与书架同一套列数算法：按屏幕宽度算，平板 8 列、手机 4 列。 */
    private fun gridSpanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 114).coerceIn(4, 12)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val EXTRA_QUERY = "query"

        fun intent(context: Context, query: String? = null): Intent =
            Intent(context, SearchActivity::class.java).apply {
                if (!query.isNullOrBlank()) putExtra(EXTRA_QUERY, query)
            }
    }
}

/** 最近搜索记录，最多保留 10 条。 */
internal object SearchHistory {
    private const val PREFS = "search"
    private const val KEY = "history"
    private const val LIMIT = 10

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, term: String) {
        val value = term.trim()
        if (value.isEmpty()) return
        val current = load(context).toMutableList()
        current.removeAll { it.equals(value, ignoreCase = true) }
        current.add(0, value)
        save(context, current.take(LIMIT))
    }

    fun clear(context: Context) = save(context, emptyList())

    private fun save(context: Context, items: List<String>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, arr.toString()).apply()
    }
}
