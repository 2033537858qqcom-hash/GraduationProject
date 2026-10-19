package cn.zzuit.campus.certificate;

import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.common.CampusReportPdf;
import cn.zzuit.campus.common.CampusReportPdf.Section;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/certificates")
public class PublicCertificateController {
    private final JdbcClient jdbc;
    private final CampusReportPdf pdf;
    public PublicCertificateController(JdbcClient jdbc, CampusReportPdf pdf) { this.jdbc = jdbc; this.pdf = pdf; }

    @GetMapping("/verify")
    public ResponseEntity<CertificateModels.Verification> verify(@RequestParam(name="number", required=false, defaultValue="") String number) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(find(number));
    }

    @GetMapping("/report")
    public ResponseEntity<byte[]> report(@RequestParam String number) throws IOException {
        // Only reuse the masked public snapshot, never the private proof PDF.
        var data = find(number);
        if (!data.valid()) throw new ApiException(HttpStatus.NOT_FOUND, "CERTIFICATE_NOT_FOUND", "未检索到有效演示证明，无法导出报告");
        var bytes = pdf.generate("在校证明核验报告（演示）", "核验编号：" + data.documentNumber(), List.of(
            new Section("核验结果", "已匹配平台中已办结的演示证明记录。"),
            new Section("学生姓名 / 学号（已脱敏）", data.studentName() + " / " + data.identityNumber()),
            new Section("学院 / 专业", data.departmentName() + " / " + data.majorName()),
            new Section("行政班 / 校区", data.className() + " / " + data.campusName()),
            new Section("生成时间", data.issuedAt() == null ? "未记录" : data.issuedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))),
            new Section("报告说明", "本报告仅核对毕业设计平台内的演示记录，不代表学校正式学籍认证、电子签章或官方存证，不替代学校正式证明。公开报告仅展示脱敏信息；完整证明由本人或当前授权的学院办理人员登录后下载。")
        ));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("证明核验报告-" + data.documentNumber() + ".pdf", StandardCharsets.UTF_8).build().toString())
            .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").body(bytes);
    }

    private CertificateModels.Verification find(String number) {
        String trimmed = number.trim();
        if (trimmed.isEmpty()) {
            return new CertificateModels.Verification(false, "", "", "", "", "", "", "", null, "请输入在校证明防伪编码进行核验");
        }
        var found = jdbc.sql("""
            SELECT r.document_number, r.issued_at,
                   c.student_name, c.identity_number, c.department_name, c.major_name, c.class_name, c.campus_name
            FROM certificate_result r
            JOIN certificate_request c ON r.request_id = c.id
            WHERE r.document_number = :num AND c.status = 'COMPLETED'
        """).param("num", trimmed).query((rs, rowNum) -> {
            String rawName = rs.getString("student_name");
            String maskedName = maskName(rawName);
            String rawId = rs.getString("identity_number");
            String maskedId = maskId(rawId);
            Timestamp ts = rs.getTimestamp("issued_at");
            LocalDateTime issuedAt = ts != null ? ts.toLocalDateTime() : null;
            return new CertificateModels.Verification(
                true,
                rs.getString("document_number"),
                maskedName,
                maskedId,
                rs.getString("department_name"),
                rs.getString("major_name"),
                rs.getString("class_name"),
                rs.getString("campus_name"),
                issuedAt,
                "核验成功 · 已匹配平台中的已办结演示证明记录"
            );
        }).optional();

        return found.orElseGet(() ->
            new CertificateModels.Verification(false, trimmed, "", "", "", "", "", "", null, "未检索到匹配的有效在校证明，请核对防伪编号是否正确")
        );
    }

    private static String maskName(String name) {
        if (name == null || name.isEmpty()) return "";
        if (name.length() == 1) return name;
        if (name.length() == 2) return name.charAt(0) + "*";
        return name.charAt(0) + "*".repeat(name.length() - 2) + name.charAt(name.length() - 1);
    }

    private static String maskId(String id) {
        if (id == null || id.length() <= 4) return id == null ? "" : id;
        if (id.length() <= 8) return id.substring(0, 2) + "****" + id.substring(id.length() - 2);
        return id.substring(0, 4) + "****" + id.substring(id.length() - 4);
    }
}
