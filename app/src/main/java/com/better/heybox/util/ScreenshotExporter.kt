package com.better.heybox.util

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.TypedValue
import com.better.heybox.App
import com.better.heybox.HeyboxPrefs
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

object ScreenshotExporter {

    private const val FOLDER_NAME = "Heybox"
    private const val FILE_PREFIX = "heybox_screenshot_"
    private const val MIME_TYPE = "image/png"

    private const val SHARE_DIR_NAME = "share"
    private const val SHARE_FILE_TTL_MS = 60L * 60L * 1000L

    private fun interface BitmapSink {
        @Throws(Exception::class)
        fun write(bitmap: Bitmap): Uri?
    }

    @JvmStatic
    fun saveScreenshot(screenshot: Bitmap?, context: Context?): Uri? {
        if (screenshot == null || context == null || screenshot.isRecycled) {
            return null
        }
        return exportWithWatermark(screenshot, context) { bitmap ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(bitmap, context)
            } else {
                saveToLegacyStorage(bitmap, context)
            }
        }
    }

    @JvmStatic
    fun prepareShareScreenshot(screenshot: Bitmap?, context: Context?): Uri? {
        if (screenshot == null || context == null || screenshot.isRecycled) {
            return null
        }
        return exportWithWatermark(screenshot, context) { bitmap -> writeShareFile(bitmap, context) }
    }

    private fun exportWithWatermark(
        screenshot: Bitmap,
        context: Context,
        sink: BitmapSink
    ): Uri? {
        var finalBitmap = screenshot
        var watermarked = false
        try {
            if (HeyboxPrefs.getBoolean(App.KEY_SCREENSHOT_ADD_WATERMARK, false)) {
                finalBitmap = addWatermark(screenshot, context)
                watermarked = finalBitmap !== screenshot
            }
            return sink.write(finalBitmap)
        } catch (ignored: Throwable) {
            return null
        } finally {
            if (watermarked && !finalBitmap.isRecycled) {
                finalBitmap.recycle()
            }
        }
    }

    @Throws(Exception::class)
    private fun writeShareFile(bitmap: Bitmap, context: Context): Uri? {
        val dir = shareDir(context) ?: return null
        pruneShareDir(dir)

        val outputFile = File(dir, FILE_PREFIX + System.currentTimeMillis() + ".png")
        val written: Boolean
        FileOutputStream(outputFile).use { out ->
            written = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (!written) {
            outputFile.delete()
            return null
        }

        val uri = HostFileProvider.getUriForFile(context, outputFile)
        if (uri == null) {
            outputFile.delete()
        }
        return uri
    }

    private fun shareDir(context: Context): File? {
        val base = context.cacheDir ?: return null
        val dir = File(base, SHARE_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            return null
        }
        return dir
    }

    private fun pruneShareDir(dir: File) {
        try {
            val files = dir.listFiles() ?: return
            val deadline = System.currentTimeMillis() - SHARE_FILE_TTL_MS
            for (file in files) {
                if (file.isFile && file.lastModified() < deadline) {
                    file.delete()
                }
            }
        } catch (ignored: Throwable) {
        }
    }

    @JvmStatic
    fun shareScreenshot(context: Context?, uri: Uri?) {
        if (context == null || uri == null) {
            return
        }
        try {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = MIME_TYPE
            intent.putExtra(Intent.EXTRA_STREAM, uri)
            intent.clipData = ClipData.newRawUri("screenshot", uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val chooser = Intent.createChooser(intent, "分享截图")
            chooser.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            context.startActivity(chooser)
        } catch (ignored: Throwable) {
        }
    }

    @Throws(Exception::class)
    private fun saveToMediaStore(bitmap: Bitmap, context: Context): Uri? {
        val filename = FILE_PREFIX + System.currentTimeMillis() + ".png"

        val values = ContentValues()
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        values.put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
        values.put(
            MediaStore.Images.Media.RELATIVE_PATH,
            Environment.DIRECTORY_PICTURES + File.separator + FOLDER_NAME
        )

        val collection =
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val itemUri = context.contentResolver.insert(collection, values) ?: return null

        context.contentResolver.openOutputStream(itemUri).use { out ->
            if (out == null) {
                return null
            }
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                return null
            }
        }
        return itemUri
    }

    @Throws(Exception::class)
    private fun saveToLegacyStorage(bitmap: Bitmap, context: Context): Uri? {
        val picturesDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val heyboxDir = File(picturesDir, FOLDER_NAME)
        if (!heyboxDir.exists() && !heyboxDir.mkdirs()) {
            return null
        }

        val filename = FILE_PREFIX + System.currentTimeMillis() + ".png"
        val outputFile = File(heyboxDir, filename)

        FileOutputStream(outputFile).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                return null
            }
        }

        val values = ContentValues()
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        values.put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
        values.put(MediaStore.Images.Media.DATA, outputFile.absolutePath)
        try {
            val registered = context.contentResolver
                .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (registered != null) {
                return registered
            }
        } catch (ignored: Throwable) {
        }
        return Uri.fromFile(outputFile)
    }

    private fun addWatermark(source: Bitmap, context: Context): Bitmap {
        return try {
            val config = source.config ?: Bitmap.Config.ARGB_8888
            val watermarked = Bitmap.createBitmap(source.width, source.height, config)
            val canvas = Canvas(watermarked)
            canvas.drawBitmap(source, 0f, 0f, null)

            val density = context.resources.displayMetrics.density
            var textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 11f, context.resources.displayMetrics
            )
            if (textSize <= 0f) {
                textSize = 11f * density
            }

            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.argb(110, 0, 0, 0)
            paint.textSize = textSize
            paint.setShadowLayer(maxOf(1f, density), 0f, 0f, Color.argb(60, 255, 255, 255))

            val watermark = "BetterHeybox"
            val textWidth = paint.measureText(watermark)
            val margin = 8f * density
            val x = maxOf(margin, canvas.width - textWidth - margin)
            val y = canvas.height - margin

            canvas.drawText(watermark, x, y, paint)
            watermarked
        } catch (ignored: Throwable) {
            source
        }
    }
}
