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
@Order(70)
@ConditionalOnProperty(name="app.demo.enabled",havingValue="true")
public class FinanceDemoInitializer implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final Environment environment;
    public FinanceDemoInitializer(JdbcClient jdbc,PasswordEncoder passwords,Environment environment) { this.jdbc=jdbc;this.passwords=passwords;this.environment=environment; }
    @Override @Transactional public void run(ApplicationArguments ignored) {
        for(String username:new String[]{"finance1001","finance2001"}) {
            if(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE username=:u").param("u",username).query(Long.class).single()>0) continue;
            if(jdbc.sql("SELECT COUNT(*) FROM department WHERE name='财务服务部门（演示）'").query(Long.class).single()==0)
                jdbc.sql("INSERT INTO department(name,kind) VALUES('财务服务部门（演示）','ADMIN')").update();
            long department=jdbc.sql("SELECT id FROM department WHERE name='财务服务部门（演示）'").query(Long.class).single();
            boolean reviewer=username.equals("finance2001");
            jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,demo) VALUES(:u,:p,:name,:number,1,:d,TRUE)")
                .param("u",username).param("p",passwords.encode(environment.getProperty("app.demo.password","Demo@2026")))
                .param("name",reviewer?"财务复核人员（演示）":"财务经办人员（演示）").param("number","DEMO-"+username).param("d",department).update();
            jdbc.sql("INSERT INTO user_role(user_id,role_code) SELECT id,:role FROM app_user WHERE username=:u")
                .param("role",reviewer?"FINANCE_REVIEWER":"FINANCE_OPERATOR").param("u",username).update();
        }
    }
}
