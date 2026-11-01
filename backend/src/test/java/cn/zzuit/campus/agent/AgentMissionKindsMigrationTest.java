package cn.zzuit.campus.agent;
import static org.assertj.core.api.Assertions.assertThat;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
class AgentMissionKindsMigrationTest {
 @Test void upgradingV42DefaultsExistingMissionsToReservationWithoutChangingTrace()throws Exception {
  try(var ds=new HikariDataSource()){
   ds.setJdbcUrl("jdbc:h2:mem:mission-kinds-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");ds.setUsername("sa");ds.setPassword("");Flyway.configure().dataSource(ds).target("42").load().migrate();
   try(var c=ds.getConnection();var sql=c.createStatement()){
    sql.executeUpdate("INSERT INTO app_user(username,password_hash,full_name,identity_number) VALUES('old','unused','历史用户','old')");
    sql.executeUpdate("INSERT INTO agent_session(user_id,request_key,title) VALUES(1,'session','历史任务')");
    sql.executeUpdate("INSERT INTO agent_mission(session_id,user_id,request_key,create_hash,goal,mode,external_consent,stage,state_json,business_key) VALUES(1,1,'mission','hash','原预约目标','LOCAL',FALSE,'WAITING_INPUT','{}','business')");
    sql.executeUpdate("INSERT INTO agent_mission_step(mission_id,step_number,action,status,message) VALUES(1,1,'request_input','NEEDS_INPUT','历史步骤')");
   }
   var migration=Flyway.configure().dataSource(ds).load();migration.migrate();migration.migrate();
   try(var c=ds.getConnection();var sql=c.createStatement();var r=sql.executeQuery("SELECT m.kind,m.goal,m.stage,s.message FROM agent_mission m JOIN agent_mission_step s ON s.mission_id=m.id")){assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("RESERVATION");assertThat(r.getString(2)).isEqualTo("原预约目标");assertThat(r.getString(3)).isEqualTo("WAITING_INPUT");assertThat(r.getString(4)).isEqualTo("历史步骤");}
  }
 }
}
