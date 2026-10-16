package cn.zzuit.campus.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(60)
@ConditionalOnProperty(name="app.demo.enabled",havingValue="true")
public class HousingDemoInitializer implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final Environment environment;
    public HousingDemoInitializer(JdbcClient jdbc,PasswordEncoder passwords,Environment environment) {this.jdbc=jdbc;this.passwords=passwords;this.environment=environment;}
    @Override @Transactional public void run(ApplicationArguments arguments) {
        if(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE username='dorm1001'").query(Long.class).single()>0) return;
        if(jdbc.sql("SELECT COUNT(*) FROM department WHERE name='学生宿舍管理部门（演示）'").query(Long.class).single()==0)
            jdbc.sql("INSERT INTO department(name,kind) VALUES('学生宿舍管理部门（演示）','ADMIN')").update();
        long department=jdbc.sql("SELECT id FROM department WHERE name='学生宿舍管理部门（演示）'").query(Long.class).single();
        jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,demo) VALUES('dorm1001',:p,'宿管人员（演示）','DEMO-DORM-1001',1,:d,TRUE)")
            .param("p",passwords.encode(environment.getProperty("app.demo.password","Demo@2026"))).param("d",department).update();
        long user=jdbc.sql("SELECT id FROM app_user WHERE username='dorm1001'").query(Long.class).single();
        jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,'DORM_MANAGER')").param("u",user).update();
    }
}
