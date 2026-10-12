package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AgentGuides {
    private final JdbcClient jdbc;
    public AgentGuides(JdbcClient jdbc) { this.jdbc=jdbc; }
    private record Ranked(Guide guide,int score) {}
    public List<Guide> search(String query) {
        if(query==null || query.isBlank() || query.length()>200) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_TOOL_ARGUMENTS","检索词须为 1—200 字");
        return jdbc.sql("SELECT * FROM agent_guide WHERE status='PUBLISHED' ORDER BY slug LIMIT 100")
            .query((r,n)->new Ranked(new Guide(r.getString("slug"),r.getString("title"),r.getString("body"),r.getInt("version"),r.getTimestamp("updated_at").toLocalDateTime(),"/assistant/guides/"+r.getString("slug")),
                (int)Arrays.stream(r.getString("keywords").split(",")).map(String::trim).filter(word->!word.isBlank() && query.contains(word)).count())).list()
            .stream().filter(x->x.score()>0 && validSlug(x.guide().slug())).sorted(Comparator.comparingInt(Ranked::score).reversed().thenComparing(x->x.guide().slug())).limit(3).map(Ranked::guide).toList();
    }
    public Guide detail(String slug) {
        if(!validSlug(slug)) throw new ApiException(HttpStatus.NOT_FOUND,"GUIDE_NOT_FOUND","指南不存在或已撤下");
        return jdbc.sql("SELECT * FROM agent_guide WHERE slug=:s AND status='PUBLISHED'").param("s",slug)
            .query((r,n)->new Guide(r.getString("slug"),r.getString("title"),r.getString("body"),r.getInt("version"),r.getTimestamp("updated_at").toLocalDateTime(),"/assistant/guides/"+r.getString("slug")))
            .optional().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"GUIDE_NOT_FOUND","指南不存在或已撤下"));
    }
    private boolean validSlug(String slug) { return slug!=null && slug.matches("[a-z0-9][a-z0-9-]{0,79}"); }
}
