package cn.zzuit.campus.agent;
import static org.assertj.core.api.Assertions.assertThat;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
class AgentLeaveMissionMigrationTest {
 @Test void upgradingV43KeepsReservationAndRepairMissionsAndAllowsLeave()throws Exception {
  try(var ds=new HikariDataSource()){
   ds.setJdbcUrl("jdbc:h2:mem:leave-mission-upgrade-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");ds.setUsername("sa");ds.setPassword("");Flyway.configure().dataSource(ds).target("43").load().migrate();
   try(var c=ds.getConnection();var sql=c.createStatement()){
    sql.executeUpdate("INSERT INTO app_user(username,password_hash,full_name,identity_number) VALUES('old','unused','历史用户','old')");sql.executeUpdate("INSERT INTO agent_session(user_id,request_key,title) VALUES(1,'session','历史对话')");
    for(String kind:java.util.List.of("RESERVATION","REPAIR"))sql.executeUpdate("INSERT INTO agent_mission(session_id,user_id,request_key,create_hash,goal,mode,external_consent,stage,state_json,business_key,kind) VALUES(1,1,'"+kind+"','hash','历史目标','LOCAL',FALSE,'DONE','{}','"+kind+"','"+kind+"')");
   }
   var m=Flyway.configure().dataSource(ds).load();m.migrate();m.migrate();
   try(var c=ds.getConnection();var sql=c.createStatement()){
    try(var r=sql.executeQuery("SELECT COUNT(*) FROM agent_mission WHERE kind IN ('RESERVATION','REPAIR') AND stage='DONE'")){r.next();assertThat(r.getInt(1)).isEqualTo(2);}
    sql.executeUpdate("INSERT INTO agent_mission(session_id,user_id,request_key,create_hash,goal,mode,external_consent,stage,state_json,business_key,kind) VALUES(1,1,'leave','hash','请假目标','LOCAL',FALSE,'WAITING_INPUT','{}','leave','LEAVE')");
    try(var r=sql.executeQuery("SELECT kind FROM agent_mission WHERE request_key='leave'")){r.next();assertThat(r.getString(1)).isEqualTo("LEAVE");}
   }
  }
 }
}
