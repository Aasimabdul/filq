package com.fliq.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

const val MAX_PAGES = 200

/** Where rendered pages go: a PDF file, or one image per page. Sizes are in points (1/72 inch). */
interface PageSink {
    val pages: Int
    /** Bytes of bitmaps that stay alive until the sink is closed (PDF output keeps them until it is written). */
    val heldBytes: Long
    fun begin(w: Int, h: Int): Canvas
    fun end()
    /** The sink recycles [b] when it is safe to (after the page, or after the PDF is written). */
    fun hold(b: Bitmap)
    fun close()
}

class PdfSink : PageSink {
    private val doc = PdfDocument()
    private var page: PdfDocument.Page? = null
    private val held = ArrayList<Bitmap>()
    override var pages = 0
        private set
    override var heldBytes = 0L
        private set

    override fun begin(w: Int, h: Int): Canvas {
        pages++
        val p = doc.startPage(PdfDocument.PageInfo.Builder(max(1, w), max(1, h), pages).create())
        page = p
        return p.canvas
    }

    override fun end() {
        page?.let { doc.finishPage(it) }
        page = null
    }

    override fun hold(b: Bitmap) {
        held += b
        heldBytes += b.allocationByteCount
    }

    fun writeTo(os: OutputStream) = doc.writeTo(os)

    override fun close() {
        end()
        doc.close()
        held.forEach { it.recycle() }
        held.clear()
    }
}

class ImageSink(private val scale: Float, private val onPage: (Bitmap, Int) -> Unit) : PageSink {
    private var bmp: Bitmap? = null
    private val held = ArrayList<Bitmap>()
    override var pages = 0
        private set
    override val heldBytes: Long get() = held.sumOf { it.allocationByteCount.toLong() }

    override fun begin(w: Int, h: Int): Canvas {
        pages++
        val k = min(scale, 2400f / max(w, h))
        val b = Bitmap.createBitmap(max(1, (w * k).roundToInt()), max(1, (h * k).roundToInt()), Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.WHITE)
        bmp = b
        return Canvas(b).apply { scale(k, k) }
    }

    override fun end() {
        val b = bmp ?: return
        bmp = null
        try {
            onPage(b, pages)
        } finally {
            b.recycle()
            held.forEach { it.recycle() }
            held.clear()
        }
    }

    override fun hold(b: Bitmap) {
        held += b
    }

    override fun close() {
        bmp?.recycle()
        bmp = null
        held.forEach { it.recycle() }
        held.clear()
    }
}

