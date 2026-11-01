package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class AgentPaymentMigrationTest {
    @Test void upgradingV39KeepsSessionHistoryAndOnlyAddsFinanceQueryPermission() throws Exception {
        try(var source=new HikariDataSource()) {
            source.setJdbcUrl("jdbc:h2:mem:agent-payment-upgrade-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");source.setUsername("sa");source.setPassword("");
            Flyway.configure().dataSource(source).target("39").load().migrate();
            try(var conn=source.getConnection();var sql=conn.createStatement()) {
                sql.executeUpdate("INSERT INTO app_user(username,password_hash,full_name,identity_number) VALUES('migration-user','unused','测试用户','migration-user')");
                sql.executeUpdate("INSERT INTO agent_session(user_id,request_key,title) VALUES(1,'session-key','原有对话')");
                sql.executeUpdate("INSERT INTO agent_turn(session_id,request_key,question,mode,status) VALUES(1,'turn-key','原有问题','LOCAL','SUCCESS')");
            }
            var flyway=Flyway.configure().dataSource(source).load();flyway.migrate();flyway.migrate();
            try(var conn=source.getConnection();var sql=conn.createStatement()) {
                try(var rows=sql.executeQuery("SELECT question,status,payment_refs_json FROM agent_turn WHERE id=1")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("原有问题");assertThat(rows.getString(2)).isEqualTo("SUCCESS");assertThat(rows.getString(3)).isNull();
                }
                try(var rows=sql.executeQuery("SELECT COUNT(*) FROM role_permission WHERE role_code IN ('FINANCE_OPERATOR','FINANCE_REVIEWER') AND permission_code='agent:query'")) {rows.next();assertThat(rows.getInt(1)).isEqualTo(2);}
                try(var rows=sql.executeQuery("SELECT COUNT(*) FROM role_permission WHERE role_code='ADMIN' AND permission_code LIKE 'payment:%'")) {rows.next();assertThat(rows.getInt(1)).isZero();}
            }
        }
    }
}
