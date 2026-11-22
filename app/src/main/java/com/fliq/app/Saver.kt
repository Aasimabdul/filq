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

/** [sameDir] is false when Android did not let us write next to the original and we used the fallback folder. */
data class Saved(val uri: Uri, val name: String, val mime: String, val where: String, val sameDir: Boolean)

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

    /**
     * Saves into [dir] (the original folder) when Android allows it for this file type,
     * otherwise into Download/Fliq.
     */
    fun save(ctx: Context, dir: String?, name: String, mime: String, write: (OutputStream) -> Unit): Saved {
        if (!dir.isNullOrEmpty()) {
            val coll = collectionFor(dir, mime)
            if (coll != null) {
                try {
                    return insert(ctx, coll, dir, name, mime, true, write)
                } catch (e: IllegalArgumentException) {
                } catch (e: SecurityException) {
                }
            }
        }
        return insert(ctx, MediaStore.Downloads.EXTERNAL_CONTENT_URI, FALLBACK, name, mime, false, write)
    }

    // Scoped storage only lets an app create files in certain folders per type.
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
        val uri = r.insert(coll, v) ?: throw IOException("Cannot create file")
        try {
            (r.openOutputStream(uri) ?: throw IOException("Cannot write file")).buffered().use { write(it) }
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            try {
                r.delete(uri, null, null)
            } catch (x: Exception) {
            }
            throw e
        }
        return Saved(uri, nameOf(ctx, uri) ?: name, mime, dir.trimEnd('/'), sameDir)
    }

    private fun nameOf(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }
}
