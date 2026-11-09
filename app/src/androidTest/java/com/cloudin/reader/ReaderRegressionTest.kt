package com.cloudin.reader

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Region
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class ReaderRegressionTest {
    @Test fun importedDirectoryGroupsOnlyNewBooksAndReusesItsName() {
        val previous = (txt("already-on-shelf") as ImportResult.Added).book
        val first = (txt("first-from-directory") as ImportResult.Added).book
        val second = (txt("second-from-directory") as ImportResult.Added).book
        assertNull(GroupStore.groupImportedBooks(context, "Empty directory", emptyList()))
        val group = checkNotNull(GroupStore.groupImportedBooks(context, "目录示例", listOf(first.id)))
        val sameGroup = GroupStore.groupImportedBooks(context, "目录示例", listOf(second.id))
        assertEquals(group.id, sameGroup?.id)
        assertEquals(listOf(group), GroupStore.load(context))
        val grouped = BookStore.load(context).associate { it.id to it.groupId }
        assertEquals(group.id, grouped[first.id])
        assertEquals(group.id, grouped[second.id])
        assertNull(grouped[previous.id])
    }

    @Test fun largeCoverLoadsAsCachedThumbnailOnMainCallback() {
        val image = File(root, "large-cover.jpg")
        val original = Bitmap.createBitmap(2048, 3072, Bitmap.Config.ARGB_8888)
        original.eraseColor(android.graphics.Color.WHITE)
        image.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
        original.recycle()

        val key = CoverThumbnailLoader.key(image.absolutePath, 240, 320)
        val ready = CountDownLatch(1)
        var result: CoverThumbnailLoader.Result? = null
        var onMain = false
        CoverThumbnailLoader.load(key) {
            result = it
            onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            ready.countDown()
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        val loaded = checkNotNull(result)
        val thumbnail = checkNotNull(loaded.bitmap)
        assertTrue(onMain)
        assertTrue(loaded.isLight)
        assertEquals(512, thumbnail.height)
        assertEquals(341, thumbnail.width)
        assertSame(loaded, CoverThumbnailLoader.cached(key))
    }

    @Test fun readingSessionSplitsAtLocalMidnightAndKeepsLegacyTotal() {
        val base = instrumentation.targetContext
        val file = "stats-test-${System.nanoTime()}"
        val isolated = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(file, mode)
        }
        val zone = ZoneId.of("Asia/Shanghai")
        val day = LocalDate.of(2026, 11, 9)
        try {
            isolated.getSharedPreferences(file, Context.MODE_PRIVATE).edit().putLong("totalMillis", 600_000L).commit()
            val end = day.atStartOfDay(zone).plusMinutes(4).toInstant().toEpochMilli()
            ReadingStats.record(isolated, end, 600_000L, zone, bookId = "first-book")
            val data = ReadingStats.read(isolated)
            assertEquals(360_000L, data.hoursOn(day.minusDays(1))[23])
            assertEquals(240_000L, data.hoursOn(day)[0])
            assertEquals(2, data.daysRead)
            assertEquals(2, data.streak(day))
            assertEquals(0, data.streak(day.plusDays(2)))
            assertEquals(1_200_000L, ReadingStats.total(isolated))
            ReadingStats.record(isolated, end, 300_000L, zone, bookId = "second-book")
            assertEquals(mapOf("first-book" to 600_000L, "second-book" to 300_000L), ReadingStats.bookTimes(isolated))
            assertEquals(1_500_000L, ReadingStats.total(isolated))
        } finally {
            base.deleteSharedPreferences(file)
        }
    }

    @Test fun foldedPaperReflectsTheLiftedCornerForBothDirections() {
        val curl = PageCurl()
        val clip = Region(0, 0, 800, 1000)
        val region = Region()
        curl.update(800f, 1000f, anchorX = 800f, anchorY = 0f, tipX = 400f, tipY = 160f)
        val topCorner = floatArrayOf(800f, 0f)
        curl.reflection.mapPoints(topCorner)
        assertArrayEquals(floatArrayOf(400f, 160f), topCorner, 0.001f)
        region.setPath(curl.exposed, clip)
        assertTrue(region.contains(760, 20))
        assertFalse(region.contains(760, 970))
        val foldedPoint = floatArrayOf(740f, 30f)
        curl.reflection.mapPoints(foldedPoint)
        region.setPath(curl.paperBack, clip)
        assertTrue(region.contains(foldedPoint[0].toInt(), foldedPoint[1].toInt()))
        assertFalse(region.contains(760, 20))

        curl.update(800f, 1000f, anchorX = 0f, anchorY = 1000f, tipX = 400f, tipY = 840f)
        val bottomCorner = floatArrayOf(0f, 1000f)
        curl.reflection.mapPoints(bottomCorner)
        assertArrayEquals(floatArrayOf(400f, 840f), bottomCorner, 0.001f)
        region.setPath(curl.exposed, clip)
        assertTrue(region.contains(40, 980))
        assertFalse(region.contains(40, 30))
    }

    @Test fun curlTipTracksBothAxesAndReverseDragDoesNotCommit() = onMain {
        val page = view()
        page.setTurnMode(ReaderPrefs.MODE_CURL)
        page.setDocument(longDocument(), 0)
        val time = SystemClock.uptimeMillis()
        fun touch(action: Int, x: Float, y: Float = 250f) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
            page.onTouchEvent(event)
            event.recycle()
        }
        val tipX = PageView::class.java.getDeclaredField("curlTipX").apply { isAccessible = true }
        val tipY = PageView::class.java.getDeclaredField("curlTipY").apply { isAccessible = true }
        touch(MotionEvent.ACTION_DOWN, 765f, 70f)
        touch(MotionEvent.ACTION_MOVE, 480f, 185f)
        assertEquals(480f, tipX.getFloat(page), 0f)
        assertEquals(185f, tipY.getFloat(page), 0f)
        touch(MotionEvent.ACTION_MOVE, 480f, 275f)
        assertEquals(480f, tipX.getFloat(page), 0f)
        assertEquals(275f, tipY.getFloat(page), 0f)
        if (InstrumentationRegistry.getArguments().getString("captureCurl") == "true") {
            touch(MotionEvent.ACTION_MOVE, 395f, 220f)
            val frame = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
            page.draw(Canvas(frame))
            File(context.cacheDir, "paper-curl-frame.png").outputStream().use {
                frame.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            frame.recycle()
        }
        touch(MotionEvent.ACTION_MOVE, 788f, 62f)
        assertEquals(788f, tipX.getFloat(page), 0f)
        assertEquals(62f, tipY.getFloat(page), 0f)
        touch(MotionEvent.ACTION_UP, 788f, 62f)
        assertEquals(0, page.pageIndex())
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File
    private lateinit var context: Context

    @Before fun setup() {
        root = File(instrumentation.targetContext.cacheDir, "regression-${System.nanoTime()}").apply { mkdirs() }
        context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = root
        }
    }

    @After fun cleanup() { root.deleteRecursively() }

    private fun txt(name: String, content: String = name): ImportResult {
        val source = File(root, "$name.txt").apply { writeText(content) }
        return BookStore.import(context, Uri.fromFile(source), source.name)
    }

    @Test fun sameBatchDuplicatesArePersistedOnlyOnce() {
        assertTrue(txt("first", "同一本书") is ImportResult.Added)
        assertEquals(ImportResult.Duplicate, txt("second", "同一本书"))
        assertEquals(1, BookStore.load(context).size)
        assertEquals(1, BookStore.booksDir(context).listFiles()!!.size)
    }

    @Test fun concurrentImportsAndMetadataUpdatesDoNotLoseBooks() {
        val book = (txt("original") as ImportResult.Added).book
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs = (0..3).map { worker -> pool.submit {
                start.await()
                repeat(12) { i ->
                    when (worker) {
                        0 -> txt("new-$i")
                        1 -> BookStore.setFavorite(context, book.id, true)
                        2 -> BookStore.setPinned(context, listOf(book.id), true)
                        3 -> BookStore.updateProgress(context, book.id, 0.5f)
                    }
                }
            } }
            start.countDown()
            jobs.forEach { it.get(30, TimeUnit.SECONDS) }
            val books = BookStore.load(context)
            assertEquals(13, books.size)
            val saved = books.single { it.id == book.id }
            assertTrue(saved.favorite)
            assertTrue(saved.pinned)
            assertEquals(0.5f, saved.progress, 0f)
        } finally { pool.shutdownNow() }
    }

    @Test fun concurrentIdenticalImportsAreDeduplicated() {
        val source = File(root, "same.txt").apply { writeText("并发导入") }
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val jobs = (0..3).map { pool.submit<ImportResult> {
                start.await()
                BookStore.import(context, Uri.fromFile(source), source.name)
            } }
            start.countDown()
            val results = jobs.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is ImportResult.Added })
            assertEquals(3, results.count { it == ImportResult.Duplicate })
            assertEquals(1, BookStore.load(context).size)
        } finally { pool.shutdownNow() }
    }

    @Test fun removingEpubDeletesSourceExtractedContentAndCover() {
        val source = File(root, "sample.epub")
        ZipOutputStream(source.outputStream()).use { zip ->
            val entries = mapOf(
                "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
                "content.opf" to """<package><metadata><title>测试</title></metadata><manifest><item id="c" href="chapter.html" media-type="application/xhtml+xml"/><item id="cover" href="cover.jpg" properties="cover-image" media-type="image/jpeg"/></manifest><spine><itemref idref="c"/></spine></package>""",
                "chapter.html" to "<html><body><h1>第一章</h1><p>测试正文</p></body></html>",
                "cover.jpg" to "fixture"
            )
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        val book = (BookStore.import(context, Uri.fromFile(source), source.name) as ImportResult.Added).book
        val storedSource = File(BookStore.booksDir(context), "${book.id}.epub")
        assertTrue(storedSource.exists())
        assertNotNull(DocumentLoader.load(book))
        BookStore.remove(context, book)
        assertFalse(storedSource.exists())
        assertFalse(File(book.path).exists())
        assertFalse(File(book.coverPath!!).exists())
        assertTrue(BookStore.load(context).isEmpty())
    }

    private fun onMain(test: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { test() } catch (t: Throwable) { failure = t } }
        failure?.let { throw it }
    }

    private fun view(): PageView = PageView(context).apply {
        setTheme(ReaderPrefs.THEME_WHITE)
        layout(0, 0, 800, 1000)
    }

    private fun longDocument() = Document("测试", (1..300).joinToString("\n") { "第 $it 行，这是用于验证分页和翻页的测试正文。" }, emptyList())

    @Test fun lastPageAndSinglePageReachCompleteProgress() = onMain {
        val view = view()
        var progress = -1f
        view.onPageChanged = { _, _, value -> progress = value }
        view.setDocument(Document("短篇", "只有一页的正文", emptyList()), 0)
        assertEquals(1f, progress, 0f)
        val doc = longDocument()
        view.setDocument(doc, 0)
        assertEquals(0f, progress, 0f)
        view.seekToOffset(doc.text.length)
        assertEquals(1f, progress, 0f)
        assertEquals(view.pageCount() - 1, view.pageIndex())
    }

    @Test fun noAnimationSwipeCommitsExactlyOnePageImmediately() = onMain {
        val view = view()
        view.setTurnMode(ReaderPrefs.MODE_NONE)
        view.setDocument(longDocument(), 0)
        val time = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float, delta: Long) {
            MotionEvent.obtain(time, time + delta, action, x, 300f, 0).also {
                view.onTouchEvent(it)
                it.recycle()
            }
        }
        event(MotionEvent.ACTION_DOWN, 700f, 0)
        event(MotionEvent.ACTION_MOVE, 100f, 100)
        assertEquals(1, view.pageIndex())
        event(MotionEvent.ACTION_MOVE, 50f, 150)
        event(MotionEvent.ACTION_UP, 50f, 200)
        assertEquals(1, view.pageIndex())
        val animating = PageView::class.java.getDeclaredField("animating").apply { isAccessible = true }
        assertFalse(animating.getBoolean(view))
    }

    @Test fun bottomInsetReducesTextAreaAndKeepsReadingPosition() = onMain {
        val view = view()
        view.setDocument(longDocument(), 0)
        view.setTurnMode(ReaderPrefs.MODE_NONE)
        view.nextPage()
        val offset = view.currentOffset()
        val count = view.pageCount()
        view.setBottomInset(250)
        assertTrue(view.pageCount() > count)
        assertTrue(view.currentOffset() <= offset)
        val pagesField = PageView::class.java.getDeclaredField("pages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val pages = pagesField.get(view) as List<IntRange>
        assertTrue(offset in pages[view.pageIndex()])
    }
}
