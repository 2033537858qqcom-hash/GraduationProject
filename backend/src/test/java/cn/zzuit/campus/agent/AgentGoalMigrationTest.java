package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.*;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AgentGoalMigrationTest {
 @Test void upgradingV45PreservesFourMissionKindsAndPersistsGoalStateAndCascade()throws Exception{
  try(var data=new HikariDataSource()){
   data.setJdbcUrl("jdbc:h2:mem:goal-upgrade-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");data.setUsername("sa");data.setPassword("");
   Flyway.configure().dataSource(data).target("45").load().migrate();
   try(var connection=data.getConnection();var sql=connection.createStatement()){
    sql.executeUpdate("INSERT INTO app_user(username,password_hash,full_name,identity_number) VALUES('history','unused','历史用户','history')");
    sql.executeUpdate("INSERT INTO agent_session(user_id,request_key,title) VALUES(1,'historical-session','历史对话')");
    for(String kind:List.of("RESERVATION","REPAIR","LEAVE","CERTIFICATE"))sql.executeUpdate("INSERT INTO agent_mission(session_id,user_id,request_key,create_hash,goal,mode,external_consent,stage,state_json,business_key,kind) VALUES(1,1,'"+kind+"','old','原有目标','LOCAL',FALSE,'DONE','{}','"+kind+"','"+kind+"')");
   }
   var flyway=Flyway.configure().dataSource(data).load();flyway.migrate();flyway.migrate();
   try(var connection=data.getConnection();var sql=connection.createStatement()){
    try(var rows=sql.executeQuery("SELECT COUNT(*) FROM agent_mission WHERE stage='DONE' AND state_json='{}'")){rows.next();assertThat(rows.getInt(1)).isEqualTo(4);}
    try(var rows=sql.executeQuery("SELECT COUNT(*) FROM agent_guide WHERE slug='reservation' AND status='PUBLISHED'")){rows.next();assertThat(rows.getInt(1)).isEqualTo(1);}
    var state=new AgentGoalModels.State();var task=new AgentGoalModels.Task();task.key="proof";task.kind="CERTIFICATE";task.label="跟踪证明";task.target="TRACK";task.missionId=4L;task.submissionVerified=true;task.watching=true;task.lastBusinessStatus="SUBMITTED";state.tasks.add(task);
    String encoded=JsonMapper.builder().build().writeValueAsString(state);
    try(var insert=connection.prepareStatement("INSERT INTO agent_goal(session_id,user_id,request_key,create_hash,goal,external_consent,security_version,stage,state_json,monitor_after) VALUES(1,1,'goal-upgrade-key','hash','跟踪目标',TRUE,0,'MONITORING',?,CURRENT_TIMESTAMP)")){insert.setString(1,encoded);insert.executeUpdate();}
    sql.executeUpdate("INSERT INTO agent_goal_event(goal_id,step_number,tool,task_key,status,message,observation_json) VALUES(1,1,'watch_task','proof','WATCHING','已开始跟踪','{}')");
    try(var rows=sql.executeQuery("SELECT state_json FROM agent_goal WHERE id=1")){rows.next();assertThat(rows.getString(1)).isEqualTo(encoded);var restored=JsonMapper.builder().build().readValue(rows.getString(1),AgentGoalModels.State.class);assertThat(restored.tasks.getFirst().watching).isTrue();assertThat(restored.tasks.getFirst().missionId).isEqualTo(4L);}
    assertThatThrownBy(()->sql.executeUpdate("UPDATE agent_goal SET step_count=81 WHERE id=1")).isInstanceOf(SQLException.class);
    sql.executeUpdate("DELETE FROM agent_session WHERE id=1");
    for(String table:List.of("agent_goal","agent_goal_event","agent_mission"))try(var rows=sql.executeQuery("SELECT COUNT(*) FROM "+table)){rows.next();assertThat(rows.getInt(1)).isZero();}
   }
  }
 }
}
