package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class AgentConversationMigrationTest {
    @Test void upgradingV40KeepsOldContextsAndBillReferences() throws Exception {
        try(var source=new HikariDataSource()) {
            source.setJdbcUrl("jdbc:h2:mem:conversation-upgrade-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");source.setUsername("sa");source.setPassword("");
            Flyway.configure().dataSource(source).target("40").load().migrate();
            try(var conn=source.getConnection();var sql=conn.createStatement()) {
                sql.executeUpdate("INSERT INTO app_user(username,password_hash,full_name,identity_number) VALUES('old-user','unused','演示用户','old-user')");
                sql.executeUpdate("INSERT INTO agent_session(user_id,request_key,title) VALUES(1,'old-session','历史对话')");
                sql.executeUpdate("INSERT INTO agent_turn(session_id,request_key,question,mode,status,context_type,context_id,payment_refs_json) VALUES(1,'old-turn','原有账单查询','LOCAL','SUCCESS','PAYMENT_MINE',12,'[12,13]')");
            }
            var flyway=Flyway.configure().dataSource(source).load();flyway.migrate();flyway.migrate();
            try(var conn=source.getConnection();var sql=conn.createStatement();var row=sql.executeQuery("SELECT question,context_type,context_id,payment_refs_json,resolved_context_type,resolved_context_id,payment_quote_json FROM agent_turn WHERE id=1")) {
                assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("原有账单查询");assertThat(row.getString(2)).isEqualTo("PAYMENT_MINE");assertThat(row.getLong(3)).isEqualTo(12);assertThat(row.getString(4)).isEqualTo("[12,13]");
                assertThat(row.getString(5)).isNull();assertThat(row.getObject(6)).isNull();assertThat(row.getString(7)).isNull();
            }
        }
    }
}
