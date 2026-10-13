package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.repair.RepairSettingsService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Stateless collection only. Confirmed saving uses the ordinary repair draft API. */
@Service
public class AgentRepairDrafts {
    static final List<String> CATEGORIES = List.of("WATER","ELECTRICITY","NETWORK","FURNITURE","DOOR_WINDOW","EQUIPMENT","OTHER");
    private static final Map<String,String> LABELS = Map.of("category","故障类别","title","故障标题","description","故障现象","locationId","报修地点","contact","联系方式");
    private static final List<String> ORDER = List.of("category","title","description","locationId","contact");
    private static final Set<String> MODEL_FIELDS = Set.of("category","title","description");
    private final IdentityService identities;
    private final RepairSettingsService settings;
    private final AgentGuides guides;
    public AgentRepairDrafts(IdentityService identities,RepairSettingsService settings,AgentGuides guides) {
        this.identities=identities; this.settings=settings; this.guides=guides;
    }
    public void require(CampusPrincipal user) { identities.requirePermission(user,"agent:query"); identities.requirePermission(user,"repair:apply"); }
    public RepairFields normalize(RepairFields input) {
        var fields = new RepairFields(text(input.category()),text(input.title()),text(input.description()),input.locationId(),text(input.locationDetail()),text(input.contact()),text(input.contactWindow()));
        validateShape(fields,false); return fields;
    }
    private String text(String value) { return value==null?"":value.trim(); }
    public RepairFields merge(RepairFields original,Plan plan) {
        if(plan.calls()==null || plan.calls().size()!=1 || plan.calls().getFirst()==null || !"prepare_repair_fields".equals(plan.calls().getFirst().name())) throw rejected();
        JsonNode patch=plan.calls().getFirst().arguments();
        if(patch==null || !patch.isObject()) throw rejected();
        var values=new LinkedHashMap<>(Map.of("category",original.category(),"title",original.title(),"description",original.description()));
        for(var entry:patch.properties()) {
            String field=entry.getKey(); JsonNode value=entry.getValue();
            // Location and contact stay in the local form. The model cannot choose a campus place or copy private contact data.
            if(!MODEL_FIELDS.contains(field) || !value.isString() || value.asText().isBlank()) throw rejected();
            values.put(field,value.asText().trim());
        }
        var fields=new RepairFields(values.get("category"),values.get("title"),values.get("description"),original.locationId(),original.locationDetail(),original.contact(),original.contactWindow());
        validateShape(fields,true); return fields;
    }
    private void validateShape(RepairFields fields,boolean fromModel) {
        try {
            if(!fields.category().isEmpty() && !CATEGORIES.contains(fields.category())) throw new IllegalArgumentException();
            if(fields.title().length()>100 || fields.description().length()>2000 || fields.locationDetail().length()>300 || fields.contact().length()>80 || fields.contactWindow().length()>100) throw new IllegalArgumentException();
            if(fields.locationId()!=null && fields.locationId()<1) throw new IllegalArgumentException();
        } catch(IllegalArgumentException ex) {
            if(fromModel) throw rejected();
            throw new ApiException(HttpStatus.BAD_REQUEST,"REPAIR_PREVIEW_INVALID","预览字段无效，请核对类别、标题、现象和字数");
        }
    }
    public RepairPreview preview(CampusPrincipal user,String run,Mode mode,RepairFields raw) {
        require(user); var fields=normalize(raw); var missing=new ArrayList<String>(); var issues=new ArrayList<String>();
        if(fields.category().isBlank()) missing.add("category");
        if(fields.title().isBlank()) missing.add("title");
        if(fields.description().isBlank()) missing.add("description");
        if(fields.locationId()==null) missing.add("locationId");
        if(fields.contact().isBlank()) missing.add("contact");
        else if(!fields.contact().matches("(?:\\+?[0-9][0-9 -]{6,23}|[^\\s@]+@[^\\s@]+\\.[^\\s@]+)")) issues.add("联系方式须为手机号码或邮箱，并只在本地表单填写。");
        String locationName=null;
        if(fields.locationId()!=null) {
            try { var location=settings.applicationLocation(user,fields.locationId()); locationName=location.campusName()+" "+location.name(); }
            catch(ApiException ex) {
                if(ex.status()==HttpStatus.UNAUTHORIZED) throw ex;
                issues.add("请从当前开放地点重新选择。助手不能猜测或保留已停用地点。");
            }
        }
        Guide source=null;
        try { source=guides.detail("repair"); }
        catch(ApiException ex) { if(ex.status()!=HttpStatus.NOT_FOUND) throw ex; issues.add("报修指南已撤下，请进入原报修页面核对办理要求。"); }
        boolean ready=missing.isEmpty() && issues.isEmpty();
        String summary=!missing.isEmpty()?"请补充："+String.join("、",ORDER.stream().filter(missing::contains).map(LABELS::get).toList())+"。地点须从当前目录选择，联系方式请在表单填写。"
            :!issues.isEmpty()?"资料需要调整，请按下方提示修改后重新核对。":"资料已补齐，请逐项检查预览。确认后仅保存草稿，提交、派单、维修和验收仍在报修页面完成。";
        return new RepairPreview(run,mode,mode==Mode.DEEPSEEK,fields,List.copyOf(missing),List.copyOf(issues),ready,summary,source,eligibility(),locationName,LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
    }
    private String eligibility() { return "演示报修规则。地点从当前开放目录选择，助手不猜测校园位置，也不代替提交或处理。"; }
    private ApiException rejected() { return new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_REPAIR_FIELDS_REJECTED","模型未返回有效的报修字段，原预览已保留，请重试或在表单填写"); }
}
