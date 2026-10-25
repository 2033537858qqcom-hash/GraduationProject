package cn.zzuit.campus.schedule;

import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Read-through projection: source workflows remain the only place to change an arrangement. */
@Service
public class PersonalScheduleService {
    public record Event(String key,String source,long sourceId,String title,LocalDateTime startsAt,
                        LocalDateTime endsAt,String location,String status,boolean tentative,String href,List<String> overlapKeys) {}
    public record Schedule(LocalDate from,LocalDate to,String timeZone,LocalDateTime generatedAt,List<String> availableSources,
                           List<String> unavailableSources,List<Event> events) {}
    private final JdbcClient jdbc;
    private final IdentityService identities;
    public PersonalScheduleService(JdbcClient jdbc,IdentityService identities) { this.jdbc=jdbc;this.identities=identities; }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Schedule mine(CampusPrincipal user,LocalDate from,LocalDate to) {
        identities.requirePermission(user,"portal:read");
        if(!to.isAfter(from) || ChronoUnit.DAYS.between(from,to)>42 || from.getYear()<2000 || from.getYear()>2099 || to.getYear()>2100)
            throw new ApiException(HttpStatus.BAD_REQUEST,"SCHEDULE_RANGE","请选择 2000—2099 年内、1—42 天的日程区间，结束日期不包含在内");
        var permissions=identities.permissions(user.id());
        var available=new ArrayList<String>();var unavailable=new ArrayList<String>();var rows=new ArrayList<Event>();
        if(permissions.contains("learning:read")) {
            available.addAll(List.of("LESSON","EXAM"));
            rows.addAll(query("""
                SELECT l.id,tc.term_id,c.name title,l.starts_at,l.ends_at,CONCAT(cp.name,' · ',l.classroom) location,'SCHEDULED' state
                FROM teaching_lesson l JOIN teaching_class tc ON tc.id=l.class_id JOIN course c ON c.id=tc.course_id
                JOIN campus cp ON cp.id=l.campus_id
                JOIN teaching_instructor ti ON ti.class_id=l.class_id AND ti.teacher_id=l.teacher_id AND ti.active=TRUE
                WHERE EXISTS(SELECT 1 FROM teaching_member m WHERE m.class_id=l.class_id AND m.student_id=:u AND m.active=TRUE)
                AND l.starts_at<:end AND l.ends_at>:start ORDER BY l.starts_at,l.id LIMIT 501
                """,user.id(),from,to,"LESSON"));
            rows.addAll(query("""
                SELECT e.id,tc.term_id,c.name title,e.starts_at,e.ends_at,CONCAT(cp.name,' · ',e.classroom) location,e.status state
                FROM academic_exam e JOIN teaching_class tc ON tc.id=e.class_id JOIN course c ON c.id=tc.course_id JOIN campus cp ON cp.id=e.campus_id
                WHERE e.status='PUBLISHED' AND EXISTS(SELECT 1 FROM teaching_member m WHERE m.class_id=e.class_id AND m.student_id=:u AND m.active=TRUE)
                AND e.starts_at<:end AND e.ends_at>:start ORDER BY e.starts_at,e.id LIMIT 501
                """,user.id(),from,to,"EXAM"));
        } else unavailable.addAll(List.of("LESSON","EXAM"));
        if(permissions.contains("leave:apply")) {
            available.add("LEAVE");
            rows.addAll(query("""
                SELECT r.id,0 term_id,'请假安排' title,c.starts_at,c.ends_at,'离校期间' location,r.status state
                FROM leave_calendar c JOIN leave_request r ON r.id=c.request_id AND r.student_id=c.student_id
                WHERE c.student_id=:u AND r.status IN ('APPROVED','RETURN_PENDING','COMPLETED')
                AND c.starts_at<:end AND c.ends_at>:start AND c.ends_at>c.starts_at ORDER BY c.starts_at,r.id LIMIT 501
                """,user.id(),from,to,"LEAVE"));
        } else unavailable.add("LEAVE");
        if(permissions.contains("reservation:apply")) {
            available.add("RESERVATION");
            rows.addAll(query("""
                SELECT r.id,0 term_id,r.activity_theme title,r.starts_at,r.ends_at,CONCAT(r.venue_name,' · ',r.location_detail) location,r.status state
                FROM venue_reservation r WHERE r.applicant_id=:u AND r.status IN ('SUBMITTED','APPROVED','IN_USE','COMPLETED')
                AND r.starts_at<:end AND r.ends_at>:start ORDER BY r.starts_at,r.id LIMIT 501
                """,user.id(),from,to,"RESERVATION"));
        } else unavailable.add("RESERVATION");
        if(rows.size()>500) throw new ApiException(HttpStatus.CONFLICT,"SCHEDULE_TOO_DENSE","此区间日程超过 500 条，请缩短查询日期后重新查看");
        rows.sort(Comparator.comparing(Event::startsAt).thenComparing(Event::endsAt).thenComparing(Event::key));
        var overlaps=new ArrayList<List<String>>();for(int n=0;n<rows.size();n++)overlaps.add(new ArrayList<>());
        for(int a=0;a<rows.size();a++) for(int b=a+1;b<rows.size() && rows.get(b).startsAt().isBefore(rows.get(a).endsAt());b++) {
            var first=rows.get(a);var second=rows.get(b);
            if(first.startsAt().isBefore(second.endsAt())) { overlaps.get(a).add(second.key());overlaps.get(b).add(first.key()); }
        }
        var events=new ArrayList<Event>();
        for(int n=0;n<rows.size();n++) {var r=rows.get(n);events.add(new Event(r.key(),r.source(),r.sourceId(),r.title(),r.startsAt(),r.endsAt(),r.location(),r.status(),r.tentative(),r.href(),List.copyOf(overlaps.get(n))));}
        return new Schedule(from,to,"Asia/Shanghai",LocalDateTime.now(ZoneId.of("Asia/Shanghai")),available,unavailable,events);
    }
    private List<Event> query(String sql,long user,LocalDate from,LocalDate to,String source) {
        return jdbc.sql(sql).param("u",user).param("start",from.atStartOfDay()).param("end",to.atStartOfDay()).query((r,n)-> {
            long id=r.getLong("id");var start=r.getTimestamp("starts_at").toLocalDateTime();String state=r.getString("state");
            String href=switch(source) {
                case "LESSON"->"/portal/learning/timetable?term="+r.getLong("term_id")+"&week="+start.toLocalDate().with(java.time.DayOfWeek.MONDAY);
                case "EXAM"->"/portal/learning/exams?term="+r.getLong("term_id");
                case "LEAVE"->"/portal/leave?id="+id+"&tab=details";
                default->"/portal/reservations?id="+id+"&tab=details";
            };
            return new Event(source+":"+id,source,id,r.getString("title"),start,r.getTimestamp("ends_at").toLocalDateTime(),r.getString("location"),state,source.equals("RESERVATION")&&state.equals("SUBMITTED"),href,List.of());
        }).list();
    }
}
