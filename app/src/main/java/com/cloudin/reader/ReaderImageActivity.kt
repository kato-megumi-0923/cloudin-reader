package com.cloudin.reader

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.IOException
import java.util.Locale

class ReaderImageActivity : AppCompatActivity() {
    private lateinit var source: File
    private lateinit var save: GlassActionButton
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && ::source.isInitialized) saveImage()
        else Toast.makeText(this, R.string.reader_image_permission_denied, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val book = BookStore.load(this).firstOrNull { it.id == intent.getStringExtra("bookId") && it.format == "epub" }
        val path = intent.getStringExtra("imagePath")
        val file = runCatching {
            val root = File(BookStore.repairPath(this, requireNotNull(book).path)).canonicalFile
            File(requireNotNull(path)).canonicalFile.takeIf { it.isFile && it.path.startsWith(root.path + File.separator) }
        }.getOrNull()
        if (file == null) {
            finish()
            return
        }
        source = file
        window.useAuroraSystemBars()
        val root = FrameLayout(this)
        val picture = ZoomImageView(this).apply {
            setBackgroundResource(R.drawable.bg_aurora)
            contentDescription = getString(R.string.reader_image_description)
        }
        root.addView(picture, FrameLayout.LayoutParams(-1, -1))
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val theme = ReaderPrefs.THEME_PURE_WHITE
        val back = glassBackButton(ReaderPrefs.ink(theme), picture, ReaderPrefs.chrome(theme)) { finish() }
        back.tintAlpha = 0.65f
        buttons.addView(back,
            LinearLayout.LayoutParams(readerDp(44), readerDp(44)))
        buttons.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f))
        save = readerButton(getString(R.string.reader_image_save), theme, picture) {
            if (Build.VERSION.SDK_INT < 29 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else saveImage()
        }
        save.tintAlpha = 0.65f
        save.isEnabled = false
        buttons.addView(save, LinearLayout.LayoutParams(readerDp(106), readerDp(48)))
        root.addView(buttons, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            marginStart = readerDp(16)
            marginEnd = readerDp(16)
        })
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            buttons.setPadding(bars.left, bars.top + readerDp(8), bars.right, 0)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        Thread {
            val bitmap = runCatching { loadPreview(file) }.getOrNull()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                if (bitmap == null) {
                    Toast.makeText(this, R.string.reader_image_display_failed, Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    picture.setImageBitmap(bitmap)
                    save.isEnabled = true
                }
            }
        }.start()
    }

    private fun saveImage() {
        save.isEnabled = false
        val file = source
        val context = applicationContext
        Thread {
            val success = runCatching { saveOriginal(context, file) }.isSuccess
            runOnUiThread {
                if (!isDestroyed) save.isEnabled = true
                Toast.makeText(context, if (success) R.string.reader_image_saved else R.string.reader_image_save_failed,
                    Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun loadPreview(file: File): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            return android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > 4096) {
                    val ratio = 4096f / longest
                    decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1),
                        (info.size.height * ratio).toInt().coerceAtLeast(1))
                }
            }
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 4096) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.path, options)
    }

    private fun saveOriginal(context: Context, file: File) {
        val resolver = context.contentResolver
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(Locale.ROOT))
            ?.takeIf { it.startsWith("image/") } ?: BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(file.path, this)
            }.outMimeType ?: throw IOException("Unknown image format")
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: file.extension
        val stem = file.nameWithoutExtension.take(120).ifBlank { "EPUB" }
        val name = "$stem-${System.currentTimeMillis()}.$extension"
        val album = context.getString(R.string.app_name)
        if (Build.VERSION.SDK_INT < 29) {
            @Suppress("DEPRECATION")
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), album)
            directory.mkdirs()
            val target = File(directory, name)
            try {
                file.copyTo(target)
            } catch (error: Exception) {
                target.delete()
                throw error
            }
            MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mime), null)
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$album")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("No media destination")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("Cannot open media destination")
            output.use { file.inputStream().use { input -> input.copyTo(it) } }
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) != 1) {
                throw IOException("Unable to publish image")
            }
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}

private class ZoomImageView(context: Context) : ImageView(context) {
    private var zoom = 1f
    private var x = 0f
    private var y = 0f
    private val matrix = Matrix()
    private val fit: Float get() = drawable?.let {
        minOf(width.toFloat() / it.intrinsicWidth, height.toFloat() / it.intrinsicHeight)
    } ?: 1f
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomAt(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
            return true
        }
    })
    private val pan = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            zoomAt(if (zoom > 1f) 1f else 2.5f, e.x, e.y)
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!scale.isInProgress && e2.pointerCount == 1) {
                x -= distanceX
                y -= distanceY
                updateMatrix()
            }
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        updateMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateMatrix()
    }

    private fun zoomAt(value: Float, focusX: Float, focusY: Float) {
        val next = value.coerceIn(1f, 6f)
        val factor = next / zoom
        x = focusX - (focusX - x) * factor
        y = focusY - (focusY - y) * factor
        zoom = next
        updateMatrix()
    }

    private fun updateMatrix() {
        val image = drawable ?: return
        if (width <= 0 || height <= 0) return
        val factor = fit * zoom
        val w = image.intrinsicWidth * factor
        val h = image.intrinsicHeight * factor
        x = if (w <= width) (width - w) / 2f else x.coerceIn(width - w, 0f)
        y = if (h <= height) (height - h) / 2f else y.coerceIn(height - h, 0f)
        matrix.setScale(factor, factor)
        matrix.postTranslate(x, y)
        imageMatrix = matrix
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        pan.onTouchEvent(event)
        return true
    }
}
