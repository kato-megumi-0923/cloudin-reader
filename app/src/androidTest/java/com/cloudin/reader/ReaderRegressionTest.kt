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

    @Test fun bundledCoverLoadsAsSharedThumbnail() {
        val key = CoverThumbnailLoader.resourceKey(context, R.drawable.reader_texture_kraft, 140, 200)
        val ready = CountDownLatch(2)
        val results = mutableListOf<CoverThumbnailLoader.Result>()
        val callback: (CoverThumbnailLoader.Result) -> Unit = {
            results.add(it)
            ready.countDown()
        }
        CoverThumbnailLoader.loadResource(context.resources, key, callback)
        CoverThumbnailLoader.loadResource(context.resources, key, callback)
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        val image = checkNotNull(results[0].bitmap)
        assertTrue(maxOf(image.width, image.height) <= 256)
        assertSame(results[0], results[1])
        assertSame(results[0], CoverThumbnailLoader.cached(key))
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
        val animator = component<TurnAnimator>(page, "turnAnimator")
        val tipX = TurnAnimator::class.java.getDeclaredField("curlTipX").apply { isAccessible = true }
        val tipY = TurnAnimator::class.java.getDeclaredField("curlTipY").apply { isAccessible = true }
        touch(MotionEvent.ACTION_DOWN, 765f, 70f)
        touch(MotionEvent.ACTION_MOVE, 480f, 185f)
        assertEquals(480f, tipX.getFloat(animator), 0f)
        assertEquals(185f, tipY.getFloat(animator), 0f)
        touch(MotionEvent.ACTION_MOVE, 480f, 275f)
        assertEquals(480f, tipX.getFloat(animator), 0f)
        assertEquals(275f, tipY.getFloat(animator), 0f)
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
        assertEquals(788f, tipX.getFloat(animator), 0f)
        assertEquals(62f, tipY.getFloat(animator), 0f)
        touch(MotionEvent.ACTION_UP, 788f, 62f)
        assertEquals(0, page.pageIndex())
    }

    /** 每一章都必须从新的一页开始，不允许接在上一章正文后面。 */
    @Test fun everyChapterStartsOnItsOwnPage() {
        val text = buildString {
            repeat(4) { index ->
                append("第00").append(index + 1).append("章 测试章节\n")
                repeat(30) { append("　　这是一段用来把页面填满的占位正文，长度取中等，保证一章会跨越多页。\n") }
            }
        }
        val starts = Regex("第0+\\d+章").findAll(text).map { it.range.first }.toList()
        assertEquals(4, starts.size)
        val paint = android.text.TextPaint().apply { textSize = 40f }
        val pages = PageBreaker.breakPages(text, paint, 720, 1100, 1.62f, starts.toIntArray())

        assertTrue("分页结果为空", pages.isNotEmpty())
        // 第 1 章在文首，天然是页首；其余每章必须各自开启一页
        for (start in starts) {
            assertTrue("章节起点 $start 未落在任何页的页首", pages.any { it.first == start })
        }
        // 任何一页内部都不允许出现章节起点
        for (page in pages) {
            for (start in starts) {
                assertFalse(
                    "章节起点 $start 被并入了 ${page.first}..${page.last} 这一页",
                    start > page.first && start <= page.last
                )
            }
        }
    }

    /** 章节偏移必须正好落在标题首字上，否则强制分页会把标题切成两半。 */
    @Test fun chapterOffsetsPointAtHeadingText() {
        val content = buildString {
            // 首行是书名而不是章节标题：它会被缩进两格，再被 trim 掉，正是偏移错位的来源
            append("书名：偏移测试\n")
            append("作者：测试\n\n")
            repeat(3) { index ->
                append("第00").append(index + 1).append("章 标题").append(index + 1).append('\n')
                append("　　这里是正文。\n")
            }
        }
        val book = (txt("chapter-offsets", content) as ImportResult.Added).book
        val document = checkNotNull(DocumentLoader.load(book))
        assertEquals(3, document.chapters.size)
        for (mark in document.chapters) {
            assertTrue(
                "章节「${mark.title}」的偏移 ${mark.offset} 没有落在标题首字上，实际是「${
                    document.text.substring(mark.offset, (mark.offset + 8).coerceAtMost(document.text.length))
                }」",
                document.text.startsWith(mark.title, mark.offset)
            )
        }
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

    @Test fun defaultCoversMigrateAndReuseLeastUsedImages() {
        repeat(DEFAULT_COVER_COUNT) { index -> txt("default-cover-$index") as ImportResult.Added }
        val library = File(root, "library.json")
        val legacy = org.json.JSONArray(library.readText())
        repeat(legacy.length()) { index ->
            legacy.getJSONObject(index).apply {
                remove("defaultCoverPoolSize")
                put("defaultCoverIndex", index % 28)
            }
        }
        library.writeText(legacy.toString())

        val migrated = BookStore.load(context)
        assertEquals((0 until DEFAULT_COVER_COUNT).toSet(), migrated.map { it.defaultCoverIndex }.toSet())
        assertEquals(DEFAULT_COVER_COUNT, org.json.JSONArray(library.readText()).getJSONObject(0).getInt("defaultCoverPoolSize"))

        val extra = (txt("extra-cover") as ImportResult.Added).book
        val next = (txt("next-cover") as ImportResult.Added).book
        assertNotEquals(extra.defaultCoverIndex, next.defaultCoverIndex)
        assertEquals(migrated, BookStore.load(context).take(DEFAULT_COVER_COUNT))

        val released = migrated.last()
        BookStore.remove(context, released)
        val replacement = (txt("replacement-cover") as ImportResult.Added).book
        assertEquals(released.defaultCoverIndex, replacement.defaultCoverIndex)
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

    private inline fun <reified T> component(view: PageView, name: String): T =
        PageView::class.java.getDeclaredField(name).apply { isAccessible = true }.get(view) as T

    private fun view(): PageView = PageView(context).apply {
        setTheme(ReaderPrefs.THEME_WHITE)
        layout(0, 0, 800, 1000)
    }

    private fun longDocument() = Document("测试", (1..300).joinToString("\n") { "第 $it 行，这是用于验证分页和翻页的测试正文。" }, emptyList())

    @Test fun collidingBookIdsUseSeparateCacheFilesAndMigrateLegacyRanges() {
        val firstId = "Aa"
        val secondId = "BB"
        assertEquals(firstId.hashCode(), secondId.hashCode())
        val first = PaginationCache(root, firstId)
        val second = PaginationCache(root, secondId)
        val document = Document("测试", "0123456789", emptyList())
        val firstKey = first.key(document, "layout")
        val secondKey = second.key(document, "layout")
        val firstPages = listOf(0..4, 5..9)
        val secondPages = listOf(0..9)
        val directory = File(root, "reader-pagination")
        val legacy = File(directory, "${firstId.hashCode()}.bin")
        first.write(firstKey, firstPages)
        assertTrue(File(directory, "book-$firstId.bin").renameTo(legacy))
        assertNull(second.read(secondKey, document.text.length))
        second.write(secondKey, secondPages)
        assertEquals(firstPages, first.read(firstKey, document.text.length))
        assertFalse(legacy.exists())
        assertEquals(firstPages, first.read(firstKey, document.text.length))
        assertEquals(secondPages, second.read(secondKey, document.text.length))
    }

    @Test fun scrollingWithinOneLineSkipsRepeatedProgressAndStillReportsCompletion() = onMain {
        val view = view()
        view.setTurnMode(ReaderPrefs.MODE_SCROLL)
        view.setDocument(longDocument(), 0)
        val progress = mutableListOf<Float>()
        view.onPageChanged = { _, _, value -> progress.add(value) }
        view.advanceScroll(0f)
        view.advanceScroll(1f)
        view.advanceScroll(1f)
        assertTrue(progress.isEmpty())
        view.advanceScroll(100_000f)
        assertTrue(view.isAtEnd())
        assertEquals(listOf(1f), progress)
        view.advanceScroll(100f)
        assertEquals(listOf(1f), progress)
    }

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
        assertFalse(component<TurnAnimator>(view, "turnAnimator").animating)
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
        val pages = component<Paginator>(view, "paginator").pages
        assertTrue(offset in pages[view.pageIndex()])
    }

    @Test fun scrollAndColumnChangesPreserveReadingAnchorAcrossResize() = onMain {
        val view = view()
        val doc = longDocument()
        view.setDocument(doc, 0)
        val anchor = doc.text.length / 3
        view.seekToOffset(anchor)
        view.setDoubleColumns(true)
        assertEquals(anchor, view.currentOffset())
        view.setTurnMode(ReaderPrefs.MODE_VERTICAL)
        assertEquals(anchor, view.currentOffset())
        view.setTurnMode(ReaderPrefs.MODE_SCROLL)
        assertEquals(anchor, view.currentOffset())
        view.advanceScroll(480f)
        val scrolled = view.currentOffset()
        assertTrue(scrolled > anchor)
        view.layout(0, 0, 1000, 800)
        assertEquals(scrolled, view.currentOffset())
        view.setDoubleColumns(false)
        assertEquals(scrolled, view.currentOffset())
        view.setTurnMode(ReaderPrefs.MODE_CURL)
        assertEquals(scrolled, view.currentOffset())
    }
}
