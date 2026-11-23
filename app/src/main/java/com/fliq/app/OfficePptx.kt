package com.fliq.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

/**
 * PowerPoint (.pptx): one page per slide with text boxes, pictures, filled shapes and simple tables.
 * Positions of placeholders are taken from the slide layout / master. Themes, charts, SmartArt and animations are not drawn.
 */
internal object PptxRender {
    private const val EMU = 12700f

    private class Run(val text: String, val size: Float, val bold: Boolean, val italic: Boolean, val under: Boolean, val color: Int?)

    private class Para {
        val runs = ArrayList<Run>()
        var algn: String? = null
        var bullet = false
        var level = 0
    }

    private class Shape(val kind: String) {
        var phType: String? = null
        var phIdx: String? = null
        var hasXfrm = false
        var x = 0f; var y = 0f; var w = 0f; var h = 0f          // EMU
        var chX = 0f; var chY = 0f; var chW = 0f; var chH = 0f  // group child space
        var fill: Int? = null
        val paras = ArrayList<Para>()
        var anchor = "t"
        var scale = 1f
        var embed: String? = null
        val rows = ArrayList<List<String>>()
        val grid = ArrayList<Float>()
        var rowH = ArrayList<Float>()
        var cellSize = 0f
        var cellText = ArrayList<String>()
        var curRow: ArrayList<String>? = null
    }

