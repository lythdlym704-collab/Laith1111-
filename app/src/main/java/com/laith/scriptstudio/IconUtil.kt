package com.laith.scriptstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/** يحوّل أي صورة يختارها المستخدم إلى PNG مربع 512 بكسل (أداة البناء ترفض JPEG بامتداد png). */
object IconUtil {

    private const val SIZE = 512

    fun saveFromUri(context: Context, uri: Uri, dest: File): Boolean {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
            val bmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false
            val png = toPng(bmp) ?: return false
            dest.parentFile?.mkdirs()
            dest.writeBytes(png)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun pngBytes(file: File): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
            toPng(bmp)
        } catch (e: Exception) {
            null
        }
    }

    private fun sampleSize(w: Int, h: Int): Int {
        var s = 1
        var cw = w
        var ch = h
        while (cw / 2 >= SIZE * 2 && ch / 2 >= SIZE * 2) {
            cw /= 2
            ch /= 2
            s *= 2
        }
        return s
    }

    private fun toPng(src: Bitmap): ByteArray? {
        val side = minOf(src.width, src.height)
        if (side <= 0) return null
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val square = Bitmap.createBitmap(src, x, y, side, side)
        val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
