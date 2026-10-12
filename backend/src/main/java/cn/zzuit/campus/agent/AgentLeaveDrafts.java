package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.leave.LeaveModels;
import cn.zzuit.campus.leave.LeaveService;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Stateless collection only; confirmed saving uses the ordinary leave API. */
@Service
public class AgentLeaveDrafts {
    private static final Map<String,String> LABELS=Map.of("reasonType","请假类型","reason","请假事由","contact","联系手机或邮箱",
        "startDate","开始日期","startTime","开始时间","endDate","结束日期","endTime","结束时间");
    private static final List<String> ORDER=List.of("reasonType","startDate","startTime","endDate","endTime","reason","contact");
    private final IdentityService identities;
    private final LeaveService leaves;
    private final AgentGuides guides;
    public AgentLeaveDrafts(IdentityService identities,LeaveService leaves,AgentGuides guides) { this.identities=identities; this.leaves=leaves; this.guides=guides; }
    public void require(CampusPrincipal user) { identities.requirePermission(user,"agent:query"); identities.requirePermission(user,"leave:apply"); }
    public LeaveFields normalize(LeaveFields input) {
        var fields=new LeaveFields(text(input.reasonType()),text(input.reason()),text(input.contact()),text(input.startDate()),text(input.startTime()),text(input.endDate()),text(input.endTime()));
        validateShape(fields,false); return fields;
    }
    private String text(String value) { return value==null?"":value.trim(); }
    public LeaveFields merge(LeaveFields original,Plan plan) {
        if(plan.calls()==null || plan.calls().size()!=1 || !"collect_leave_fields".equals(plan.calls().getFirst().name())) throw rejected();
        JsonNode patch=plan.calls().getFirst().arguments();
        if(patch==null || !patch.isObject()) throw rejected();
        var values=new LinkedHashMap<>(values(original));
        for(var entry:patch.properties()) {
            String field=entry.getKey(); JsonNode value=entry.getValue();
            // Contact is only collected in the local form, never copied from model output.
            if(!LABELS.containsKey(field) || field.equals("contact") || !value.isString() || value.asText().isBlank()) throw rejected();
            values.put(field,value.asText().trim());
        }
        var fields=new LeaveFields(values.get("reasonType"),values.get("reason"),values.get("contact"),values.get("startDate"),values.get("startTime"),values.get("endDate"),values.get("endTime"));
        validateShape(fields,true); return fields;
    }
    private Map<String,String> values(LeaveFields f) { return Map.of("reasonType",f.reasonType(),"reason",f.reason(),"contact",f.contact(),"startDate",f.startDate(),"startTime",f.startTime(),"endDate",f.endDate(),"endTime",f.endTime()); }
    private void validateShape(LeaveFields f,boolean fromModel) {
        try {
            if(!f.reasonType().isEmpty() && !List.of("PERSONAL","SICK","OTHER").contains(f.reasonType())) throw new IllegalArgumentException();
            if(f.reason().length()>2000 || f.contact().length()>80) throw new IllegalArgumentException();
            for(String date:List.of(f.startDate(),f.endDate())) if(!date.isEmpty()) {
                if(!date.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException();
                LocalDate.parse(date);
            }
            for(String time:List.of(f.startTime(),f.endTime())) if(!time.isEmpty()) {
                if(!time.matches("\\d{2}:\\d{2}")) throw new IllegalArgumentException();
                LocalTime.parse(time);
            }
        } catch(IllegalArgumentException | DateTimeException ex) {
            if(fromModel) throw rejected();
            throw new ApiException(HttpStatus.BAD_REQUEST,"LEAVE_PREVIEW_INVALID","预览字段无效，请核对日期、时间和字数");
        }
    }
    public LeavePreview preview(CampusPrincipal user,String run,Mode mode,LeaveFields fields) {
        require(user);
        var eligibility=leaves.eligibility(user);
        var missing=ORDER.stream().filter(key->values(fields).get(key).isBlank()).toList();
        var issues=new ArrayList<String>();
        if(eligibility.classGroupId()==null) issues.add("当前账号未分配行政班，无法保存请假草稿，请先核对账号资料。");
        Guide source=null;
        try { source=guides.detail("leave"); }
        catch(ApiException ex) { if(ex.status()!=HttpStatus.NOT_FOUND) throw ex; issues.add("请假指南已撤下，请进入原请假页面核对办理要求。"); }
        LocalDateTime start=dateTime(fields.startDate(),fields.startTime()),end=dateTime(fields.endDate(),fields.endTime());
        if(start!=null && !start.isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai")))) issues.add("开始时间已到，请确认未来的请假时间。");
        if(missing.isEmpty()) issues.addAll(leaves.draftIssues(new LeaveModels.Draft("preview-only",fields.reasonType(),fields.reason(),fields.contact(),start,end,0)));
        else {
            if(start!=null && end!=null) {
                if(!end.isAfter(start)) issues.add("结束时间须晚于开始时间");
                if(Duration.between(start,end).compareTo(Duration.ofDays(eligibility.maxDays()))>0) issues.add("演示流程单次最多申请 "+eligibility.maxDays()+" 天；正式规则须由学校确认");
            }
        }
        String duration=start!=null && end!=null && end.isAfter(start)?duration(Duration.between(start,end)):null;
        boolean ready=missing.isEmpty() && issues.isEmpty();
        String summary=!missing.isEmpty()?"请补充："+String.join("、",missing.stream().map(LABELS::get).toList())+"。日期和时间不会自动补齐，联系方式请在表单填写。"
            :!issues.isEmpty()?"资料需要调整，请按下方提示修改后重新核对。":"资料已补齐，请逐项检查预览。确认后仅保存草稿，提交审核仍在请假页面完成。";
        return new LeavePreview(run,mode,mode==Mode.DEEPSEEK,fields,missing,List.copyOf(issues),ready,summary,duration,source,eligibility.message(),LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
    }
    private LocalDateTime dateTime(String date,String time) { return date.isEmpty() || time.isEmpty()?null:LocalDateTime.of(LocalDate.parse(date),LocalTime.parse(time)); }
    private String duration(Duration d) { long minutes=d.toMinutes(); return (minutes/1440>0?minutes/1440+" 天 ":"")+(minutes%1440/60>0?minutes%1440/60+" 小时 ":"")+(minutes%60>0?minutes%60+" 分钟":""); }
    private ApiException rejected() { return new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_LEAVE_FIELDS_REJECTED","模型未返回有效的请假字段，原预览已保留，请重试或在表单填写"); }
}
