package cn.zzuit.campus.repair;

import cn.zzuit.campus.notice.NoticeWriter;
import java.sql.Timestamp;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepairReminderService {
    private final JdbcClient jdbc;
    private final NoticeWriter notices;
    private final Clock clock;
    private final int hours;
    public RepairReminderService(JdbcClient jdbc,NoticeWriter notices,Clock clock,@Value("${app.repair.reminders.hours:48}") int hours) {
        if(hours<1 || hours>8760)throw new IllegalArgumentException("Repair reminder hours must be between 1 and 8760");
        this.jdbc=jdbc;this.notices=notices;this.clock=clock;this.hours=hours;
    }
    @Transactional public int processDue() {
        jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update();
        LocalDateTime now=LocalDateTime.now(clock);LocalDate date=now.toLocalDate();
        var due=jdbc.sql("""
            SELECT r.id,r.reporter_id,r.completion_round FROM repair_request r
            JOIN repair_completion c ON c.request_id=r.id AND c.result_round=r.completion_round
            JOIN app_user u ON u.id=r.reporter_id AND u.enabled=TRUE
            WHERE r.status='AWAITING_CONFIRMATION' AND r.current_cancellation_id IS NULL AND c.created_at<=:cutoff
            AND EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code
                WHERE ur.user_id=u.id AND rp.permission_code='repair:apply')
            AND NOT EXISTS(SELECT 1 FROM repair_reminder m WHERE m.request_id=r.id AND m.result_round=r.completion_round
                AND m.recipient_id=r.reporter_id AND m.reminder_date=:date)
            """+" AND "+RepairSettingsService.RESIDENCY_SQL+" ORDER BY c.created_at,r.id LIMIT 100").param("cutoff",Timestamp.valueOf(now.minusHours(hours))).param("date",date)
            .query((r,n)->new Due(r.getLong(1),r.getLong(2),r.getInt(3))).list();
        for(var r:due) {
            jdbc.sql("INSERT INTO repair_reminder(request_id,result_round,recipient_id,reminder_date,sent_at) VALUES(:id,:round,:recipient,:date,:now)")
                .param("id",r.id()).param("round",r.round()).param("recipient",r.recipient()).param("date",date).param("now",Timestamp.valueOf(now)).update();
            notices.write(r.recipient(),"报修结果待你验收","REPAIR",r.id(),"MINE","本次维修结果已反馈，请核对后确认，或说明问题仍未解决。");
        }
        return due.size();
    }
    private record Due(long id,long recipient,int round) {}
}
