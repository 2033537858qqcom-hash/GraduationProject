package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentMissionModels.*;
import cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.leave.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Leave-domain execution uses existing eligibility and draft rules, with private fields kept local. */
@Service
public class AgentLeaveMissionTools {
    private final AgentLeaveDrafts drafts;
    private final LeaveService leaves;
    private final tools.jackson.databind.json.JsonMapper json=tools.jackson.databind.json.JsonMapper.builder().build();
    public AgentLeaveMissionTools(AgentLeaveDrafts drafts,LeaveService leaves){this.drafts=drafts;this.leaves=leaves;}
    public LeaveFields normalize(LeaveFields fields){return drafts.normalize(fields==null?new LeaveFields("","","","","","",""):fields);}
    private LeavePreview preview(CampusPrincipal user,State s){return drafts.preview(user,"mission-preview",Mode.LOCAL,s.leaveFields);}
    private List<String> missing(State s){var f=s.leaveFields;var list=new ArrayList<String>();if(f.reasonType().isBlank())list.add("reasonType");if(f.reason().isBlank())list.add("reason");if(f.contact().isBlank())list.add("contact");if(f.startDate().isBlank())list.add("startDate");if(f.startTime().isBlank())list.add("startTime");if(f.endDate().isBlank())list.add("endDate");if(f.endTime().isBlank())list.add("endTime");return list;}
    public List<String> allowed(State s){
        if(s.draftId!=null)return List.of(s.verified?"finish":"verify_leave");
        if(!missing(s).isEmpty())return List.of("request_input");
        if(!s.leaveChecked)return List.of("check_leave_requirements");
        if(s.leavePreview!=null&&!s.leavePreview.ready())return List.of("request_input");
        if(!s.leaveEligibilityChecked)return List.of("inspect_leave_eligibility");
        if(!s.proposalReady)return List.of("prepare_leave");
        return s.confirmed?List.of("save_leave"):List.of();
    }
    private String eligibilityHash(CampusPrincipal user,State s){var e=leaves.eligibility(user);var p=preview(user,s);return json.writeValueAsString(Arrays.asList(e.classGroupId(),e.className(),e.counselorName(),e.canSubmit(),e.maxDays(),p.source()==null?null:p.source().version()));}
    public Object fingerprint(State s,CampusPrincipal user){return Map.of("kind","LEAVE","fields",s.leaveFields,"eligibility",Objects.toString(s.leaveEligibilityHash,""),"user",user.id(),"securityVersion",user.securityVersion());}
    public void invalidate(State s,String message){s.confirmed=false;s.proposalReady=false;s.proposalHash=null;s.leaveChecked=false;s.leaveEligibilityChecked=false;s.leavePreview=null;s.leaveEligibilityHash=null;s.missing=List.of("startDate","startTime","endDate","endTime");s.message=message;}
    private AgentMissionTools.Outcome result(String action,String status,String stage,State s){return new AgentMissionTools.Outcome(stage,new Observation(action,status,0,s.missing));}
    private void ready(CampusPrincipal user,State s){s.leavePreview=preview(user,s);if(!s.leavePreview.ready())throw changed("请假资料或规则已变化，请重新核对："+s.leavePreview.summary());if(!Objects.equals(s.leaveEligibilityHash,eligibilityHash(user,s)))throw changed("班级、办理资格或指南版本已变化，请重新核对并确认");}
    private LocalDateTime time(String day,String time){return LocalDateTime.parse(day+"T"+time);}
    public AgentMissionTools.Outcome execute(CampusPrincipal user,String action,State s,String key,java.util.function.Function<Object,String> hash){
        drafts.require(user);
        switch(action){
            case "request_input" -> {s.leavePreview=preview(user,s);s.missing=s.leavePreview.missing();s.message=s.leavePreview.summary();return result(action,"NEEDS_INPUT","WAITING_INPUT",s);}
            case "check_leave_requirements" -> {s.leavePreview=preview(user,s);s.missing=s.leavePreview.missing();s.leaveChecked=true;s.message=s.leavePreview.ready()?"已核对请假类型、时间、时长、联系方式与有效指南。":s.leavePreview.summary();return result(action,s.leavePreview.ready()?"OK":"NEEDS_INPUT","RUNNING",s);}
            case "inspect_leave_eligibility" -> {s.leaveEligibilityHash=eligibilityHash(user,s);s.leaveEligibilityChecked=true;s.message="已读取本人班级和当前请假办理资格。";return result(action,"OK","RUNNING",s);}
            case "prepare_leave" -> {ready(user,s);s.proposalHash=hash.apply(fingerprint(s,user));s.proposalReady=true;s.confirmed=false;s.message="请逐项核对请假时间、实际事由及联系方式，确认后仅保存本人草稿。";return result(action,"WAITING_CONFIRMATION","WAITING_CONFIRMATION",s);}
            case "save_leave" -> {if(!s.confirmed||!Objects.equals(s.proposalHash,hash.apply(fingerprint(s,user))))throw changed("请假预览已变化，请重新确认");ready(user,s);var f=s.leaveFields;var d=leaves.save(user,null,new LeaveModels.Draft(key,f.reasonType(),f.reason(),f.contact(),time(f.startDate(),f.startTime()),time(f.endDate(),f.endTime()),0));s.draftId=d.id();s.message="请假草稿已保存，正在读取原申请核验。";return result(action,"SAVED","RUNNING",s);}
            case "verify_leave" -> {var d=leaves.detail(user,"mine",s.draftId);var f=s.leaveFields;if(d.studentId()!=user.id()||!d.requestKey().equals(key)||!d.status().equals("DRAFT")||!d.reasonType().equals(f.reasonType())||!d.reason().equals(f.reason())||!d.contact().equals(f.contact())||!d.startsAt().equals(time(f.startDate(),f.startTime()))||!d.endsAt().equals(time(f.endDate(),f.endTime())))throw new ApiException(HttpStatus.CONFLICT,"MISSION_RESULT_CHANGED","原请假申请已变化，请进入原页面核对，Agent不会覆盖修改");s.verified=true;s.message="已读取本人原请假申请核验草稿字段和状态。";return result(action,"VERIFIED","RUNNING",s);}
            case "finish" -> {if(!s.verified)throw changed("原请假申请尚未核验");s.message="任务完成：本人请假草稿已保存并核验。请到原页面补充材料并手动提交，等待辅导员处理。";return result(action,"DONE","DONE",s);}
            default -> throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_ACTION_REJECTED","当前请假任务不允许该动作");
        }
    }
    public Business business(CampusPrincipal user,State s){if(s.draftId==null)return null;var d=leaves.detail(user,"mine",s.draftId);return new Business(d.id(),d.status(),d.version(),"/portal/leave?id="+d.id()+"&tab=details");}
    private ApiException changed(String message){return new ApiException(HttpStatus.CONFLICT,"MISSION_REPLAN_REQUIRED",message);}
}
