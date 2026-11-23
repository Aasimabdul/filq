package com.fliq.app

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/** [sameDir] is false when Android did not let us write next to the original folder. */
data class Saved(val uri: Uri, val name: String, val mime: String, val where: String, val sameDir: Boolean)

/** Saved outputs and per-file failures from a conversion job. */
class JobResult(val saved: List<Saved>, val failed: List<String> = emptyList())

/** Finds the folder a shared file lives in, as a MediaStore relative path like "Pictures/Screenshots/". */
object Place {
    fun dirOf(ctx: Context, uri: Uri): String? {
        try {
            if (uri.authority == MediaStore.AUTHORITY) return mediaDir(ctx, uri)

            if (DocumentsContract.isDocumentUri(ctx, uri)) {
                val id = DocumentsContract.getDocumentId(uri)
                when (uri.authority) {
                    "com.android.externalstorage.documents" ->
                        if (id.startsWith("primary:")) return parent(id.substringAfter(':'))

                    "com.android.providers.media.documents" -> {
                        val num = id.substringAfter(':').toLongOrNull() ?: return null
                        val base = when (id.substringBefore(':')) {
                            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            "document" -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
                            else -> return null
                        }
                        return mediaDir(ctx, ContentUris.withAppendedId(base, num))
                    }
                }
            }

            // Many file managers put the real path inside the shared URI.
            val path = uri.path ?: return null
            val marker = "/storage/emulated/0/"
            val i = path.indexOf(marker)
            if (i >= 0) return parent(path.substring(i + marker.length))
        } catch (e: Exception) {
            // Folder discovery is best-effort; saving falls back to Download/Fliq.
        }
        return null
    }

    private fun mediaDir(ctx: Context, uri: Uri): String? =
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }

    private fun parent(p: String): String {
        val i = p.lastIndexOf('/')
        return if (i < 0) "" else p.substring(0, i + 1)
    }
}

object Saver {
    const val FALLBACK = "Download/Fliq/"

    /** Save in the original folder when Android permits it, otherwise use Download/Fliq. */
    fun save(ctx: Context, dir: String?, name: String, mime: String, write: (OutputStream) -> Unit): Saved {
        if (!dir.isNullOrEmpty()) {
            val coll = collectionFor(dir, mime)
            if (coll != null) {
                try {
                    return insert(ctx, coll, dir, name, mime, true, write)
                } catch (e: IllegalArgumentException) {
                    // Unsupported collection/path: try the fallback location.
                } catch (e: SecurityException) {
                    // Scoped-storage restrictions: try the fallback location.
                }
            }
        }
        return insert(ctx, MediaStore.Downloads.EXTERNAL_CONTENT_URI, FALLBACK, name, mime, false, write)
    }

    private fun collectionFor(dir: String, mime: String): Uri? {
        val top = dir.substringBefore('/')
        val media = mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")
        return when {
            top == Environment.DIRECTORY_DOWNLOADS -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            (top == Environment.DIRECTORY_PICTURES || top == Environment.DIRECTORY_DCIM) && mime.startsWith("image/") ->
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            top == Environment.DIRECTORY_DOCUMENTS && !media ->
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> null
        }
    }

    private fun insert(
        ctx: Context, coll: Uri, dir: String, name: String, mime: String, sameDir: Boolean,
        write: (OutputStream) -> Unit
    ): Saved {
        val r = ctx.contentResolver
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (dir.endsWith("/")) dir else "$dir/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = r.insert(coll, v) ?: throw IOException("Cannot create output file")
        try {
            (r.openOutputStream(uri) ?: throw IOException("Cannot write output file")).buffered().use { write(it) }
            validateOutput(ctx, uri, mime)
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            try {
                r.delete(uri, null, null)
            } catch (_: Exception) {
            }
            throw e
        }
        return Saved(uri, nameOf(ctx, uri) ?: name, mime, dir.trimEnd('/'), sameDir)
    }

    /**
     * Reject empty output and check the file signature for formats this app generates.
     * This is a lightweight corruption guard, not a substitute for opening the file in a reader.
     */
    private fun validateOutput(ctx: Context, uri: Uri, mime: String) {
        val prefix = (ctx.contentResolver.openInputStream(uri)
            ?: throw IOException("Output validation failed: file cannot be read")).use { input ->
            val bytes = ByteArray(16)
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n <= 0) break
                count += n
            }
            if (count == 0) throw IOException("Output validation failed: the file is empty")
            bytes.copyOf(count)
        }

        fun starts(vararg expected: Int): Boolean =
            prefix.size >= expected.size && expected.indices.all { (prefix[it].toInt() and 0xff) == expected[it] }

        val valid = when (mime.lowercase(Locale.ROOT)) {
            "application/pdf" -> prefix.size >= 5 && String(prefix, 0, 5, Charsets.US_ASCII) == "%PDF-"
            "image/jpeg" -> starts(0xff, 0xd8, 0xff)
            "image/png" -> starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
            "image/webp" -> prefix.size >= 12 &&
                String(prefix, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(prefix, 8, 4, Charsets.US_ASCII) == "WEBP"
            "image/bmp" -> starts(0x42, 0x4d)
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                starts(0x50, 0x4b, 0x03, 0x04)
            else -> true // Unknown/rename-only formats still must be non-empty.
        }
        if (!valid) throw IOException("Output validation failed: the file does not match $mime")
    }

    private fun nameOf(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
