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
 * Offline, text-first DOCX -> PDF conversion.
 * Extracts paragraph text from the DOCX package. It does not preserve images, tables,
 * exact styles, or page layout. Legacy binary .doc and other Office formats are not supported.
 */
object WordToPdf {
    fun convert(context: Context, uri: Uri, output: OutputStream) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val inputFile = File.createTempFile("fliq-docx-", ".docx", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                inputFile.outputStream().use { out -> input.copyTo(out, 32 * 1024) }
            } ?: throw IOException("Cannot open Word document")

            val paragraphs = extractParagraphs(inputFile)
            if (paragraphs.none { it.isNotBlank() }) {
                throw IOException("No readable text found in this DOCX file")
            }

            PDDocument().use { pdf ->
                val pageWidth = PDRectangle.LETTER.width
                val margin = 50f
                val fontSize = 11f
                val leading = 15f
                val maxWidth = pageWidth - margin * 2
                var page = PDPage(PDRectangle.LETTER)
                pdf.addPage(page)
                var stream = PDPageContentStream(pdf, page)
                var y = page.mediaBox.height - margin

                fun newPage() {
                    stream.close()
                    page = PDPage(PDRectangle.LETTER)
                    pdf.addPage(page)
                    stream = PDPageContentStream(pdf, page)
                    y = page.mediaBox.height - margin
                }

                try {
                    for (paragraph in paragraphs) {
                        val words = paragraph.split(Regex("\\s+")).filter { it.isNotEmpty() }
                        var line = StringBuilder()
                        val lines = ArrayList<String>()
                        for (word in words) {
                            val candidate = if (line.isEmpty()) word else "$line $word"
                            if (line.isNotEmpty() && textWidth(candidate, fontSize) > maxWidth) {
                                lines.add(line.toString())
                                line = StringBuilder(word)
                            } else {
                                line = StringBuilder(candidate)
                            }
                        }
                        if (line.isNotEmpty()) lines.add(line.toString())
                        if (lines.isEmpty()) lines.add(" ")

                        for (text in lines) {
                            if (y < margin + leading) newPage()
                            stream.beginText()
                            stream.setFont(PDType1Font.HELVETICA, fontSize)
                            stream.newLineAtOffset(margin, y)
                            stream.showText(toWinAnsi(text))
                            stream.endText()
                            y -= leading
                        }
                        y -= 5f
                    }
                } finally {
                    stream.close()
                }
                pdf.save(output)
            }
        } finally {
            inputFile.delete()
        }
    }

    private fun extractParagraphs(file: File): List<String> {
        val paragraphs = ArrayList<String>()
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml")
                ?: throw IOException("This is not a supported DOCX document")
            zip.getInputStream(entry).use { input ->
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setInput(input, "UTF-8")
                var current = StringBuilder()
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                            "p" -> current = StringBuilder()
                            "t" -> {
                                val text = parser.nextText()
                                current.append(text)
                            }
                            "tab" -> current.append('\t')
                            "br", "cr" -> current.append(' ')
                        }
                        XmlPullParser.END_TAG -> if (parser.name.substringAfter(':') == "p") {
                            paragraphs.add(current.toString())
                            current = StringBuilder()
                        }
                    }
                    event = parser.next()
                }
            }
        }
        return paragraphs
    }

    private fun textWidth(text: String, size: Float): Float {
        val safe = toWinAnsi(text)
        return try {
            PDType1Font.HELVETICA.getStringWidth(safe) / 1000f * size
        } catch (_: Exception) {
            safe.length * size * 0.55f
        }
    }

    private fun toWinAnsi(value: String): String =
        value.map { if (it.code in 32..255 && it != '\u007f') it else if (it.isWhitespace()) ' ' else '?' }
            .joinToString("")
}
