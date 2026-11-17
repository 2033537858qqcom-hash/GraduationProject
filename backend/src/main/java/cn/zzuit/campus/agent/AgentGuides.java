package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AgentGuides {
    private final JdbcClient jdbc;
    private static final Map<String,List<String>> SYNONYMS=Map.ofEntries(
        Map.entry("调宿",List.of("换宿舍","换寝室","搬宿舍","搬寝室","转宿","宿舍调整","床位调整","退宿")),
        Map.entry("证明",List.of("在校证明","在读证明","学籍证明","成绩单","开证明","办证明","实习证明")),
        Map.entry("请假",List.of("病假","事假","休假","假条","销假","返校")),
        Map.entry("报修",List.of("维修","坏了","漏水","跳闸","断电","断网","灯不亮","堵塞","报修单","路由器","空调","卫生间")),
        Map.entry("预约",List.of("借用","借教室","订教室","订会议室","场地借用","活动室","报告厅","体育场馆","组会","开会","团建","自习室")),
        Map.entry("成绩",List.of("分数","绩点","挂科","重修","学分","gpa")),
        Map.entry("课表",List.of("课程表","上课","授课","课程安排","没课","空档")),
        Map.entry("缴费",List.of("交费","账单","欠费","水电费","电费","水费","充值","退费")),
        Map.entry("消息",List.of("通知","公告","未读","提醒","站内信"))
    );
    private static final Set<String> STOP_GRAMS=Set.of("怎么","如何","办理","申请","规则","材料","领取","流程","查询","查看","什么","哪些","需要","可以","我的","这个","指南","未知","学生","学校","校园","平台","系统","业务","问题","信息","老师","同学","现在","时候");
    public AgentGuides(JdbcClient jdbc) { this.jdbc=jdbc; }
    private record Ranked(Guide guide,double score) {}
    public List<Guide> search(String query) {
        if(query==null || query.isBlank() || query.length()>200) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_TOOL_ARGUMENTS","检索词须为 1—200 字");
        String normalized=query.trim().toLowerCase(Locale.ROOT);
        Set<String> expandedTerms=expandSynonyms(normalized);
        var rows=jdbc.sql("SELECT * FROM agent_guide WHERE status='PUBLISHED' ORDER BY slug LIMIT 100")
            .query((r,n)->new RawGuide(r.getString("slug"),r.getString("title"),r.getString("keywords"),r.getString("body"),r.getInt("version"),r.getTimestamp("updated_at").toLocalDateTime())).list();
        var idf=computeBigramIdf(rows);
        var queryGrams=bigrams(normalized);
        return rows.stream()
            .map(row->{
                double score=scoreGuide(normalized,expandedTerms,queryGrams,idf,row);
                var guide=new Guide(row.slug(),row.title(),row.body(),row.version(),row.updatedAt(),"/assistant/guides/"+row.slug());
                return new Ranked(guide,score);
            })
            .filter(x->x.score()>0 && validSlug(x.guide().slug()))
            .sorted(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(x->x.guide().slug()))
            .limit(3).map(Ranked::guide).toList();
    }
    private record RawGuide(String slug,String title,String keywords,String body,int version,java.time.LocalDateTime updatedAt) {}
    private Map<String,Double> computeBigramIdf(List<RawGuide> rows) {
        var df=new HashMap<String,Integer>();
        int n=Math.max(1,rows.size());
        for(var row:rows) {
            for(String g:bigrams(row.title()+" "+row.keywords()+" "+row.body())) df.merge(g,1,Integer::sum);
        }
        var idf=new HashMap<String,Double>();
        df.forEach((gram,count)->idf.put(gram,Math.log(1.0+(double)n/count)));
        return idf;
    }
    private Set<String> bigrams(String text) {
        var set=new LinkedHashSet<String>();
        if(text==null) return set;
        String lower=text.toLowerCase(Locale.ROOT);
        for(int i=0;i+2<=lower.length();i++) {
            String g=lower.substring(i,i+2);
            if(g.matches("[\\u4e00-\\u9fa5]{2}") && !STOP_GRAMS.contains(g)) set.add(g);
        }
        return set;
    }
    private double cosineSimilarity(Set<String> a,Set<String> b,Map<String,Double> idf) {
        if(a.isEmpty() || b.isEmpty()) return 0.0;
        double dot=0,normA=0,normB=0;
        for(String g:a) { double w=idf.getOrDefault(g,1.0); normA+=w*w; if(b.contains(g)) dot+=w*w; }
        for(String g:b) { double w=idf.getOrDefault(g,1.0); normB+=w*w; }
        return normA==0||normB==0?0.0:dot/(Math.sqrt(normA)*Math.sqrt(normB));
    }
    private String bestExcerpt(String body,String query,Set<String> expandedTerms) {
        if(body==null || body.length()<=240) return Objects.toString(body,"");
        String[] parts=body.split("\\n+");
        if(parts.length<=1) return body;
        String header=parts[0];
        String best=null; int bestScore=0;
        for(int i=1;i<parts.length;i++) {
            String p=parts[i].trim(); if(p.isBlank()) continue;
            int s=0; String lower=p.toLowerCase(Locale.ROOT);
            for(String g:bigrams(query)) if(lower.contains(g)) s+=2;
            for(String t:expandedTerms) if(lower.contains(t)) s+=1;
            if(s>bestScore) { bestScore=s; best=p; }
        }
        if(best==null || bestScore<3 || best.equals(header)) return body;
        return header+"\n"+best+"\n（完整条款见指南详情）";
    }
    private Set<String> expandSynonyms(String normalized) {
        var expanded=new LinkedHashSet<String>();
        for(var entry:SYNONYMS.entrySet()) {
            if(normalized.contains(entry.getKey())) expanded.add(entry.getKey());
            for(String alias:entry.getValue()) {
                if(normalized.contains(alias)) expanded.add(entry.getKey());
            }
        }
        return expanded;
    }
    private double scoreGuide(String query,Set<String> expandedTerms,Set<String> queryGrams,Map<String,Double> idf,RawGuide row) {
        double score=0;
        String titleLower=row.title()==null?"":row.title().toLowerCase(Locale.ROOT);
        String bodyLower=row.body()==null?"":row.body().toLowerCase(Locale.ROOT);
        String keywordsRaw=Objects.toString(row.keywords(),"");
        for(String raw:keywordsRaw.split(",")) {
            String word=raw.trim().toLowerCase(Locale.ROOT);
            if(word.isBlank()) continue;
            if(query.contains(word)) score+=4.0;
            else if(expandedTerms.stream().anyMatch(term->word.contains(term)||term.contains(word))) score+=3.0;
        }
        for(String term:expandedTerms) {
            if(titleLower.contains(term)) score+=3.0;
            else if(bodyLower.contains(term)) score+=1.2;
        }
        if(score==0 && !queryGrams.isEmpty()) {
            String titleDomain=titleLower.replaceAll("(?:办理指南|申请与下载指南|查询指南|指南|规则|流程|说明)$","");
            double titleSim=cosineSimilarity(queryGrams,bigrams(titleDomain+" "+keywordsRaw),idf);
            double bodySim=cosineSimilarity(queryGrams,bigrams(bodyLower),idf);
            if(titleSim>=0.18) score+=titleSim*10.0;
            else if(bodySim>=0.24) score+=bodySim*6.0;
        }
        return score;
    }
    public Guide detail(String slug) {
        if(!validSlug(slug)) throw new ApiException(HttpStatus.NOT_FOUND,"GUIDE_NOT_FOUND","指南不存在或已撤下");
        return jdbc.sql("SELECT * FROM agent_guide WHERE slug=:s AND status='PUBLISHED'").param("s",slug)
            .query((r,n)->new Guide(r.getString("slug"),r.getString("title"),r.getString("body"),r.getInt("version"),r.getTimestamp("updated_at").toLocalDateTime(),"/assistant/guides/"+r.getString("slug")))
            .optional().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"GUIDE_NOT_FOUND","指南不存在或已撤下"));
    }
    private boolean validSlug(String slug) { return slug!=null && slug.matches("[a-z0-9][a-z0-9-]{0,79}"); }
}
