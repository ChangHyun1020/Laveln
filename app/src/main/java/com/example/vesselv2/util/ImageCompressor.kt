package com.example.vesselv2.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import androidx.core.graphics.scale

/**
 * ImageCompressor - 이미지 리사이징 및 JPEG 압축 유틸리티
 */
object ImageCompressor {

    private const val MAX_DIMENSION = 1920   // 최대 해상도 제한
    private const val JPEG_QUALITY = 90      // JPEG 품질 (0~100)

    /**
     * URI로부터 비트맵을 읽어 압축된 ByteArray 반환
     */
    fun compressFromUri(context: Context, uri: Uri): ByteArray? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            val originalWidth = options.outWidth
            val originalHeight = options.outHeight
            if (originalWidth <= 0 || originalHeight <= 0) return null

            options.inSampleSize = calculateInSampleSize(originalWidth, originalHeight)
            options.inJustDecodeBounds = false

            val sampledBitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            compressBitmap(sampledBitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * ByteArray로부터 비트맵을 읽어 압축된 ByteArray 반환 (벌크 작업용)
     */
    fun compressFromBytes(data: ByteArray): ByteArray? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)

            val originalWidth = options.outWidth
            val originalHeight = options.outHeight
            if (originalWidth <= 0 || originalHeight <= 0) return null

            options.inSampleSize = calculateInSampleSize(originalWidth, originalHeight)
            options.inJustDecodeBounds = false

            val sampledBitmap = BitmapFactory.decodeByteArray(data, 0, data.size, options)
                ?: return null

            compressBitmap(sampledBitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var inSampleSize = 1
        val longerSide = maxOf(width, height)
        while (longerSide / inSampleSize > MAX_DIMENSION * 2) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun compressBitmap(bitmap: Bitmap): ByteArray {
        val resized = resizeBitmap(bitmap)
        val outputStream = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        if (resized !== bitmap) resized.recycle()
        bitmap.recycle()
        return outputStream.toByteArray()
    }

    private fun resizeBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val longerSide = maxOf(width, height)
        if (longerSide <= MAX_DIMENSION) return bitmap

        val ratio = MAX_DIMENSION.toFloat() / longerSide
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return bitmap.scale(newWidth, newHeight)
    }
}
