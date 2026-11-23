package com.fliq.app

import android.content.Context
import android.webkit.MimeTypeMap
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Renamer {
    private val COPY = Regex("(?i)\\bcopy( of)?\\b")
    private val NUM_SUFFIX = Regex("\\(\\d+\\)")
    private val SEPARATORS = Regex("[_\\-.]+")
    private val SPACES = Regex("\\s+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Removes characters Android file names can't contain and trims the result. */
    fun sanitize(s: String): String =
        s.filter { it.code >= 32 && it !in "\\/:*?\"<>|" }
            .replace(SPACES, " ").trim().trim('.').take(120).trim()

    /** "IMG_2024_(1) copy" -> "IMG 2024" */
    fun clean(base: String): String {
        val s = base.replace(COPY, " ").replace(NUM_SUFFIX, " ").replace(SEPARATORS, " ")
            .replace(SPACES, " ").trim()
        return s.ifEmpty { base }
    }

    /** "My Photo (1)" -> "my_photo_1" */
    fun snake(base: String): String {
        val s = base.lowercase(Locale.ROOT).replace(NOT_WORD, "_").trim('_')
        return s.ifEmpty { base }
    }

    /** "report_final" -> "2026-11-23 report final" */
    fun dated(base: String): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + " " + clean(base)

    /**
     * Saves a renamed copy of every file next to the original.
     * Files that already have that name are skipped, and one failing file doesn't stop the rest.
     */
    fun renameAll(ctx: Context, srcs: List<Src>, newBase: (Src, Int) -> String): JobResult {
        val saved = ArrayList<Saved>()
        val failed = ArrayList<String>()
        srcs.forEachIndexed { i, s ->
            val base = sanitize(newBase(s, i)).ifEmpty { s.base }
            val name = if (s.ext.isEmpty()) base else base + "." + s.ext
            if (name == s.name) {
                failed += s.name + ": already has this name"
                return@forEachIndexed
            }
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(s.ext.lowercase(Locale.ROOT))
                ?: s.mime.takeIf { it.isNotBlank() && !it.endsWith("/*") }
                ?: "application/octet-stream"
            try {
                saved += Saver.save(ctx, s.dir, name, mime) { os ->
                    (ctx.contentResolver.openInputStream(s.uri) ?: throw IOException("Cannot open file"))
                        .use { it.copyTo(os) }
                }
            } catch (e: Exception) {
                failed += s.name + ": " + (e.message ?: "failed")
            }
        }
        return JobResult(saved, failed)
    }
}