    fun render(pkg: Pkg, sink: PageSink) {
        val rels = pkg.rels("ppt/_rels/presentation.xml.rels")
        var cx = 12192000f
        var cy = 6858000f
        val slides = ArrayList<String>()
        val pp = pkg.parser("ppt/presentation.xml") ?: throw IOException("This isn't a PowerPoint file")
        var ev = pp.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                if (pp.name == "sldSz") {
                    cx = pp.attr("cx")?.toFloatOrNull() ?: cx
                    cy = pp.attr("cy")?.toFloatOrNull() ?: cy
                } else if (pp.name == "sldId") {
                    val t = pp.getAttributeValue(R_NS, "id")?.let { rels[it] }?.target
                    if (t != null) slides.add(resolvePath("ppt/", t))
                }
            }
            ev = pp.next()
        }
        if (slides.isEmpty()) throw IOException("No slides found in this file")
        val wPt = max(100, (cx / EMU).roundToInt())
        val hPt = max(100, (cy / EMU).roundToInt())

        val phCache = HashMap<String, Map<String, FloatArray>>()
        for (slide in slides) {
            if (sink.pages >= MAX_PAGES) break
            val dir = slide.substringBeforeLast('/', "") + "/"
            val name = slide.substringAfterLast('/')
            val srels = pkg.rels(dir + "_rels/" + name + ".rels")
            val layoutPath = srels.values.firstOrNull { it.type.endsWith("/slideLayout") }?.let { resolvePath(dir, it.target) }
            var masterPath: String? = null
            if (layoutPath != null) {
                val ldir = layoutPath.substringBeforeLast('/', "") + "/"
                masterPath = pkg.rels(ldir + "_rels/" + layoutPath.substringAfterLast('/') + ".rels").values
                    .firstOrNull { it.type.endsWith("/slideMaster") }?.let { resolvePath(ldir, it.target) }
            }
            val layoutBoxes = layoutPath?.let { phCache.getOrPut(it) { phBoxes(pkg, it) } } ?: emptyMap()
            val masterBoxes = masterPath?.let { phCache.getOrPut(it) { phBoxes(pkg, it) } } ?: emptyMap()

            val canvas = sink.begin(wPt, hPt)
            try {
                canvas.drawColor(Color.WHITE)
                try {
                    drawSlide(pkg, slide, dir, srels, canvas, sink, wPt.toFloat(), hPt.toFloat(), layoutBoxes, masterBoxes)
                } catch (e: org.xmlpull.v1.XmlPullParserException) {
                    // a broken slide stays partly drawn
                }
            } finally {
                sink.end()
            }
        }
    }

    private fun norm(t: String?): String = when (t) {
        null, "body", "obj" -> "body"
        "ctrTitle" -> "title"
        else -> t
    }

    /** Placeholder positions (EMU: x, y, w, h) of a slide layout or master, by "i<idx>" and "t<type>". */
    private fun phBoxes(pkg: Pkg, path: String): Map<String, FloatArray> {
        val out = HashMap<String, FloatArray>()
        val p = try { pkg.parser(path) } catch (e: Exception) { null } ?: return out
        var type: String? = null
        var idx: String? = null
        var inX = false
        var got = 0
        val v = FloatArray(4)
        try {
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "sp" -> { type = null; idx = null; got = 0 }
                        "ph" -> { type = p.attr("type"); idx = p.attr("idx") }
                        "xfrm" -> inX = true
                        "off" -> if (inX) { v[0] = p.attr("x")?.toFloatOrNull() ?: 0f; v[1] = p.attr("y")?.toFloatOrNull() ?: 0f; got = got or 1 }
                        "ext" -> if (inX) { v[2] = p.attr("cx")?.toFloatOrNull() ?: 0f; v[3] = p.attr("cy")?.toFloatOrNull() ?: 0f; got = got or 2 }
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    if (p.name == "xfrm") inX = false
                    else if (p.name == "sp" && got == 3 && (type != null || idx != null)) {
                        idx?.let { out["i$it"] = v.clone() }
                        out["t" + norm(type)] = v.clone()
                    }
                }
                ev = p.next()
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun lum(c: Int): Float = (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    private fun hex(v: String?): Int? = try {
        if (v == null) null else Color.parseColor("#$v")
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun drawSlide(
        pkg: Pkg, path: String, dir: String, rels: Map<String, Pkg.Rel>, canvas: Canvas, sink: PageSink,
        pw: Float, ph: Float, layoutBoxes: Map<String, FloatArray>, masterBoxes: Map<String, FloatArray>
    ) {
        val p = pkg.parser(path) ?: return
        var bg = Color.WHITE
        val names = ArrayList<String>()
        val stack = ArrayList<Shape>()
        var para: Para? = null
        var rb = false; var ri = false; var ru = false; var rs = 0f; var rc: Int? = null
        var inRun = false
        var inCell = false
        var cellParas = ArrayList<Para>()

        fun slideLum() = lum(bg)

        fun draw(s: Shape) {
            val groups = stack.filter { it.kind == "grpSp" }
            var box = floatArrayOf(s.x, s.y, s.w, s.h)
            var have = s.hasXfrm
            if (!have && s.phType != null || (!have && s.phIdx != null)) {
                val key = s.phIdx?.let { "i$it" }
                val tk = "t" + norm(s.phType)
                val found = (key?.let { layoutBoxes[it] } ?: layoutBoxes[tk] ?: masterBoxes[tk])
                if (found != null) {
                    box = found.clone(); have = true
                } else when (norm(s.phType)) {
                    "title" -> { box = floatArrayOf(0.06f * pw * EMU, 0.05f * ph * EMU, 0.88f * pw * EMU, 0.2f * ph * EMU); have = true }
                    "subTitle" -> { box = floatArrayOf(0.1f * pw * EMU, 0.3f * ph * EMU, 0.8f * pw * EMU, 0.2f * ph * EMU); have = true }
                    "body" -> { box = floatArrayOf(0.06f * pw * EMU, 0.28f * ph * EMU, 0.88f * pw * EMU, 0.62f * ph * EMU); have = true }
                }
            }
            if (!have) return
            for (g in groups.asReversed()) {
                val sx = if (g.chW > 0f) g.w / g.chW else 1f
                val sy = if (g.chH > 0f) g.h / g.chH else 1f
                box[0] = g.x + (box[0] - g.chX) * sx
                box[1] = g.y + (box[1] - g.chY) * sy
                box[2] *= sx
                box[3] *= sy
            }
            val x = box[0] / EMU
            val y = box[1] / EMU
            val w = box[2] / EMU
            val h = box[3] / EMU
            if (w <= 0f || h <= 0f) return
            var under = slideLum()
            s.fill?.let {
                canvas.drawRect(x, y, x + w, y + h, Paint().apply { color = it })
                under = lum(it)
            }
            when (s.kind) {
                "pic" -> {
                    val target = s.embed?.let { rels[it] }?.target ?: return
                    val bytes = pkg.bytes(resolvePath(dir, target)) ?: return
                    val bmp = Imgs.decode(bytes, Imgs.side(sink)) ?: return
                    sink.hold(bmp)
                    canvas.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
                }
                "graphicFrame" -> if (s.rows.isNotEmpty()) drawTable(canvas, x, y, w, s)
                else -> if (s.paras.any { it.runs.isNotEmpty() }) drawText(canvas, x, y, w, h, s, under)
            }
        }

        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val n = p.name
                names.add(n)
                val cur = stack.lastOrNull()
                when (n) {
                    "sp", "pic", "graphicFrame", "cxnSp", "grpSp" -> stack.add(Shape(n))
                    "ph" -> cur?.let { it.phType = p.attr("type"); it.phIdx = p.attr("idx") }
                    "off" -> if (cur != null && names.getOrNull(names.size - 2) == "xfrm") {
                        cur.x = p.attr("x")?.toFloatOrNull() ?: 0f
                        cur.y = p.attr("y")?.toFloatOrNull() ?: 0f
                        cur.hasXfrm = true
                    }
                    "ext" -> if (cur != null && names.getOrNull(names.size - 2) == "xfrm") {
                        cur.w = p.attr("cx")?.toFloatOrNull() ?: 0f
                        cur.h = p.attr("cy")?.toFloatOrNull() ?: 0f
                    }
                    "chOff" -> cur?.let { it.chX = p.attr("x")?.toFloatOrNull() ?: 0f; it.chY = p.attr("y")?.toFloatOrNull() ?: 0f }
                    "chExt" -> cur?.let { it.chW = p.attr("cx")?.toFloatOrNull() ?: 0f; it.chH = p.attr("cy")?.toFloatOrNull() ?: 0f }
                    "bodyPr" -> cur?.let { it.anchor = p.attr("anchor") ?: "t" }
                    "normAutofit" -> cur?.let { it.scale = (p.attr("fontScale")?.toFloatOrNull() ?: 100000f) / 100000f }
                    "blip" -> if (cur != null && cur.kind == "pic") cur.embed = p.getAttributeValue(R_NS, "embed")
                    "srgbClr", "schemeClr", "sysClr" -> {
                        val parent = names.getOrNull(names.size - 2)
                        val grand = names.getOrNull(names.size - 3)
                        if (parent == "solidFill") {
                            val c: Int? = when (n) {
                                "srgbClr" -> hex(p.attr("val"))
                                "sysClr" -> hex(p.attr("lastClr"))
                                else -> when (p.attr("val")) {
                                    "bg1", "lt1" -> Color.WHITE
                                    "tx1", "dk1" -> Color.BLACK
                                    else -> null
                                }
                            }
                            when (grand) {
                                "spPr" -> if (cur != null && cur.kind != "grpSp") cur.fill = c
                                "bgPr" -> if (c != null) {
                                    bg = c
                                    canvas.drawColor(c)
                                }
                                "rPr" -> rc = c
                            }
                        }
                    }
                    "tbl" -> cur?.let { it.cellSize = 0f }
                    "gridCol" -> cur?.grid?.add(p.attr("w")?.toFloatOrNull() ?: 0f)
                    "tr" -> cur?.let { it.curRow = ArrayList(); it.rowH.add(p.attr("h")?.toFloatOrNull() ?: 0f) }
                    "tc" -> { inCell = true; cellParas = ArrayList() }
                    "p" -> {
                        para = Para()
                        if (inCell) cellParas.add(para!!)
                        else cur?.paras?.add(para!!)
                    }
                    "pPr" -> para?.let {
                        it.algn = p.attr("algn")
                        it.level = p.attr("lvl")?.toIntOrNull() ?: 0
                    }
                    "buChar", "buAutoNum" -> para?.bullet = true
                    "buNone" -> para?.bullet = false
                    "r", "fld" -> { inRun = true; rb = false; ri = false; ru = false; rs = 0f; rc = null }
                    "rPr" -> if (inRun) {
                        rs = (p.attr("sz")?.toFloatOrNull() ?: 0f) / 100f
                        rb = p.attr("b") == "1" || p.attr("b") == "true"
                        ri = p.attr("i") == "1" || p.attr("i") == "true"
                        ru = p.attr("u")?.let { it != "none" } ?: false
                    }
                    "br" -> para?.runs?.add(Run("\n", rs, rb, ri, ru, rc))
                    "t" -> {
                        val t = p.nextText()
                        names.removeAt(names.size - 1)   // nextText consumed the end tag
                        if (inRun && para != null) {
                            para!!.runs.add(Run(t, rs, rb, ri, ru, rc))
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG) {
                val n = p.name
                when (n) {
                    "r", "fld" -> inRun = false
                    "tc" -> {
                        inCell = false
                        val cur = stack.lastOrNull()
                        if (cur != null) {
                            val txt = cellParas.joinToString("\n") { pa -> pa.runs.joinToString("") { it.text } }
                            cur.curRow?.add(txt)
                            if (cur.cellSize == 0f) cellParas.firstOrNull()?.runs?.firstOrNull { it.size > 0 }?.let { cur.cellSize = it.size }
                        }
                        para = null
                    }
                    "tr" -> stack.lastOrNull()?.let { s -> s.curRow?.let { s.rows.add(it) }; s.curRow = null }
                    "p" -> if (!inCell) para = null
                    "bg" -> {}
                    "sp", "pic", "graphicFrame", "cxnSp", "grpSp" -> {
                        if (stack.isNotEmpty()) {
                            val s = stack.removeAt(stack.size - 1)
                            if (s.kind != "grpSp" && s.kind != "cxnSp") draw(s)
                            // keep the group on the stack until its own end tag: children were drawn already
                            if (s.kind == "grpSp") { /* popped: its transform no longer applies */ }
                        }
                    }
                }
                if (names.isNotEmpty()) names.removeAt(names.size - 1)
            }
            ev = p.next()
        }
    }

    private fun drawText(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, s: Shape, bgLum: Float) {
        val title = norm(s.phType) == "title"
        val defSize = if (title) 32f else if (s.phType == "subTitle") 24f else 18f
        val inX = 7.2f
        val inY = 3.6f
        val width = max(20f, w - 2 * inX)
        val avail = h - 2 * inY
        var k = s.scale
        var built = build(s.paras, k, defSize, width, bgLum)
        var guard = 0
        while (built.height > avail && k > 0.4f && guard++ < 12) {
            k *= 0.92f
            built = build(s.paras, k, defSize, width, bgLum)
        }
        var ty = y + inY
        when (s.anchor) {
            "ctr" -> ty += max(0f, (avail - built.height) / 2f)
            "b" -> ty += max(0f, avail - built.height)
        }
        for ((i, l) in built.layouts.withIndex()) {
            canvas.save()
            canvas.translate(x + inX + built.indent[i], ty)
            l.draw(canvas)
            canvas.restore()
            ty += l.height + built.gap
        }
    }

    private class Built(val layouts: List<StaticLayout>, val indent: FloatArray, val gap: Float, val height: Float)

    private fun build(paras: List<Para>, k: Float, defSize: Float, width: Float, bgLum: Float): Built {
        val base = if (bgLum < 0.5f) Color.WHITE else Color.BLACK
        val layouts = ArrayList<StaticLayout>()
        val indent = FloatArray(paras.size)
        var total = 0f
        val gap = 0.3f * defSize * k
        for ((i, pa) in paras.withIndex()) {
            val sb = SpannableStringBuilder()
            if (pa.bullet) sb.append("•  ")
            for (r in pa.runs) {
                val st = sb.length
                sb.append(r.text)
                val en = sb.length
                if (en == st) continue
                val f = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                if (r.bold && r.italic) sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), st, en, f)
                else if (r.bold) sb.setSpan(StyleSpan(Typeface.BOLD), st, en, f)
                else if (r.italic) sb.setSpan(StyleSpan(Typeface.ITALIC), st, en, f)
                if (r.under) sb.setSpan(UnderlineSpan(), st, en, f)
                val size = (if (r.size > 0f) r.size else defSize) * k
                sb.setSpan(AbsoluteSizeSpan(max(4, size.roundToInt()), false), st, en, f)
                var c = r.color ?: base
                if (kotlin.math.abs(lum(c) - bgLum) < 0.25f) c = base
                sb.setSpan(ForegroundColorSpan(c), st, en, f)
            }
            if (sb.isEmpty()) sb.append(" ")
            indent[i] = pa.level * 18f * k
            val align = when (pa.algn) {
                "ctr" -> Layout.Alignment.ALIGN_CENTER
                "r" -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            val l = layoutOf(sb, textPaint(defSize * k, color = base), (width - indent[i]).toInt(), align)
            layouts.add(l)
            total += l.height + gap
        }
        return Built(layouts, indent, gap, max(0f, total - gap))
    }

    private fun drawTable(canvas: Canvas, x: Float, y: Float, w: Float, s: Shape) {
        val ncols = s.rows.maxOf { it.size }
        if (ncols == 0) return
        val gridSum = s.grid.sum()
        val cw = FloatArray(ncols) { i ->
            if (gridSum > 0f && i < s.grid.size) s.grid[i] / gridSum * w else w / ncols
        }
        val size = if (s.cellSize > 0f) s.cellSize else 14f
        val paint = textPaint(size)
        val border = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.7f; color = 0xFF888888.toInt() }
        var ty = y
        for ((ri, row) in s.rows.withIndex()) {
            val ls = row.mapIndexed { ci, t -> layoutOf(t, paint, (cw[min(ci, ncols - 1)] - 8f).toInt()) }
            val rh = max((s.rowH.getOrElse(ri) { 0f }) / EMU, (ls.maxOfOrNull { it.height } ?: 0) + 6f)
            var tx = x
            for (ci in 0 until ncols) {
                canvas.drawRect(tx, ty, tx + cw[ci], ty + rh, border)
                if (ci < ls.size) {
                    canvas.save()
                    canvas.translate(tx + 4f, ty + 3f)
                    ls[ci].draw(canvas)
                    canvas.restore()
                }
                tx += cw[ci]
            }
            ty += rh
        }
    }
}
