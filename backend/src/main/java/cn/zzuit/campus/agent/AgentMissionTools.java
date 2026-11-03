package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentMissionModels.*;
import cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.reservation.ReservationDtos.*;
import cn.zzuit.campus.reservation.VenueReservationService;
import cn.zzuit.campus.resource.PhysicalRoomService;
import cn.zzuit.campus.schedule.PersonalScheduleService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Scoped tools: real services execute actions; model arguments cannot select identity or resource. */
@Service
public class AgentMissionTools {
    private final AgentPlanningQueries planning;
    private final PersonalScheduleService schedules;
    private final VenueReservationService reservations;
    private final PhysicalRoomService rooms;
    private final JdbcClient jdbc;
    private final AgentRepairMissionTools repair;
    private final AgentLeaveMissionTools leave;
    private final AgentCertificateMissionTools certificate;
    private final JsonMapper json=JsonMapper.builder().build();
    private final JsonMapper canonicalJson=JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    public AgentMissionTools(AgentPlanningQueries planning,PersonalScheduleService schedules,VenueReservationService reservations,PhysicalRoomService rooms,JdbcClient jdbc,AgentRepairMissionTools repair,AgentLeaveMissionTools leave,AgentCertificateMissionTools certificate) {
        this.planning=planning;this.schedules=schedules;this.reservations=reservations;this.rooms=rooms;this.jdbc=jdbc;this.repair=repair;this.leave=leave;this.certificate=certificate;
    }
    public boolean certificate(State s){return "CERTIFICATE".equals(s.kind);}
    public CertificateFields normalizeCertificate(CertificateFields fields){return certificate.normalize(fields);}
    public boolean leave(State s){return "LEAVE".equals(s.kind);}
    public LeaveFields normalizeLeave(LeaveFields f){return leave.normalize(f);}
    public boolean repair(State s){return "REPAIR".equals(s.kind);}
    public RepairFields normalizeRepair(RepairFields f){return repair.normalize(f);}
    public List<cn.zzuit.campus.repair.RepairSettingsModels.PublicLocation> repairLocations(CampusPrincipal user,State s){return repair(s)?repair.locations(user):List.of();}
    public record Outcome(String stage,Observation observation) {}
    static LocalDateTime now(){return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));}
    private String text(String value){return value==null||value.isBlank()?null:value.trim();}
    public Fields normalize(Fields f){return f==null?new Fields(null,null,null,null,null,null,null,null):new Fields(text(f.campus()),text(f.startsAt()),text(f.endsAt()),f.participants(),text(f.keyword()),text(f.activityTheme()),text(f.activityType()),text(f.contactPhone()));}
    public Fields initial(String goal,Fields supplied) {
        var calls=planning.local(goal+" 查找空闲场地",null);
        var args=calls.stream().filter(c->c.name().equals("find_available_venues")).findFirst().orElseThrow().arguments();
        var f=normalize(supplied);
        return new Fields(f.campus()!=null?f.campus():args.path("campus").asString(null),f.startsAt()!=null?f.startsAt():args.path("startsAt").asString(null),f.endsAt()!=null?f.endsAt():args.path("endsAt").asString(null),f.participants()!=null?f.participants():args.has("participants")?args.path("participants").asInt():null,f.keyword()!=null?f.keyword():args.path("keyword").asString(null),f.activityTheme(),f.activityType(),f.contactPhone());
    }
    public boolean sameSearch(Fields a,Fields b){return Objects.equals(a.campus(),b.campus())&&Objects.equals(a.startsAt(),b.startsAt())&&Objects.equals(a.endsAt(),b.endsAt())&&Objects.equals(a.participants(),b.participants())&&Objects.equals(a.keyword(),b.keyword());}
    private LocalDateTime date(String value){try{return value==null?null:LocalDateTime.parse(value);}catch(Exception invalid){return null;}}
    public List<String> missing(Fields f) {
        var missing=new ArrayList<String>();
        if(f.campus()==null)missing.add("campus");if(f.participants()==null)missing.add("participants");
        var from=date(f.startsAt());var to=date(f.endsAt());if(from==null)missing.add("startsAt");if(to==null)missing.add("endsAt");
        if(from!=null&&to!=null) {
            long minutes=Duration.between(from,to).toMinutes();
            if(from.getYear()<2000||from.getYear()>2099||to.getYear()>2100||from.isBefore(now())||from.getSecond()!=0||to.getSecond()!=0||from.getNano()!=0||to.getNano()!=0||minutes<30||minutes>840)missing.add("timeRange");
        }
        return missing;
    }
    private boolean scheduleBlocked(State state){return !state.scheduleStatus.equals("OK")||state.conflictCount>0;}
    public List<String> allowed(State state) {
        if(certificate(state))return certificate.allowed(state);
        if(leave(state))return leave.allowed(state);
        if(repair(state))return repair.allowed(state);
        if(!missing(state.fields).isEmpty())return List.of("request_input");
        if(state.draftId!=null)return List.of(state.verified?"finish":"verify_reservation");
        if(state.scheduleChecked&&scheduleBlocked(state)||state.venuesChecked&&!state.venueStatus.equals("OK"))return List.of("request_input");
        if(!state.scheduleChecked||!state.venuesChecked){var result=new ArrayList<String>();if(!state.scheduleChecked)result.add("check_schedule");if(!state.venuesChecked)result.add("find_venues");return result;}
        if(state.selectedKey==null)return List.of("present_options");
        if(!state.proposalReady)return List.of("prepare_reservation");
        return state.confirmed?List.of("save_reservation"):List.of();
    }
    private Observation observation(String action,String status,long count,State s){return new Observation(action,status,count,List.copyOf(s.missing));}
    private Outcome outcome(String action,String status,long count,String stage,State s){return new Outcome(stage,observation(action,status,count,s));}
    private void checkSchedule(CampusPrincipal user,State state) {
        var from=date(state.fields.startsAt());var to=date(state.fields.endsAt());
        var schedule=schedules.mine(user,from.toLocalDate(),to.toLocalDate().plusDays(1));
        var overlaps=schedule.events().stream().filter(e->e.startsAt().isBefore(to)&&e.endsAt().isAfter(from)).toList();
        state.conflictCount=overlaps.size();state.scheduleChecked=true;state.scheduleStatus=schedule.unavailableSources().isEmpty()?"OK":"PARTIAL";
        state.conflicts=overlaps.stream().limit(8).map(e->new Fact(e.key(),e.title(),e.startsAt()+"—"+e.endsAt()+" · "+(e.tentative()?"待审安排":"已有安排"),e.href())).toList();
    }
    private Option selected(State state){return state.options.stream().filter(o->o.key().equals(state.selectedKey)).findFirst().orElseThrow(()->changed("候选已变化，请重新选择场地"));}
    private void recheck(CampusPrincipal user,State state) {
        if(!missing(state.fields).isEmpty())throw changed("时间或查询条件已失效，请重新核对");
        var option=selected(state);var venue=reservations.getVenue(option.venueId());
        if(!venue.enabled()||!venue.category().equals("VENUE")||venue.version()!=option.venueVersion()||venue.capacity()==null||venue.capacity()<state.fields.participants())throw changed("场地资料或容量已变化，请重新选择");
        String campus=jdbc.sql("SELECT code FROM campus WHERE id=:id").param("id",venue.campusId()).query(String.class).single();
        if(!state.fields.campus().equals("ALL")&&!state.fields.campus().equals(campus))throw changed("场地校区已变化，请重新查询");
        if(state.fields.keyword()!=null&&!(venue.name()+" "+venue.buildingName()+" "+venue.locationDetail()).toLowerCase(Locale.ROOT).contains(state.fields.keyword().toLowerCase(Locale.ROOT)))throw changed("场地名称已变化，请重新查询");
        if(reservations.getVenueReviewerIds(venue.id()).stream().noneMatch(id->id!=user.id()))throw changed("该场地没有有效的其他审批人员，请重新选择");
        rooms.requireUsable(venue.id(),state.fields.participants());checkSchedule(user,state);
        if(scheduleBlocked(state))throw changed("本人日程存在冲突或部分来源不可核对，请修改时段后重新规划");
        if(reservations.checkConflict(new CheckConflictCommand(venue.id(),date(state.fields.startsAt()),date(state.fields.endsAt()),null)).hasConflict())throw changed("场地已有新的占用，请重新选择时段或场地");
    }
    public String fingerprint(State state,CampusPrincipal user){return certificate(state)?hash(certificate.fingerprint(state,user)):leave(state)?hash(leave.fingerprint(state,user)):repair(state)?hash(repair.fingerprint(state,user)):hash(Map.of("fields",state.fields,"option",selected(state),"user",user.id(),"securityVersion",user.securityVersion()));}
    public String hash(Object input) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalJson.writeValueAsString(input).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new IllegalStateException("任务请求摘要生成失败",failure);}
    }
    public void invalidate(State state,String message) {
        if(certificate(state)){certificate.invalidate(state,message);return;}
        if(leave(state)){leave.invalidate(state,message);return;}
        if(repair(state)){repair.invalidate(state,message);return;}
        state.options=List.of();state.selectedKey=null;state.proposalHash=null;state.proposalReady=false;state.confirmed=false;
        state.scheduleChecked=false;state.venuesChecked=false;state.conflicts=List.of();state.conflictCount=0;state.message=message;state.missing=List.of("startsAt","endsAt","keyword");
    }
    public Outcome execute(CampusPrincipal user,String action,State state,String businessKey) {
        if(certificate(state))return certificate.execute(user,action,state,businessKey,this::hash);
        if(leave(state))return leave.execute(user,action,state,businessKey,this::hash);
        if(repair(state))return repair.execute(user,action,state,businessKey,this::hash);
        switch(action) {
            case "request_input" -> {
                state.missing=missing(state.fields);
                if(!state.missing.isEmpty())state.message="请补齐校区、人数和明确的未来起止时间；不会默认下午的具体时段。";
                else if(state.scheduleChecked&&scheduleBlocked(state)){state.missing=List.of("startsAt","endsAt");state.message="本人日程存在冲突或部分来源不可核对，请修改时段后重新规划。";}
                else if(state.venuesChecked&&!state.venueStatus.equals("OK")){state.missing=List.of("campus","startsAt","endsAt","keyword");state.message="当前条件没有可用候选，请调整校区、时段或场地关键词后重新规划。";}
                else state.message="请补齐活动主题和类型，再核对预约草稿。";
                return outcome(action,"NEEDS_INPUT",0,"WAITING_INPUT",state);
            }
            case "check_schedule" -> {checkSchedule(user,state);state.message=state.conflictCount==0?"已检查本人日程。":"发现本人日程重叠，请核对。";return outcome(action,state.scheduleStatus,state.conflictCount,"RUNNING",state);}
            case "find_venues" -> {
                var args=new HashMap<String,Object>();args.put("campus",state.fields.campus());args.put("participants",state.fields.participants());args.put("startsAt",state.fields.startsAt());args.put("endsAt",state.fields.endsAt());if(state.fields.keyword()!=null)args.put("keyword",state.fields.keyword());
                var result=planning.execute(user,new Call("find_available_venues",json.valueToTree(args)),null);var options=new ArrayList<Option>();
                if(result.status().equals("OK"))for(var item:result.items()) {
                    long id=Long.parseLong(item.key().substring("VENUE:".length()));var venue=reservations.getVenue(id);
                    options.add(new Option(UUID.randomUUID().toString(),id,venue.name(),venue.campusName()+" · "+venue.locationDetail(),venue.capacity(),venue.version()));
                }
                state.options=List.copyOf(options);state.venuesChecked=true;state.venueStatus=options.isEmpty()?"NO_DATA":result.status();state.message=result.message();
                return outcome(action,state.venueStatus,options.size(),"RUNNING",state);
            }
            case "present_options" -> {state.message="已检查本人日程与场地可用性，请选择候选；查询不会锁定场地。";return outcome(action,"WAITING_SELECTION",state.options.size(),"WAITING_SELECTION",state);}
            case "prepare_reservation" -> {
                recheck(user,state);var required=new ArrayList<String>();if(state.fields.activityTheme()==null)required.add("activityTheme");if(state.fields.activityType()==null)required.add("activityType");state.missing=required;
                if(!required.isEmpty()){state.message="请补齐活动主题和类型，再核对草稿预览。";return outcome(action,"NEEDS_INPUT",0,"WAITING_INPUT",state);}
                state.proposalHash=fingerprint(state,user);state.proposalReady=true;state.confirmed=false;state.message="请核对所选场地、时间、人数及活动信息，确认后仅保存本人预约草稿。";
                return outcome(action,"WAITING_CONFIRMATION",1,"WAITING_CONFIRMATION",state);
            }
            case "save_reservation" -> {
                if(!state.confirmed||!Objects.equals(state.proposalHash,fingerprint(state,user)))throw changed("草稿预览已变化，请重新核对并确认");
                recheck(user,state);var detail=reservations.saveDraft(user,new CreateReservationCommand(businessKey,selected(state).venueId(),state.fields.activityTheme(),state.fields.activityType(),state.fields.participants(),"",date(state.fields.startsAt()),date(state.fields.endsAt()),state.fields.contactPhone()));
                state.draftId=detail.id();state.message="草稿已保存，正在读取原申请核验结果。";return outcome(action,"SAVED",1,"RUNNING",state);
            }
            case "verify_reservation" -> {
                var detail=reservations.getDetail(user,state.draftId);
                if(detail.applicantId()!=user.id()||!detail.requestKey().equals(businessKey)||!detail.status().equals("DRAFT")||detail.venueId()!=selected(state).venueId()||!detail.startsAt().equals(date(state.fields.startsAt()))||!detail.endsAt().equals(date(state.fields.endsAt()))||detail.expectedParticipants()!=state.fields.participants()||!Objects.equals(detail.activityTheme(),state.fields.activityTheme())||!Objects.equals(detail.activityType(),state.fields.activityType())||!Objects.equals(detail.contactPhone(),Objects.toString(state.fields.contactPhone(),"")))throw new ApiException(HttpStatus.CONFLICT,"MISSION_RESULT_CHANGED","原申请已变化，请进入原业务页面核对，任务不会覆盖修改");
                state.verified=true;state.message="已从原申请核验草稿保存结果。";return outcome(action,"VERIFIED",1,"RUNNING",state);
            }
            case "finish" -> {if(!state.verified)throw changed("任务结果尚未核验");state.message="任务完成：本人预约草稿已保存并核验。请进入原页面补充资料、手动提交，等待负责人审核。";return outcome(action,"DONE",1,"DONE",state);}
            default -> throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_ACTION_REJECTED","规划动作不在当前任务允许范围内");
        }
    }
    public Business business(CampusPrincipal user,State state) {
        if(certificate(state))return certificate.business(user,state);
        if(leave(state))return leave.business(user,state);
        if(repair(state))return repair.business(user,state);
        if(state.draftId==null)return null;var detail=reservations.getDetail(user,state.draftId);
        if(detail.applicantId()!=user.id())throw changed("原申请归属已变化");
        return new Business(detail.id(),detail.status(),detail.version(),"/portal/reservations?id="+detail.id()+"&tab=details");
    }
    private ApiException changed(String message){return new ApiException(HttpStatus.CONFLICT,"MISSION_REPLAN_REQUIRED",message);}
}
