package com.fliq.app

import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Word (.docx): headings, bold/italic/underline/size/colour, alignment, bullets, tables and pictures. */
internal object DocxRender {
    private class Tbl {
        val rows = ArrayList<List<CharSequence>>()
        var cur: ArrayList<CharSequence>? = null
        var cell: SpannableStringBuilder? = null
    }

    private class Pic(val path: String, val w: Float, val h: Float)

    private val HEAD = floatArrayOf(22f, 18f, 15f, 13f, 12f, 11.5f)

    fun render(pkg: Pkg, sink: PageSink) {
        val rels = pkg.rels("word/_rels/document.xml.rels")
        val p = pkg.parser("word/document.xml") ?: throw IOException("This isn't a Word document")
        val flow = Flow(sink, 595, 842, 56f)

        var skip = -1
        var para: SpannableStringBuilder? = null
        var pStyle: String? = null
        var jc: String? = null
        var numbered = false
        var ilvl = 0
        var indLeft = 0f
        var breakBefore = false
        var breakAfter = false
        var inPPr = false
        var inRPr = false
        var bold = false
        var italic = false
        var under = false
        var size = 0f
        var color = 0
        var hasColor = false
        var extW = 0f
        var extH = 0f
        val pics = ArrayList<Pic>()
        val tables = ArrayList<Tbl>()

        fun finishParagraph() {
            val sb = para ?: return
            para = null
            val tbl = tables.lastOrNull()
            if (tbl != null) {
                val cell = tbl.cell
                if (cell != null) {
                    if (cell.isNotEmpty()) cell.append('\n')
                    cell.append(sb)
                }
                pics.clear()
                return
            }
            if (breakBefore) flow.newPage()
            var base = 11f
            var heading = false
            val ps = pStyle?.lowercase().orEmpty()
            when {
                ps == "title" -> { base = 26f; heading = true }
                ps == "subtitle" -> { base = 14f }
                ps.startsWith("heading") -> {
                    val lvl = (ps.drop(7).trim().toIntOrNull() ?: 1).coerceIn(1, 6)
                    base = HEAD[lvl - 1]
                    heading = true
                }
            }
            if (numbered) sb.insert(0, "•  ")
            if (sb.isEmpty() && pics.isEmpty()) {
                flow.space(11f)
            } else {
                val align = when (jc) {
                    "center" -> Layout.Alignment.ALIGN_CENTER
                    "right", "end" -> Layout.Alignment.ALIGN_OPPOSITE
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
                if (sb.isNotEmpty()) {
                    flow.text(sb, textPaint(base, bold = heading), align, indLeft + ilvl * 14f, if (heading) 8f else 6f)
                }
                for (pic in pics) {
                    val bytes = pkg.bytes(pic.path) ?: continue
                    val bmp = Imgs.decode(bytes, Imgs.side(sink)) ?: continue
                    flow.image(bmp, pic.w, pic.h)
                }
            }
            if (breakAfter) flow.newPage()
            pics.clear()
        }

        fun addText(t: String) {
            val sb = para ?: return
            val st = sb.length
            sb.append(t)
            val en = sb.length
            if (en == st) return
            val f = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            if (bold && italic) sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), st, en, f)
            else if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), st, en, f)
            else if (italic) sb.setSpan(StyleSpan(Typeface.ITALIC), st, en, f)
            if (under) sb.setSpan(UnderlineSpan(), st, en, f)
            if (size > 0f) sb.setSpan(AbsoluteSizeSpan(size.roundToInt(), false), st, en, f)
            if (hasColor) sb.setSpan(ForegroundColorSpan(color), st, en, f)
        }

        fun addPic(path: String?) {
            if (path == null || tables.isNotEmpty()) return
            pics.add(Pic(path, extW, extH))
        }

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (skip >= 0) {
                if (ev == XmlPullParser.END_TAG && p.depth == skip) skip = -1
                ev = p.next()
                continue
            }
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "txbxContent", "Fallback" -> skip = p.depth
                    "p" -> {
                        para = SpannableStringBuilder()
                        pStyle = null; jc = null; numbered = false; ilvl = 0; indLeft = 0f
                        breakBefore = false; breakAfter = false
                    }
                    "pPr" -> inPPr = true
                    "pStyle" -> if (inPPr) pStyle = p.attr("val")
                    "jc" -> if (inPPr) jc = p.attr("val")
                    "numPr" -> if (inPPr) numbered = true
                    "ilvl" -> if (inPPr) ilvl = p.attr("val")?.toIntOrNull() ?: 0
                    "ind" -> if (inPPr) {
                        val l = p.attr("left") ?: p.attr("start")
                        indLeft = min(180f, max(0f, (l?.toFloatOrNull() ?: 0f) / 20f))
                    }
                    "pageBreakBefore" -> if (inPPr) breakBefore = isOn(p.attr("val"))
                    "r" -> { bold = false; italic = false; under = false; size = 0f; hasColor = false }
                    "rPr" -> if (!inPPr) inRPr = true
                    "b" -> if (inRPr) bold = isOn(p.attr("val"))
                    "i" -> if (inRPr) italic = isOn(p.attr("val"))
                    "u" -> if (inRPr) under = isOn(p.attr("val"))
                    "sz" -> if (inRPr) size = (p.attr("val")?.toFloatOrNull() ?: 0f) / 2f
                    "color" -> if (inRPr) {
                        val v = p.attr("val")
                        if (v != null && v != "auto") {
                            try {
                                color = Color.parseColor("#$v")
                                hasColor = true
                            } catch (e: IllegalArgumentException) {
                                hasColor = false
                            }
                        }
                    }
                    "t" -> if (!inPPr) addText(p.nextText())
                    "tab" -> if (!inPPr && !inRPr) addText("\t")
                    "cr" -> addText("\n")
                    "br" -> if (!inPPr) {
                        if (p.attr("type") == "page") breakAfter = true else addText("\n")
                    }
                    "tbl" -> tables.add(Tbl())
                    "tr" -> tables.lastOrNull()?.cur = ArrayList()
                    "tc" -> tables.lastOrNull()?.cell = SpannableStringBuilder()
                    "extent" -> {
                        extW = (p.attr("cx")?.toFloatOrNull() ?: 0f) / 12700f
                        extH = (p.attr("cy")?.toFloatOrNull() ?: 0f) / 12700f
                    }
                    "blip" -> addPic(p.attr("embed")?.let { rels[it] }?.let { resolvePath("word/", it.target) })
                    "imagedata" -> addPic(p.attr("id")?.let { rels[it] }?.let { resolvePath("word/", it.target) })
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "pPr" -> inPPr = false
                    "rPr" -> inRPr = false
                    "p" -> finishParagraph()
                    "tc" -> {
                        val t = tables.lastOrNull()
                        val cell = t?.cell
                        if (t != null && cell != null) {
                            t.cur?.add(cell)
                            t.cell = null
                        }
                    }
                    "tr" -> {
                        val t = tables.lastOrNull()
                        val cur = t?.cur
                        if (t != null && cur != null) {
                            t.rows.add(cur)
                            t.cur = null
                        }
                    }
                    "tbl" -> {
                        val t = tables.removeAt(tables.size - 1)
                        val parent = tables.lastOrNull()?.cell
                        if (parent != null) {
                            // a table inside a table cell: keep its text, row by row
                            for (r in t.rows) {
                                if (parent.isNotEmpty()) parent.append('\n')
                                parent.append(r.joinToString("  |  "))
                            }
                        } else {
                            drawTable(flow, t.rows)
                        }
                    }
                }
            }
            ev = p.next()
        }
        flow.endPage()
    }

    private fun drawTable(flow: Flow, rows: List<List<CharSequence>>) {
        val ncols = min(Office.MAX_COLS, rows.maxOfOrNull { it.size } ?: 0)
        if (ncols == 0) return
        val paint = textPaint(10f)
        val natural = FloatArray(ncols) { 24f }
        for (r in rows) {
            for (c in 0 until min(ncols, r.size)) {
                var longest = 0f
                for (ln in r[c].toString().take(300).split('\n')) longest = max(longest, paint.measureText(ln))
                natural[c] = max(natural[c], min(320f, longest + 10f))
            }
        }
        val sum = natural.sum()
        val k = flow.contentW / sum
        val widths = FloatArray(ncols) { max(24f, natural[it] * k) }
        // after the minimum width is applied the total can exceed the page a little: shrink back
        val total = widths.sum()
        if (total > flow.contentW) for (i in widths.indices) widths[i] *= flow.contentW / total
        flow.table(rows, widths, paint)
    }
}
