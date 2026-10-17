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

@Component @Order(40)
@ConditionalOnProperty(name="app.demo.enabled",havingValue="true")
public class CertificateDemoInitializer implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final Environment environment;
    public CertificateDemoInitializer(JdbcClient jdbc,PasswordEncoder passwords,Environment environment) {
        this.jdbc=jdbc; this.passwords=passwords; this.environment=environment;
    }
    @Override @Transactional public void run(ApplicationArguments args) {
        if(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE username='affairs1001'").query(Long.class).single()>0) return;
        jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,demo) VALUES('affairs1001',:hash,'学生事务人员（演示）','DEMO-AFFAIRS-1001',1,1,TRUE)")
            .param("hash",passwords.encode(environment.getProperty("app.demo.password","Demo@2026"))).update();
        jdbc.sql("INSERT INTO user_role(user_id,role_code) SELECT id,'STUDENT_AFFAIRS' FROM app_user WHERE username='affairs1001'").update();
        // Only bind resources when creating this demo account. A restart must not
        // restore responsibilities deliberately removed by resource managers.
        jdbc.sql("""
            INSERT INTO venue_manager(venue_id,user_id)
            SELECT v.id,u.id FROM venue_resource v CROSS JOIN app_user u WHERE u.username='affairs1001'
            """).update();
    }
}
