package com.rabitah.backend.notice;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Service;

/** Creates a downloadable PDF when an administrator writes a notice instead of uploading one. */
@Service
public final class NoticePdfService {
    private static final float MARGIN = 54;
    private static final float BODY_SIZE = 11;
    private static final float LEADING = 16;

    public byte[] create(String title, String body, String scope) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PageWriter writer = new PageWriter(document);
            writer.heading(title);
            writer.small("Official Rabitah notice" + (scope.isBlank() ? "" : " · " + scope));
            writer.rule();
            for (String line : wrappedLines(body)) {
                writer.body(line);
            }
            writer.close();
            document.save(output);
            return output.toByteArray();
        }
    }

    private List<String> wrappedLines(String text) throws IOException {
        List<String> result = new ArrayList<>();
        float width = PDRectangle.A4.getWidth() - MARGIN * 2;
        for (String paragraph : text.replace("\r", "").split("\n", -1)) {
            if (paragraph.isBlank()) {
                result.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (PDType1Font.HELVETICA.getStringWidth(candidate) / 1000 * BODY_SIZE <= width) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    result.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            if (!line.isEmpty()) {
                result.add(line.toString());
            }
        }
        return result;
    }

    private static final class PageWriter {
        private final PDDocument document;
        private PDPageContentStream stream;
        private float y;
        private int page;

        private PageWriter(PDDocument document) throws IOException {
            this.document = document;
            nextPage();
        }

        private void nextPage() throws IOException {
            if (stream != null) {
                footer();
                stream.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = page.getMediaBox().getHeight() - MARGIN;
            this.page++;
        }

        private void heading(String value) throws IOException {
            write(value, PDType1Font.HELVETICA_BOLD, 17, 24);
        }

        private void small(String value) throws IOException {
            write(value, PDType1Font.HELVETICA_OBLIQUE, 9, 16);
        }

        private void body(String value) throws IOException {
            write(value, PDType1Font.HELVETICA, BODY_SIZE, LEADING);
        }

        private void rule() throws IOException {
            y -= 6;
            stream.moveTo(MARGIN, y);
            stream.lineTo(PDRectangle.A4.getWidth() - MARGIN, y);
            stream.stroke();
            y -= 16;
        }

        private void write(String value, PDType1Font font, float size, float leading) throws IOException {
            if (y - leading < MARGIN + 20) {
                nextPage();
            }
            if (value.isBlank()) {
                y -= leading;
                return;
            }
            stream.beginText();
            stream.setFont(font, size);
            stream.newLineAtOffset(MARGIN, y);
            stream.showText(printable(value));
            stream.endText();
            y -= leading;
        }

        private void footer() throws IOException {
            if (stream == null) {
                return;
            }
            stream.beginText();
            stream.setFont(PDType1Font.HELVETICA, 8);
            stream.newLineAtOffset(MARGIN, MARGIN / 2);
            stream.showText("Rabitah official notice - Page " + page);
            stream.endText();
        }

        private void close() throws IOException {
            footer();
            stream.close();
            stream = null;
        }

        private String printable(String value) {
            return value.replaceAll("[^\\x20-\\x7E]", "?");
        }
    }
}
