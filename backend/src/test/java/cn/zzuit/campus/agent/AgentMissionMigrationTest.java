package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class AgentMissionMigrationTest {
    @Test void upgradingV41RetainsHistoryAndTaskTraceCascadesWithSession() throws Exception {
        try(var source=new HikariDataSource()) {
            source.setJdbcUrl("jdbc:h2:mem:mission-upgrade-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");source.setUsername("sa");source.setPassword("");
            Flyway.configure().dataSource(source).target("41").load().migrate();
            try(var conn=source.getConnection();var sql=conn.createStatement()) {
                sql.executeUpdate("INSERT INTO app_user(username,password_hash,full_name,identity_number) VALUES('old-user','unused','历史用户','old-user')");
                sql.executeUpdate("INSERT INTO agent_session(user_id,request_key,title) VALUES(1,'old-session','历史对话')");
                sql.executeUpdate("INSERT INTO agent_turn(session_id,request_key,question,mode,status,payment_refs_json) VALUES(1,'old-turn','原有问题','LOCAL','SUCCESS','[12]')");
            }
            var flyway=Flyway.configure().dataSource(source).load();flyway.migrate();flyway.migrate();
            try(var conn=source.getConnection();var sql=conn.createStatement()) {
                try(var row=sql.executeQuery("SELECT question,payment_refs_json FROM agent_turn WHERE id=1")){assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("原有问题");assertThat(row.getString(2)).isEqualTo("[12]");}
                sql.executeUpdate("INSERT INTO agent_session(user_id,request_key,title) VALUES(1,'mission-session','任务对话')");
                sql.executeUpdate("INSERT INTO agent_mission(session_id,user_id,request_key,create_hash,goal,mode,external_consent,stage,state_json,business_key) VALUES(2,1,'mission-key','hash','预约任务','LOCAL',FALSE,'DONE','{}','business-key')");
                sql.executeUpdate("INSERT INTO agent_mission_step(mission_id,step_number,action,status,message) VALUES(1,1,'finish','DONE','完成')");
                sql.executeUpdate("DELETE FROM agent_session WHERE id=2");
                try(var row=sql.executeQuery("SELECT COUNT(*) FROM agent_mission_step")){row.next();assertThat(row.getInt(1)).isZero();}
                try(var row=sql.executeQuery("SELECT COUNT(*) FROM agent_mission")){row.next();assertThat(row.getInt(1)).isZero();}
                try(var row=sql.executeQuery("SELECT COUNT(*) FROM agent_turn")){row.next();assertThat(row.getInt(1)).isEqualTo(1);}
            }
        }
    }
}
