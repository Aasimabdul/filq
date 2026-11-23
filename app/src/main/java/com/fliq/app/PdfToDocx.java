package com.fliq.app;

import java.io.IOException;
import java.io.OutputStream;

/** PDF text -> .docx, fully offline. Text only: images, tables and exact layout are not carried over. */
public final class PdfToDocx {
    private PdfToDocx() { }

    public static void convert(byte[] pdf, OutputStream os) throws IOException {
        DocxWriter dw = new DocxWriter(os);
        int[] count = {0};
        int unmapped = PdfText.extract(pdf, (text, size, bold, newPage) -> {
            dw.paragraph(text, size, bold, newPage);
            count[0]++;
        });
        if (count[0] == 0) {
            throw new IOException(unmapped > 0
                ? "Can't read this PDF's text (unsupported fonts)"
                : "No text found in this PDF (scanned pages need OCR)");
        }
        dw.finish();
    }
}
