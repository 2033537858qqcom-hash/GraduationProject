package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AgentHousingDrafts {
    private final IdentityService identities;
    private final AgentGuides guides;
    private final JdbcClient jdbc;
    public AgentHousingDrafts(IdentityService identities,AgentGuides guides,JdbcClient jdbc) {this.identities=identities;this.guides=guides;this.jdbc=jdbc;}
    public void require(CampusPrincipal user) {identities.requirePermission(user,"agent:query");identities.requirePermission(user,"housing:read");}
    public HousingFields normalize(HousingFields f) {
        var result=new HousingFields(text(f.reason()),text(f.preferredArea()),text(f.materialNote()));
        if(result.reason().length()>500 || result.preferredArea().length()>200 || result.materialNote().length()>500)throw new ApiException(HttpStatus.BAD_REQUEST,"HOUSING_PREVIEW_INVALID","请核对调宿字段字数");
        return result;
    }
    public HousingFields merge(HousingFields raw,Plan plan) {
        var f=normalize(raw);
        if(plan.calls()==null || plan.calls().size()!=1 || plan.calls().getFirst()==null || !"prepare_housing_fields".equals(plan.calls().getFirst().name()))throw rejected();
        var patch=plan.calls().getFirst().arguments();if(patch==null || !patch.isObject())throw rejected();
        var values=new HashMap<>(Map.of("reason",f.reason(),"preferredArea",f.preferredArea(),"materialNote",f.materialNote()));
        for(var entry:patch.properties()) {
            if(!values.containsKey(entry.getKey()) || !entry.getValue().isString() || entry.getValue().asText().isBlank())throw rejected();
            values.put(entry.getKey(),entry.getValue().asText().trim());
        }
        try {return normalize(new HousingFields(values.get("reason"),values.get("preferredArea"),values.get("materialNote")));}
        catch(ApiException ex) {throw rejected();}
    }
    public HousingPreview preview(CampusPrincipal user,String run,Mode mode,HousingFields raw) {
        require(user);var f=normalize(raw);var missing=new ArrayList<String>();var issues=new ArrayList<String>();
        if(f.reason().isBlank())missing.add("reason");if(f.preferredArea().isBlank())missing.add("preferredArea");if(f.materialNote().isBlank())missing.add("materialNote");
        var stay=jdbc.sql("""
            SELECT h.id,h.version,c.name campus,b.name building,r.name room,r.floor_label,bed.label bed
            FROM housing_assignment h JOIN dorm_bed bed ON bed.id=h.bed_id JOIN dorm_room r ON r.id=bed.room_id
            JOIN dorm_building b ON b.id=r.building_id JOIN campus c ON c.id=b.campus_id
            WHERE h.current_student_id=:u AND h.status='CURRENT'
            """
        ).param("u",user.id()).query((r,n)->new Stay(token(user.id()+":"+r.getLong("id")+":"+r.getInt("version")+":"+r.getString("campus")+":"+r.getString("building")+":"+r.getString("room")+":"+r.getString("floor_label")+":"+r.getString("bed")),r.getString("campus")+" · "+r.getString("building")+" · "+r.getString("room")+" · "+r.getString("bed"))).optional().orElse(null);
        if(stay==null)issues.add("暂无当前入住记录，请联系宿管核对，不能准备调宿草稿。");
        if(jdbc.sql("SELECT COUNT(*) FROM housing_transfer WHERE current_student_id=:u").param("u",user.id()).query(Long.class).single()>0)issues.add("已有未结束的调宿申请，请进入原申请继续办理。");
        Guide source=null;try {source=guides.detail("housing");}catch(ApiException ex){if(ex.status()!=HttpStatus.NOT_FOUND)throw ex;issues.add("住宿指南已撤下，请联系管理人员核对规则。");}
        boolean ready=missing.isEmpty() && issues.isEmpty();require(user);
        return new HousingPreview(run,mode,mode==Mode.DEEPSEEK,f,List.copyOf(missing),List.copyOf(issues),ready,
            ready?"信息已补齐，核对当前住宿后可保存本人草稿。":"请补齐填写内容，并核对当前住宿与申请状态。",source,
            "仅保存草稿；意向区域不代表已分配床位，提交、审核和入住由原业务流程办理。",stay==null?null:stay.place(),stay==null?null:token(stay.token()+":"+(source==null?"":source.version())),LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
    }
    private record Stay(String token,String place) {}
    private String text(String s){return s==null?"":s.trim();}
    private String token(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private ApiException rejected(){return new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_FIELDS_REJECTED","模型返回了不允许的调宿字段，未更新预览");}
}
