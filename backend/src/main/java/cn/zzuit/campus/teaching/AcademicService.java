package cn.zzuit.campus.teaching;

import static cn.zzuit.campus.teaching.TeachingModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.resource.RoomService;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AcademicService {
    private final JdbcClient jdbc;
    private final TeachingService teaching;
    private final TeachingScheduleService schedule;
    private final RoomService rooms;
    public AcademicService(JdbcClient jdbc,TeachingService teaching,TeachingScheduleService schedule,RoomService rooms) {
        this.jdbc=jdbc; this.teaching=teaching; this.schedule=schedule; this.rooms=rooms;
    }

    private long department(CampusPrincipal user) {
        teaching.permission(user,"academic:manage");
        return jdbc.sql("SELECT department_id FROM app_user WHERE id=:u AND department_id IS NOT NULL")
            .param("u",user.id()).query(Long.class).optional().orElseThrow(teaching::notFound);
    }
    public Catalog catalog(CampusPrincipal user) {
        long department=department(user);
        var courses=jdbc.sql("SELECT * FROM course ORDER BY code").query((r,n)->new Course(r.getLong("id"),r.getString("code"),r.getString("name"),r.getBigDecimal("credits"))).list();
        var classes=jdbc.sql("""
            SELECT tc.id,tc.term_id,tc.course_id,tc.name,c.name course_name,c.credits,d.name department_name,
            (SELECT COUNT(*) FROM teaching_member m WHERE m.class_id=tc.id AND m.active=TRUE) member_count
            FROM teaching_class tc JOIN course c ON c.id=tc.course_id JOIN department d ON d.id=tc.department_id
            WHERE tc.department_id=:d ORDER BY tc.id DESC
            """).param("d",department).query((r,n)->new ClassInfo(r.getLong("id"),r.getLong("term_id"),r.getLong("course_id"),r.getString("name"),r.getString("course_name"),r.getBigDecimal("credits"),r.getString("department_name"),r.getLong("member_count"))).list();
        var campuses=jdbc.sql("SELECT id,name FROM campus ORDER BY id").query((r,n)->new Campus(r.getLong(1),r.getString(2))).list();
        return new Catalog(teaching.terms(user),courses,classes,people(department,"TEACHER"),people(department,"STUDENT"),campuses,rooms.academicRooms(user));
    }
    private List<Person> people(long department,String role) {
        return jdbc.sql("SELECT u.id,u.full_name,u.identity_number,cg.name class_name FROM app_user u JOIN user_role ur ON ur.user_id=u.id LEFT JOIN class_group cg ON cg.id=u.class_group_id WHERE u.department_id=:d AND ur.role_code=:r AND u.enabled=TRUE ORDER BY u.identity_number")
            .param("d",department).param("r",role).query((r,n)->new Person(r.getLong("id"),r.getString("full_name"),r.getString("identity_number"),r.getString("class_name"))).list();
    }
    @Transactional
    public Term createTerm(CampusPrincipal user,TermInput input) {
        department(user);
        if (input.endsOn().isBefore(input.startsOn()) || input.endsOn().isAfter(input.startsOn().plusYears(1))) teaching.bad("学期结束日期须在开始日期之后且跨度不超过一年");
        var key=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO academic_term(name,starts_on,ends_on) VALUES(:n,:s,:e)")
            .param("n",input.name()).param("s",java.sql.Date.valueOf(input.startsOn())).param("e",java.sql.Date.valueOf(input.endsOn())).update(key,"id");
        return new Term(key.getKey().longValue(),input.name(),input.startsOn(),input.endsOn());
    }
    @Transactional
    public Course createCourse(CampusPrincipal user,CourseInput input) {
        department(user);
        if (input.credits().stripTrailingZeros().scale()>1) teaching.bad("学分最多保留一位小数");
        var key=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO course(code,name,credits) VALUES(:c,:n,:v)").param("c",input.code()).param("n",input.name()).param("v",input.credits()).update(key,"id");
        return new Course(key.getKey().longValue(),input.code(),input.name(),input.credits());
    }
    @Transactional
    public ClassDetail createClass(CampusPrincipal user,ClassInput input) {
        teaching.lockWrites();
        long department=department(user);
        if (jdbc.sql("SELECT COUNT(*) FROM academic_term WHERE id=:t").param("t",input.termId()).query(Long.class).single()==0
            || jdbc.sql("SELECT COUNT(*) FROM course WHERE id=:c").param("c",input.courseId()).query(Long.class).single()==0) teaching.bad("学期或课程不存在");
        if (new HashSet<>(input.teacherIds()).size()!=input.teacherIds().size() || new HashSet<>(input.studentIds()).size()!=input.studentIds().size()) teaching.bad("教学关系不能重复");
        input.teacherIds().forEach(id->validatePerson(id,department,"TEACHER"));
        input.studentIds().forEach(id->validatePerson(id,department,"STUDENT"));
        var key=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO teaching_class(term_id,course_id,department_id,name) VALUES(:t,:c,:d,:n)")
            .param("t",input.termId()).param("c",input.courseId()).param("d",department).param("n",input.name()).update(key,"id");
        long id=key.getKey().longValue();
        for (long teacher:input.teacherIds()) jdbc.sql("INSERT INTO teaching_instructor(class_id,teacher_id) VALUES(:c,:u)").param("c",id).param("u",teacher).update();
        for (long student:input.studentIds()) jdbc.sql("INSERT INTO teaching_member(class_id,student_id) VALUES(:c,:u)").param("c",id).param("u",student).update();
        teaching.audit(id,user.id(),"CLASS_CREATE",id,input.name());
        return teaching.detail(user,id,"academic");
    }
    private void validatePerson(long id,long department,String role) {
        if (jdbc.sql("SELECT COUNT(*) FROM app_user u JOIN user_role ur ON ur.user_id=u.id WHERE u.id=:u AND u.department_id=:d AND u.enabled=TRUE AND ur.role_code=:r")
            .param("u",id).param("d",department).param("r",role).query(Long.class).single()==0) teaching.bad("所选人员不属于当前学院的有效"+(role.equals("TEACHER")?"教师":"学生"));
    }
    @Transactional
    public ClassDetail relation(CampusPrincipal user,long classId,RelationInput input,boolean teacher) {
        teaching.lockWrites();
        teaching.academicAccess(user,classId);
        validatePerson(input.userId(),department(user),teacher?"TEACHER":"STUDENT");
        String table=teacher?"teaching_instructor":"teaching_member", column=teacher?"teacher_id":"student_id";
        if (!teacher && input.active() && jdbc.sql("SELECT COUNT(*) FROM teaching_member WHERE class_id=:c AND student_id=:u AND active=TRUE")
            .param("c",classId).param("u",input.userId()).query(Long.class).single()==0) {
            schedule.requireStudentAvailable(classId,input.userId()); rooms.requireMemberCapacity(classId);
        }
        if (jdbc.sql("SELECT COUNT(*) FROM "+table+" WHERE class_id=:c AND "+column+"=:u").param("c",classId).param("u",input.userId()).query(Long.class).single()==0) {
            jdbc.sql("INSERT INTO "+table+"(class_id,"+column+",active) VALUES(:c,:u,:a)").param("c",classId).param("u",input.userId()).param("a",input.active()).update();
        } else jdbc.sql("UPDATE "+table+" SET active=:a WHERE class_id=:c AND "+column+"=:u").param("a",input.active()).param("c",classId).param("u",input.userId()).update();
        teaching.audit(classId,user.id(),teacher?"INSTRUCTOR_CHANGE":"MEMBER_CHANGE",input.userId(),input.active()?"关联生效":"关联停用");
        return teaching.detail(user,classId,"academic");
    }
    @Transactional
    public ClassDetail addLesson(CampusPrincipal user,long classId,LessonInput input) {
        teaching.lockWrites();
        teaching.academicAccess(user,classId);
        if (jdbc.sql("SELECT COUNT(*) FROM teaching_instructor WHERE class_id=:c AND teacher_id=:u AND active=TRUE")
            .param("c",classId).param("u",input.teacherId()).query(Long.class).single()==0) teaching.bad("请先建立有效授课关系");
        timeRange(classId,input.startsAt(),input.endsAt());
        var room=rooms.forScheduling(input.roomId(),classId,false);
        schedule.requireAvailable(classId,input.teacherId(),room.id(),input.startsAt(),input.endsAt(),0);
        var key=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO teaching_lesson(class_id,teacher_id,campus_id,classroom,room_id,starts_at,ends_at) VALUES(:c,:u,:cp,:r,:room,:s,:e)")
            .param("c",classId).param("u",input.teacherId()).param("cp",room.campusId()).param("r",room.name()).param("room",room.id())
            .param("s",Timestamp.valueOf(input.startsAt())).param("e",Timestamp.valueOf(input.endsAt())).update(key,"id");
        teaching.audit(classId,user.id(),"LESSON_ADD",key.getKey().longValue(),input.startsAt().toString());
        teaching.notifyMembers(classId,"课表安排已更新","你的教学班新增了一次上课安排，请在我的学习中查看课表。");
        return teaching.detail(user,classId,"academic");
    }
    private void timeRange(long classId,LocalDateTime start,LocalDateTime end) {
        if (!end.isAfter(start) || end.isAfter(start.plusHours(12))) teaching.bad("结束时间须晚于开始时间，单次安排不超过十二小时");
        var term=jdbc.sql("SELECT t.* FROM academic_term t JOIN teaching_class c ON c.term_id=t.id WHERE c.id=:c")
            .param("c",classId).query((r,n)->new Term(r.getLong("id"),r.getString("name"),r.getDate("starts_on").toLocalDate(),r.getDate("ends_on").toLocalDate())).single();
        if (start.toLocalDate().isBefore(term.startsOn()) || end.toLocalDate().isAfter(term.endsOn())) teaching.bad("时间须位于教学班所属学期内");
    }
    @Transactional
    public ImportResult importGrades(CampusPrincipal user,long classId,GradeImport input) {
        if (!input.preview()) teaching.lockWrites();
        teaching.academicAccess(user,classId);
        var seen=new HashSet<Long>();
        var priorRows=teaching.gradesForClass(classId).stream().collect(java.util.stream.Collectors.toMap(Grade::studentId,g->g));
        for (var row:input.rows()) {
            if (!seen.add(row.studentId())) teaching.bad("导入包含重复学生");
            if (row.score().stripTrailingZeros().scale()>1) teaching.bad("成绩最多保留一位小数");
            if (jdbc.sql("SELECT COUNT(*) FROM teaching_member m JOIN app_user u ON u.id=m.student_id WHERE m.class_id=:c AND m.student_id=:u AND m.active=TRUE AND u.enabled=TRUE")
                .param("c",classId).param("u",row.studentId()).query(Long.class).single()==0) teaching.bad("导入包含不属于当前教学班的有效学生");
            var old=java.util.Optional.ofNullable(priorRows.get(row.studentId()));
            if ((old.isPresent() && old.get().version()!=row.version()) || (old.isEmpty() && row.version()!=0)) teaching.conflict("成绩版本已变化，请刷新后重新导入");
        }
        if (input.preview()) return new ImportResult(input.rows().size(),true);
        for (var row:input.rows()) {
            var old=java.util.Optional.ofNullable(priorRows.get(row.studentId()));
            long id;
            if (old.isEmpty()) {
                var key=new GeneratedKeyHolder();
                jdbc.sql("INSERT INTO academic_grade(class_id,student_id,score) VALUES(:c,:u,:s)").param("c",classId).param("u",row.studentId()).param("s",row.score()).update(key,"id");
                id=key.getKey().longValue();
            } else {
                id=old.get().id();
                if (jdbc.sql("UPDATE academic_grade SET score=:s,status='DRAFT',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:v")
                    .param("s",row.score()).param("id",id).param("v",row.version()).update()!=1) teaching.conflict("成绩版本已变化，请刷新后重试");
                if (old.get().status().equals("PUBLISHED")) personalNotice(row.studentId(),"成绩信息正在更正","你的课程成绩正在更正，发布后可在我的学习中查看。原始记录已保留审计。");
            }
            teaching.audit(classId,user.id(),"GRADE_DRAFT",id,"学生 "+row.studentId()+"；原记录 "+(old.isEmpty()?"无":old.get().score()+" / "+old.get().status()+" / v"+old.get().version())+"；新成绩 "+row.score()+" / DRAFT");
        }
        return new ImportResult(input.rows().size(),false);
    }
    @Transactional
    public Grade gradeState(CampusPrincipal user,long id,Transition input) {
        teaching.lockWrites();
        long classId=jdbc.sql("SELECT class_id FROM academic_grade WHERE id=:id").param("id",id).query(Long.class).optional().orElseThrow(teaching::notFound);
        teaching.academicAccess(user,classId);
        if (!input.status().equals("DRAFT") && !input.status().equals("PUBLISHED")) teaching.bad("成绩只支持草稿或已发布状态");
        var old=teaching.gradesForClass(classId).stream().filter(g->g.id()==id).findFirst().orElseThrow(teaching::notFound);
        if (old.status().equals(input.status())) teaching.conflict("成绩已经处于目标状态");
        if (jdbc.sql("UPDATE academic_grade SET status=:s,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:v")
            .param("s",input.status()).param("id",id).param("v",input.version()).update()!=1) teaching.conflict("成绩版本已变化，请刷新后重试");
        teaching.audit(classId,user.id(),"GRADE_"+input.status(),id,old.courseName()+"："+old.score());
        personalNotice(old.studentId(),input.status().equals("PUBLISHED")?"课程成绩已发布":"课程成绩已撤下","请在我的学习中查看当前已发布成绩。具体结果仅在本人查询页面展示。");
        return teaching.gradesForClass(classId).stream().filter(g->g.id()==id).findFirst().orElseThrow(teaching::notFound);
    }
    @Transactional
    public Exam saveExam(CampusPrincipal user,long classId,ExamInput input) {
        teaching.lockWrites();
        teaching.academicAccess(user,classId);
        timeRange(classId,input.startsAt(),input.endsAt());
        var room=rooms.forScheduling(input.roomId(),classId,true);
        var old=teaching.examsForClass(classId).stream().findFirst();
        if (old.isEmpty()) {
            if (input.version()!=0) teaching.conflict("考试版本不匹配");
            jdbc.sql("INSERT INTO academic_exam(class_id,campus_id,classroom,room_id,starts_at,ends_at) VALUES(:c,:cp,:r,:room,:s,:e)")
                .param("c",classId).param("cp",room.campusId()).param("r",room.name()).param("room",room.id()).param("s",Timestamp.valueOf(input.startsAt())).param("e",Timestamp.valueOf(input.endsAt())).update();
        } else {
            if (jdbc.sql("UPDATE academic_exam SET campus_id=:cp,classroom=:r,room_id=:room,starts_at=:s,ends_at=:e,status='DRAFT',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:v")
                .param("cp",room.campusId()).param("r",room.name()).param("room",room.id()).param("s",Timestamp.valueOf(input.startsAt())).param("e",Timestamp.valueOf(input.endsAt()))
                .param("id",old.get().id()).param("v",input.version()).update()!=1) teaching.conflict("考试版本已变化，请刷新后重试");
            if (old.get().status().equals("PUBLISHED")) teaching.notifyMembers(classId,"考试安排正在调整","原考试安排已撤下，请以重新发布后的安排为准。");
        }
        var exam=teaching.examsForClass(classId).get(0);
        teaching.audit(classId,user.id(),"EXAM_DRAFT",exam.id(),"原安排："+(old.isEmpty()?"无":old.get().startsAt()+"—"+old.get().endsAt()+" / "+old.get().campusId()+" / "+old.get().classroom()+" / "+old.get().status())+"；新安排："+input.startsAt()+"—"+input.endsAt()+" / "+room.campusId()+" / "+room.name()+" / roomId="+room.id());
        return exam;
    }
    @Transactional
    public Exam examState(CampusPrincipal user,long id,Transition input) {
        teaching.lockWrites();
        long classId=jdbc.sql("SELECT class_id FROM academic_exam WHERE id=:id").param("id",id).query(Long.class).optional().orElseThrow(teaching::notFound);
        teaching.academicAccess(user,classId);
        if (!input.status().equals("DRAFT") && !input.status().equals("PUBLISHED")) teaching.bad("考试只支持草稿或已发布状态");
        var exam=teaching.examsForClass(classId).get(0);
        if (exam.status().equals(input.status())) teaching.conflict("考试已经处于目标状态");
        if(exam.version()!=input.version()) teaching.conflict("考试版本已变化，请刷新后重试");
        if (input.status().equals("PUBLISHED")) {
            if(exam.roomId()==null) teaching.conflict("考场尚未关联教室资源，请重新保存安排");
            var room=rooms.forScheduling(exam.roomId(),classId,true);
            schedule.requireAvailable(classId,0,room.id(),exam.startsAt(),exam.endsAt(),id);
        }
        if (jdbc.sql("UPDATE academic_exam SET status=:s,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:v")
            .param("s",input.status()).param("id",id).param("v",input.version()).update()!=1) teaching.conflict("考试版本已变化，请刷新后重试");
        teaching.audit(classId,user.id(),"EXAM_"+input.status(),id,exam.startsAt()+" / "+exam.classroom());
        teaching.notifyMembers(classId,input.status().equals("PUBLISHED")?"考试安排已发布":"考试安排已撤下","请在我的学习中查看当前有效的考试安排。");
        return teaching.examsForClass(classId).get(0);
    }
    private void personalNotice(long student,String title,String body) {
        jdbc.sql("INSERT INTO user_notice(user_id,title,body) VALUES(:u,:t,:b)").param("u",student).param("t",title).param("b",body).update();
    }
}
