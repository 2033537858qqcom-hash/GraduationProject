package cn.zzuit.campus.bootstrap;

import java.util.List;
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
@Order(50)
@ConditionalOnProperty(name="app.demo.enabled",havingValue="true")
public class RepairDemoInitializer implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final Environment environment;
    public RepairDemoInitializer(JdbcClient jdbc,PasswordEncoder passwords,Environment environment) {this.jdbc=jdbc;this.passwords=passwords;this.environment=environment;}
    @Override @Transactional public void run(ApplicationArguments arguments) {
        create("dispatcher1001","后勤派单人员（演示）","DEMO-DISPATCH-1001","REPAIR_DISPATCHER","DISPATCH");
        create("technician1001","维修人员（演示）","DEMO-TECH-1001","REPAIR_TECHNICIAN","WORK");
    }
    private void create(String username,String name,String number,String role,String capability) {
        // Only seed a newly created demo account. Never restore deleted scopes or edited roles/passwords on restart.
        if(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE username=:u").param("u",username).query(Long.class).single()>0)return;
        if(jdbc.sql("SELECT COUNT(*) FROM department WHERE name='后勤服务部门（演示）'").query(Long.class).single()==0)
            jdbc.sql("INSERT INTO department(name,kind) VALUES('后勤服务部门（演示）','ADMIN')").update();
        long department=jdbc.sql("SELECT id FROM department WHERE name='后勤服务部门（演示）'").query(Long.class).single();
        jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,demo) VALUES(:u,:p,:n,:number,1,:d,TRUE)")
            .param("u",username).param("p",passwords.encode(environment.getProperty("app.demo.password","Demo@2026"))).param("n",name).param("number",number).param("d",department).update();
        long user=jdbc.sql("SELECT id FROM app_user WHERE username=:u").param("u",username).query(Long.class).single();
        jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,:r)").param("u",user).param("r",role).update();
        for(String category:List.of("WATER","ELECTRICITY","NETWORK","FURNITURE","DOOR_WINDOW","EQUIPMENT","OTHER"))
            jdbc.sql("INSERT INTO repair_staff_scope(user_id,capability,campus_id,category) VALUES(:u,:a,1,:c)").param("u",user).param("a",capability).param("c",category).update();
    }
}
