package db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.zip.CRC32;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** V14 used an unnamed CHECK; discover its database-generated name without changing applied migrations. */
public class V15__repair_cancellation_and_followup extends BaseJavaMigration {
    private static final List<String> DDL=List.of(
        "ALTER TABLE repair_request ADD CONSTRAINT ck_repair_workflow_state CHECK(status IN ('DRAFT','SUBMITTED','RETURNED','ASSIGNED','IN_PROGRESS','AWAITING_CONFIRMATION','COMPLETED','REJECTED','WITHDRAWN','CANCELLED'))",
        """
        CREATE TABLE repair_cancellation (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          request_id BIGINT NOT NULL,
          requester_id BIGINT NOT NULL,
          reason VARCHAR(2000) NOT NULL,
          status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
          decision_actor_id BIGINT,
          decision_comment VARCHAR(2000) NOT NULL DEFAULT '',
          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
          resolved_at TIMESTAMP,
          FOREIGN KEY(request_id) REFERENCES repair_request(id),
          FOREIGN KEY(requester_id) REFERENCES app_user(id),
          FOREIGN KEY(decision_actor_id) REFERENCES app_user(id),
          CHECK(status IN ('PENDING','APPROVED','REJECTED','WITHDRAWN'))
        )
        """,
        "ALTER TABLE repair_request ADD COLUMN current_cancellation_id BIGINT",
        "ALTER TABLE repair_request ADD CONSTRAINT fk_repair_current_cancellation FOREIGN KEY(current_cancellation_id) REFERENCES repair_cancellation(id)",
        "CREATE INDEX idx_repair_cancellation ON repair_cancellation(request_id,id)",
        "CREATE INDEX idx_repair_pending_cancellation ON repair_request(current_cancellation_id,status,id)",
        """
        CREATE TABLE repair_reminder (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          request_id BIGINT NOT NULL,
          result_round INTEGER NOT NULL,
          recipient_id BIGINT NOT NULL,
          reminder_date DATE NOT NULL,
          sent_at TIMESTAMP NOT NULL,
          FOREIGN KEY(request_id) REFERENCES repair_request(id),
          FOREIGN KEY(recipient_id) REFERENCES app_user(id),
          UNIQUE(request_id,result_round,recipient_id,reminder_date)
        )
        """);
    @Override public Integer getChecksum() {
        CRC32 checksum=new CRC32();checksum.update(("discover-status-check-v1\n"+String.join("\n",DDL)).getBytes(StandardCharsets.UTF_8));return (int)checksum.getValue();
    }
    @Override public void migrate(Context context) throws Exception {
        Connection connection=context.getConnection();
        String schema=connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL")?connection.getCatalog():connection.getSchema();
        String sql="""
            SELECT tc.constraint_name,cc.check_clause FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
            WHERE tc.table_schema=? AND tc.table_name='repair_request' AND tc.constraint_type='CHECK'
            """;
        List<String> matches=new ArrayList<>();
        try(PreparedStatement statement=connection.prepareStatement(sql)) {
            statement.setString(1,schema);
            try(ResultSet rows=statement.executeQuery()) {
                while(rows.next()) {
                    // MySQL 8.4 stores literals as _utf8mb4\'DRAFT\'; H2 stores plain 'DRAFT'.
                    String clause=rows.getString(2).toUpperCase(Locale.ROOT).replace("\\","").replace("`","").replace("_UTF8MB4","");
                    if(clause.contains("'DRAFT'") && clause.contains("'WITHDRAWN'") && !clause.contains("'CANCELLED'") && clause.matches("(?s).*\\bSTATUS\\b.*"))matches.add(rows.getString(1));
                }
            }
        }
        if(matches.size()!=1 || !matches.getFirst().matches("[A-Za-z0-9_]{1,64}"))throw new SQLException("Expected exactly one original repair workflow status constraint");
        String quote=connection.getMetaData().getIdentifierQuoteString().trim();
        try(Statement statement=connection.createStatement()) {
            statement.execute("ALTER TABLE repair_request DROP CONSTRAINT "+quote+matches.getFirst()+quote);
            for(String ddl:DDL)statement.execute(ddl);
        }
    }
}
