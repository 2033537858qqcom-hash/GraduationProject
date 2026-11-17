package cn.zzuit.campus.teaching;

import cn.zzuit.campus.teaching.TeachingModels.Lesson;
import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Landscape A4 timetable grid generator matching the web morning/afternoon/evening matrix. */
@Component
public class TimetableGridPdf {
    private static final Color BRAND = new Color(49, 82, 68);
    private static final Color HEADING = new Color(19, 45, 50);
    private static final Color INK = new Color(44, 62, 66);
    private static final Color MUTED = new Color(102, 114, 110);
    private static final Color GRID_BORDER = new Color(198, 193, 180);
    private static final Color HEADER_BG = new Color(241, 237, 228);
    private static final Color PERIOD_BG = new Color(247, 244, 237);
    private static final Color CARD_BG = new Color(245, 242, 234);
    private static final Color CARD_BORDER = new Color(214, 209, 197);

    private static final float MARGIN = 32f;
    private static final float PERIOD_COL_W = 68f;
    private static final float HEADER_ROW_H = 38f;
    private static final float MIN_ROW_H = 122f;
    private static final float PAGE_BOTTOM = 42f;
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final String[][] PERIODS = {
        {"上午", "08:00-12:00"},
        {"下午", "12:00-18:00"},
        {"晚上", "18:00-22:00"},
    };
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private final byte[] fontBytes;

    public TimetableGridPdf() throws IOException {
        try (var input = new ClassPathResource("fonts/LXGWWenKai-Regular.ttf").getInputStream()) {
            this.fontBytes = input.readAllBytes();
        }
    }

