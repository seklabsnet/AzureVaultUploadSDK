package com.company.upload.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

internal class AndroidImageCompressor(private val context: Context) {

    fun compress(uri: Uri, maxWidth: Int = 1920, maxHeight: Int = 1920, quality: Int = 85): ByteArray {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI: $uri")

        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()

        val sampleSize = calculateSampleSize(options.outWidth, options.outHeight, maxWidth, maxHeight)

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val stream2 = context.contentResolver.openInputStream(uri)!!
        val bitmap = BitmapFactory.decodeStream(stream2, null, decodeOptions)!!
        stream2.close()

        val scaled = scaleBitmap(bitmap, maxWidth, maxHeight)

        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)

        if (scaled != bitmap) scaled.recycle()
        bitmap.recycle()

        return output.toByteArray()
    }

    private fun calculateSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var sampleSize = 1
        if (width > reqWidth || height > reqHeight) {
            val halfWidth = width / 2
            val halfHeight = height / 2
            while (halfWidth / sampleSize >= reqWidth && halfHeight / sampleSize >= reqHeight) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    private fun scaleBitmap(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        if (bitmap.width <= maxWidth && bitmap.height <= maxHeight) return bitmap
        val ratio = minOf(maxWidth.toFloat() / bitmap.width, maxHeight.toFloat() / bitmap.height)
        val newWidth = (bitmap.width * ratio).toInt()
        val newHeight = (bitmap.height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}
