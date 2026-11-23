package com.fliq.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.PorterDuff
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import com.caverock.androidsvg.SVG
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Target(val label: String, val ext: String, val mime: String) {
    JPG("JPG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
    WEBP("WebP", "webp", "image/webp"),
    BMP("BMP", "bmp", "image/bmp"),
    PDF("PDF", "pdf", "application/pdf"),
    DOCX("Word", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
}

/**
 * Low-memory conversions. Everything is processed one file (or one page) at a time,
 * bitmaps are recycled immediately and big images are decoded at a capped size.
 */
object Converter {
    private const val MAX_PIXELS = 16_000_000.0   // ~64 MB bitmap at most
    private const val PDF_IMG_SIDE = 2500
    private const val PDF_RENDER_SIDE = 2200
    private const val SVG_SIDE = 2048

    private fun offered(k: Kind): Set<Target> = when (k) {
        Kind.IMAGE, Kind.SVG -> setOf(Target.JPG, Target.PNG, Target.WEBP, Target.BMP, Target.PDF)
        Kind.PDF -> setOf(Target.JPG, Target.PNG, Target.WEBP, Target.DOCX)
        Kind.OFFICE -> setOf(Target.JPG, Target.PNG, Target.WEBP, Target.PDF)
        Kind.OTHER -> emptySet()
    }

    /** Formats every selected file can be converted to. A mixed selection gets the common ones (JPG/PNG/WebP). */
    fun targetsFor(srcs: List<Src>): List<Target> {
        if (srcs.isEmpty()) return emptyList()
        var common: Set<Target> = Target.values().toSet()
        for (s in srcs) common = common intersect offered(s.kind)
        return Target.values().filter { t ->
            t in common && !srcs.all { it.kind == Kind.IMAGE && normExt(it.ext) == t.ext }
        }
    }

    /** One PDF from several files is only offered when every file is an image or SVG. */
    fun canMerge(srcs: List<Src>): Boolean =
        srcs.size > 1 && srcs.all { it.kind == Kind.IMAGE || it.kind == Kind.SVG }

    private fun normExt(e: String): String =
        e.lowercase(Locale.ROOT).let { if (it == "jpeg") "jpg" else it }

    private val noProgress: (Int, Int) -> Unit = { _, _ -> }

    /**
     * Converts every file to [t] and saves each next to its original, one file at a time.
     * A file that fails is reported but does not stop the others. [merge] makes one PDF from all images.
     */
    fun convert(ctx: Context, srcs: List<Src>, t: Target, merge: Boolean, progress: (Int, Int) -> Unit): JobResult {
        if (merge && t == Target.PDF && canMerge(srcs)) {
            val one = Saver.save(ctx, srcs[0].dir, srcs[0].base + "_merged.pdf", Target.PDF.mime) { os ->
                writePdf(ctx, srcs, os, progress)
            }
            return JobResult(listOf(one))
        }
        val out = ArrayList<Saved>()
        val failed = ArrayList<String>()
        val inner = if (srcs.size == 1) progress else noProgress
        srcs.forEachIndexed { i, s ->
            progress(i, srcs.size)
            try {
                out += convertOne(ctx, s, t, inner)
            } catch (e: OutOfMemoryError) {
                failed += s.name + ": not enough memory"
            } catch (e: Exception) {
                failed += s.name + ": " + (e.message ?: "failed")
            }
        }
        return JobResult(out, failed)
    }

    private fun convertOne(ctx: Context, s: Src, t: Target, progress: (Int, Int) -> Unit): List<Saved> =
        when (s.kind) {
            Kind.IMAGE, Kind.SVG -> when {
                s.kind == Kind.IMAGE && normExt(s.ext) == t.ext -> emptyList()  // already in that format
                t == Target.PDF -> listOf(
                    Saver.save(ctx, s.dir, s.base + ".pdf", t.mime) { os -> writePdf(ctx, listOf(s), os, noProgress) }
                )
                else -> {
                    val bmp = loadBitmap(ctx, s, 0)
                    try {
                        listOf(Saver.save(ctx, s.dir, s.base + "." + t.ext, t.mime) { os -> encode(bmp, t, os) })
                    } finally {
                        bmp.recycle()
                    }
                }
            }
            Kind.PDF ->
                if (t == Target.DOCX) {
                    listOf(Saver.save(ctx, s.dir, s.base + ".docx", t.mime) { os -> PdfToDocx.convert(readPdf(ctx, s), os) })
                } else {
                    pdfToImages(ctx, s, t, progress)
                }
            Kind.OFFICE ->
                if (t == Target.PDF) officeToPdf(ctx, s, t) else officeToImages(ctx, s, t)
            Kind.OTHER -> throw IOException("can't convert this file type yet")
        }

    // ---------- loading ----------

    private fun loadBitmap(ctx: Context, s: Src, maxSide: Int): Bitmap =
        if (s.kind == Kind.SVG) loadSvg(ctx, s.uri, if (maxSide > 0) min(maxSide, SVG_SIDE) else SVG_SIDE)
        else loadImage(ctx, s.uri, maxSide)

    private fun loadImage(ctx: Context, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(ctx.contentResolver, uri)
        try {
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                val w = info.size.width
                val h = info.size.height
                val longSide = max(w, h)
                var k = 1.0
                if (maxSide in 1 until longSide) k = maxSide.toDouble() / longSide
                val px = w.toDouble() * h * k * k
                if (px > MAX_PIXELS) k *= sqrt(MAX_PIXELS / px)
                if (k < 1.0) {
                    decoder.setTargetSize(max(1, (w * k).roundToInt()), max(1, (h * k).roundToInt()))
                }
            }
        } catch (e: IOException) {
            throw IOException("Can't read this image")
        }
    }

    private fun loadSvg(ctx: Context, uri: Uri, side: Int): Bitmap {
        val svg = (ctx.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open file"))
            .use { SVG.getFromInputStream(it) }
        var nw = svg.documentWidth
        var nh = svg.documentHeight
        val vb = svg.documentViewBox
        if (nw <= 0f || nh <= 0f) {
            if (vb != null && vb.width() > 0f && vb.height() > 0f) {
                nw = vb.width(); nh = vb.height()
            } else {
                nw = 512f; nh = 512f
            }
        }
        val k = side / max(nw, nh)
        val ow = max(1, (nw * k).roundToInt())
        val oh = max(1, (nh * k).roundToInt())
        val bmp = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (vb == null) canvas.scale(ow / nw, oh / nh)
        svg.renderToCanvas(canvas)
        return bmp
    }

    // ---------- encoding ----------

    @Suppress("DEPRECATION")
    private fun encode(bmp: Bitmap, t: Target, os: OutputStream) {
        when (t) {
            Target.JPG -> {
                whiteBehind(bmp)
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, os)) throw IOException("JPG encode failed")
            }
            Target.PNG -> {
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) throw IOException("PNG encode failed")
            }
            Target.WEBP -> {
                val f = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                else Bitmap.CompressFormat.WEBP
                if (!bmp.compress(f, 90, os)) throw IOException("WebP encode failed")
            }
            Target.BMP -> writeBmp(bmp, os)
            Target.PDF, Target.DOCX -> throw IllegalStateException("${t.label} is not an image format")
        }
    }

    /** Paints white behind transparent pixels, in place (no second bitmap). */
    private fun whiteBehind(bmp: Bitmap) {
        if (bmp.hasAlpha() && bmp.isMutable) Canvas(bmp).drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
    }

    /** 24-bit BMP, written row by row. */
    private fun writeBmp(bmp: Bitmap, os: OutputStream) {
        val w = bmp.width
        val h = bmp.height
        val rowSize = (w * 3 + 3) and 3.inv()
        val dataSize = rowSize * h
        val head = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
        head.put('B'.code.toByte()).put('M'.code.toByte())
        head.putInt(54 + dataSize).putInt(0).putInt(54)
        head.putInt(40).putInt(w).putInt(h)
        head.putShort(1).putShort(24).putInt(0).putInt(dataSize)
        head.putInt(2835).putInt(2835).putInt(0).putInt(0)
        os.write(head.array())
        val px = IntArray(w)
        val row = ByteArray(rowSize)
        for (y in h - 1 downTo 0) {
            bmp.getPixels(px, 0, w, 0, y, w, 1)
            var i = 0
            for (c in px) {
                val a = c ushr 24
                var r = (c shr 16) and 0xFF
                var g = (c shr 8) and 0xFF
                var b = c and 0xFF
                if (a < 255) {
                    r = (r * a + 255 * (255 - a)) / 255
                    g = (g * a + 255 * (255 - a)) / 255
                    b = (b * a + 255 * (255 - a)) / 255
                }
                row[i++] = b.toByte()
                row[i++] = g.toByte()
                row[i++] = r.toByte()
            }
            os.write(row)
        }
    }

    // ---------- images -> PDF (own tiny streaming writer: one JPEG in memory at a time) ----------

    private fun writePdf(ctx: Context, srcs: List<Src>, os: OutputStream, progress: (Int, Int) -> Unit) {
        val n = srcs.size
        val offs = LongArray(3 + 3 * n)          // index = object number
        var pos = 0L
        fun putB(b: ByteArray) { os.write(b); pos += b.size }
        fun putS(s: String) { putB(s.toByteArray(Charsets.ISO_8859_1)) }
        fun obj(num: Int) { offs[num] = pos; putS("$num 0 obj\n") }

        putS("%PDF-1.4\n")
        putB(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
        obj(1)
        putS("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        val kids = StringBuilder()
        for (i in 0 until n) {
            progress(i, n)
            val bmp = loadBitmap(ctx, srcs[i], PDF_IMG_SIDE)
            val w = bmp.width
            val h = bmp.height
            val jpg = ByteArrayOutputStream()
            try {
                whiteBehind(bmp)
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, jpg)) throw IOException("Encode failed")
            } finally {
                bmp.recycle()
            }
            var pw = 595
            var ph = (pw * h.toDouble() / w).roundToInt()
            if (ph > 14000) {
                ph = 14000
                pw = (ph * w.toDouble() / h).roundToInt()
            }
            pw = max(pw, 1)
            ph = max(ph, 1)

            val img = 3 + 3 * i
            val con = img + 1
            val pg = img + 2
            obj(img)
            putS("<< /Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace /DeviceRGB " +
                "/BitsPerComponent 8 /Filter /DCTDecode /Length ${jpg.size()} >>\nstream\n")
            jpg.writeTo(os)
            pos += jpg.size()
            putS("\nendstream\nendobj\n")

            val content = "q $pw 0 0 $ph 0 0 cm /Im0 Do Q"
            obj(con)
            putS("<< /Length ${content.length} >>\nstream\n$content\nendstream\nendobj\n")

            obj(pg)
            putS("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] " +
                "/Resources << /XObject << /Im0 $img 0 R >> >> /Contents $con 0 R >>\nendobj\n")
            kids.append("$pg 0 R ")
        }
        obj(2)
        putS("<< /Type /Pages /Kids [ $kids] /Count $n >>\nendobj\n")

        val xref = pos
        putS("xref\n0 ${offs.size}\n")
        putS("0000000000 65535 f \n")
        for (k in 1 until offs.size) putS(String.format(Locale.US, "%010d 00000 n \n", offs[k]))
        putS("trailer\n<< /Size ${offs.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
    }

    // ---------- PDF -> images (one page in memory at a time) ----------

    private fun pdfToImages(ctx: Context, s: Src, t: Target, progress: (Int, Int) -> Unit): List<Saved> {
        val tmp = File.createTempFile("fliq_", ".pdf", ctx.cacheDir)
        try {
            (ctx.contentResolver.openInputStream(s.uri) ?: throw IOException("Cannot open file"))
                .use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            return pdfFileToImages(ctx, tmp, s, t, progress)
        } finally {
            tmp.delete()
        }
    }

    /** Word / Excel / PowerPoint / CSV / text -> PDF, drawn on the phone (see Office). */
    private fun officeToPdf(ctx: Context, s: Src, t: Target): List<Saved> {
        val sink = PdfSink()
        try {
            Office.render(ctx, s, sink)
            return listOf(Saver.save(ctx, s.dir, s.base + ".pdf", t.mime) { os -> sink.writeTo(os) })
        } finally {
            sink.close()
        }
    }

    /** Same pages as images, each saved as soon as it is drawn (one page bitmap in memory at a time). */
    private fun officeToImages(ctx: Context, s: Src, t: Target): List<Saved> {
        val out = ArrayList<Saved>()
        val sink = ImageSink(2f) { bmp, n ->
            val name = s.base + "_p" + n.toString().padStart(2, '0') + "." + t.ext
            out += Saver.save(ctx, s.dir, name, t.mime) { os -> encode(bmp, t, os) }
        }
        try {
            Office.render(ctx, s, sink)
        } finally {
            sink.close()
        }
        return out
    }

    private const val MAX_PDF_BYTES = 48 * 1024 * 1024

    /** The whole PDF in memory (the text reader needs random access); refused when it is very large. */
    private fun readPdf(ctx: Context, s: Src): ByteArray {
        (ctx.contentResolver.openInputStream(s.uri) ?: throw IOException("Cannot open file")).use { i ->
            if (s.size > MAX_PDF_BYTES) throw IOException("PDF is too big to convert to Word on this phone")
            val bo = ByteArrayOutputStream(if (s.size > 0) s.size.toInt() else 64 * 1024)
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf)
                if (n < 0) break
                bo.write(buf, 0, n)
                if (bo.size() > MAX_PDF_BYTES) throw IOException("PDF is too big to convert to Word on this phone")
            }
            return bo.toByteArray()
        }
    }

    /** Renders [pdf] page by page; the images are named after [s] and saved next to it. */
    private fun pdfFileToImages(ctx: Context, pdf: File, s: Src, t: Target, progress: (Int, Int) -> Unit): List<Saved> {
        val out = ArrayList<Saved>()
        val pfd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(pfd)
        } catch (e: SecurityException) {
            pfd.close()
            throw IOException("This PDF is password-protected")
        } catch (e: IOException) {
            pfd.close()
            throw IOException("Can't read this PDF")
        }
        try {
            val pages = renderer.pageCount
            val digits = pages.toString().length
            for (i in 0 until pages) {
                progress(i, pages)
                val bmp = renderPage(renderer, i)
                try {
                    val name = s.base + "_p" + (i + 1).toString().padStart(digits, '0') + "." + t.ext
                    out += Saver.save(ctx, s.dir, name, t.mime) { os -> encode(bmp, t, os) }
                } finally {
                    bmp.recycle()
                }
            }
        } finally {
            renderer.close()
            try { pfd.close() } catch (e: IOException) { }
        }
        return out
    }

    private fun renderPage(r: PdfRenderer, index: Int): Bitmap {
        val page = r.openPage(index)
        try {
            val pw = page.width
            val ph = page.height
            val k = min(2f, PDF_RENDER_SIDE / max(pw, ph).toFloat())
            val bmp = Bitmap.createBitmap(
                max(1, (pw * k).roundToInt()), max(1, (ph * k).roundToInt()), Bitmap.Config.ARGB_8888
            )
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } finally {
            page.close()
        }
    }
}
