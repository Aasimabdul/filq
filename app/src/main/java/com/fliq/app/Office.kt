package com.fliq.app

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

/** An opened .docx/.xlsx/.pptx (a zip of XML parts). Parts are streamed, never loaded whole. */
internal class Pkg(file: File) : Closeable {
    private val z = try {
        ZipFile(file)
    } catch (e: java.util.zip.ZipException) {
        throw IOException("This file is damaged or not a real Office file")
    }

    fun has(name: String) = z.getEntry(name) != null

    fun bytes(name: String, max: Int = 12 * 1024 * 1024): ByteArray? {
        val e = z.getEntry(name) ?: return null
        if (e.size > max) return null
        return z.getInputStream(e).use { it.readBytes() }
    }

    fun parser(name: String): XmlPullParser? {
        val e = z.getEntry(name) ?: return null
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(z.getInputStream(e), null)
        return p
    }

    class Rel(val type: String, val target: String)

    /** Relationship id -> target, for a "_rels/x.rels" part. */
    fun rels(path: String): Map<String, Rel> {
        val out = HashMap<String, Rel>()
        val p = try { parser(path) } catch (e: Exception) { null } ?: return out
        try {
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "Relationship") {
                    val id = p.attr("Id")
                    val t = p.attr("Target")
                    if (id != null && t != null && p.attr("TargetMode") != "External") out[id] = Rel(p.attr("Type") ?: "", t)
                }
                ev = p.next()
            }
        } catch (e: Exception) {
        }
        return out
    }

    override fun close() = z.close()
}

internal fun XmlPullParser.attr(name: String): String? {
    for (i in 0 until attributeCount) if (getAttributeName(i) == name) return getAttributeValue(i)
    return null
}

internal fun resolvePath(baseDir: String, target: String): String {
    if (target.startsWith("/")) return target.substring(1)
    val parts = ArrayList<String>()
    for (s in (baseDir + target).split('/')) {
        when (s) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts.add(s)
        }
    }
    return parts.joinToString("/")
}

internal fun isOn(v: String?): Boolean = v == null || (v != "0" && v != "false" && v != "off" && v != "none")

/** Renders Word, Excel, PowerPoint, CSV and text files onto pages, offline, with Android's own text engine. */
object Office {
    private const val MAX_TEXT = 3 * 1024 * 1024

    /** Draws every page of [s] into [sink]. Throws IOException with a readable message when it can't. */
    fun render(ctx: Context, s: Src, sink: PageSink) {
        when (s.ext.lowercase(Locale.ROOT)) {
            "csv", "txt" -> renderText(ctx, s, sink)
            "docx", "xlsx", "pptx" -> {
                val tmp = File.createTempFile("fliq_", "." + s.ext.lowercase(Locale.ROOT), ctx.cacheDir)
                try {
                    (ctx.contentResolver.openInputStream(s.uri) ?: throw IOException("Cannot open file"))
                        .use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    Pkg(tmp).use { pkg ->
                        when (s.ext.lowercase(Locale.ROOT)) {
                            "docx" -> DocxRender.render(pkg, sink)
                            "xlsx" -> XlsxRender.render(pkg, sink)
                            else -> PptxRender.render(pkg, sink)
                        }
                    }
                } finally {
                    tmp.delete()
                }
            }
            else -> throw IOException("This Office format isn't supported offline (use docx, xlsx or pptx)")
        }
        if (sink.pages == 0) throw IOException("Nothing to show in this file")
    }

    private fun readText(ctx: Context, s: Src): String {
        val bytes = (ctx.contentResolver.openInputStream(s.uri) ?: throw IOException("Cannot open file")).use { i ->
            val buf = ByteArray(MAX_TEXT + 1)
            var n = 0
            while (n < buf.size) {
                val r = i.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            buf.copyOf(min(n, MAX_TEXT))
        }
        var off = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) off = 3
        return try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, off, bytes.size - off)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            String(bytes, off, bytes.size - off, charset("windows-1252"))
        }
    }

    private fun renderText(ctx: Context, s: Src, sink: PageSink) {
        val text = readText(ctx, s)
        if (s.ext.equals("csv", true)) {
            val flow = Flow(sink, 842, 595, 28f)
            renderGrid(flow, null, parseCsv(text), true)
            flow.endPage()
            return
        }
        val flow = Flow(sink, 595, 842, 56f)
        val paint = textPaint(10f, mono = true)
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val end = min(lines.size, i + 150)
            flow.text(lines.subList(i, end).joinToString("\n").replace("\t", "    "), paint, after = 0f)
            i = end
        }
        flow.endPage()
    }

    // ---------- CSV ----------

    internal fun parseCsv(text: String): List<List<String>> {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val delim = listOf(',', ';', '\t').maxByOrNull { d -> firstLine.count { it == d } }
            ?.takeIf { d -> firstLine.contains(d) } ?: ','
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        val n = text.length
        while (i < n && rows.size < MAX_ROWS) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < n && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> quoted = true
                delim -> { row.add(cell.toString()); cell.setLength(0) }
                '\n', '\r' -> {
                    if (c == '\r' && i + 1 < n && text[i + 1] == '\n') i++
                    row.add(cell.toString()); cell.setLength(0)
                    rows.add(row); row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    // ---------- shared by CSV and Excel ----------

    internal const val MAX_ROWS = 5000
    internal const val MAX_COLS = 40

    /** Draws [rows] as a bordered table; columns that don't fit the page width continue on following pages. */
    internal fun renderGrid(flow: Flow, title: String?, rows: List<List<String>>, header: Boolean) {
        val paint = textPaint(9f)
        val bold = textPaint(9f, bold = true)
        if (title != null) flow.text(title, textPaint(13f, bold = true), after = 8f)
        val ncols = min(MAX_COLS, rows.maxOfOrNull { it.size } ?: 0)
        if (ncols == 0) {
            flow.text("(empty)", paint)
            return
        }
        val widths = FloatArray(ncols) { 30f }
        for (r in rows.take(300)) {
            for (c in 0 until min(ncols, r.size)) {
                val t = r[c]
                if (t.isEmpty()) continue
                var longest = 0f
                for (ln in t.take(200).split('\n')) longest = max(longest, paint.measureText(ln))
                widths[c] = max(widths[c], min(200f, longest + 10f))
            }
        }
        // split the columns into page-wide groups
        var start = 0
        var first = true
        while (start < ncols) {
            var end = start
            var sum = 0f
            while (end < ncols && (end == start || sum + widths[end] <= flow.contentW)) { sum += widths[end]; end++ }
            if (!first) flow.newPage()
            first = false
            val slice = rows.map { r -> List(end - start) { k -> r.getOrElse(start + k) { "" } as CharSequence } }
            flow.table(slice, widths.copyOfRange(start, end), paint, bold, header)
            start = end
        }
    }
}
