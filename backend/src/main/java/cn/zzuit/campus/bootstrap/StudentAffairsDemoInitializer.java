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
@Order(30)
@ConditionalOnProperty(name="app.demo.enabled",havingValue="true")
public class StudentAffairsDemoInitializer implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final Environment environment;
    public StudentAffairsDemoInitializer(JdbcClient jdbc,PasswordEncoder passwords,Environment environment) { this.jdbc=jdbc; this.passwords=passwords; this.environment=environment; }
    @Override @Transactional public void run(ApplicationArguments args) {
        // Existing accounts/roles/assignments are never restored over administrator edits.
        if(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE username='counselor1001'").query(Long.class).single()>0) return;
        jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,demo) VALUES('counselor1001',:hash,'辅导员（演示）','F1001',1,1,TRUE)")
            .param("hash",passwords.encode(environment.getProperty("app.demo.password","Demo@2026"))).update();
        long id=jdbc.sql("SELECT id FROM app_user WHERE username='counselor1001'").query(Long.class).single();
        jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,'TEACHER'),(:id,'COUNSELOR')").param("id",id).update();
        if(jdbc.sql("SELECT COUNT(*) FROM class_group cg JOIN major m ON m.id=cg.major_id WHERE cg.id=1 AND m.department_id=1 AND m.demo=TRUE AND NOT EXISTS(SELECT 1 FROM counselor_assignment WHERE class_group_id=1)").query(Long.class).single()>0)
            jdbc.sql("INSERT INTO counselor_assignment(class_group_id,counselor_id) VALUES(1,:id)").param("id",id).update();
    }
}
