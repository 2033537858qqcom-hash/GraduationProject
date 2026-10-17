package cn.zzuit.campus.bootstrap;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(70)
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class ReservationDemoInitializer implements ApplicationRunner {

    private final JdbcClient jdbc;

    public ReservationDemoInitializer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        var affairsUser = jdbc.sql("SELECT id FROM app_user WHERE username = 'affairs1001'").query(Long.class).optional();
        var studentOpt = jdbc.sql("SELECT id, full_name, identity_number FROM app_user WHERE username = '20260001'")
            .query((r, n) -> new DemoStudent(r.getLong("id"), r.getString("full_name"), r.getString("identity_number")))
            .optional();
        if (studentOpt.isEmpty()) return;

        if (jdbc.sql("SELECT COUNT(*) FROM venue_reservation WHERE applicant_id = :id").param("id", studentOpt.get().id()).query(Long.class).single() > 0) {
            return;
        }

        var student = studentOpt.get();
        if (affairsUser.isEmpty()) return;

        // 1. 示例1：图书馆学术报告厅借用（已审批通过）
        var libVenue = jdbc.sql("SELECT id, name, place_id, location_detail FROM venue_resource WHERE code = 'VEN-LIB-101'")
            .query((r, n) -> new DemoVenue(r.getLong("id"), r.getString("name"), r.getString("place_id"), r.getString("location_detail")))
            .optional();
        if (libVenue.isPresent()) {
            var v = libVenue.get();
            var start1 = LocalDateTime.now().plusDays(2).withHour(14).withMinute(0).withSecond(0).truncatedTo(ChronoUnit.SECONDS);
            var end1 = start1.plusHours(3);

            jdbc.sql("""
                INSERT INTO venue_reservation (
                    applicant_id, request_key, venue_id, applicant_name, identity_number,
                    contact_phone, department_name, class_name, venue_name, place_id,
                    location_detail, activity_theme, activity_type, expected_participants,
                    borrow_equipment, starts_at, ends_at, status, processing_comment, version
                ) VALUES (
                    :applicantId, 'DEMO-REQ-RES-001', :venueId, :applicantName, :identityNumber,
                    '13800138001', '信息工程学院', '软件工程2022级01班', :venueName, :placeId,
                    :locationDetail, '计算机学科竞赛经验交流与国奖答辩汇报会', 'ACADEMIC', 120,
                    '超清LED大屏,无线扩音麦克风(4支)', :startsAt, :endsAt, 'APPROVED', '审批通过，已协调多媒体中控配合。', 1
                )
                """)
                .param("applicantId", student.id())
                .param("venueId", v.id())
                .param("applicantName", student.fullName())
                .param("identityNumber", student.identityNumber())
                .param("venueName", v.name())
                .param("placeId", v.placeId())
                .param("locationDetail", v.locationDetail())
                .param("startsAt", start1)
                .param("endsAt", end1)
                .update();

            long resId1 = jdbc.sql("SELECT id FROM venue_reservation WHERE request_key = 'DEMO-REQ-RES-001'").query(Long.class).single();
            jdbc.sql("""
                INSERT INTO venue_reservation_action (reservation_id, reservation_version, actor_id, actor_name, action, status, comment)
                VALUES (:id, 0, :actorId, :actorName, 'SUBMIT', 'SUBMITTED', '提交报告厅借用申请')
                """)
                .param("id", resId1).param("actorId", student.id()).param("actorName", student.fullName()).update();
            jdbc.sql("""
                INSERT INTO venue_reservation_action (reservation_id, reservation_version, actor_id, actor_name, action, status, comment)
                VALUES (:id, 1, :actorId, '学生事务人员（演示）', 'APPROVE', 'APPROVED', '审批通过，已协调多媒体中控配合。')
                """)
                .param("id", resId1).param("actorId", affairsUser.get()).update();
        }

        // 2. 示例2：室内体育馆羽毛球场借用（审核中）
        var gymVenue = jdbc.sql("SELECT id, name, place_id, location_detail FROM venue_resource WHERE code = 'VEN-GYM-02'")
            .query((r, n) -> new DemoVenue(r.getLong("id"), r.getString("name"), r.getString("place_id"), r.getString("location_detail")))
            .optional();
        if (gymVenue.isPresent()) {
            var v = gymVenue.get();
            var start2 = LocalDateTime.now().plusDays(4).withHour(16).withMinute(30).withSecond(0).truncatedTo(ChronoUnit.SECONDS);
            var end2 = start2.plusHours(2);

            jdbc.sql("""
                INSERT INTO venue_reservation (
                    applicant_id, request_key, venue_id, applicant_name, identity_number,
                    contact_phone, department_name, class_name, venue_name, place_id,
                    location_detail, activity_theme, activity_type, expected_participants,
                    borrow_equipment, starts_at, ends_at, status, version
                ) VALUES (
                    :applicantId, 'DEMO-REQ-RES-002', :venueId, :applicantName, :identityNumber,
                    '13800138001', '信息工程学院', '软件工程2022级01班', :venueName, :placeId,
                    :locationDetail, '信工院系羽毛球友谊赛与体能训练', 'SPORTS', 16,
                    '羽毛球标准比赛网架', :startsAt, :endsAt, 'SUBMITTED', 0
                )
                """)
                .param("applicantId", student.id())
                .param("venueId", v.id())
                .param("applicantName", student.fullName())
                .param("identityNumber", student.identityNumber())
                .param("venueName", v.name())
                .param("placeId", v.placeId())
                .param("locationDetail", v.locationDetail())
                .param("startsAt", start2)
                .param("endsAt", end2)
                .update();

            long resId2 = jdbc.sql("SELECT id FROM venue_reservation WHERE request_key = 'DEMO-REQ-RES-002'").query(Long.class).single();
            jdbc.sql("""
                INSERT INTO venue_reservation_action (reservation_id, reservation_version, actor_id, actor_name, action, status, comment)
                VALUES (:id, 0, :actorId, :actorName, 'SUBMIT', 'SUBMITTED', '提交羽毛球场地借用申请')
                """)
                .param("id", resId2).param("actorId", student.id()).param("actorName", student.fullName()).update();
        }
    }

    private record DemoStudent(long id, String fullName, String identityNumber) {}
    private record DemoVenue(long id, String name, String placeId, String locationDetail) {}
}
