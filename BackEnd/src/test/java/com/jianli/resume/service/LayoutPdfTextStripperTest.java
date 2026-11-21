package com.jianli.resume.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LayoutPdfTextStripperTest {

    @Test
    void restoresVisualOrderWhenPdfDrawOrderIsReversed() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            write(document, page, "BOTTOM", 50, 600);
            write(document, page, "MIDDLE", 50, 650);
            write(document, page, "TOP", 50, 700);

            String text = new LayoutPdfTextStripper().extract(document);

            assertTrue(text.indexOf("TOP") < text.indexOf("MIDDLE"));
            assertTrue(text.indexOf("MIDDLE") < text.indexOf("BOTTOM"));
        }
    }

    @Test
    void keepsColumnsSeparateOnTheSameVisualLine() throws Exception {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);

            write(document, page, "RIGHT", 300, 700);
            write(document, page, "LEFT", 50, 700);

            String text = new LayoutPdfTextStripper().extract(document);

            assertTrue(text.contains("LEFT\tRIGHT"), text);
        }
    }

    private void write(PDDocument document, PDPage page, String text, float x, float y) throws Exception {
        try (PDPageContentStream stream = new PDPageContentStream(
                document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            stream.beginText();
            stream.setFont(PDType1Font.HELVETICA, 12);
            stream.newLineAtOffset(x, y);
            stream.showText(text);
            stream.endText();
        }
    }
}