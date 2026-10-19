package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.assertThat;
import cn.zzuit.campus.common.CampusReportPdf;
import cn.zzuit.campus.common.CampusReportPdf.Section;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

class CampusReportPdfTest {
    @Test void longChineseTextAndUnsupportedCharactersWrapAndPaginateWithoutLoss() throws Exception {
        var pdf = new CampusReportPdf();
        var sections = List.of(new Section("长文本分页验证", "校园课程与教室安排\n".repeat(95) + "最后一项 🧪"));
        try (var document = Loader.loadPDF(pdf.generate("课程与报告", "中文导出测试", sections))) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            var stripper = new PDFTextStripper() {
                @Override protected void writeString(String text, List<TextPosition> positions) throws java.io.IOException {
                    for (var position : positions) {
                        assertThat(position.getXDirAdj()).isGreaterThanOrEqualTo(51);
                        assertThat(position.getXDirAdj() + position.getWidthDirAdj()).isLessThan(546);
                        assertThat(position.getYDirAdj()).isBetween(30f, 812f);
                    }
                    super.writeString(text, positions);
                }
            };
            assertThat(stripper.getText(document)).contains("中文导出测试", "最后一项", "[U+1F9EA]");
            assertThat(document.getDocumentCatalog().getOpenAction()).isNull();
        }
    }
}
