package cn.zzuit.campus.bootstrap;

import java.time.LocalDate;
import java.time.ZoneId;
import java.sql.Timestamp;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(10)
@ConditionalOnProperty(name="app.demo.enabled", havingValue="true")
public class DemoDataInitializer implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final Environment environment;
    public DemoDataInitializer(JdbcClient jdbc, PasswordEncoder passwords, Environment environment) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.environment = environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (jdbc.sql("SELECT COUNT(*) FROM major WHERE id=1").query(Long.class).single() == 0) {
            jdbc.sql("INSERT INTO major(id,department_id,name,demo) VALUES(1,1,'软件工程（演示专业）',TRUE)").update();
            jdbc.sql("INSERT INTO class_group(id,major_id,name) VALUES(1,1,'2026级演示1班')").update();
        }
        String hash = passwords.encode(environment.getProperty("app.demo.password", "Demo@2026"));
        account("20260001", "陈同学（演示）", "20260001", "STUDENT", hash, true);
        account("20260002", "李同学（演示）", "20260002", "STUDENT", hash, true);
        account("teacher1001", "王老师（演示）", "T1001", "TEACHER", hash, false);
        account("teacher1002", "赵老师（演示）", "T1002", "TEACHER", hash, false);
        account("academic1001", "教务老师（演示）", "J1001", "ACADEMIC_STAFF", hash, false);
        account("staff1001", "服务人员（演示）", "S1001", "SERVICE_STAFF", hash, false);
        account("editor", "内容编辑（演示）", "E1001", "CONTENT_EDITOR", hash, false);
        account("reviewer", "内容审核（演示）", "R1001", "CONTENT_REVIEWER", hash, false);
        account("admin", "管理员（演示）", "A1001", "ADMIN", hash, false);
    }

    private void account(String username, String name, String number, String role, String hash, boolean student) {
        if (jdbc.sql("SELECT COUNT(*) FROM app_user WHERE username=:name").param("name",username)
            .query(Long.class).single() > 0) return;
        jdbc.sql("""
            INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,class_group_id)
            VALUES(:username,:hash,:name,:number,1,1,:classId)
            """).param("username",username).param("hash",hash).param("name",name)
            .param("number",number).param("classId",student ? 1L : null).update();
        long id = jdbc.sql("SELECT id FROM app_user WHERE username=:name")
            .param("name",username).query(Long.class).single();
        jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,:role)")
            .param("id",id).param("role",role).update();
        if (student) {
            jdbc.sql("INSERT INTO user_notice(user_id,title,body) VALUES(:id,:title,:body)")
                .param("id",id).param("title", "欢迎使用校园服务平台")
                .param("body", name + "，这是一条仅属于你账号的演示通知。可查看课表、消息和本人资料。").update();
            LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            for (int index = 0; index < 3; index++) {
                var start = day.plusDays(index).atTime(index % 2 == 0 ? 8 : 10, 0);
                jdbc.sql("""
                    INSERT INTO timetable_entry(student_id,course_name,teacher_name,classroom,campus_id,starts_at,ends_at)
                    VALUES(:id,:course,'王老师（演示）','演示教室 A-201',1,:start,:end)
                    """).param("id",id).param("course", new String[]{"Java 程序设计","数据库原理","软件工程"}[index])
                    .param("start",Timestamp.valueOf(start)).param("end",Timestamp.valueOf(start.plusMinutes(100))).update();
            }
        }
    }
}
