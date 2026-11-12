package com.cloudin.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Optional end-to-end check of the explicit fixtures imported through the system picker. */
@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {

    /** 目录面板没有稳定的控件 id，用 SoftScrollView 实例类型定位它的窗口。 */
    private fun findSoftScroll(view: View): SoftScrollView? {
        if (view is SoftScrollView) return view
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                findSoftScroll(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    @Test fun epubIllustrationRendersAndOpenedBookRemainsVisibleOnReturn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File.createTempFile("illustrated-check-", ".epub", context.cacheDir)
        val color = Color.rgb(218, 32, 91)
        val image = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        image.recycle()
        val book = try {
            ZipOutputStream(source.outputStream()).use { zip ->
                fun add(name: String, data: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
                add("META-INF/container.xml", """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""".toByteArray())
                add("book.opf", """<package><metadata><title>${source.nameWithoutExtension}</title></metadata><manifest><item id="one" href="text/one.xhtml" media-type="application/xhtml+xml"/><item id="two" href="text/two.xhtml" media-type="application/xhtml+xml"/><item id="art" href="img/test%20image.png" media-type="image/png"/></manifest><spine><itemref idref="one"/><itemref idref="two"/></spine></package>""".toByteArray())
                add("text/one.xhtml", """<html><body><h1>第一章</h1><p>测试正文</p><img src="../img/test%20image.png"/></body></html>""".toByteArray())
                // 无 toc，标题只能从正文/页面 <title> 读；页面带 <title> 才会被收录进目录
                add("text/two.xhtml", """<html><head><title>第二章</title></head><body><svg><image xlink:href="../img/test%20image.png"/></svg></body></html>""".toByteArray())
                add("img/test image.png", bytes)
            }
            (BookStore.import(context, Uri.fromFile(source), source.name) as ImportResult.Added).book
        } finally { source.delete() }

        fun onUi(block: () -> Unit) {
            var failure: Throwable? = null
            instrumentation.runOnMainSync { try { block() } catch (t: Throwable) { failure = t } }
            failure?.let { throw it }
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 10_000
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                onUi { ready = condition() }
                if (!ready) Thread.sleep(50)
            }
            assertTrue(message, ready)
        }
        fun shelfPosition(): Int = BookStore.load(context)
            .sortedWith(compareByDescending<Book> { it.pinned }.thenByDescending { it.lastRead })
            .indexOfFirst { it.id == book.id }

        var shelf: MainActivity? = null
        var reader: ReaderActivity? = null
        var viewer: ReaderImageActivity? = null
        var savedImage: Uri? = null
        fun savedImages(): Set<Uri> {
            if (Build.VERSION.SDK_INT < 29) return emptySet()
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val id = MediaStore.Images.Media._ID
            return context.contentResolver.query(collection, arrayOf(id),
                "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?", arrayOf("test image-%"), null)?.use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)))
                }
            }.orEmpty()
        }
        try {
            val doc = checkNotNull(DocumentLoader.load(book))
            assertEquals(2, doc.images.size)
            assertTrue(doc.images.all { doc.text[it.offset] == '\ufffc' })
            assertEquals(doc.chapters[1].offset, doc.images[1].offset)
            onUi {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            await("Shelf did not open") {
                shelf = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
                shelf != null
            }
            val main = checkNotNull(shelf)
            val recycler = (main.findViewById<android.widget.FrameLayout>(R.id.container)).getChildAt(0) as RecyclerView
            val initial = shelfPosition()
            assertTrue(initial >= 0)
            onUi { recycler.scrollToPosition(initial) }
            await("EPUB card was not laid out") { recycler.findViewHolderForAdapterPosition(initial)?.itemView?.isShown == true }
            onUi { assertTrue(recycler.findViewHolderForAdapterPosition(initial)!!.itemView.performClick()) }
            await("Reader did not open") {
                reader = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ReaderActivity>().firstOrNull()
                reader?.findViewById<PageView>(R.id.pageView)?.pageCount()?.let { it > 0 } == true
            }
            val activeReader = checkNotNull(reader)
            val page = activeReader.findViewById<PageView>(R.id.pageView)
            val asset = doc.images.first().asset
            val key = CoverThumbnailLoader.key(asset.path, 320, 240)
            await("Illustration did not load") { CoverThumbnailLoader.cached(key)?.bitmap != null }
            onUi {
                val frame = Bitmap.createBitmap(page.width / 3, page.height / 3, Bitmap.Config.ARGB_8888)
                try {
                    page.draw(Canvas(frame).apply { scale(1f / 3, 1f / 3) })
                    val point = (0 until frame.height step 3).firstNotNullOfOrNull { y ->
                        (0 until frame.width step 3).firstOrNull { x -> frame.getPixel(x, y) == color }?.let { it to y }
                    }
                    assertNotNull(point)
                    val (x, y) = checkNotNull(point)
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, now, action, x * 3f + 6, y * 3f + 6, 0)
                        page.onTouchEvent(event)
                        event.recycle()
                    }
                } finally { frame.recycle() }
            }
            await("Tapping the EPUB image did not open its viewer") {
                viewer = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ReaderImageActivity>().firstOrNull()
                viewer != null
            }
            if (Build.VERSION.SDK_INT >= 29) {
                val existing = savedImages()
                var saveButton: View? = null
                await("Image did not finish loading") {
                    val matches = arrayListOf<View>()
                    viewer?.window?.decorView?.findViewsWithText(matches, "保存图片", View.FIND_VIEWS_WITH_TEXT)
                    saveButton = matches.firstOrNull()?.parent as? GlassActionButton
                    saveButton?.isEnabled == true
                }
                onUi { assertTrue(checkNotNull(saveButton).performClick()) }
                await("Image was not published to the gallery") {
                    savedImage = (savedImages() - existing).singleOrNull()
                    savedImage != null
                }
                val savedBytes = context.contentResolver.openInputStream(checkNotNull(savedImage))!!.use { it.readBytes() }
                assertArrayEquals(bytes, savedBytes)
                val relativePath = MediaStore.Images.Media.RELATIVE_PATH
                context.contentResolver.query(checkNotNull(savedImage), arrayOf(relativePath), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals("Pictures/${context.getString(R.string.app_name)}/", it.getString(0))
                }
            }
            onUi { viewer?.finish(); activeReader.finish() }
            await("Opened EPUB was not visible when returning to the shelf") {
                val position = shelfPosition()
                position >= 0 && BookStore.load(context).any { it.id == book.id && it.lastRead > 0 } &&
                    recycler.findViewHolderForAdapterPosition(position)?.itemView?.getGlobalVisibleRect(Rect()) == true
            }
        } finally {
            onUi { viewer?.finish(); reader?.finish(); shelf?.finish() }
            savedImage?.let { context.contentResolver.delete(it, null, null) }
            BookStore.remove(context, book)
        }
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun catalogFitsChaptersAndHidesReaderChrome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun checkCatalog(chapterCount: Int, openPanel: Int): Int {
            val source = File.createTempFile("catalog-check-", ".txt", context.cacheDir)
            source.writeText((1..chapterCount).joinToString("\n") { "第${it}章 目录测试\n正文 ${source.name}" })
            val book = try {
                (BookStore.import(context, Uri.fromFile(source), source.name) as ImportResult.Added).book
            } finally {
                source.delete()
            }
            val activity = instrumentation.startActivitySync(
                Intent(context, ReaderActivity::class.java).putExtra("bookId", book.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as ReaderActivity
            try {
                val page = activity.findViewById<PageView>(R.id.pageView)
                val deadline = System.currentTimeMillis() + 10000
                var ready = false
                while (!ready && System.currentTimeMillis() < deadline) {
                    instrumentation.runOnMainSync { ready = page.pageCount() > 0 }
                    if (!ready) Thread.sleep(50)
                }
                assertTrue("Reader did not load", ready)
                instrumentation.runOnMainSync {
                    page.onCenterTap?.invoke()
                    activity.findViewById<View>(openPanel).performClick()
                    activity.findViewById<View>(R.id.tabCatalog).performClick()
                }
                var catalogScroll: SoftScrollView? = null
                val layoutDeadline = System.currentTimeMillis() + 5000
                while (catalogScroll == null && System.currentTimeMillis() < layoutDeadline) {
                    instrumentation.runOnMainSync {
                        catalogScroll = WindowInspector.getGlobalWindowViews()
                            .firstNotNullOfOrNull { findSoftScroll(it) }?.takeIf { it.height > 0 }
                    }
                    if (catalogScroll == null) Thread.sleep(50)
                }
                val scroll = checkNotNull(catalogScroll) { "Catalog did not finish laying out" }
                val window = scroll.rootView
                var height = 0
                var failure: Throwable? = null
                instrumentation.runOnMainSync {
                    try {
                        listOf(R.id.topArea, R.id.bottomArea, R.id.settingsPanel).forEach { id ->
                            assertEquals(View.GONE, activity.findViewById<View>(id).visibility)
                        }
                        val attributes = window.layoutParams as android.view.WindowManager.LayoutParams
                        assertEquals(R.style.SettingsCardAnimation, attributes.windowAnimations)
                        val card = scroll.parent as View
                        height = card.height
                        assertTrue(height in 1..activity.resources.displayMetrics.heightPixels)
                        assertEquals(chapterCount > 1, scroll.canScrollVertically(1))
                        // 目录/书签双标签：切到书签应显示空态
                        val bookmarkTabs = arrayListOf<View>()
                        window.findViewsWithText(bookmarkTabs, "书签", View.FIND_VIEWS_WITH_TEXT)
                        assertTrue((bookmarkTabs.first().parent as View).performClick())
                        val empties = arrayListOf<View>()
                        window.findViewsWithText(empties, "暂无书签", View.FIND_VIEWS_WITH_TEXT)
                        assertTrue(empties.isNotEmpty())
                    } catch (t: Throwable) { failure = t }
                }
                failure?.let { throw it }
                // 返回键应关闭目录面板
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                val closeDeadline = System.currentTimeMillis() + 2000
                var closed = false
                while (!closed && System.currentTimeMillis() < closeDeadline) {
                    instrumentation.runOnMainSync {
                        closed = WindowInspector.getGlobalWindowViews().none { findSoftScroll(it) != null }
                    }
                    if (!closed) Thread.sleep(50)
                }
                assertTrue("Back key did not close the catalog", closed)
                return height
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
                BookStore.remove(context, book)
            }
        }
        val short = checkCatalog(1, R.id.tabProgress)
        val long = checkCatalog(50, R.id.tabSettings)
        assertTrue("Long catalog should be taller than a single-chapter catalog", long > short)
    }

    @Test fun readerGlassButtonsOpenTheirPanels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File.createTempFile("widget-check-", ".txt", context.cacheDir)
        source.writeText("第一章 测试\n阅读工具栏验证 ${source.name}")
        val book = try {
            (BookStore.import(context, Uri.fromFile(source), source.name) as ImportResult.Added).book
        } finally {
            source.delete()
        }
        val originalMode = ReaderPrefs.turnMode(context)
        try {
            val activity = instrumentation.startActivitySync(
                Intent(context, ReaderActivity::class.java).putExtra("bookId", book.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as ReaderActivity
            try {
                var failure: Throwable? = null
                instrumentation.runOnMainSync {
                    try {
                        activity.findViewById<PageView>(R.id.pageView).onCenterTap?.invoke()
                        val back = activity.findViewById<GlassActionButton>(R.id.backBar)
                        assertTrue(back.isShown)
                        assertEquals(context.readerDp(46), back.layoutParams.width)
                        assertEquals(context.readerDp(46), back.layoutParams.height)
                        assertEquals(context.getString(R.string.settings_back), back.contentDescription)
                        val toolbar = activity.findViewById<AgslGlassView>(R.id.bottomBar)
                        assertTrue(toolbar.isShown)
                        fun click(id: Int) {
                            val button = toolbar.findViewById<View>(id)
                            assertFalse(button is AgslGlassView)
                            assertTrue(button.isShown)
                            assertTrue(button.performClick())
                        }
                        val panel = activity.findViewById<AgslGlassView>(R.id.settingsPanel)
                        fun controls(label: String) = arrayListOf<View>().also {
                            panel.findViewsWithText(it, label, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                        }
                        assertEquals(4, (toolbar.getChildAt(0) as android.view.ViewGroup).childCount)
                        click(R.id.tabProgress)
                        assertTrue(panel.findViewById<View>(R.id.seekBar).isShown)
                        assertTrue(controls("返回阅读设置").isEmpty())
                        click(R.id.tabSettings)
                        assertTrue(panel.findViewById<View>(R.id.readerSettingFont).isShown)
                        assertTrue(panel.getChildAt(0).translationX < 0f)
                        assertTrue(controls("关闭阅读设置").isEmpty())
                        assertTrue(panel.findViewById<View>(R.id.readerSettingMore).performClick())
                        assertTrue(panel.findViewById<View>(R.id.readerSettingProgress).performClick())
                        assertTrue(panel.findViewById<View>(R.id.seekBar).isShown)
                        assertTrue(controls("返回阅读设置").isEmpty())
                        assertTrue(panel.getChildAt(0).translationX > 0f)
                        click(R.id.tabSettings)
                        assertTrue(panel.findViewById<View>(R.id.readerSettingTurn).performClick())
                        val modes = arrayListOf<View>()
                        panel.findViewsWithText(modes, "仿真", View.FIND_VIEWS_WITH_TEXT)
                        assertEquals(1, modes.size)
                        val chosen = arrayListOf<View>()
                        val selectedLabel = context.getString(ReaderPrefs.turns.first { it.first == originalMode }.second)
                        panel.findViewsWithText(chosen, selectedLabel, View.FIND_VIEWS_WITH_TEXT)
                        assertTrue(chosen.single().isSelected)
                        val accessibility = chosen.single().createAccessibilityNodeInfo()
                        assertEquals(android.widget.RadioButton::class.java.name, accessibility.className)
                        assertTrue(accessibility.isCheckable)
                        assertTrue(accessibility.isChecked)
                        assertTrue(accessibility.contentDescription.contains(selectedLabel))
                        assertTrue(chosen.single().performClick())
                        assertEquals(originalMode, ReaderPrefs.turnMode(context))
                        assertTrue(controls("返回阅读设置").single().performClick())
                        assertTrue(panel.findViewById<View>(R.id.readerSettingFont).isShown)
                        val theme = ReaderPrefs.theme(context)
                        try {
                            click(R.id.tabNight)
                            assertEquals(!ReaderPrefs.palette(theme).dark, ReaderPrefs.palette(ReaderPrefs.theme(context)).dark)
                            click(R.id.tabProgress)
                            assertTrue(toolbar.findViewById<View>(R.id.tabProgress).isSelected)
                            assertFalse(toolbar.findViewById<View>(R.id.tabSettings).isSelected)
                            assertNull(toolbar.findViewById<View>(R.id.tabNight).background)
                        } finally {
                            ReaderPrefs.setTheme(context, theme)
                        }
                        assertTrue(back.performClick())
                        assertTrue(activity.isFinishing)
                    } catch (t: Throwable) { failure = t }
                }
                failure?.let { throw it }
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        } finally {
            BookStore.remove(context, book)
        }
    }

    @Test fun automaticReadingStartsScrollingInCurlModeAndRestoresItOnStop() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val oldMode = ReaderPrefs.turnMode(context)
        val oldSpeed = ReaderPrefs.autoSpeed(context)
        val source = File.createTempFile("auto-reading-", ".txt", context.cacheDir)
        source.writeText((1..260).joinToString("\n") { "第 $it 行，自动阅读测试正文。 ${source.name}" })
        val book = try {
            (BookStore.import(context, Uri.fromFile(source), source.name) as ImportResult.Added).book
        } finally {
            source.delete()
        }
        ReaderPrefs.setTurnMode(context, ReaderPrefs.MODE_CURL)
        ReaderPrefs.setAutoSpeed(context, 85)
        var activity: ReaderActivity? = null
        try {
            val reader = instrumentation.startActivitySync(
                Intent(context, ReaderActivity::class.java).putExtra("bookId", book.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as ReaderActivity
            activity = reader
            val page = reader.findViewById<PageView>(R.id.pageView)
            fun onUi(block: () -> Unit) {
                var failure: Throwable? = null
                instrumentation.runOnMainSync { try { block() } catch (t: Throwable) { failure = t } }
                failure?.let { throw it }
            }
            fun await(timeout: Long, message: String, check: () -> Boolean) {
                val deadline = SystemClock.uptimeMillis() + timeout
                while (SystemClock.uptimeMillis() < deadline) {
                    var ready = false
                    onUi { ready = check() }
                    if (ready) return
                    Thread.sleep(40)
                }
                fail(message)
            }
            fun autoButton(label: String): GlassActionButton {
                val matches = arrayListOf<View>()
                reader.findViewById<View>(R.id.settingsPanel).findViewsWithText(matches, label, View.FIND_VIEWS_WITH_TEXT)
                return matches.first { it.parent is GlassActionButton }.parent as GlassActionButton
            }
            await(8000, "Reader did not load") { page.pageCount() > 2 && reader.hasWindowFocus() }
            onUi {
                page.onCenterTap?.invoke()
                reader.findViewById<View>(R.id.tabSettings).performClick()
                autoButton("自动阅读").performClick()
                autoButton("开始自动阅读").performClick()
            }
            await(2400, "Auto reading did not move the text in curl mode") { page.currentOffset() > 0 }
            onUi {
                page.onCenterTap?.invoke()
                val offset = page.currentOffset()
                reader.findViewById<View>(R.id.tabSettings).performClick()
                autoButton("自动阅读").performClick()
                autoButton("停止自动阅读").performClick()
                assertTrue(page.usesCurl)
                assertEquals(offset, page.currentOffset())
                assertEquals(ReaderPrefs.MODE_CURL, ReaderPrefs.turnMode(context))
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            ReaderPrefs.setTurnMode(context, oldMode)
            ReaderPrefs.setAutoSpeed(context, oldSpeed)
            BookStore.remove(context, book)
        }
    }

    @Test fun openImportedFixtures() {
        val args = InstrumentationRegistry.getArguments()
        val ids = args.getString("fixtureIds")?.split(',').orEmpty()
        assumeTrue("Pass the IDs of the disposable reader-* fixtures", ids.size == 2)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val books = BookStore.load(context).filter { it.id in ids }
        assertEquals(2, books.size)
        assertEquals(setOf("reader-check", "reader-short"), books.map { it.title }.toSet())
        for (book in books) {
            val activity = instrumentation.startActivitySync(
                Intent(context, ReaderActivity::class.java).putExtra("bookId", book.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as ReaderActivity
            try {
                var ready = false
                val deadline = System.currentTimeMillis() + 15000
                while (!ready && System.currentTimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        ready = activity.findViewById<PageView>(R.id.pageView).pageCount() > 0
                    }
                    if (!ready) Thread.sleep(100)
                }
                assertTrue("Reader did not finish loading", ready)
                instrumentation.waitForIdleSync()
                var failure: Throwable? = null
                instrumentation.runOnMainSync {
                    try {
                        val view = activity.findViewById<PageView>(R.id.pageView)
                        if (book.title == "reader-short") {
                            assertEquals(1, view.pageCount())
                            assertEquals("100%", activity.findViewById<android.widget.TextView>(R.id.txtProgress).text.toString())
                        } else {
                            assertTrue(view.pageCount() > 1)
                            view.setTurnMode(ReaderPrefs.MODE_NONE)
                            view.nextPage()
                            assertEquals(1, view.pageIndex())
                        }
                    } catch (t: Throwable) { failure = t }
                }
                failure?.let { throw it }
                instrumentation.waitForIdleSync()
                Thread.sleep(250)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                assertNotNull(screenshot)
                File(context.cacheDir, "${book.title}-verified.png").outputStream().use {
                    screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                screenshot.recycle()
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        }
        assertEquals(1f, BookStore.load(context).single { it.title == "reader-short" && it.id in ids }.progress, 0f)
        if (args.getString("cleanupFixtures") == "true") books.forEach { BookStore.remove(context, it) }
    }

    @Test fun largeBookReusesPaginationAndRestoresItsPlace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File.createTempFile("large-reader-", ".txt", context.cacheDir)
        source.bufferedWriter().use { out ->
            repeat(4800) { paragraph ->
                out.appendLine("第${paragraph + 1}段 " + "阅读测试包含中文标点、数字和英文 words。".repeat(15))
            }
        }
        val book = try {
            (BookStore.import(context, Uri.fromFile(source), source.name) as ImportResult.Added).book
        } finally {
            source.delete()
        }
        val cache = File(context.cacheDir, "reader-pagination/book-${book.id}.bin")
        fun open(expectedOffset: Int, nextOffset: Int? = null): Long {
            val start = SystemClock.elapsedRealtime()
            val reader = instrumentation.startActivitySync(
                Intent(context, ReaderActivity::class.java).putExtra("bookId", book.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as ReaderActivity
            try {
                val page = reader.findViewById<PageView>(R.id.pageView)
                var offset = -1
                while (offset < 0 && SystemClock.elapsedRealtime() - start < 20000) {
                    instrumentation.runOnMainSync {
                        if (reader.findViewById<View>(R.id.loading).visibility == View.GONE &&
                            page.pageCount() > 1
                        ) offset = page.currentOffset()
                    }
                    if (offset < 0) Thread.sleep(20)
                }
                assertEquals(expectedOffset, offset)
                val elapsed = SystemClock.elapsedRealtime() - start
                if (nextOffset != null) instrumentation.runOnMainSync { page.seekToOffset(nextOffset) }
                return elapsed
            } finally {
                instrumentation.runOnMainSync { reader.finish() }
                instrumentation.waitForIdleSync()
            }
        }
        try {
            val cold = open(0, 375_000)
            assertTrue("Pagination cache should exist", cache.length() > 0)
            assertTrue(cache.setLastModified(System.currentTimeMillis() - 60_000))
            val writtenAt = cache.lastModified()
            val warm = open(375_000)
            assertEquals("Reopening should read the cache without rewriting it", writtenAt, cache.lastModified())
            android.util.Log.i("ReaderTest", "Large TXT: cold=${cold}ms, cached=${warm}ms")
        } finally {
            BookStore.remove(context, book)
            cache.delete()
        }
    }
}
