package com.example.wiprober

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri

internal data class NoteImageMetadata(
    val width: Int,
    val height: Int,
    val format: String
)

/** Reads note-photo metadata off the UI thread without decoding the bitmap pixels. */
internal class NoteImageMetadataReader(
    private val contentResolver: ContentResolver
) {
    fun read(uri: Uri): NoteImageMetadata {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        requireNotNull(contentResolver.openInputStream(uri)) { "Cannot open note image" }.use { input ->
            ByteLimitInputStream(input, MAX_IMAGE_BYTES, "Note image is too large").use { bounded ->
                BitmapFactory.decodeStream(bounded, null, options)
            }
        }
        require(options.outWidth > 0 && options.outHeight > 0) { "Unsupported note image" }
        require(options.outWidth <= MAX_IMAGE_DIMENSION && options.outHeight <= MAX_IMAGE_DIMENSION) {
            "Note image dimensions are too large"
        }
        require(options.outWidth.toLong() * options.outHeight.toLong() <= MAX_IMAGE_PIXELS) {
            "Note image contains too many pixels"
        }
        val format = when (options.outMimeType?.lowercase()) {
            "image/png" -> "PNG"
            "image/jpeg" -> "JPEG"
            "image/webp" -> "WEBP"
            "image/heif", "image/heic" -> "HEIC"
            else -> throw IllegalArgumentException("Unsupported note image format")
        }
        return NoteImageMetadata(options.outWidth, options.outHeight, format)
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 50L * 1024L * 1024L
        const val MAX_IMAGE_DIMENSION = 30_000
        const val MAX_IMAGE_PIXELS = 120_000_000L
    }
}
