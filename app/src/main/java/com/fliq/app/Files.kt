package com.fliq.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.util.Locale

enum class Kind { IMAGE, SVG, PDF, OFFICE, OTHER }

fun extOf(name: String): String {
    val i = name.lastIndexOf('.')
    if (i <= 0 || name.length - i - 1 > 8) return ""
    val e = name.substring(i + 1)
    return if (e.all { it.isLetterOrDigit() }) e else ""
}

fun formatSize(b: Long): String = when {
    b < 0 -> ""
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
}

/** One shared file. [dir] is the original folder (e.g. "Pictures/Screenshots/") when it can be found. */
data class Src(val uri: Uri, val name: String, val mime: String, val size: Long, val dir: String?) {
    val ext: String get() = extOf(name)
    val base: String get() = if (ext.isEmpty()) name else name.dropLast(ext.length + 1)

    val kind: Kind
        get() {
            val e = ext.lowercase(Locale.ROOT)
            return when {
                e == "svg" || mime.equals("image/svg+xml", ignoreCase = true) -> Kind.SVG
                e == "pdf" || mime.equals("application/pdf", ignoreCase = true) -> Kind.PDF
                e in OFFICE_EXT -> Kind.OFFICE
                e in IMAGE_EXT || mime.startsWith("image/", ignoreCase = true) -> Kind.IMAGE
                else -> Kind.OTHER
            }
        }

    companion object {
        // Android ImageDecoder handles these common raster formats; actual decoding can
        // still depend on the OS version/device codec (notably HEIF and AVIF).
        private val IMAGE_EXT = setOf(
            "jpg", "jpeg", "jpe", "jfif",
            "png", "webp", "bmp", "gif",
            "heic", "heif", "avif"
        )
        private val OFFICE_EXT = setOf("doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp")

        fun query(ctx: Context, uri: Uri): Src {
            var n: String? = null
            var sz = -1L
            try {
                ctx.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0 && !c.isNull(ni)) n = c.getString(ni)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (si >= 0 && !c.isNull(si)) sz = c.getLong(si)
                    }
                }
            } catch (_: Exception) {
                // Providers may not expose metadata; fall back to the URI and MIME type.
            }
            var name = n ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            val mime = ctx.contentResolver.getType(uri)
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(name).lowercase(Locale.ROOT))
                ?: "application/octet-stream"
            if (extOf(name).isEmpty()) {
                MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { name = "$name.$it" }
            }
            return Src(uri, name, mime, sz, Place.dirOf(ctx, uri))
        }
    }
}
