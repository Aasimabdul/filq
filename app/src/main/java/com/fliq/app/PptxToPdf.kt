package com.fliq.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipFile
import org.apache.pdfbox.android.PDFBoxResourceLoader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * Offline text-first PPTX -> PDF. Each slide becomes one PDF page containing its extracted text.
 * This deliberately avoids a remote office engine; slide backgrounds, images, charts, SmartArt,
 * animations, and exact positions/styles are not rendered. Legacy .ppt is not supported offline.
 */
object PptxToPdf {
    fun convert(context: Context, uri: Uri, output: OutputStream) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val file = File.createTempFile("fliq-pptx-", ".pptx", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { out -> input.copyTo(out, 32 * 1024) }
            } ?: throw IOException("Cannot open PowerPoint file")

            val slides = extractSlides(file)
            if (slides.isEmpty()) throw IOException("No readable slides found in this PPTX")
            if (slides.all { it.none(String::isNotBlank) }) {
                throw IOException("No selectable text found in this PPTX")
            }

            PDDocument().use { pdf ->
                for (slide in slides) {
                    val page = PDPage(PDRectangle.LETTER)
                    pdf.addPage(page)
                    PDPageContentStream(pdf, page).use { stream ->
                        val margin = 48f
                        val fontSize = 11f
                        val leading = 15f
                        val maxWidth = page.mediaBox.width - margin * 2
                        var y = page.mediaBox.height - margin
                        for (paragraph in slide) {
                            val words = paragraph.split(Regex("\\s+")).filter { it.isNotEmpty() }
                            var line = StringBuilder()
                            val lines = ArrayList<String>()
                            for (word in words) {
                                val candidate = if (line.isEmpty()) word else "$line $word"
                                val safe = safeText(candidate)
                                val width = try { PDType1Font.HELVETICA.getStringWidth(safe) / 1000f * fontSize }
                                    catch (_: Exception) { safe.length * fontSize * 0.55f }
                                if (line.isNotEmpty() && width > maxWidth) {
                                    lines.add(line.toString())
                                    line = StringBuilder(word)
                                } else line = StringBuilder(candidate)
                            }
                            if (line.isNotEmpty()) lines.add(line.toString())
                            for (text in lines) {
                                if (y < margin + leading) break
                                stream.beginText()
                                stream.setFont(PDType1Font.HELVETICA, fontSize)
                                stream.newLineAtOffset(margin, y)
                                stream.showText(safeText(text))
                                stream.endText()
                                y -= leading
                            }
                            y -= 6f
                            if (y < margin) break
                        }
                    }
                }
                pdf.save(output)
            }
        } finally {
            file.delete()
        }
    }

    private fun extractSlides(file: File): List<List<String>> {
        val result = ArrayList<Pair<Int, List<String>>>()
        ZipFile(file).use { zip ->
            val pattern = Regex("""ppt/slides/slide(\d+)\.xml""")
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val match = pattern.matchEntire(entry.name) ?: continue
                val texts = ArrayList<String>()
                zip.getInputStream(entry).use { input ->
                    val parser = XmlPullParserFactory.newInstance().newPullParser()
                    parser.setInput(input, "UTF-8")
                    var event = parser.eventType
                    while (event != XmlPullParser.END_DOCUMENT) {
                        if (event == XmlPullParser.START_TAG && parser.name.substringAfter(':') == "t") {
                            val text = parser.nextText().trim()
                            if (text.isNotEmpty()) texts.add(text)
                        }
                        event = parser.next()
                    }
                }
                result.add(match.groupValues[1].toInt() to texts)
            }
        }
        return result.sortedBy { it.first }.map { it.second }
    }

    private fun safeText(value: String): String =
        value.map { if (it.code in 32..255 && it != '\\u007f') it else if (it.isWhitespace()) ' ' else '?' }
            .joinToString("")
}
