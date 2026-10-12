package cn.zzuit.campus.teaching;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Shared occupancy rules. Call inside the transaction holding teaching.lockWrites(). */
@Service
public class TeachingScheduleService {
    // Lessons retain their reservation when an instructor relation is suspended.
    // Only published exams reserve time; withdrawing an exam releases it.
    private static final String OCCUPANCY="""
        (SELECT id,class_id,room_id,starts_at,ends_at,teacher_id,'LESSON' kind FROM teaching_lesson
         UNION ALL
         SELECT id,class_id,room_id,starts_at,ends_at,NULL teacher_id,'EXAM' kind
         FROM academic_exam WHERE status='PUBLISHED')
        """;
    private final JdbcClient jdbc;
    private final TeachingService teaching;
    public TeachingScheduleService(JdbcClient jdbc,TeachingService teaching) { this.jdbc=jdbc; this.teaching=teaching; }

    public void requireAvailable(long classId,long teacherId,long roomId,LocalDateTime start,LocalDateTime end,long ignoredExamId) {
        long conflicts=jdbc.sql("SELECT COUNT(*) FROM "+OCCUPANCY+"""
            o WHERE o.starts_at<:end AND o.ends_at>:start AND (o.kind<>'EXAM' OR o.id<>:exam)
            AND (o.class_id=:c OR o.teacher_id=:teacher OR o.room_id=:room
            OR EXISTS(SELECT 1 FROM teaching_member a JOIN teaching_member b ON a.student_id=b.student_id
                WHERE a.class_id=o.class_id AND b.class_id=:c AND a.active=TRUE AND b.active=TRUE))
            """).param("start",Timestamp.valueOf(start)).param("end",Timestamp.valueOf(end)).param("exam",ignoredExamId)
            .param("c",classId).param("teacher",teacherId).param("room",roomId).query(Long.class).single();
        if (conflicts>0) teaching.conflict("安排与教学班、教师、教室或学生已有课程、已发布考试冲突，请调整时间或地点");
    }

    public void requireStudentAvailable(long classId,long studentId) {
        long conflicts=jdbc.sql("SELECT COUNT(*) FROM "+OCCUPANCY+" pending JOIN "+OCCUPANCY+"""
            other ON pending.starts_at<other.ends_at AND pending.ends_at>other.starts_at
            AND (pending.kind<>other.kind OR pending.id<>other.id)
            WHERE pending.class_id=:c AND (other.class_id=:c OR EXISTS(
                SELECT 1 FROM teaching_member m WHERE m.class_id=other.class_id AND m.student_id=:student AND m.active=TRUE))
            """).param("c",classId).param("student",studentId).query(Long.class).single();
        if (conflicts>0) teaching.conflict("无法启用学生关联：该教学班的课程或已发布考试与学生已有安排冲突，请先调整安排");
    }
}
