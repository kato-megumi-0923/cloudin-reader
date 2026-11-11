package com.cloudin.reader

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ReadingBackgroundActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.useAuroraSystemBars()
        val ink = getColor(R.color.ink)
        val backdrop = View(this).apply { setBackgroundResource(R.drawable.bg_aurora) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val heading = FrameLayout(this)
        heading.addView(readerLabel(getString(R.string.reader_background), ink, 19f).apply { setTypeface(null, Typeface.BOLD) },
            FrameLayout.LayoutParams(-1, -1))
        heading.addView(glassBackButton(ink, backdrop) { finish() },
            FrameLayout.LayoutParams(readerDp(44), readerDp(44), Gravity.START or Gravity.CENTER_VERTICAL)
            .apply { marginStart = readerDp(14) })
        root.addView(heading, LinearLayout.LayoutParams(-1, readerDp(58)))
        root.addView(readerLabel(getString(R.string.reader_system_backgrounds), ink, 13f).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(readerDp(19), 0, 0, 0)
        }, LinearLayout.LayoutParams(-1, readerDp(34)))
        val grid = GridLayoutManager(this, 2)
        val recycler = RecyclerView(this).apply {
            layoutManager = grid
            adapter = BackgroundAdapter()
            setPadding(readerDp(12), 0, readerDp(12), readerDp(10))
            clipToPadding = false
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) {
                    // RecyclerView ignores a layout request while dispatching its current layout.
                    post {
                        val columns = ((width - paddingLeft - paddingRight) / readerDp(140)).coerceIn(2, 8)
                        if (grid.spanCount != columns) grid.spanCount = columns
                    }
                }
            }
        }
        root.addView(recycler, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(FrameLayout(this).apply {
            addView(backdrop, FrameLayout.LayoutParams(-1, -1))
            addView(root, FrameLayout.LayoutParams(-1, -1))
        })
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private class Card(view: View, val preview: FrameLayout, val image: ImageView, val sample: TextView,
                       val name: TextView, val use: TextView) : RecyclerView.ViewHolder(view)

    private inner class BackgroundAdapter : RecyclerView.Adapter<Card>() {
        override fun getItemCount() = ReaderPrefs.themes.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Card {
            val box = LinearLayout(this@ReadingBackgroundActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = readerPill(Color.WHITE, 12)
                clipToOutline = true
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(readerDp(5), readerDp(5), readerDp(5), readerDp(5)) }
            }
            val preview = FrameLayout(this@ReadingBackgroundActivity)
            val image = ImageView(this@ReadingBackgroundActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val sample = readerLabel(getString(R.string.reader_background_sample), Color.BLACK, 12f).apply {
                typeface = Typeface.SERIF
                gravity = Gravity.TOP or Gravity.START
                setPadding(readerDp(9), readerDp(11), readerDp(9), readerDp(8))
                setLineSpacing(readerDp(3).toFloat(), 1f)
            }
            preview.addView(image, FrameLayout.LayoutParams(-1, -1))
            preview.addView(sample, FrameLayout.LayoutParams(-1, -1))
            box.addView(preview, LinearLayout.LayoutParams(-1, readerDp(222)))
            val name = readerLabel("", 0xFF211F1D.toInt()).apply { setTypeface(null, Typeface.BOLD) }
            box.addView(name, LinearLayout.LayoutParams(-1, readerDp(35)))
            val use = readerLabel("", 0xFF211F1D.toInt(), 12f).apply {
                setPadding(readerDp(9), 0, readerDp(9), 0)
                isFocusable = true
            }
            box.addView(use, LinearLayout.LayoutParams(-2, readerDp(44)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = readerDp(8)
            })
            return Card(box, preview, image, sample, name, use)
        }

        override fun onBindViewHolder(holder: Card, position: Int) {
            val palette = ReaderPrefs.themes[position]
            val selected = ReaderPrefs.theme(this@ReadingBackgroundActivity) == palette.id
            holder.preview.setBackgroundColor(palette.paper)
            holder.image.setImageResource(palette.texture)
            holder.sample.setTextColor(palette.ink)
            val name = getString(palette.nameRes)
            holder.name.text = name
            holder.use.text = if (selected) getString(R.string.reader_theme_in_use) else getString(R.string.reader_theme_use)
            holder.use.contentDescription = getString(if (selected) R.string.reader_theme_in_use_named else R.string.reader_theme_use_named, name)
            holder.use.setTextColor(if (selected) 0xFFB52E3A.toInt() else 0xFF211F1D.toInt())
            holder.use.background = readerPill(if (selected) Color.WHITE else 0xFFF4F3F2.toInt()).apply {
                if (selected) setStroke(readerDp(1), 0xFFEFB9BE.toInt())
            }
            holder.use.setGlassClickListener {
                val previous = ReaderPrefs.theme(this@ReadingBackgroundActivity)
                if (previous != palette.id) {
                    ReaderPrefs.setTheme(this@ReadingBackgroundActivity, palette.id)
                    setResult(RESULT_OK)
                    val previousPosition = ReaderPrefs.themes.indexOfFirst { it.id == previous }
                    if (previousPosition >= 0) notifyItemChanged(previousPosition)
                    val newPosition = holder.bindingAdapterPosition
                    if (newPosition != RecyclerView.NO_POSITION) notifyItemChanged(newPosition)
                }
            }
        }
    }
}
