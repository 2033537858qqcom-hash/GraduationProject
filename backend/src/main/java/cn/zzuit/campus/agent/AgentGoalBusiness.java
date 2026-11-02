package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentGoalModels.*;
import cn.zzuit.campus.certificate.*;
import cn.zzuit.campus.leave.*;
import cn.zzuit.campus.repair.*;
import cn.zzuit.campus.reservation.*;
import cn.zzuit.campus.schedule.PersonalScheduleService;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Human-authorized submission of an exact current business snapshot through its ordinary service. */
@Service
public class AgentGoalBusiness {
 private final AgentMissionService missions;
 private final CertificateService certificates;
 private final LeaveService leaves;
 private final RepairService repairs;
 private final VenueReservationService reservations;
 private final PersonalScheduleService schedules;
 private final AgentGuides guides;
 private final AgentMissionTools hashes;
 private record Snapshot(Object original,String status,int version,List<SubmissionField> fields,List<String> issues,Object eligibility,int guideVersion){}
 public AgentGoalBusiness(AgentMissionService missions,CertificateService certificates,LeaveService leaves,RepairService repairs,VenueReservationService reservations,PersonalScheduleService schedules,AgentGuides guides,AgentMissionTools hashes){this.missions=missions;this.certificates=certificates;this.leaves=leaves;this.repairs=repairs;this.reservations=reservations;this.schedules=schedules;this.guides=guides;this.hashes=hashes;}
 private void field(List<SubmissionField> fields,String label,Object value){fields.add(new SubmissionField(label,Objects.toString(value,"未填写")));}
 private Snapshot snapshot(CampusPrincipal user,Task task,long id){
  var fields=new ArrayList<SubmissionField>();var issues=new ArrayList<String>();Object original,eligibility=List.of();String status;int version,guideVersion=-1;
  switch(task.kind){
   case "CERTIFICATE"->{
    var request=certificates.detail(user,"mine",id);original=request;status=request.status();version=request.version();var identity=request.identity();
    field(fields,"姓名",identity.studentName());field(fields,"学号",identity.identityNumber());field(fields,"学院",identity.departmentName());field(fields,"专业",identity.majorName());field(fields,"行政班",identity.className());field(fields,"校区",identity.campusName());field(fields,"实际用途",request.purpose());field(fields,"领取方式","线上下载演示PDF");
    try{var current=certificates.eligibility(user);eligibility=current;if(!current.canSubmit())issues.add(current.message());if(!current.identity().equals(identity))issues.add("当前登记资料与原申请快照不一致，请在原页面重新核对保存");}
    catch(ApiException failure){if(!failure.code().equals("IDENTITY_INCOMPLETE"))throw failure;issues.add(failure.getMessage());}
   }
   case "LEAVE"->{
    var request=leaves.detail(user,"mine",id);original=request;status=request.status();version=request.version();var current=leaves.eligibility(user);eligibility=current;
    field(fields,"姓名",request.studentName());field(fields,"学号",request.identityNumber());field(fields,"行政班",request.className());field(fields,"请假类型",Map.of("PERSONAL","事假","SICK","病假","OTHER","其他").getOrDefault(request.reasonType(),request.reasonType()));
    field(fields,"开始时间",request.startsAt());field(fields,"结束时间",request.endsAt());field(fields,"实际事由",request.reason());field(fields,"联系方式",request.contact());field(fields,"材料",request.fileName()==null?"未附材料":request.fileName());
    if(!current.canSubmit())issues.add(current.message());if(!Objects.equals(current.classGroupId(),request.classGroupId()))issues.add("行政班已变化，请在原页面重新保存草稿");
    issues.addAll(leaves.draftIssues(new LeaveModels.Draft(request.requestKey(),request.reasonType(),request.reason(),request.contact(),request.startsAt(),request.endsAt(),request.version())));
   }
   case "REPAIR"->{
    var request=repairs.detail(user,"mine",id);original=request;status=request.status();version=request.version();eligibility=List.of(request.locationEligible(),request.allowedActions());
    field(fields,"报修人",request.reporterName());field(fields,"校区",request.campusName());field(fields,"报修地点",request.locationName());field(fields,"位置补充",request.locationDetail());field(fields,"故障类别",request.category());field(fields,"故障标题",request.title());field(fields,"故障现象",request.description());field(fields,"联系方式",request.contact());field(fields,"联系时间",request.contactWindow());field(fields,"图片",request.images().stream().map(RepairModels.Image::name).toList());
    if(!request.allowedActions().contains("submit"))issues.add("当前原工单不能提交");if(!request.locationEligible())issues.add(request.locationEligibilityMessage());
    if(request.locationId()==null||request.category().isBlank()||request.title().isBlank()||request.description().isBlank()||request.contact().isBlank())issues.add("请在原页面补齐地点、类别、故障信息与联系方式");
   }
   case "RESERVATION"->{
    var request=reservations.getDetail(user,id);original=request;status=request.status();version=request.version();var venue=reservations.getVenue(request.venueId());
    var reviewers=reservations.getVenueReviewerIds(venue.id()).stream().filter(reviewer->reviewer!=user.id()).toList();
    var schedule=schedules.mine(user,request.startsAt().toLocalDate(),request.endsAt().toLocalDate().plusDays(1));var overlaps=schedule.events().stream().filter(event->!event.key().equals("RESERVATION:"+id)&&event.startsAt().isBefore(request.endsAt())&&event.endsAt().isAfter(request.startsAt())).toList();
    eligibility=Arrays.asList(venue,reviewers,overlaps,schedule.unavailableSources());
    field(fields,"申请人",request.applicantName());field(fields,"学号",request.identityNumber());field(fields,"场地",request.venueName());field(fields,"位置",request.locationDetail());field(fields,"开始时间",request.startsAt());field(fields,"结束时间",request.endsAt());field(fields,"预计人数",request.expectedParticipants());field(fields,"活动主题",request.activityTheme());field(fields,"活动类型",request.activityType());field(fields,"联系方式",request.contactPhone());
    if(!venue.enabled()||venue.capacity()==null||venue.capacity()<request.expectedParticipants())issues.add("场地已停用或容量不足");if(reviewers.isEmpty())issues.add("没有有效的其他审批负责人");
    if(!request.startsAt().isAfter(AgentMissionTools.now()))issues.add("开始时间已到，请在原页面调整时段");
    if(request.contactPhone()==null||request.contactPhone().trim().length()<7)issues.add("请在原页面填写有效活动联系人电话");if(request.activityTheme()==null||request.activityTheme().trim().length()<2)issues.add("请在原页面填写实际活动主题");
    if(!overlaps.isEmpty()||!schedule.unavailableSources().isEmpty())issues.add("本人日程存在冲突或部分来源不可核对");
    if(reservations.checkConflict(new ReservationDtos.CheckConflictCommand(venue.id(),request.startsAt(),request.endsAt(),id)).hasConflict())issues.add("场地已有新的占用，请重新核对方案");
   }
   default->throw changed("当前任务没有对应的办理服务");
  }
  if(!Set.of("DRAFT","RETURNED").contains(status))issues.add("当前原申请不在草稿或待补交状态");
  try{guideVersion=guides.detail(task.kind.toLowerCase(Locale.ROOT)).version();}catch(ApiException failure){if(failure.status()!=HttpStatus.NOT_FOUND)throw failure;issues.add("有效办理指南已撤下，请先核对办理要求");}
  return new Snapshot(original,status,version,List.copyOf(fields),List.copyOf(issues),eligibility,guideVersion);
 }
 public SubmissionPreview preview(CampusPrincipal user,long session,Task task){
  if(task.missionId==null||task.cancelled||task.target.equals("DRAFT"))return null;var mission=missions.detail(user,session,task.missionId);if(mission.business()==null)return null;
  var snapshot=snapshot(user,task,mission.business().id());String hash=hashes.hash(Arrays.asList(task.kind,task.target,user.id(),user.securityVersion(),snapshot.original,snapshot.eligibility,snapshot.guideVersion));
  return new SubmissionPreview(task.kind,mission.business().id(),snapshot.status,snapshot.version,snapshot.fields,hash,snapshot.issues.isEmpty(),snapshot.issues,mission.business().targetPath());
 }
 public void authorize(CampusPrincipal user,long session,Task task,String hash,Boolean confirmed){
  if(!Boolean.TRUE.equals(confirmed))throw new ApiException(HttpStatus.BAD_REQUEST,"GOAL_SUBMIT_CONFIRM_REQUIRED","请本人核对原申请后明确确认提交");
  var preview=preview(user,session,task);if(preview==null||!preview.canSubmit()||!Objects.equals(preview.proposalHash(),hash))throw changed("原申请、办理资格或指南已变化，请刷新并重新核对提交预览");
  task.submissionHash=hash;task.submissionVerified=false;task.submittedVersion=null;
 }
 public void submit(CampusPrincipal user,long session,Task task,String operationKey){
  var preview=preview(user,session,task);if(preview==null||task.submissionHash==null||!preview.canSubmit()||!task.submissionHash.equals(preview.proposalHash()))throw changed("本人提交授权已失效，原申请、资格或指南已变化，请重新确认");
  long id=preview.businessId();var current=snapshot(user,task,id);
  switch(task.kind){
   case "CERTIFICATE"->{var request=(CertificateModels.Request)current.original;certificates.transition(user,id,"submit",new CertificateModels.Action(request.version(),"本人核对Agent原申请预览后授权提交"));}
   case "LEAVE"->{var request=(LeaveModels.Request)current.original;leaves.transition(user,id,"submit",new LeaveModels.Action(request.version(),"本人核对Agent原申请预览后授权提交"));}
   case "REPAIR"->{var request=(RepairModels.Detail)current.original;repairs.transition(user,"mine",id,"submit",new RepairModels.Action(operationKey,request.version(),"本人核对Agent原工单预览后授权提交",null,null,null,null));}
   case "RESERVATION"->{var request=(ReservationDtos.ReservationDetail)current.original;reservations.submit(user,new ReservationDtos.CreateReservationCommand(request.requestKey(),request.venueId(),request.activityTheme(),request.activityType(),request.expectedParticipants(),request.borrowEquipment(),request.startsAt(),request.endsAt(),request.contactPhone()));}
   default->throw changed("当前任务没有对应的提交服务");
  }
  var result=missions.detail(user,session,task.missionId).business();if(result==null||!result.status().equals("SUBMITTED"))throw changed("提交结果未核验，事务未确认成功");
  task.submittedVersion=result.version();task.submissionHash=null;task.lastBusinessStatus=result.status();
 }
 public String verify(CampusPrincipal user,long session,Task task){
  if(task.missionId==null)throw changed("当前任务尚未关联原申请");var business=missions.detail(user,session,task.missionId).business();
  var submitted=Set.of("SUBMITTED","RETURNED","APPROVED","IN_USE","COMPLETED","ASSIGNED","IN_PROGRESS","AWAITING_CONFIRMATION","REJECTED");
  if(business==null||!submitted.contains(business.status())||task.submittedVersion!=null&&business.version()<task.submittedVersion)throw changed("原申请尚未达到可核验的已提交状态");
  task.submissionVerified=true;task.lastBusinessStatus=business.status();return business.status();
 }
 public boolean terminal(String status){return Set.of("COMPLETED","REJECTED","WITHDRAWN","CANCELLED","EXPIRED").contains(status);}
 public String statusLabel(String status){return Map.ofEntries(Map.entry("DRAFT","草稿"),Map.entry("SUBMITTED","待审核"),Map.entry("RETURNED","待补充"),Map.entry("APPROVED","已批准"),Map.entry("IN_USE","使用中"),Map.entry("COMPLETED","已完成"),Map.entry("REJECTED","已拒绝"),Map.entry("WITHDRAWN","已撤回"),Map.entry("CANCELLED","已取消"),Map.entry("EXPIRED","已过期"),Map.entry("ASSIGNED","已派单"),Map.entry("IN_PROGRESS","维修中"),Map.entry("AWAITING_CONFIRMATION","待本人验收")).getOrDefault(Objects.toString(status,""),"待核对");}
 private ApiException changed(String message){return new ApiException(HttpStatus.CONFLICT,"GOAL_SUBMISSION_CHANGED",message);}
}
