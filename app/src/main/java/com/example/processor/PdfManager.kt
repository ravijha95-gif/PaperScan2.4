package com.example.processor

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

enum class PdfPageSize {
    A4,       // Standard 595 x 842 points (72 dpi)
    AUTO_FIT  // Native image aspect ratio and bounds
}

enum class PdfQuality(val label: String, val scaleFactor: Float, val compressQuality: Int) {
    HIGH("High Quality (Original)", 1.0f, 95),
    REGULAR("Regular (Balanced)", 0.75f, 85),
    LOW("Compact (Email friendly)", 0.5f, 65)
}

object PdfManager {

    /**
     * Generates a multi-page PDF from a list of processed image paths.
     * Respects A4 or Auto-fit page sizing and selected compression quality.
     */
    suspend fun generatePdf(
        context: Context,
        pageImagePaths: List<String>,
        documentTitle: String,
        pageSize: PdfPageSize = PdfPageSize.A4,
        quality: PdfQuality = PdfQuality.REGULAR
    ): File = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()

        val safeTitle = documentTitle.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val timestamp = System.currentTimeMillis()
        val pdfsDir = File(context.filesDir, "pdfs").apply { if (!exists()) mkdirs() }
        val outputFile = File(pdfsDir, "${safeTitle}_${timestamp}.pdf")

        // Standard A4 dimensions in PostScript points (1/72 inch): 595 x 842
        val a4Width = 595
        val a4Height = 842
        val margin = 20

        var pageIndex = 1

        for (imagePath in pageImagePaths) {
            val file = File(imagePath)
            if (!file.exists()) continue

            // Load bitmap
            val originalBmp = BitmapFactory.decodeFile(imagePath) ?: continue

            // Apply quality scale if needed
            val bmp = if (quality.scaleFactor < 1.0f) {
                val newW = (originalBmp.width * quality.scaleFactor).toInt().coerceAtLeast(100)
                val newH = (originalBmp.height * quality.scaleFactor).toInt().coerceAtLeast(100)
                val scaled = Bitmap.createScaledBitmap(originalBmp, newW, newH, true)
                if (scaled !== originalBmp) originalBmp.recycle()
                scaled
            } else {
                originalBmp
            }

            val (pageWidth, pageHeight, destRect) = when (pageSize) {
                PdfPageSize.A4 -> {
                    // Compute aspect-fit inside A4 printable area
                    val maxW = (a4Width - margin * 2).toFloat()
                    val maxH = (a4Height - margin * 2).toFloat()

                    val scale = min(maxW / bmp.width, maxH / bmp.height)
                    val drawW = (bmp.width * scale).toInt()
                    val drawH = (bmp.height * scale).toInt()

                    val left = margin + (maxW - drawW).toInt() / 2
                    val top = margin + (maxH - drawH).toInt() / 2
                    val rect = Rect(left, top, left + drawW, top + drawH)
                    Triple(a4Width, a4Height, rect)
                }
                PdfPageSize.AUTO_FIT -> {
                    // Match image dimensions scaled down to sensible point size (max 800 width)
                    val scale = if (bmp.width > 800) 800f / bmp.width else 1.0f
                    val w = (bmp.width * scale).toInt()
                    val h = (bmp.height * scale).toInt()
                    val rect = Rect(0, 0, w, h)
                    Triple(w, h, rect)
                }
            }

            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas: Canvas = page.canvas

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(bmp, null, destRect, paint)

            pdfDocument.finishPage(page)
            bmp.recycle()
            pageIndex++
        }

        FileOutputStream(outputFile).use { out ->
            pdfDocument.writeTo(out)
        }
        pdfDocument.close()

        outputFile
    }

    /**
     * Creates an Android ACTION_SEND share Intent for the generated PDF via FileProvider.
     */
    fun createSharePdfIntent(context: Context, pdfFile: File, title: String): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdfFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Creates an Android ACTION_SEND share Intent for a single page JPEG image via FileProvider.
     */
    fun createShareImageIntent(context: Context, imageFile: File, title: String): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )

        return Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Creates an Android ACTION_SEND_MULTIPLE share Intent for all multi-page JPEG images.
     */
    fun createShareMultipleImagesIntent(context: Context, imageFiles: List<File>, title: String): Intent {
        val uris = ArrayList<Uri>()
        for (file in imageFiles) {
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                uris.add(uri)
            }
        }

        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Copies and saves exported high-res JPEGs to the public Pictures/PaperScan directory
     * so they are immediately accessible in the system Gallery app.
     */
    suspend fun saveJpegsToGallery(
        context: Context,
        imageFiles: List<File>,
        documentTitle: String
    ): Int = withContext(Dispatchers.IO) {
        var savedCount = 0
        val safeDocName = documentTitle.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val timestamp = System.currentTimeMillis()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val contentResolver = context.contentResolver
            for ((idx, file) in imageFiles.withIndex()) {
                if (!file.exists()) continue
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "${safeDocName}_page_${idx + 1}_$timestamp.jpg")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PaperScan")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    savedCount++
                }
            }
        } else {
            val picturesDir = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
                "PaperScan"
            ).apply { if (!exists()) mkdirs() }

            for ((idx, file) in imageFiles.withIndex()) {
                if (!file.exists()) continue
                val destFile = File(picturesDir, "${safeDocName}_page_${idx + 1}_$timestamp.jpg")
                file.copyTo(destFile, overwrite = true)
                savedCount++
            }
        }
        savedCount
    }
}
