package com.fliq.app

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.pdfbox.android.PDFBoxResourceLoader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper

/**
 * Offline, text-first PDF -> DOCX conversion.
 * Preserves extracted text and page separation, not the original PDF layout, tables, fonts, or images.
 * Scanned/image-only PDFs need OCR and are not supported here.
 */
object PdfToWord {
    fun convert(context: Context, src: Src, output: OutputStream) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val inputFile = File.createTempFile("fliq-pdf-", ".pdf", context.cacheDir)
        try {
            context.contentResolver.openInputStream(src.uri)?.use { input ->
                inputFile.outputStream().use { out -> input.copyTo(out, 32 * 1024) }
            } ?: throw IOException("Cannot open PDF file")

            PDDocument.load(inputFile).use { document ->
                if (document.isEncrypted) throw IOException("Encrypted PDFs are not supported")
                if (document.numberOfPages <= 0) throw IOException("PDF has no pages")

                val stripper = PDFTextStripper()
                ZipOutputStream(output).use { zip ->
                    put(zip, "[Content_Types].xml", CONTENT_TYPES)
                    put(zip, "_rels/.rels", ROOT_RELS)
                    zip.putNextEntry(ZipEntry("word/document.xml"))
                    zip.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""".toByteArray(Charsets.UTF_8))

                    var hasText = false
                    for (page in 1..document.numberOfPages) {
                        stripper.startPage = page
                        stripper.endPage = page
                        val pageText = stripper.getText(document)
                        val lines = pageText.replace("\r\n", "\n").replace('\r', '\n').split('\n')
                        for (line in lines) {
                            if (line.isNotBlank()) hasText = true
                            writeParagraph(zip, line)
                        }
                        if (page != document.numberOfPages) {
                            zip.write("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""".toByteArray(Charsets.UTF_8))
                        }
                    }
                    if (!hasText) throw IOException("No selectable text found. Scanned PDFs need OCR.")
                    zip.write("""<w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="720" w:footer="720" w:gutter="0"/></w:sectPr></w:body></w:document>""".toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    put(zip, "word/_rels/document.xml.rels", DOCUMENT_RELS)
                    zip.finish()
                }
            }
        } finally {
            inputFile.delete()
        }
    }

    private fun writeParagraph(zip: ZipOutputStream, line: String) {
        val xml = "<w:p><w:r><w:t xml:space=\"preserve\">" + escapeXml(line) + "</w:t></w:r></w:p>"
        zip.write(xml.toByteArray(Charsets.UTF_8))
    }

    private fun put(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (c == '\t' || c == '\n' || c == '\r' || c >= ' ') append(c)
            }
        }
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>"""

    private const val DOCUMENT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"/>"""
}
