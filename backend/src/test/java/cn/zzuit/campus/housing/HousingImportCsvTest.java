package cn.zzuit.campus.housing;

import static org.assertj.core.api.Assertions.*;
import cn.zzuit.campus.common.ApiException;
import org.junit.jupiter.api.Test;

class HousingImportCsvTest {
    static final String HEADER="学号,房间编号,床位编号,入住说明\r\n";
    @Test void preservesAccountZerosAndQuotedPhysicalLines() {
        var parsed=HousingImportCsv.parse("\uFEFF"+HEADER+"00123,R101,A,\"报到,核对\"\r\n00456,R102,B,\"补充\r\n\"\"说明\"\"\"\r\n");
        assertThat(parsed.ignored()).isZero();assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0).username()).isEqualTo("00123");assertThat(parsed.rows().get(0).reason()).isEqualTo("报到,核对");
        assertThat(parsed.rows().get(1).line()).isEqualTo(3);assertThat(parsed.rows().get(1).reason()).isEqualTo("补充\n\"说明\"");
    }
    @Test void ignoresOnlyEmptyOrUnfilledTemplateRows() {
        var parsed=HousingImportCsv.parse(HEADER+"\r\n,R101,A,\r\n001,R101,B,报到\r\n002,R101,C\r\n,R101,D,必须核对学号");
        assertThat(parsed.ignored()).isEqualTo(2);assertThat(parsed.rows()).hasSize(3);
        assertThat(parsed.rows().get(1).line()).isEqualTo(5);assertThat(parsed.rows().get(1).errors()).hasSize(2);
        assertThat(parsed.rows().get(2).errors()).contains("学号须为 1—64 个字符");
    }
    @Test void rejectsMalformedHeadersQuotesAndNulls() {
        for(String source:new String[]{"学号,床位编号,房间编号,入住说明\n1,R,A,说明",HEADER+"1,R,A,\"未闭合",HEADER+"1,R,A,\"说明\"多余",HEADER+"1,R,A,\u0000",HEADER})
            assertThatThrownBy(()->HousingImportCsv.parse(source)).isInstanceOf(ApiException.class);
    }
    @Test void enforcesByteAndRowLimitsAndReturnsFieldErrors() {
        assertThatThrownBy(()->HousingImportCsv.parse(HEADER+"中".repeat(40000))).isInstanceOf(ApiException.class).hasMessageContaining("100 KB");
        assertThatThrownBy(()->HousingImportCsv.parse(HEADER+"001,R,A,报到\n".repeat(201))).isInstanceOf(ApiException.class).hasMessageContaining("200");
        assertThat(HousingImportCsv.parse(HEADER+"001,错误房间,A,"+"长".repeat(201)).rows().get(0).errors()).hasSize(2);
        assertThat(HousingImportCsv.parse(HEADER+"001,R,A,报到\n".repeat(200)).rows()).hasSize(200);
    }
    @Test void exportedCodeCellsDoNotBecomeSpreadsheetFormulas() {
        assertThat(HousingImportCsv.cell("-101")).isEqualTo("\"'-101\"");assertThat(HousingImportCsv.cell("=HYPERLINK(\"x\")")).startsWith("\"'=");
        var parsed=HousingImportCsv.parse(HEADER+"001,"+HousingImportCsv.cell("-101")+","+HousingImportCsv.cell("-A")+",报到");
        assertThat(parsed.rows().get(0).roomCode()).isEqualTo("-101");assertThat(parsed.rows().get(0).bedCode()).isEqualTo("-A");assertThat(parsed.rows().get(0).errors()).isEmpty();
    }
}
