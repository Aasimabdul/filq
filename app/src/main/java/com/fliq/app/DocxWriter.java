package com.fliq.app;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Streaming minimal .docx writer (plain Java, no Android classes). Paragraphs are written as they arrive. */
public final class DocxWriter {
    private final ZipOutputStream zip;
    private final BufferedWriter w;
    private boolean finished;

    private static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";

    public DocxWriter(OutputStream os) throws IOException {
        zip = new ZipOutputStream(os);
        put("[Content_Types].xml",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "</Types>");
        put("_rels/.rels",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "</Relationships>");
        put("word/_rels/document.xml.rels",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>");
        put("word/styles.xml",
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:styles " + NS + "><w:docDefaults><w:rPrDefault><w:rPr>"
                + "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\" w:eastAsia=\"Calibri\"/>"
                + "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:rPrDefault>"
                + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"259\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>"
                + "</w:docDefaults><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style></w:styles>");
        zip.putNextEntry(new ZipEntry("word/document.xml"));
        w = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8), 16 * 1024);
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document " + NS + "><w:body>");
    }

    private void put(String name, String xml) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    public void paragraph(String text, double sizePt, boolean bold, boolean pageBreakBefore) throws IOException {
        int half = (int) Math.round(sizePt * 2);
        half = Math.max(8, Math.min(144, half));
        w.write("<w:p><w:pPr>");
        if (pageBreakBefore) w.write("<w:pageBreakBefore/>");
        w.write("</w:pPr><w:r><w:rPr>");
        if (bold) w.write("<w:b/><w:bCs/>");
        w.write(String.format(Locale.US, "<w:sz w:val=\"%d\"/><w:szCs w:val=\"%d\"/></w:rPr>", half, half));
        StringBuilder seg = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t' || c == '\n') {
                flushText(seg);
                w.write(c == '\t' ? "<w:tab/>" : "<w:br/>");
            } else if (valid(text, i)) {
                seg.append(c);
            }
        }
        flushText(seg);
        w.write("</w:r></w:p>");
    }

    private void flushText(StringBuilder seg) throws IOException {
        if (seg.length() == 0) return;
        w.write("<w:t xml:space=\"preserve\">");
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c == '&') w.write("&amp;");
            else if (c == '<') w.write("&lt;");
            else if (c == '>') w.write("&gt;");
            else w.write(c);
        }
        w.write("</w:t>");
        seg.setLength(0);
    }

    /** XML 1.0 allows only some characters; lone surrogates and control codes are dropped. */
    private static boolean valid(String s, int i) {
        char c = s.charAt(i);
        if (c < 0x20) return c == '\r';
        if (c == 0xFFFE || c == 0xFFFF) return false;
        if (Character.isHighSurrogate(c)) return i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1));
        if (Character.isLowSurrogate(c)) return i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
        return true;
    }

    /** Finishes the file. Does not close the underlying stream. */
    public void finish() throws IOException {
        if (finished) return;
        finished = true;
        w.write("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
            + "<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\" w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/>"
            + "</w:sectPr></w:body></w:document>");
        w.flush();
        zip.closeEntry();
        zip.finish();
    }
}
