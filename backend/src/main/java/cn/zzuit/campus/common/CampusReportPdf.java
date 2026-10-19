package cn.zzuit.campus.common;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Text reports with an embedded Chinese font, wrapping and page breaks. */
@Component
public class CampusReportPdf {
    public record Section(String heading, String body) {}
    private static final Color BLUE = new Color(36, 76, 120), INK = new Color(39, 57, 73), MUTED = new Color(108, 125, 140);
    private final byte[] fontBytes;

    public CampusReportPdf() throws IOException {
        try (var input = new ClassPathResource("fonts/LXGWWenKai-Regular.ttf").getInputStream()) { fontBytes = input.readAllBytes(); }
    }

    public byte[] generate(String title, String subtitle, List<Section> sections) throws IOException {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.getDocumentInformation().setTitle(title);
            document.getDocumentInformation().setAuthor("校园综合服务平台 · 毕业设计演示");
            var font = PDType0Font.load(document, new ByteArrayInputStream(fontBytes), true);
            try (var writer = new Writer(document, font)) {
                writer.paragraph(title, 20, 30, BLUE);
                writer.paragraph(subtitle, 10, 17, MUTED);
                writer.y -= 16;
                for (var section : sections) {
                    if (writer.y < 130) writer.nextPage();
                    writer.paragraph(section.heading(), 11, 19, BLUE);
                    writer.paragraph(section.body(), 11, 18, INK);
                    writer.y -= 11;
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static class Writer implements AutoCloseable {
        private static final float LEFT = 52, WIDTH = PDRectangle.A4.getWidth() - 104;
        private final PDDocument document;
        private final PDType0Font font;
        private PDPageContentStream stream;
        private float y;
        private int page;
        Writer(PDDocument document, PDType0Font font) throws IOException { this.document = document; this.font = font; nextPage(); }
        void nextPage() throws IOException {
            if (stream != null) stream.close();
            var sheet = new PDPage(PDRectangle.A4); document.addPage(sheet);
            stream = new PDPageContentStream(document, sheet); page++;
            text("郑州工业应用技术学院", 17, LEFT, 782, BLUE);
            text("校园综合服务平台", 10, LEFT, 759, MUTED);
            stream.setStrokingColor(new Color(215, 225, 235));
            stream.moveTo(LEFT, 743); stream.lineTo(LEFT + WIDTH, 743); stream.stroke();
            text("毕业设计演示", 9, LEFT, 38, MUTED);
            text("第 " + page + " 页", 9, 490, 38, MUTED);
            y = 710;
        }
        void text(String value, float size, float x, float baseline, Color color) throws IOException {
            stream.beginText(); stream.setFont(font, size); stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x, baseline); stream.showText(value); stream.endText();
        }
        void paragraph(String value, float size, float height, Color color) throws IOException {
            var line = new StringBuilder();
            for (int code : (value == null ? "未填写" : value).codePoints().toArray()) {
                if (code == '\n') { line(line.toString(), size, height, color); line.setLength(0); continue; }
                String character = Character.isISOControl(code) ? " " : new String(Character.toChars(code));
                try { font.encode(character); }
                catch (IllegalArgumentException ex) { character = "[U+" + Integer.toHexString(code).toUpperCase(Locale.ROOT) + "]"; }
                if (!line.isEmpty() && font.getStringWidth(line + character) * size / 1000 > WIDTH) {
                    line(line.toString(), size, height, color); line.setLength(0);
                }
                line.append(character);
            }
            if (!line.isEmpty()) line(line.toString(), size, height, color);
        }
        void line(String value, float size, float height, Color color) throws IOException {
            if (y < 70 + height) nextPage();
            text(value, size, LEFT, y, color); y -= height;
        }
        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }
}