object Imgs {
    /** Decodes an embedded picture at a limited size. Returns null for formats Android can't read (EMF, WMF...). */
    fun decode(bytes: ByteArray, maxSide: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val long = max(w, h)
            if (long > maxSide) {
                val k = maxSide.toDouble() / long
                d.setTargetSize(max(1, (w * k).roundToInt()), max(1, (h * k).roundToInt()))
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** Smaller pictures once a PDF has already collected a lot of them. */
    fun side(sink: PageSink): Int = if (sink.heldBytes > 40L * 1024 * 1024) 400 else 1000
}

fun textPaint(size: Float, bold: Boolean = false, italic: Boolean = false, color: Int = Color.BLACK, mono: Boolean = false): TextPaint =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = Typeface.create(
            if (mono) Typeface.MONOSPACE else Typeface.SANS_SERIF,
            (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
        )
    }

fun layoutOf(t: CharSequence, p: TextPaint, w: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, spacing: Float = 1f): StaticLayout =
    StaticLayout.Builder.obtain(t, 0, t.length, p, max(1, w))
        .setAlignment(align)
        .setIncludePad(false)
        .setLineSpacing(0f, spacing)
        .build()

/** Flows text, pictures and tables onto pages of a [PageSink]; starts a new page when the current one is full. */
class Flow(private val sink: PageSink, val pw: Int, val ph: Int, val margin: Float) {
    private var canvas: Canvas? = null
    private var y = margin
    private var stopped = false
    val contentW: Float get() = pw - 2 * margin

    private fun room(h: Float): Boolean {
        if (stopped) return false
        var c = canvas
        if (c != null && y + h > ph - margin && y > margin + 1f) {
            if (sink.pages >= MAX_PAGES) {
                val p = textPaint(9f, color = 0xFF888888.toInt())
                c.drawText("… page limit reached, the rest is not shown", margin, ph - margin / 2, p)
                stopped = true
                return false
            }
            endPage()
            c = null
        }
        if (c == null) {
            if (sink.pages >= MAX_PAGES) {
                stopped = true
                return false
            }
            canvas = sink.begin(pw, ph)
            y = margin
        }
        return true
    }

    /** The next content starts on a fresh page. */
    fun newPage() = endPage()

    fun endPage() {
        if (canvas != null) {
            sink.end()
            canvas = null
        }
    }

    fun space(h: Float) {
        if (canvas != null) y += h
    }

    /** A paragraph, split across pages line by line. */
    fun text(t: CharSequence, p: TextPaint, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, indent: Float = 0f, after: Float = 6f) {
        val width = (contentW - indent).toInt().coerceAtLeast(30)
        val layout = layoutOf(t, p, width, align, 1.12f)
        var line = 0
        val n = layout.lineCount
        while (line < n) {
            if (!room(layout.getLineBottom(line) - layout.getLineTop(line).toFloat())) return
            val top = layout.getLineTop(line)
            val avail = ph - margin - y
            var end = line
            while (end < n && layout.getLineBottom(end) - top <= avail) end++
            if (end == line) end = line + 1
            val bottom = layout.getLineBottom(end - 1)
            val c = canvas!!
            c.save()
            c.translate(margin + indent, y - top)
            c.clipRect(0f, top.toFloat(), width.toFloat(), bottom.toFloat())
            layout.draw(c)
            c.restore()
            y += (bottom - top)
            line = end
        }
        y += after
    }

    /** A picture scaled to fit the page width. Its bitmap is handed to the sink to recycle later. */
    fun image(bmp: Bitmap, wPt: Float, hPt: Float) {
        sink.hold(bmp)
        var w = if (wPt > 0) wPt else bmp.width.toFloat()
        var h = if (hPt > 0) hPt else bmp.height.toFloat()
        val k = min(1f, min(contentW / w, (ph - 2 * margin) / h))
        w *= k
        h *= k
        if (!room(h)) return
        canvas!!.drawBitmap(bmp, null, RectF(margin, y, margin + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
        y += h + 6f
    }

    /** A bordered grid; rows are kept whole and move to the next page when they don't fit. */
    fun table(rows: List<List<CharSequence>>, colW: FloatArray, paint: TextPaint, boldPaint: TextPaint? = null, header: Boolean = false) {
        val pad = 3f
        val border = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
            color = 0xFF999999.toInt()
        }
        val fill = Paint().apply { color = 0xFFEEEEEE.toInt() }
        for ((ri, row) in rows.withIndex()) {
            val isHead = header && ri == 0 && boldPaint != null
            val layouts = row.mapIndexedNotNull { ci, t ->
                if (ci >= colW.size) null
                else layoutOf(t, if (isHead) boldPaint!! else paint, (colW[ci] - 2 * pad).toInt().coerceAtLeast(8))
            }
            var h = (layouts.maxOfOrNull { it.height } ?: 0).toFloat() + 2 * pad
            h = min(max(h, paint.textSize + 2 * pad), ph - 2 * margin)
            if (!room(h)) return
            val c = canvas!!
            var x = margin
            for (ci in colW.indices) {
                val r = RectF(x, y, x + colW[ci], y + h)
                if (isHead) c.drawRect(r, fill)
                c.drawRect(r, border)
                if (ci < layouts.size) {
                    c.save()
                    c.clipRect(r)
                    c.translate(x + pad, y + pad)
                    layouts[ci].draw(c)
                    c.restore()
                }
                x += colW[ci]
            }
            y += h
        }
        y += 8f
    }
}
