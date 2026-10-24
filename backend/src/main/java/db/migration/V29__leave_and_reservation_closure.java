package db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.zip.CRC32;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Extend generated CHECK names without modifying migrations already applied to user databases. */
public class V29__leave_and_reservation_closure extends BaseJavaMigration {
    private static final List<String> DDL = List.of(
        "ALTER TABLE leave_request ADD CONSTRAINT ck_leave_closure_state CHECK(status IN ('DRAFT','SUBMITTED','RETURNED','APPROVED','RETURN_PENDING','COMPLETED','REJECTED','WITHDRAWN'))",
        "ALTER TABLE leave_request ADD COLUMN returned_at TIMESTAMP",
        "ALTER TABLE leave_request ADD COLUMN closed_at TIMESTAMP",
        "ALTER TABLE venue_reservation ADD CONSTRAINT ck_reservation_closure_state CHECK(status IN ('DRAFT','SUBMITTED','RETURNED','APPROVED','IN_USE','COMPLETED','EXPIRED','REJECTED','WITHDRAWN','CANCELLED'))",
        """
        CREATE TABLE service_followup_reminder (
          kind VARCHAR(16) NOT NULL,
          request_id BIGINT NOT NULL,
          recipient_id BIGINT NOT NULL,
          reminder_date DATE NOT NULL,
          PRIMARY KEY(kind,request_id,recipient_id,reminder_date),
          FOREIGN KEY(recipient_id) REFERENCES app_user(id)
        )
        """
    );

    @Override public Integer getChecksum() {
        var crc = new CRC32();
        crc.update(("discover-status-check-v1\n" + String.join("\n", DDL)).getBytes(StandardCharsets.UTF_8));
        return (int) crc.getValue();
    }

    @Override public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        dropOriginalStateCheck(connection, "leave_request");
        dropOriginalStateCheck(connection, "venue_reservation");
        try (var statement = connection.createStatement()) {
            for (var ddl : DDL) statement.execute(ddl);
        }
    }

    private void dropOriginalStateCheck(Connection connection, String table) throws SQLException {
        boolean mysql = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
        String schema = mysql ? connection.getCatalog() : connection.getSchema();
        var matches = new ArrayList<String>();
        try (var statement = connection.prepareStatement("""
            SELECT tc.constraint_name,cc.check_clause FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
            WHERE tc.table_schema=? AND tc.table_name=? AND tc.constraint_type='CHECK'
            """)) {
            statement.setString(1, schema); statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String clause = rows.getString(2).toUpperCase(Locale.ROOT).replace("\\", "").replace("`", "");
                    if (clause.matches("(?s).*\\bSTATUS\\b.*") && clause.contains("'DRAFT'") && clause.contains("'WITHDRAWN'")) matches.add(rows.getString(1));
                }
            }
        }
        if (matches.size()!=1 || !matches.getFirst().matches("[A-Za-z0-9_]{1,64}")) throw new SQLException("Expected one workflow CHECK for " + table);
        String quote = connection.getMetaData().getIdentifierQuoteString().trim();
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + (mysql ? " DROP CHECK " : " DROP CONSTRAINT ") + quote + matches.getFirst() + quote);
        }
    }
}
