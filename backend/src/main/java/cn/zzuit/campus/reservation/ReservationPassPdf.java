package cn.zzuit.campus.reservation;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class ReservationPassPdf {
    private static final Color BLUE = new Color(36, 76, 120);
    private static final Color INK = new Color(39, 57, 73);
    private static final Color MUTED = new Color(108, 125, 140);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private final byte[] fontBytes;

    public ReservationPassPdf() throws IOException {
        try (var input = new ClassPathResource("fonts/LXGWWenKai-Regular.ttf").getInputStream()) {
            fontBytes = input.readAllBytes();
        }
    }

    public byte[] generate(ReservationDtos.ReservationDetail reservation) throws IOException {
        if (!"APPROVED".equals(reservation.status())) throw new IllegalArgumentException("Only approved reservations have a pass");
        String number = "PASS-" + reservation.id() + "-" + reservation.requestKey().substring(0, Math.min(8, reservation.requestKey().length())).toUpperCase(Locale.ROOT);
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.getDocumentInformation().setTitle("公共场地借用电子通行凭证（演示）");
            document.getDocumentInformation().setAuthor("校园综合服务平台 · 毕业设计演示");
            var font = PDType0Font.load(document, new ByteArrayInputStream(fontBytes), true);
            try (var writer = new Writer(document, font, number)) {
                writer.paragraph("公共场地借用电子通行凭证", 19, 29, BLUE);
                writer.paragraph("凭证编号：" + number + "   /   申请版本：V" + reservation.version(), 10, 19, MUTED);
                writer.gap(14);
                writer.field("活动主题", reservation.activityTheme());
                writer.field("借用场地 / 设备", reservation.venueName());
                writer.field("场地位置", reservation.locationDetail());
                writer.field("准入使用时段", TIME.format(reservation.startsAt()) + " 至 " + TIME.format(reservation.endsAt()));
                writer.field("活动申请人", reservation.applicantName() + "（" + reservation.identityNumber() + "）");
                writer.field("所属学院 / 班级", reservation.departmentName() + " · " + (reservation.className() == null ? "教师 / 部门" : reservation.className()));
                writer.field("联系电话 / 预计人数", reservation.contactPhone() + " · " + reservation.expectedParticipants() + " 人");
                if (reservation.borrowEquipment() != null && !reservation.borrowEquipment().isBlank()) writer.field("核准借用配套设备", reservation.borrowEquipment());
                writer.field("使用提示", "活动开始前请联系场地管理员办理准入；遵守场地管理规定，结束时清理场地并关闭设备。仅适用于所载时段，取消或变更以系统最新状态为准。");
                writer.gap(4);
                writer.paragraph("本文件为毕业设计演示凭证，不作为学校正式通行授权。", 10, 17, MUTED);
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static final class Writer implements AutoCloseable {
        private static final float LEFT = 52, WIDTH = PDRectangle.A4.getWidth() - 104;
        private final PDDocument document;
        private final PDType0Font font;
        private final String number;
        private PDPageContentStream stream;
        private float y;
        private int page;

        Writer(PDDocument document, PDType0Font font, String number) throws IOException {
            this.document = document; this.font = font; this.number = number;
            nextPage();
        }

        private void nextPage() throws IOException {
            if (stream != null) stream.close();
            var sheet = new PDPage(PDRectangle.A4);
            document.addPage(sheet);
            stream = new PDPageContentStream(document, sheet);
            page++;
            text("郑州工业应用技术学院", 18, LEFT, 781, BLUE);
            text("校园综合服务平台 · 数字化空间服务", 10, LEFT, 757, MUTED);
            stream.setStrokingColor(new Color(215, 225, 235));
            stream.moveTo(LEFT, 742); stream.lineTo(LEFT + WIDTH, 742); stream.stroke();
            text("毕业设计演示 · 非学校正式凭证", 9, LEFT, 38, MUTED);
            text("第 " + page + " 页", 9, 493, 38, MUTED);
            y = 708;
            if (page > 1) { text(number + "（续页）", 10, LEFT, y, MUTED); y -= 28; }
        }

        private void text(String value, float size, float x, float baseline, Color color) throws IOException {
            stream.beginText(); stream.setFont(font, size); stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x, baseline); stream.showText(value); stream.endText();
        }

        private String safe(String value) throws IOException {
            var result = new StringBuilder();
            for (int code : (value == null ? "未填写" : value).codePoints().toArray()) {
                if (code == '\n') { result.append('\n'); continue; }
                if (Character.isISOControl(code)) { result.append(' '); continue; }
                String character = new String(Character.toChars(code));
                try { font.encode(character); result.append(character); }
                catch (IllegalArgumentException ex) { result.append("[U+").append(Integer.toHexString(code).toUpperCase(Locale.ROOT)).append(']'); }
            }
            return result.toString();
        }

        void gap(float height) { y -= height; }

        void field(String label, String value) throws IOException {
            if (y < 113) nextPage();
            paragraph(label, 10, 17, MUTED);
            paragraph(value, 12, 20, INK);
            gap(10);
        }

        void paragraph(String value, float size, float lineHeight, Color color) throws IOException {
            var line = new StringBuilder();
            for (int code : safe(value).codePoints().toArray()) {
                if (code == '\n') { line(line.toString(), size, lineHeight, color); line.setLength(0); continue; }
                String character = new String(Character.toChars(code));
                if (line.length() > 0 && font.getStringWidth(line + character) * size / 1000 > WIDTH) {
                    line(line.toString(), size, lineHeight, color); line.setLength(0);
                }
                line.append(character);
            }
            if (line.length() > 0) line(line.toString(), size, lineHeight, color);
        }

        private void line(String value, float size, float height, Color color) throws IOException {
            if (y < 68 + height) nextPage();
            text(value, size, LEFT, y, color);
            y -= height;
        }

        @Override public void close() throws IOException { if (stream != null) stream.close(); }
    }
}
