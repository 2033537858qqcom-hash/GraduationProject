package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentMissionModels.*;
import cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.repair.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Repair-domain adapter for the same durable task executor; observations contain no repair text. */
@Service
public class AgentRepairMissionTools {
    private final AgentRepairDrafts drafts;
    private final RepairSettingsService settings;
    private final RepairService repairs;
    private final tools.jackson.databind.json.JsonMapper json=tools.jackson.databind.json.JsonMapper.builder().build();
    public AgentRepairMissionTools(AgentRepairDrafts drafts,RepairSettingsService settings,RepairService repairs){this.drafts=drafts;this.settings=settings;this.repairs=repairs;}
    public RepairFields normalize(RepairFields fields){return drafts.normalize(fields==null?new RepairFields("","","",null,"","",""):fields);}
    public List<RepairSettingsModels.PublicLocation> locations(CampusPrincipal user){return settings.publicLocations(user,null,1,50).items();}
    public RepairPreview preview(CampusPrincipal user,State s){return drafts.preview(user,"mission-preview",Mode.LOCAL,s.repairFields);}
    private List<String> missing(State s){var f=s.repairFields;var list=new ArrayList<String>();if(f.category().isBlank())list.add("category");if(f.title().isBlank())list.add("title");if(f.description().isBlank())list.add("description");if(f.locationId()==null)list.add("locationId");if(f.contact().isBlank())list.add("contact");return list;}
    public List<String> allowed(State s){
        if(s.draftId!=null)return List.of(s.verified?"finish":"verify_repair");
        if(!missing(s).isEmpty())return List.of("request_input");
        if(!s.repairChecked)return List.of("check_repair_requirements");
        if(s.repairPreview!=null&&!s.repairPreview.ready())return List.of("request_input");
        if(!s.repairLocationChecked)return List.of("inspect_repair_location");
        if(!s.proposalReady)return List.of("prepare_repair");
        return s.confirmed?List.of("save_repair"):List.of();
    }
    private String locationHash(CampusPrincipal user,State s){var l=settings.applicationLocation(user,s.repairFields.locationId());return json.writeValueAsString(Map.of("id",l.id(),"version",l.version(),"name",l.name(),"campus",l.campusId(),"kind",l.kind()));}
    public Object fingerprint(State s,CampusPrincipal user){return Map.of("kind","REPAIR","fields",s.repairFields,"location",Objects.toString(s.repairLocationHash,""),"user",user.id(),"securityVersion",user.securityVersion());}
    public void invalidate(State s,String message){s.confirmed=false;s.proposalReady=false;s.proposalHash=null;s.repairChecked=false;s.repairLocationChecked=false;s.repairPreview=null;s.repairLocationHash=null;s.missing=List.of("locationId","contact");s.message=message;}
    private AgentMissionTools.Outcome outcome(String action,String status,String stage,State s){return new AgentMissionTools.Outcome(stage,new Observation(action,status,0,s.missing));}
    private void ready(CampusPrincipal user,State s){var preview=preview(user,s);s.repairPreview=preview;if(!preview.ready())throw changed("报修资料或地点已变化，请重新核对："+preview.summary());if(!Objects.equals(s.repairLocationHash,locationHash(user,s)))throw changed("报修地点资料已变化，请重新选择并核对");}
    public AgentMissionTools.Outcome execute(CampusPrincipal user,String action,State s,String businessKey,java.util.function.Function<Object,String> hash){
        drafts.require(user);
        switch(action){
            case "request_input" -> {s.repairPreview=preview(user,s);s.missing=s.repairPreview.missing();s.message=s.repairPreview.summary();return outcome(action,"NEEDS_INPUT","WAITING_INPUT",s);}
            case "check_repair_requirements" -> {s.repairPreview=preview(user,s);s.missing=s.repairPreview.missing();s.repairChecked=true;s.message=s.repairPreview.ready()?"已核对报修类别、现象、联系方式及有效办理指南。":s.repairPreview.summary();return outcome(action,s.repairPreview.ready()?"OK":"NEEDS_INPUT","RUNNING",s);}
            case "inspect_repair_location" -> {s.repairLocationHash=locationHash(user,s);s.repairLocationChecked=true;s.message="已核对当前开放地点和本人可报修范围。";return outcome(action,"OK","RUNNING",s);}
            case "prepare_repair" -> {ready(user,s);s.proposalHash=hash.apply(fingerprint(s,user));s.proposalReady=true;s.confirmed=false;s.message="请核对报修地点、故障现象和联系方式，确认后仅保存本人草稿。";return outcome(action,"WAITING_CONFIRMATION","WAITING_CONFIRMATION",s);}
            case "save_repair" -> {
                if(!s.confirmed||!Objects.equals(s.proposalHash,hash.apply(fingerprint(s,user))))throw changed("报修预览已变化，请重新确认");ready(user,s);var f=s.repairFields;
                var detail=repairs.save(user,null,new RepairModels.Draft(businessKey,0,f.locationId(),f.category(),f.title(),f.description(),f.locationDetail(),f.contact(),f.contactWindow()));s.draftId=detail.id();s.message="报修草稿已保存，正在读取原工单核验。";return outcome(action,"SAVED","RUNNING",s);
            }
            case "verify_repair" -> {
                var d=repairs.detail(user,"mine",s.draftId);var f=s.repairFields;
                if(!d.status().equals("DRAFT")||!Objects.equals(d.locationId(),f.locationId())||!d.category().equals(f.category())||!d.title().equals(f.title())||!d.description().equals(f.description())||!d.contact().equals(f.contact())||!d.locationDetail().equals(f.locationDetail())||!d.contactWindow().equals(f.contactWindow()))throw new ApiException(HttpStatus.CONFLICT,"MISSION_RESULT_CHANGED","原报修草稿已变化，请在原工单核对，Agent不会覆盖修改");
                s.verified=true;s.message="已读取本人原工单核验报修草稿。";return outcome(action,"VERIFIED","RUNNING",s);
            }
            case "finish" -> {if(!s.verified)throw changed("原工单尚未核验");s.message="任务完成：本人报修草稿已保存并核验。请到原页面补充图片、手动提交，等待后勤分诊。";return outcome(action,"DONE","DONE",s);}
            default -> throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_ACTION_REJECTED","当前报修任务不允许该动作");
        }
    }
    public Business business(CampusPrincipal user,State s){if(s.draftId==null)return null;var d=repairs.detail(user,"mine",s.draftId);return new Business(d.id(),d.status(),d.version(),"/repairs/mine?id="+d.id()+"&tab=details");}
    private ApiException changed(String message){return new ApiException(HttpStatus.CONFLICT,"MISSION_REPLAN_REQUIRED",message);}
}
