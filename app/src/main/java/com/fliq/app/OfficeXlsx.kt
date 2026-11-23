package com.fliq.app

import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Excel (.xlsx): every visible sheet as a table (values as shown by Excel's stored results; dates converted). */
internal object XlsxRender {
    fun render(pkg: Pkg, sink: PageSink) {
        val rels = pkg.rels("xl/_rels/workbook.xml.rels")
        val sheets = ArrayList<Pair<String, String>>()
        pkg.parser("xl/workbook.xml")?.let { p ->
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && p.name == "sheet" && p.attr("state") != "hidden" && p.attr("state") != "veryHidden") {
                    val target = p.attr("id")?.let { rels[it] }?.target
                    if (target != null) sheets.add((p.attr("name") ?: "Sheet") to resolvePath("xl/", target))
                }
                ev = p.next()
            }
        }
        if (sheets.isEmpty()) {
            for (i in 1..20) if (pkg.has("xl/worksheets/sheet$i.xml")) sheets.add("Sheet $i" to "xl/worksheets/sheet$i.xml")
        }
        if (sheets.isEmpty()) throw IOException("No sheets found in this file")

        val shared = readShared(pkg)
        val dates = dateStyles(pkg)
        val flow = Flow(sink, 842, 595, 28f)
        for ((i, s) in sheets.withIndex()) {
            if (i > 0) flow.newPage()
            val rows = readSheet(pkg, s.second, shared, dates)
            Office.renderGrid(flow, if (sheets.size > 1) s.first else null, rows, false)
        }
        flow.endPage()
    }

    private fun readShared(pkg: Pkg): List<String> {
        val out = ArrayList<String>()
        val p = pkg.parser("xl/sharedStrings.xml") ?: return out
        var sb: StringBuilder? = null
        var skip = -1
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT && out.size < 300_000) {
            if (ev == XmlPullParser.START_TAG && skip < 0) {
                when (p.name) {
                    "si" -> sb = StringBuilder()
                    "rPh" -> skip = p.depth
                    "t" -> sb?.append(p.nextText())
                }
            } else if (ev == XmlPullParser.END_TAG) {
                if (skip >= 0) {
                    if (p.depth == skip) skip = -1
                } else if (p.name == "si") {
                    out.add(sb?.toString().orEmpty())
                    sb = null
                }
            }
            ev = p.next()
        }
        return out
    }

    /** For each cell style (xf index): is it a date/time format? */
    private fun dateStyles(pkg: Pkg): BooleanArray {
        val p = try { pkg.parser("xl/styles.xml") } catch (e: Exception) { null } ?: return BooleanArray(0)
        val codes = HashMap<Int, String>()
        val flags = ArrayList<Boolean>()
        var inXfs = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "numFmt" -> {
                        val id = p.attr("numFmtId")?.toIntOrNull()
                        val code = p.attr("formatCode")
                        if (id != null && code != null) codes[id] = code
                    }
                    "cellXfs" -> inXfs = true
                    "xf" -> if (inXfs) {
                        val id = p.attr("numFmtId")?.toIntOrNull() ?: 0
                        flags.add(id in 14..22 || id in 45..47 || (codes[id]?.let { looksLikeDate(it) } ?: false))
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "cellXfs") {
                inXfs = false
            }
            ev = p.next()
        }
        return flags.toBooleanArray()
    }

    private fun looksLikeDate(code: String): Boolean {
        val c = code.replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^\\]]*\\]"), "").replace(Regex("\\\\."), "").lowercase()
        return c.any { it == 'y' || it == 'd' || it == 'h' || it == 's' }
    }

    private fun readSheet(pkg: Pkg, path: String, shared: List<String>, dates: BooleanArray): List<List<String>> {
        val p = pkg.parser(path) ?: return emptyList()
        val rows = ArrayList<List<String>>()
        var cur: ArrayList<String>? = null
        var lastRow = 0
        var col = 0
        var type: String? = null
        var style = -1
        var value = ""
        var inIs = false
        var cells = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT && rows.size < Office.MAX_ROWS && cells < 150_000) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "row" -> {
                        val rn = p.attr("r")?.toIntOrNull() ?: (lastRow + 1)
                        var gap = rn - lastRow - 1
                        while (gap > 0 && gap <= 3 && rows.isNotEmpty()) {
                            rows.add(emptyList())
                            gap--
                        }
                        lastRow = rn
                        cur = ArrayList()
                    }
                    "c" -> {
                        col = columnOf(p.attr("r"), cur?.size ?: 0)
                        type = p.attr("t")
                        style = p.attr("s")?.toIntOrNull() ?: -1
                        value = ""
                    }
                    "is" -> inIs = true
                    "t" -> if (inIs) value += p.nextText()
                    "v" -> value = convert(p.nextText(), type, style, shared, dates)
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "is" -> inIs = false
                    "c" -> {
                        val row = cur
                        if (row != null && col < Office.MAX_COLS) {
                            while (row.size < col) row.add("")
                            if (row.size == col) row.add(value) else row[col] = value
                            cells++
                        }
                    }
                    "row" -> {
                        cur?.let { rows.add(it) }
                        cur = null
                    }
                }
            }
            ev = p.next()
        }
        // drop trailing empty rows
        while (rows.isNotEmpty() && rows.last().all { it.isEmpty() }) rows.removeAt(rows.size - 1)
        if (rows.size >= Office.MAX_ROWS) rows.add(listOf("… more rows are not shown"))
        return rows
    }

    private fun columnOf(ref: String?, fallback: Int): Int {
        if (ref == null) return fallback
        var n = 0
        var any = false
        for (ch in ref) {
            if (ch in 'A'..'Z') { n = n * 26 + (ch - 'A' + 1); any = true } else if (ch in 'a'..'z') { n = n * 26 + (ch - 'a' + 1); any = true } else break
        }
        return if (any) n - 1 else fallback
    }

    private fun convert(v: String, type: String?, style: Int, shared: List<String>, dates: BooleanArray): String {
        return when (type) {
            "s" -> v.trim().toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
            "b" -> if (v.trim() == "1") "TRUE" else "FALSE"
            "str", "e", "inlineStr" -> v
            else -> {
                val d = v.toDoubleOrNull() ?: return v
                if (style in dates.indices && dates[style] && d > 0 && d < 2958466) dateOf(d)
                else if (d == Math.rint(d) && abs(d) < 1e15) d.toLong().toString()
                else BigDecimal(d).round(MathContext(10)).stripTrailingZeros().toPlainString()
            }
        }
    }

    private fun dateOf(v: Double): String {
        val days = floor(v).toLong()
        val frac = v - days
        val time = if (frac > 0.00001) {
            val secs = (frac * 86400).roundToInt()
            String.format(java.util.Locale.US, "%02d:%02d", (secs / 3600) % 24, (secs / 60) % 60)
        } else null
        if (days < 1) return time ?: "00:00"
        val d = LocalDate.of(1899, 12, 30).plusDays(days).toString()
        return if (time != null) "$d $time" else d
    }
}
