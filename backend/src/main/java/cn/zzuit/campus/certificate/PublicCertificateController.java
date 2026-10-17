package cn.zzuit.campus.certificate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public/certificates")
public class PublicCertificateController {
    private final JdbcClient jdbc;
    public PublicCertificateController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/verify")
    public CertificateModels.Verification verify(@RequestParam(name="number", required=false, defaultValue="") String number) {
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
                "核验成功 · 该证明由郑州工业应用技术学院权威出具并备案有效"
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
