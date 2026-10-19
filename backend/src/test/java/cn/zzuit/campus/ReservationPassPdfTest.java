package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import cn.zzuit.campus.reservation.ReservationDtos.ReservationDetail;
import cn.zzuit.campus.reservation.ReservationPassPdf;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

class ReservationPassPdfTest {
    private final ReservationPassPdf pdf = new ReservationPassPdf();

    ReservationPassPdfTest() throws Exception {}

    private ReservationDetail detail(String theme, String equipment, String status) {
        var start = LocalDateTime.of(2026, 10, 20, 22, 0);
        return new ReservationDetail(12, 1, "demo-request", 1, "图书馆一楼学术报告厅", "VENUE", "east-library",
            "图书馆一楼南侧大厅东翼", "陈同学（演示）", "20260001", "13800138001", "信息工程学院", "软件工程演示班",
            theme, "ACADEMIC", 120, equipment, start, start.plusHours(4), status, "审批通过", 2, start, start, List.of());
    }

    @Test void chinesePassFitsA4AndPreservesBothDates() throws Exception {
        byte[] bytes = pdf.generate(detail("计算机学科竞赛经验交流与答辩汇报会", "超清 LED 大屏、无线麦克风（4 支）", "APPROVED"));
        try (var document = Loader.loadPDF(bytes)) {
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            assertThat(document.getPage(0).getMediaBox().getWidth()).isEqualTo(PDRectangle.A4.getWidth());
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("公共场地借用电子通行凭证", "PASS-12-DEMO-REQ", "陈同学", "20260001", "2026-10-20 22:00", "2026-10-21 02:00", "无线麦克风", "毕业设计演示");
            Files.write(Path.of("target/reservation-pass-demo.pdf"), bytes);
            ImageIO.write(new PDFRenderer(document).renderImageWithDPI(0, 120), "png", Path.of("target/reservation-pass-demo.png").toFile());
        }
    }

    @Test void longMultilineContentPaginatesInsidePageMargins() throws Exception {
        String equipment = "设备清单与安全注意事项：\n" + "扩音设备与显示设备，请清点数量后归还。\n".repeat(75) + "最后一项归还完成 🧪";
        try (var document = Loader.loadPDF(pdf.generate(detail("长文本打印测试", equipment, "APPROVED")))) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            var stripper = new PDFTextStripper() {
                @Override protected void writeString(String text, List<TextPosition> positions) throws java.io.IOException {
                    for (var position : positions) {
                        assertThat(position.getXDirAdj()).isGreaterThanOrEqualTo(51);
                        assertThat(position.getXDirAdj() + position.getWidthDirAdj()).isLessThan(547);
                        assertThat(position.getYDirAdj()).isBetween(30f, 812f);
                    }
                    super.writeString(text, positions);
                }
            };
            assertThat(stripper.getText(document)).contains("最后一项归还完成", "续页");
        }
    }

    @Test void unfinishedOrCancelledRequestsDoNotProduceAPass() {
        for (String status : List.of("DRAFT", "SUBMITTED", "RETURNED", "REJECTED", "CANCELLED", "WITHDRAWN")) {
            assertThatThrownBy(() -> pdf.generate(detail("非有效预约", null, status))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
