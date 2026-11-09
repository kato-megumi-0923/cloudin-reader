package com.cloudin.reader

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.recyclerview.widget.RecyclerView
import com.example.liquidglass.LiquidGlassChip
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
                add("text/two.xhtml", """<html><body><svg><image xlink:href="../img/test%20image.png"/></svg></body></html>""".toByteArray())
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
                    assertTrue((0 until frame.height step 3).any { y ->
                        (0 until frame.width step 3).any { x -> frame.getPixel(x, y) == color }
                    })
                } finally { frame.recycle() }
                activeReader.finish()
            }
            await("Opened EPUB was not visible when returning to the shelf") {
                val position = shelfPosition()
                position >= 0 && BookStore.load(context).any { it.id == book.id && it.lastRead > 0 } &&
                    recycler.findViewHolderForAdapterPosition(position)?.itemView?.getGlobalVisibleRect(Rect()) == true
            }
        } finally {
            onUi { reader?.finish(); shelf?.finish() }
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
                var catalogWindow: View? = null
                val layoutDeadline = System.currentTimeMillis() + 5000
                while (catalogWindow == null && System.currentTimeMillis() < layoutDeadline) {
                    instrumentation.runOnMainSync {
                        catalogWindow = WindowInspector.getGlobalWindowViews().firstOrNull {
                            (it.findViewById<View>(R.id.cardRows)?.height ?: 0) > 0
                        }
                    }
                    if (catalogWindow == null) Thread.sleep(50)
                }
                val window = checkNotNull(catalogWindow) { "Catalog did not finish laying out" }
                var height = 0
                var failure: Throwable? = null
                instrumentation.runOnMainSync {
                    try {
                        listOf(R.id.topArea, R.id.bottomArea, R.id.progressRow, R.id.settingsPanel).forEach { id ->
                            assertEquals(View.GONE, activity.findViewById<View>(id).visibility)
                        }
                        val scroll = window.findViewById<View>(R.id.cardRows).parent as ScrollView
                        val content = scroll.parent as View
                        height = content.height
                        assertTrue(height in 1..(page.height * 0.66f).toInt())
                        assertEquals(chapterCount > 1, scroll.canScrollVertically(1))
                        val cancel = window.findViewById<View>(R.id.cardCancel)
                        assertTrue(cancel.isShown && cancel.height > 0)
                        assertTrue(cancel.performClick())
                    } catch (t: Throwable) { failure = t }
                }
                failure?.let { throw it }
                return height
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
                instrumentation.waitForIdleSync()
                BookStore.remove(context, book)
            }
        }
        val short = checkCatalog(1, R.id.tabProgress)
        val long = checkCatalog(50, R.id.tabFont)
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
                        val toolbar = activity.findViewById<AgslGlassView>(R.id.bottomBar)
                        assertTrue(toolbar.isShown)
                        fun click(id: Int) {
                            val button = toolbar.findViewById<View>(id)
                            assertFalse(button is AgslGlassView)
                            assertTrue(button.isShown)
                            assertTrue(button.performClick())
                        }
                        click(R.id.tabProgress)
                        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.progressRow).visibility)
                        click(R.id.tabTheme)
                        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.sectionTheme).visibility)
                        click(R.id.tabFont)
                        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.sectionFont).visibility)
                        click(R.id.tabTurn)
                        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.sectionTurn).visibility)
                        val chips = mapOf(
                            ReaderPrefs.MODE_NONE to R.id.turnNone,
                            ReaderPrefs.MODE_SLIDE to R.id.turnSlide,
                            ReaderPrefs.MODE_CURL to R.id.turnCurl,
                            ReaderPrefs.MODE_FADE to R.id.turnFade
                        )
                        fun assertSavedSelection() {
                            chips.forEach { (mode, id) ->
                                assertEquals(mode == originalMode, activity.findViewById<LiquidGlassChip>(id).isChecked)
                            }
                            assertEquals(originalMode, ReaderPrefs.turnMode(context))
                        }
                        assertSavedSelection()
                        activity.findViewById<LiquidGlassChip>(chips.getValue(originalMode)).performClick()
                        assertSavedSelection()
                        click(R.id.tabTheme)
                        click(R.id.tabTurn)
                        assertSavedSelection()
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
}
