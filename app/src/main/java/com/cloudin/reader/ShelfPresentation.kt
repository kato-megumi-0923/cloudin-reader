package com.cloudin.reader

import android.content.Context

/**
 * 书架的公共排版口径：真实书架与皮肤预览共用，避免预览另写一套列数/排序。
 * 预览拿到的书单就是书架当前这一屏会显示的顺序。
 */
internal fun shelfColumns(context: Context): Int =
    (context.resources.configuration.screenWidthDp / 114).coerceIn(4, 12)

private val shelfOrder = compareByDescending<Book> { it.pinned }.thenByDescending { it.lastRead }

internal fun shelfOrderedBooks(context: Context): List<Book> =
    BookStore.load(context).sortedWith(shelfOrder)
