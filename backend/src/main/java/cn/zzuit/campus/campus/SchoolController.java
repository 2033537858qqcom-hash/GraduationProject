package cn.zzuit.campus.campus;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SchoolController {
    private final JdbcClient jdbc;
    public SchoolController(JdbcClient jdbc) { this.jdbc = jdbc; }

    @GetMapping("/api/health")
    Map<String, String> health() {
        jdbc.sql("SELECT 1").query(Integer.class).single();
        return Map.of("status", "UP");
    }

    @GetMapping("/api/public/school")
    School school() {
        List<Campus> campuses = jdbc.sql("SELECT id,code,name,address FROM campus ORDER BY id")
            .query((rs, row) -> new Campus(rs.getLong("id"), rs.getString("code"),
                rs.getString("name"), rs.getString("address"))).list();
        return new School("郑州工业应用技术学院", "Zhengzhou University of Industrial Technology",
            "励学 · 敦行 · 明理 · 至善", "https://www.zzuit.edu.cn/", campuses);
    }

    public record Campus(long id, String code, String name, String address) {}
    public record School(String name, String englishName, String motto, String officialWebsite, List<Campus> campuses) {}
}
