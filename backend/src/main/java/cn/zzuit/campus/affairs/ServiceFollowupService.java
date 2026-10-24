package cn.zzuit.campus.affairs;

import cn.zzuit.campus.notice.NoticeWriter;
import cn.zzuit.campus.reservation.VenueReservationService;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceFollowupService {
    private final JdbcClient jdbc;
    private final NoticeWriter notices;
    private final VenueReservationService reservations;
    private final Clock clock;

    public ServiceFollowupService(JdbcClient jdbc,NoticeWriter notices,VenueReservationService reservations,Clock clock) {
        this.jdbc=jdbc; this.notices=notices; this.reservations=reservations; this.clock=clock;
    }

    @Transactional public int processDue() {
        jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update();
        var now=LocalDateTime.now(clock);
        int processed=expireUnused(now);
        long leaveCursor=0;
        while(true) {
        var leaves=jdbc.sql("SELECT id,student_id,class_group_id FROM leave_request WHERE status='APPROVED' AND ends_at<=:now AND id>:cursor ORDER BY id LIMIT 100")
            .param("cursor",leaveCursor).param("now",now).query((r,n)->new long[]{r.getLong(1),r.getLong(2),r.getLong(3)}).list();
        for(var leave:leaves) {
            processed+=remind("LEAVE",leave[0],leave[1],"MINE","leave:apply","请假到期，请核对返校销假","请假已到期。如已返校，请在原申请申报销假；仍未返校请联系辅导员。");
            var counselors=jdbc.sql("""
                SELECT u.id FROM counselor_assignment a JOIN app_user u ON u.id=a.counselor_id AND u.enabled=TRUE
                JOIN class_group cg ON cg.id=a.class_group_id JOIN major m ON m.id=cg.major_id AND m.department_id=u.department_id
                WHERE a.class_group_id=:g
                  AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_code='COUNSELOR')
                  AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_code='TEACHER')
                """).param("g",leave[2]).query(Long.class).list();
            for(long counselor:counselors) if(counselor!=leave[1]) processed+=remind("LEAVE",leave[0],counselor,"REVIEW","leave:review","班级请假已到期，待返校跟进","该学生尚未申报返校，请核对实际行程并跟进销假，不能仅按到期自动认定返校。");
        }
        if(leaves.size()<100) break;
        leaveCursor=leaves.getLast()[0];
        }
        long usageCursor=0;
        while(true) {
        var using=jdbc.sql("SELECT id,applicant_id,venue_id FROM venue_reservation WHERE status='IN_USE' AND ends_at<=:now AND id>:cursor ORDER BY id LIMIT 100")
            .param("cursor",usageCursor).param("now",now).query((r,n)->new long[]{r.getLong(1),r.getLong(2),r.getLong(3)}).list();
        for(var usage:using) {
            processed+=remind("RESERVATION",usage[0],usage[1],"MINE","reservation:apply","借用时段已结束，待核销","请联系场地负责人核实设施与设备交接，使用记录不会自动标记归还。");
            for(long reviewer:reservations.getVenueReviewerIds(usage[2])) if(reviewer!=usage[1]) processed+=remind("RESERVATION",usage[0],reviewer,"REVIEW","reservation:review","场地使用超时，待结束核销","批准时段已结束，请核实现场使用与交接情况，在原申请登记使用结束。");
        }
        if(using.size()<100) break;
        usageCursor=using.getLast()[0];
        }
        return processed;
    }

    private int expireUnused(LocalDateTime now) {
        var due=jdbc.sql("SELECT id,applicant_id,venue_id,version FROM venue_reservation WHERE status IN ('SUBMITTED','APPROVED') AND ends_at<=:now ORDER BY ends_at,id LIMIT 100")
            .param("now",now).query((r,n)->new long[]{r.getLong(1),r.getLong(2),r.getLong(3),r.getInt(4)}).list();
        for(var item:due) {
            jdbc.sql("UPDATE venue_reservation SET status='EXPIRED',processing_comment='时段已结束，未登记使用，预约已过期',version=version+1,updated_at=:now WHERE id=:id")
                .param("now",now).param("id",item[0]).update();
            jdbc.sql("""
                INSERT INTO venue_reservation_action(reservation_id,reservation_version,actor_id,actor_name,action,status,comment,created_at)
                VALUES(:id,:version,NULL,'系统','EXPIRE','EXPIRED','时段已结束且未登记使用，预约自动过期',:now)
                """).param("id",item[0]).param("version",item[3]+1).param("now",now).update();
            notices.write(item[1],"场地预约已过期","RESERVATION",item[0],"MINE","预约时段已结束且未登记使用，原凭证失效；需要使用请重新申请。");
        }
        return due.size();
    }

    private int remind(String kind,long id,long recipient,String view,String permission,String title,String body) {
        var date=LocalDateTime.now(clock).toLocalDate();
        if(jdbc.sql("""
            SELECT COUNT(*) FROM app_user u WHERE u.id=:u AND u.enabled=TRUE
            AND EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code WHERE ur.user_id=u.id AND rp.permission_code=:permission)
            AND NOT EXISTS(SELECT 1 FROM service_followup_reminder r WHERE r.kind=:kind AND r.request_id=:id AND r.recipient_id=:u AND r.reminder_date=:date)
            """).param("u",recipient).param("permission",permission).param("kind",kind).param("id",id).param("date",date).query(Long.class).single()==0) return 0;
        jdbc.sql("INSERT INTO service_followup_reminder(kind,request_id,recipient_id,reminder_date) VALUES(:kind,:id,:u,:date)")
            .param("kind",kind).param("id",id).param("u",recipient).param("date",date).update();
        notices.write(recipient,title,kind,id,view,body);
        return 1;
    }
}
