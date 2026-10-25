package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.reservation.ReservationDtos.CheckConflictCommand;
import cn.zzuit.campus.reservation.VenueReservationService;
import cn.zzuit.campus.resource.PhysicalRoomService;
import cn.zzuit.campus.schedule.PersonalScheduleService;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Read-only projections. No tool output is sent back to the model. */
@Service
public class AgentPlanningQueries {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final PersonalScheduleService schedules;
    private final VenueReservationService reservations;
    private final PhysicalRoomService physicalRooms;
    private final JsonMapper json=JsonMapper.builder().build();
    private static final Set<String> BOOKING_STATES=Set.of("ALL","DRAFT","SUBMITTED","RETURNED","APPROVED","IN_USE","COMPLETED","EXPIRED","REJECTED","WITHDRAWN","CANCELLED");
    private static final DateTimeFormatter MINUTE=DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter DISPLAY=DateTimeFormatter.ofPattern("MM月dd日 HH:mm");
    public AgentPlanningQueries(JdbcClient jdbc,IdentityService identities,PersonalScheduleService schedules,VenueReservationService reservations,PhysicalRoomService physicalRooms) {
        this.jdbc=jdbc;this.identities=identities;this.schedules=schedules;this.reservations=reservations;this.physicalRooms=physicalRooms;
    }
    public static Set<String> fields(String name) {
        return switch(name) {case "my_schedule"->Set.of("date","range");case "my_reservations"->Set.of("id","status");case "find_available_venues"->Set.of("campus","participants","startsAt","endsAt","keyword");default->Set.of();};
    }
    public static void schema(String name,Map<String,Object> fields) {
        switch(name) {
            case "my_schedule" -> { fields.put("date",Map.of("type","string","description","可选 ISO 日期；缺省今天，单独周一等不明确日期须询问"));fields.put("range",Map.of("type","string","enum",List.of("DAY","WEEK"),"description","DAY 查询一天，WEEK 查询日期所在的周；默认 WEEK")); }
            case "my_reservations" -> { fields.put("id",Map.of("type","integer","minimum",1,"description","只取用户明确给出的本人预约编号；可省略以使用当前预约上下文或列表"));fields.put("status",Map.of("type","string","enum",BOOKING_STATES.stream().sorted().toList())); }
            case "find_available_venues" -> {
                fields.put("campus",Map.of("type","string","enum",List.of("EAST","WEST","ALL"),"description","东、西或明确全部校区；未说明则省略并询问，不猜测"));
                fields.put("participants",Map.of("type","integer","minimum",1,"maximum",10000,"description","用户明确给出的预计人数"));
                fields.put("startsAt",Map.of("type","string","description","明确的北京时间，YYYY-MM-DDTHH:mm；不猜测上午、下午的具体时间"));
                fields.put("endsAt",Map.of("type","string","description","明确的北京时间结束时刻；跨日须明确结束日期"));
                fields.put("keyword",Map.of("type","string","maxLength",40,"description","可选，用户明确提供的场地名称或类型关键词"));
            }
            default -> { }
        }
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ToolResult execute(CampusPrincipal user,Call call,Context context) {
        return switch(call.name()) {
            case "my_schedule"->schedule(user,call.arguments());
            case "my_reservations"->bookings(user,call.arguments(),context);
            case "find_available_venues"->available(user,call.arguments());
            default->throw invalid();
        };
    }
    public void validateReservation(CampusPrincipal user,long id) { ownBooking(user,id); }
    private ToolResult schedule(CampusPrincipal user,JsonNode args) {
        identities.requirePermission(user,"portal:read");
        String date=text(args,"date",today().toString(),10),range=choice(args,"range","WEEK",Set.of("DAY","WEEK"));
        LocalDate day;try { day=LocalDate.parse(date); }catch(Exception ex) {return needs("my_schedule","请明确日程日期，例如今天、下周或 2029-04-02。","/portal/schedule");}
        LocalDate monday=day.with(DayOfWeek.MONDAY),from=range.equals("DAY")?day:monday;
        var result=schedules.mine(user,from,from.plusDays(range.equals("DAY")?1:7));
        var items=result.events().stream().limit(12).map(e-> {
            String kind=switch(e.source()) {case "LESSON"->"课程";case "EXAM"->"考试";case "LEAVE"->"请假";default->"预约";};
            String title=kind+" · "+(e.source().equals("LESSON")||e.source().equals("EXAM")?e.title():e.source().equals("LEAVE")?"请假安排":"场地借用");
            String state=e.source().equals("LEAVE")?(e.status().equals("COMPLETED")?"已销假":e.status().equals("RETURN_PENDING")?"待确认销假":"已批准"):e.source().equals("RESERVATION")?state(e.status()):"已安排";
            return new Fact("SCHEDULE:"+e.key(),title,period(e.startsAt(),e.endsAt())+" · "+e.location()+" · "+state+(e.overlapKeys().isEmpty()?"":" · 存在个人时间重叠")+(e.tentative()?"（待审预约尚未确定）":""),e.href());
        }).toList();
        String path="/portal/schedule?week="+monday+(range.equals("DAY")?"&day="+day:"");
        String message=from+" 起"+(range.equals("DAY")?"一天":"一周")+"共 "+result.events().size()+" 项，展示至多 12 项。待审预约尚未确定，个人时间重叠不会改变原审批结果。";
        if(!result.unavailableSources().isEmpty()) message+=" 当前部分来源没有查询权限，尚未汇总，请到日程页核对可见来源。";
        return result("my_schedule",result.unavailableSources().isEmpty()?"OK":"PARTIAL",message,result.events().size(),items,path);
    }
    private record Booking(long id,String venue,String status,LocalDateTime startsAt,LocalDateTime endsAt,int version) {}
    private Booking ownBooking(CampusPrincipal user,long id) {
        identities.requirePermission(user,"reservation:apply");
        return jdbc.sql("SELECT id,venue_name,status,starts_at,ends_at,version FROM venue_reservation WHERE id=:id AND applicant_id=:user")
            .param("id",id).param("user",user.id()).query((r,n)->new Booking(r.getLong(1),r.getString(2),r.getString(3),r.getTimestamp(4).toLocalDateTime(),r.getTimestamp(5).toLocalDateTime(),r.getInt(6)))
            .optional().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"RESERVATION_NOT_FOUND","本人预约不存在或已不可读取"));
    }
    private ToolResult bookings(CampusPrincipal user,JsonNode args,Context context) {
        identities.requirePermission(user,"reservation:apply");
        Long id=number(args,"id");String state=choice(args,"status","ALL",BOOKING_STATES);
        if(id==null && context!=null && context.type().equals("RESERVATION_MINE"))id=context.id();
        if(id!=null) {if(!state.equals("ALL"))throw invalid();var row=ownBooking(user,id);return result("my_reservations","OK","已重新核对本人范围和当前状态，资料与办理请进入原申请。",1,List.of(bookingFact(row)),bookingPath(id));}
        var page=reservations.listMine(user,state.equals("ALL")?null:state,1,8);
        var items=page.items().stream().map(r->bookingFact(new Booking(r.id(),r.venueName(),r.status(),r.startsAt(),r.endsAt(),r.version()))).toList();
        return result("my_reservations","OK","本人共 "+page.total()+" 条，展示最近至多 8 条。活动说明、联系方式及办理记录留在原申请。",page.total(),items,"/portal/reservations?tab=mine"+(state.equals("ALL")?"":"&status="+state));
    }
    private Fact bookingFact(Booking b) { return new Fact("RESERVATION:"+b.id(),"预约 #"+b.id()+" · "+b.venue(),state(b.status())+" · "+period(b.startsAt(),b.endsAt())+" · V"+b.version(),bookingPath(b.id())); }
    private ToolResult available(CampusPrincipal user,JsonNode args) {
        identities.requirePermission(user,"reservation:apply");
        String campus=choice(args,"campus",null,Set.of("EAST","WEST","ALL")),start=text(args,"startsAt",null,19),end=text(args,"endsAt",null,19),keyword=text(args,"keyword",null,40);
        Long people=number(args,"participants");if(people!=null && people>10000)throw invalid();
        if(campus==null || people==null || start==null || end==null)return needs("find_available_venues","请提供校区、预计人数和明确起止时间，例如：明天东校区 20人 09:00-11:00 查找空闲场地。","/portal/reservations");
        LocalDateTime from,to;try {from=LocalDateTime.parse(start);to=LocalDateTime.parse(end);}catch(Exception ex) {return needs("find_available_venues","日期或时间不明确，请使用 YYYY-MM-DD HH:mm，并明确跨日时的结束日期。","/portal/reservations");}
        long minutes=Duration.between(from,to).toMinutes();
        if(from.getYear()<2000 || from.getYear()>2099 || to.getYear()>2100 || from.getSecond()!=0 || to.getSecond()!=0 || from.getNano()!=0 || to.getNano()!=0 || !to.isAfter(from) || from.isBefore(LocalDateTime.now(ZoneId.of("Asia/Shanghai"))) || minutes<30 || minutes>840)
            return needs("find_available_venues","请选择未来的明确时段，精确到分钟，单次 30 分钟至 14 小时，结束时间须晚于开始时间。","/portal/reservations");
        String like=keyword==null?null:"%"+keyword.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
        var ids=jdbc.sql("""
            SELECT v.id FROM venue_resource v JOIN campus c ON c.id=v.campus_id
            WHERE v.enabled=TRUE AND v.category='VENUE' AND v.capacity>=:people AND (:campus='ALL' OR c.code=:campus)
            AND (:keyword IS NULL OR v.name LIKE :keyword ESCAPE '!' OR v.building_name LIKE :keyword ESCAPE '!' OR v.location_detail LIKE :keyword ESCAPE '!')
            ORDER BY v.id LIMIT 201
            """).param("people",people).param("campus",campus).param("keyword",like).query(Long.class).list();
        if(ids.size()>200)return needs("find_available_venues","匹配的场地目录超过 200 项，请补充校区或场地名称关键词后重新查询。","/portal/reservations");
        var items=new ArrayList<Fact>();long total=0;
        for(long id:ids) {
            if(reservations.getVenueReviewerIds(id).stream().noneMatch(owner->owner!=user.id()))continue;
            try {physicalRooms.requireUsable(id,people.intValue());}
            catch(ApiException ex) {if(ex.code().equals("CLASSROOM_UNAVAILABLE"))continue;throw ex;}
            if(reservations.checkConflict(new CheckConflictCommand(id,from,to,null)).hasConflict())continue;
            total++;if(items.size()>=8)continue;
            var venue=reservations.getVenue(id);
            String path="/portal/reservations?resource="+id+"&starts="+from.format(MINUTE)+"&ends="+to.format(MINUTE)+"&people="+people;
            items.add(new Fact("VENUE:"+id,venue.name(),venue.campusName()+" · "+venue.locationDetail()+" · 容量 "+venue.capacity()+" 人 · "+period(from,to)+" · 点击后核对借用条件",path));
        }
        String message="截至 "+LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(DISPLAY)+"，系统中有 "+total+" 项场地符合校区、人数且未发现时段占用，展示至多 8 项。查询不锁定资源，使用守则请在资源页核对，提交时仍会复核占用、容量和有效审批人员。";
        return result("find_available_venues","OK",message,total,items,"/portal/reservations");
    }
    public List<Call> local(String question,Context context) {
        var calls=new ArrayList<Call>();
        boolean schedule=question.matches(".*(日程|时间重叠|时间冲突|(今天|明天|本周|这周|下周).*(有哪些安排|有什么安排|全部安排|安排汇总)).*");
        boolean finding=question.matches(".*(空闲场地|空场地|查找.*场地|找.*场地|查询.*场地|场地.*可用|可预约.*场地|可以预约.*场地).*");
        boolean booking=question.matches(".*(我的预约|我的借用|本人预约|本人借用|预约进度|借用进度|预约编号|借用编号).*" ) || context!=null && context.type().equals("RESERVATION_MINE") && question.matches(".*(当前|这条|这个|进度|状态).*");
        if(schedule) {
            var a=new HashMap<String,Object>();String date=explicitDate(question,true);a.put("date",date==null?"未明确":date);a.put("range",question.contains("今天")||question.contains("明天")||question.matches(".*\\d{4}-\\d{2}-\\d{2}.*")?"DAY":"WEEK");calls.add(call("my_schedule",a));
        }
        if(booking) {
            var a=new HashMap<String,Object>();var matcher=Pattern.compile("(?:预约|借用)(?:编号|\\s*#)\\s*([1-9]\\d{0,14})").matcher(question);if(matcher.find())a.put("id",Long.parseLong(matcher.group(1)));
            if(!a.containsKey("id") && (context==null || !context.type().equals("RESERVATION_MINE")))a.put("status",question.contains("待审批")||question.contains("待审核")?"SUBMITTED":question.contains("使用中")?"IN_USE":question.contains("已批准")?"APPROVED":"ALL");
            calls.add(call("my_reservations",a));
        }
        if(finding) {
            var a=new HashMap<String,Object>();if(question.contains("东校区")&&question.contains("西校区")||question.matches(".*(全部校区|两个校区).*"))a.put("campus","ALL");else if(question.contains("东校区"))a.put("campus","EAST");else if(question.contains("西校区"))a.put("campus","WEST");
            var people=Pattern.compile("(?<!\\d)([1-9]\\d{0,4})\\s*(?:人|位)").matcher(question);if(people.find()){long value=Long.parseLong(people.group(1));if(!people.find())a.put("participants",value);}
            var times=Pattern.compile("(?<!\\d)([0-2]?\\d:[0-5]\\d)(?!\\d)").matcher(question);var found=new ArrayList<String>();while(times.find())found.add(times.group(1));
            var dates=Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(question);var dateValues=new ArrayList<String>();while(dates.find())dateValues.add(dates.group());String date=explicitDate(question,false);
            if(found.size()==2 && date!=null && dateValues.size()<=2){a.put("startsAt",date+"T"+padded(found.get(0)));a.put("endsAt",(dateValues.size()==2?dateValues.get(1):date)+"T"+padded(found.get(1)));}
            var keyword=Pattern.compile("(?:关键词|名称)[:：]?\\s*([^\\s，。,。！？!?]{1,40})").matcher(question);if(keyword.find())a.put("keyword",keyword.group(1));else for(String word:List.of("报告厅","研讨室","篮球","羽毛球","网球","教室"))if(question.contains(word)){a.put("keyword",word);break;}
            calls.add(call("find_available_venues",a));
        }
        return calls.isEmpty()?null:calls;
    }
    private String explicitDate(String text,boolean defaultToday) {
        var date=Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(text);if(date.find())return date.group();
        LocalDate base=today();if(text.contains("明天"))return base.plusDays(1).toString();if(text.contains("后天"))return base.plusDays(2).toString();
        if(text.contains("下周"))base=base.plusWeeks(1);
        var day=Pattern.compile("(?:本周|这周|下周)([一二三四五六日天])").matcher(text);if(day.find())return base.with(DayOfWeek.of("一二三四五六日".indexOf(day.group(1).replace('天','日'))+1)).toString();
        if(text.matches(".*(?:周[一二三四五六日天]|星期[一二三四五六日天]).*") && !text.contains("本周")&&!text.contains("这周")&&!text.contains("下周"))return null;
        return defaultToday || text.contains("今天") ? base.toString() : null;
    }
    private String padded(String time) {return time.length()==4?"0"+time:time;}
    private Call call(String name,Map<String,?> args) {return new Call(name,json.valueToTree(args));}
    private LocalDate today() {return LocalDate.now(ZoneId.of("Asia/Shanghai"));}
    private String bookingPath(long id) {return "/portal/reservations?id="+id+"&tab=details";}
    private String period(LocalDateTime from,LocalDateTime to) {return from.format(DISPLAY)+"—"+to.format(DISPLAY);}
    private ToolResult needs(String name,String message,String path) {return result(name,"NEEDS_INPUT",message,0,List.of(),path);}
    private ToolResult result(String name,String status,String message,long total,List<Fact> facts,String path) {return new ToolResult(name,switch(name){case "my_schedule"->"查看本人综合日程";case "my_reservations"->"查看本人借用进度";default->"查找空闲场地";},status,message,total,facts,List.of(),path);}
    private String state(String state) {return switch(state){case "DRAFT"->"草稿";case "SUBMITTED"->"待审批";case "RETURNED"->"待补充";case "APPROVED"->"已批准，待使用";case "IN_USE"->"使用中";case "COMPLETED"->"已结束核销";case "EXPIRED"->"已过期未使用";case "REJECTED"->"已驳回";case "WITHDRAWN"->"已撤回";case "CANCELLED"->"已取消";default->"未知状态";};}
    private String text(JsonNode args,String name,String fallback,int max) {var x=args.path(name);if(x.isMissingNode()||x.isNull())return fallback;if(!x.isString()||x.asText().isBlank()||x.asText().length()>max)throw invalid();return x.asText().trim();}
    private String choice(JsonNode args,String name,String fallback,Set<String> values) {String value=text(args,name,fallback,40);if(value!=null&&!values.contains(value))throw invalid();return value;}
    private Long number(JsonNode args,String name) {var x=args.path(name);if(x.isMissingNode()||x.isNull())return null;if(!x.isIntegralNumber()||!x.asText().matches("[1-9]\\d{0,14}"))throw invalid();return x.asLong();}
    private ApiException invalid() {return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_TOOL_ARGUMENTS","查询参数无效，请核对日期、人数和校区后重试");}
}
