package cn.zzuit.campus.bootstrap;

import java.time.LocalDate;
import java.sql.Timestamp;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(20)
@ConditionalOnProperty(name="app.demo.enabled", havingValue="true")
public class TeachingDemoInitializer implements ApplicationRunner {
    private final JdbcClient jdbc;
    public TeachingDemoInitializer(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (jdbc.sql("SELECT COUNT(*) FROM academic_term").query(Long.class).single() > 0) return;
        for(String code:new String[]{"A-201","A-202","B-301"}) {
            jdbc.sql("INSERT INTO classroom_resource(campus_id,code,name,building_name,room_number,floor_label,room_type,teaching_capacity,exam_capacity,origin) VALUES(1,:code,:name,:building,:number,:floor,'LECTURE',60,30,'DEMO')")
                .param("code","DEMO-"+code).param("name","演示教室 "+code).param("building","演示教学楼 "+code.substring(0,1)).param("number",code.substring(2)).param("floor",code.startsWith("B")?"三层":"二层").update();
        }
        jdbc.sql("INSERT INTO academic_term(id,name,starts_on,ends_on) VALUES(1,'2026—2027 秋季学期（演示）','2026-09-07','2027-01-24')").update();
        jdbc.sql("INSERT INTO course(id,code,name,credits) VALUES(1,'DEMO-JAVA','Java 程序设计',3),(2,'DEMO-DB','数据库原理',3),(3,'DEMO-SE','软件工程',2)").update();
        jdbc.sql("INSERT INTO teaching_class(id,term_id,course_id,department_id,name) VALUES(1,1,1,1,'Java 教学班 01'),(2,1,2,1,'数据库教学班 02'),(3,1,3,1,'软件工程联合教学班')").update();
        long first = user("20260001"), second = user("20260002");
        long teacher = user("teacher1001"), otherTeacher = user("teacher1002");
        for (long[] relation : new long[][]{{1,teacher},{2,otherTeacher},{3,teacher}}) {
            jdbc.sql("INSERT INTO teaching_instructor(class_id,teacher_id) VALUES(:c,:u)")
                .param("c",relation[0]).param("u",relation[1]).update();
        }
        for (long[] relation : new long[][]{{1,first},{2,second},{3,first},{3,second}}) {
            jdbc.sql("INSERT INTO teaching_member(class_id,student_id) VALUES(:c,:u)")
                .param("c",relation[0]).param("u",relation[1]).update();
        }
        LocalDate beginning = LocalDate.of(2026,9,7);
        for (int week = 0; week < 20; week++) {
            lesson(1,teacher,beginning.plusWeeks(week).atTime(8,0),"演示教室 A-201");
            lesson(2,otherTeacher,beginning.plusWeeks(week).plusDays(1).atTime(10,0),"演示教室 A-202");
            lesson(3,teacher,beginning.plusWeeks(week).plusDays(3).atTime(14,0),"演示教室 B-301");
        }
        for (long[] material : new long[][]{{1,teacher},{2,otherTeacher},{3,teacher}}) {
            jdbc.sql("INSERT INTO course_material(class_id,author_id,title,body,status) VALUES(:c,:u,'课程导学与学习准备','本教学资料为毕业设计演示。请先阅读课程目标，准备开发环境，课堂资料将在本页面更新。','PUBLISHED')")
                .param("c",material[0]).param("u",material[1]).update();
        }
        jdbc.sql("INSERT INTO academic_grade(class_id,student_id,score,status) VALUES(1,:a,86,'PUBLISHED'),(2,:b,91,'PUBLISHED'),(3,:a,88,'DRAFT'),(3,:b,93,'DRAFT')")
            .param("a",first).param("b",second).update();
        for(int index=1;index<=3;index++) {
            String code=index==1?"A-201":index==2?"A-202":"B-301";
            String start=index==1?"2027-01-11 11:00:00":index==2?"2027-01-12 13:00:00":"2027-01-14 09:00:00";
            String end=index==1?"2027-01-11 13:00:00":index==2?"2027-01-12 15:00:00":"2027-01-14 11:00:00";
            jdbc.sql("INSERT INTO academic_exam(class_id,campus_id,classroom,room_id,starts_at,ends_at,status) VALUES(:class,1,:name,:room,:start,:end,:state)")
                .param("class",index).param("name","演示教室 "+code).param("room",room(code)).param("start",start).param("end",end).param("state",index==3?"DRAFT":"PUBLISHED").update();
        }
    }

    private long user(String name) {
        return jdbc.sql("SELECT id FROM app_user WHERE username=:u").param("u",name).query(Long.class).single();
    }
    private void lesson(long classId, long teacher, java.time.LocalDateTime start, String room) {
        jdbc.sql("INSERT INTO teaching_lesson(class_id,teacher_id,campus_id,classroom,room_id,starts_at,ends_at) VALUES(:c,:t,1,:r,:room,:s,:e)")
            .param("c",classId).param("t",teacher).param("r",room).param("room",room(room.substring(room.lastIndexOf(' ')+1)))
            .param("s",Timestamp.valueOf(start)).param("e",Timestamp.valueOf(start.plusMinutes(100))).update();
    }
    private long room(String code) { return jdbc.sql("SELECT id FROM classroom_resource WHERE campus_id=1 AND code=:code").param("code","DEMO-"+code).query(Long.class).single(); }
}
