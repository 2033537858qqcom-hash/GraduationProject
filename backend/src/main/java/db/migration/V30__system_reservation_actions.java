package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** An automated expiry has no human actor; retain its explicit system display name. */
public class V30__system_reservation_actions extends BaseJavaMigration {
    @Override public Integer getChecksum() { return 30001; }
    @Override public void migrate(Context context) throws Exception {
        boolean mysql=context.getConnection().getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
        try(var statement=context.getConnection().createStatement()) {
            statement.execute(mysql?"ALTER TABLE venue_reservation_action MODIFY COLUMN actor_id BIGINT NULL"
                :"ALTER TABLE venue_reservation_action ALTER COLUMN actor_id DROP NOT NULL");
        }
    }
}