    public byte[] generate(String title, String subtitle, LocalDate weekStart, List<Lesson> lessons, boolean teacher)
            throws IOException {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.getDocumentInformation().setTitle(title);
            document.getDocumentInformation().setAuthor("校园综合服务平台 · 课程表");
            var font = PDType0Font.load(document, new ByteArrayInputStream(fontBytes), true);
            var pageBox = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            float tableW = pageBox.getWidth() - MARGIN * 2;
            float dayColW = (tableW - PERIOD_COL_W) / 7f;
            float innerCardTextW = dayColW - 16f;

            @SuppressWarnings("unchecked")
            List<LessonCard>[][] cells = new List[3][7];
            for (int p = 0; p < 3; p++) {
                for (int d = 0; d < 7; d++) cells[p][d] = new ArrayList<>();
            }

            for (var lesson : lessons.stream()
                    .sorted(Comparator.comparing(Lesson::startsAt).thenComparingLong(Lesson::id))
                    .toList()) {
                int dayIndex = (int) java.time.temporal.ChronoUnit.DAYS.between(weekStart, lesson.startsAt().toLocalDate());
                if (dayIndex < 0 || dayIndex >= 7) continue;
                int periodIndex = periodOf(lesson.startsAt().getHour());
                cells[periodIndex][dayIndex].add(buildCard(font, lesson, teacher, innerCardTextW));
            }

            try (var drawer = new Drawer(document, font, pageBox, title, subtitle, weekStart, tableW, dayColW)) {
                for (int p = 0; p < 3; p++) {
                    float rowH = MIN_ROW_H;
                    for (int d = 0; d < 7; d++) {
                        float cellH = 10f;
                        for (var card : cells[p][d]) cellH += card.height + 5f;
                        if (cellH + 4f > rowH) rowH = cellH + 4f;
                    }
                    drawer.ensureSpace(rowH);
                    drawer.drawPeriodRow(PERIODS[p][0], PERIODS[p][1], cells[p], rowH);
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static int periodOf(int hour) {
        if (hour < 12) return 0;
        if (hour < 18) return 1;
        return 2;
    }

    private LessonCard buildCard(PDType0Font font, Lesson lesson, boolean teacher, float maxW) throws IOException {
        String timeRange = CLOCK.format(lesson.startsAt()) + "-"
                + (lesson.endsAt().toLocalDate().equals(lesson.startsAt().toLocalDate())
                        ? CLOCK.format(lesson.endsAt())
                        : lesson.endsAt().toString().replace('T', ' '));
        List<String> titleLines = wrapText(font, lesson.courseName(), 8.5f, maxW);
        List<String> roomLines = wrapText(font, lesson.classroom() + " · " + lesson.campusName(), 7.2f, maxW);
        List<String> metaLines = wrapText(font, teacher ? lesson.className() : lesson.teacherName(), 7.2f, maxW);
        float height = 7f + 11f + titleLines.size() * 11f + (roomLines.size() + metaLines.size()) * 9.5f + 4f;
        return new LessonCard(timeRange, titleLines, roomLines, metaLines, height);
    }

    private static List<String> wrapText(PDType0Font font, String raw, float fontSize, float maxWidth) throws IOException {
        List<String> out = new ArrayList<>();
        String clean = sanitize(font, raw == null ? "" : raw);
        var current = new StringBuilder();
        for (int cp : clean.codePoints().toArray()) {
            String ch = new String(Character.toChars(cp));
            if (cp == '\n') {
                if (!current.isEmpty()) out.add(current.toString());
                current.setLength(0);
                continue;
            }
            if (!current.isEmpty() && font.getStringWidth(current + ch) * fontSize / 1000f > maxWidth) {
                out.add(current.toString());
                current.setLength(0);
            }
            current.append(ch);
        }
        if (!current.isEmpty()) out.add(current.toString());
        if (out.isEmpty()) out.add("");
        return out;
    }

    private static String sanitize(PDType0Font font, String value) throws IOException {
        var sb = new StringBuilder();
        for (int cp : value.codePoints().toArray()) {
            String ch = Character.isISOControl(cp) ? " " : new String(Character.toChars(cp));
            try {
                font.encode(ch);
                sb.append(ch);
            } catch (IllegalArgumentException ex) {
                sb.append("[U+").append(Integer.toHexString(cp).toUpperCase(Locale.ROOT)).append("]");
            }
        }
        return sb.toString();
    }

    private record LessonCard(
            String timeRange, List<String> titleLines, List<String> roomLines, List<String> metaLines, float height) {}

    private static final class Drawer implements AutoCloseable {
        private final PDDocument document;
        private final PDType0Font font;
        private final PDRectangle pageBox;
        private final String title;
        private final String subtitle;
        private final LocalDate weekStart;
        private final float tableW;
        private final float dayColW;
        private PDPageContentStream stream;
        private float cursorY;
        private int pageNumber;

        Drawer(
                PDDocument document,
                PDType0Font font,
                PDRectangle pageBox,
                String title,
                String subtitle,
                LocalDate weekStart,
                float tableW,
                float dayColW)
                throws IOException {
            this.document = document;
            this.font = font;
            this.pageBox = pageBox;
            this.title = title;
            this.subtitle = subtitle;
            this.weekStart = weekStart;
            this.tableW = tableW;
            this.dayColW = dayColW;
            startPage();
        }

        void ensureSpace(float rowH) throws IOException {
            if (cursorY - rowH < PAGE_BOTTOM) {
                startPage();
            }
        }

        private void startPage() throws IOException {
            if (stream != null) stream.close();
            var page = new PDPage(pageBox);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            pageNumber++;

            float topY = pageBox.getHeight() - 32f;
            drawText("郑州工业应用技术学院 · " + title, 15f, MARGIN, topY, BRAND);
            drawText(subtitle, 9.5f, MARGIN, topY - 18f, MUTED);

            stream.setStrokingColor(GRID_BORDER);
            stream.setLineWidth(0.75f);
            stream.moveTo(MARGIN, topY - 27f);
            stream.lineTo(MARGIN + tableW, topY - 27f);
            stream.stroke();

            // Footer
            drawText("说明：仅包含当前账号所选周次的课程安排；排课变更以教务系统最新信息为准。", 8f, MARGIN, 20f, MUTED);
            String pageLabel = "第 " + pageNumber + " 页";
            float pw = font.getStringWidth(pageLabel) * 8f / 1000f;
            drawText(pageLabel, 8f, MARGIN + tableW - pw, 20f, MUTED);

            cursorY = topY - 36f;
            drawTableHeader();
        }

        private void drawTableHeader() throws IOException {
            float bottomY = cursorY - HEADER_ROW_H;
            fillRect(MARGIN, bottomY, tableW, HEADER_ROW_H, HEADER_BG);
            strokeRect(MARGIN, bottomY, tableW, HEADER_ROW_H, GRID_BORDER, 0.8f);

            float periodW = PERIOD_COL_W;
            drawCenteredText("时段", 9.5f, MARGIN, periodW, bottomY + 15f, HEADING);
            drawVerticalLine(MARGIN + periodW, bottomY, cursorY);

            for (int d = 0; d < 7; d++) {
                float colX = MARGIN + periodW + d * dayColW;
                if (d > 0) drawVerticalLine(colX, bottomY, cursorY);
                LocalDate day = weekStart.plusDays(d);
                drawCenteredText(WEEKDAYS[d], 10f, colX, dayColW, bottomY + 20f, HEADING);
                drawCenteredText(day.toString(), 7.5f, colX, dayColW, bottomY + 7f, MUTED);
            }
            cursorY = bottomY;
        }

        void drawPeriodRow(String label, String range, List<LessonCard>[] dayCards, float rowH) throws IOException {
            float bottomY = cursorY - rowH;
            fillRect(MARGIN, bottomY, PERIOD_COL_W, rowH, PERIOD_BG);
            strokeRect(MARGIN, bottomY, tableW, rowH, GRID_BORDER, 0.8f);
            drawVerticalLine(MARGIN + PERIOD_COL_W, bottomY, cursorY);

            float midY = bottomY + rowH / 2f;
            drawCenteredText(label, 10.5f, MARGIN, PERIOD_COL_W, midY + 5f, HEADING);
            drawCenteredText(range, 7.2f, MARGIN, PERIOD_COL_W, midY - 8f, MUTED);

            for (int d = 0; d < 7; d++) {
                float colX = MARGIN + PERIOD_COL_W + d * dayColW;
                if (d > 0) drawVerticalLine(colX, bottomY, cursorY);
                List<LessonCard> cards = dayCards[d];
                if (cards.isEmpty()) {
                    drawCenteredText("-", 9f, colX, dayColW, midY - 3f, MUTED);
                    continue;
                }
                float cardY = cursorY - 5f;
                for (var card : cards) {
                    float cardBottom = cardY - card.height;
                    float cardX = colX + 4f;
                    float cardW = dayColW - 8f;
                    fillRect(cardX, cardBottom, cardW, card.height, CARD_BG);
                    strokeRect(cardX, cardBottom, cardW, card.height, CARD_BORDER, 0.6f);
                    // Top accent bar matching web card top border
                    fillRect(cardX, cardY - 1.8f, cardW, 1.8f, BRAND);

                    float textY = cardY - 11f;
                    drawText(card.timeRange, 7.4f, cardX + 4f, textY, MUTED);
                    textY -= 11f;
                    for (String line : card.titleLines) {
                        drawText(line, 8.5f, cardX + 4f, textY, HEADING);
                        textY -= 11f;
                    }
                    for (String line : card.roomLines) {
                        drawText(line, 7.2f, cardX + 4f, textY, INK);
                        textY -= 9.5f;
                    }
                    for (String line : card.metaLines) {
                        drawText(line, 7.2f, cardX + 4f, textY, MUTED);
                        textY -= 9.5f;
                    }
                    cardY = cardBottom - 5f;
                }
            }
            cursorY = bottomY;
        }

        private void drawText(String value, float size, float x, float baseline, Color color) throws IOException {
            String safe = sanitize(font, value);
            stream.beginText();
            stream.setFont(font, size);
            stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x, baseline);
            stream.showText(safe);
            stream.endText();
        }

        private void drawCenteredText(String value, float size, float colX, float colW, float baseline, Color color)
                throws IOException {
            String safe = sanitize(font, value);
            float w = font.getStringWidth(safe) * size / 1000f;
            drawText(safe, size, colX + Math.max(2f, (colW - w) / 2f), baseline, color);
        }

        private void fillRect(float x, float y, float w, float h, Color color) throws IOException {
            stream.setNonStrokingColor(color);
            stream.addRect(x, y, w, h);
            stream.fill();
        }

        private void strokeRect(float x, float y, float w, float h, Color color, float lineW) throws IOException {
            stream.setStrokingColor(color);
            stream.setLineWidth(lineW);
            stream.addRect(x, y, w, h);
            stream.stroke();
        }

        private void drawVerticalLine(float x, float bottomY, float topY) throws IOException {
            stream.setStrokingColor(GRID_BORDER);
            stream.setLineWidth(0.65f);
            stream.moveTo(x, bottomY);
            stream.lineTo(x, topY);
            stream.stroke();
        }

        @Override
        public void close() throws IOException {
            if (stream != null) stream.close();
        }
    }
}
