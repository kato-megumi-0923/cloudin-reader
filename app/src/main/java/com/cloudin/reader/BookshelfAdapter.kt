package com.cloudin.reader

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

enum class ShelfLayout { GRID, LIST }

class BookshelfAdapter(
    private var layout: ShelfLayout,
    private val onClick: (Book) -> Unit,
    private val onLongClick: (Book) -> Unit,
    private val onFavorite: (Book) -> Unit,
    private val onFolderClick: (BookGroup?) -> Unit,
    private val onFolderLongClick: (BookGroup) -> Unit
) : RecyclerView.Adapter<BookshelfAdapter.Holder>() {

    /** 第一项为文件夹；null 表示「新建分组」占位卡片。 */
    private val folders = mutableListOf<Pair<BookGroup?, Int>>()
    private val items = mutableListOf<Book>()
    private val selected = mutableSetOf<String>()
    var selecting = false
        private set

    var onSelectionChanged: ((Int) -> Unit)? = null

    fun submit(folderList: List<Pair<BookGroup?, Int>>, list: List<Book>) {
        folders.clear()
        folders.addAll(folderList)
        items.clear()
        items.addAll(list)
        selected.retainAll(items.map { it.id }.toSet())
        notifyDataSetChanged()
    }

    fun setLayout(newLayout: ShelfLayout) {
        if (layout == newLayout) return
        layout = newLayout
        notifyDataSetChanged()
    }

    fun selectedIds(): List<String> = selected.toList()

    fun enterSelection(book: Book) {
        selecting = true
        selected.clear()
        selected.add(book.id)
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selected.size)
    }

    fun startSelecting() {
        selecting = true
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun selectAll() {
        selecting = true
        selected.clear()
        selected.addAll(items.map { it.id })
        notifyDataSetChanged()
        onSelectionChanged?.invoke(selected.size)
    }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    fun allSelected(): Boolean = selected.size == items.size && items.isNotEmpty()

    fun exitSelection() {
        selecting = false
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged?.invoke(0)
    }

    private fun toggle(book: Book) {
        if (!selected.remove(book.id)) selected.add(book.id)
        notifyItemChanged(folders.size + items.indexOf(book))
        onSelectionChanged?.invoke(selected.size)
    }

    private fun isFolder(position: Int) = position < folders.size

    override fun getItemViewType(position: Int): Int {
        if (isFolder(position)) return 10
        return if (layout == ShelfLayout.LIST) 2 else 1
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val res = when (viewType) {
            10 -> R.layout.item_folder
            2 -> R.layout.item_book_list
            else -> R.layout.item_book
        }
        return Holder(LayoutInflater.from(parent.context).inflate(res, parent, false))
    }

    override fun getItemCount(): Int = folders.size + items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        if (isFolder(position)) {
            val (group, count) = folders[position]
            holder.bindFolder(group, count)
        } else {
            holder.bindBook(items[position - folders.size])
        }
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: BookCoverView? = view.findViewById(R.id.cover)
        private val folderCard: FolderCardView? = view.findViewById(R.id.folderCard)
        private val folderName: TextView? = view.findViewById(R.id.folderName)
        private val folderCount: TextView? = view.findViewById(R.id.folderCount)
        private val title: TextView? = view.findViewById(R.id.title)
        private val progress: TextView? = view.findViewById(R.id.progress)
        private val check: TextView? = view.findViewById(R.id.check)
        private val heart: ImageView? = view.findViewById(R.id.heart)
        private val pinBadge: ImageView? = view.findViewById(R.id.pinBadge)

        fun bindFolder(group: BookGroup?, count: Int) {
            folderCard?.bind(group, count)
            folderName?.text = group?.name ?: "新建分组"
            folderCount?.text = if (group == null) "" else "$count 本"
            itemView.setOnClickListener { onFolderClick(group) }
            itemView.setOnLongClickListener {
                group?.let { onFolderLongClick(it) }
                group != null
            }
        }

        fun bindBook(book: Book) {
            cover?.bind(book)
            title?.text = book.title
            val percent = (book.progress * 100).toInt()
            progress?.text = when {
                book.progress <= 0.001f || percent == 0 -> "未读"
                book.progress >= 0.999f -> "已读完"
                else -> "已读 $percent%"
            }

            check?.visibility = if (selecting) View.VISIBLE else View.GONE
            val isSelected = selecting && selected.contains(book.id)
            check?.background = itemView.context.getDrawable(
                if (isSelected) R.drawable.bg_check_on else R.drawable.bg_check_off
            )
            check?.setTextColor(if (isSelected) Color.WHITE else Color.TRANSPARENT)

            val lightCover = cover?.coverIsLight ?: true
            pinBadge?.visibility = if (book.pinned) View.VISIBLE else View.GONE
            pinBadge?.setImageResource(
                if (lightCover) R.drawable.ic_pin_badge_dark else R.drawable.ic_pin_badge_light
            )
            heart?.let { h ->
                h.setImageResource(
                    if (book.favorite) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
                )
                h.setColorFilter(
                    when {
                        book.favorite -> Color.parseColor("#FF4D5E")
                        lightCover -> Color.parseColor("#3A3D45")
                        else -> Color.parseColor("#FFFFFF")
                    }
                )
                h.setOnClickListener { onFavorite(book) }
            }

            itemView.setOnClickListener {
                if (selecting) toggle(book) else onClick(book)
            }
            itemView.setOnLongClickListener {
                if (selecting) toggle(book) else onLongClick(book)
                true
            }
        }
    }
}
