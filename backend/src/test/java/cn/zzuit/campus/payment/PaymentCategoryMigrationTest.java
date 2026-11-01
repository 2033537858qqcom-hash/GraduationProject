package cn.zzuit.campus.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PaymentCategoryMigrationTest {
    @Test void upgradingV37KeepsExistingStandardsRoomBalancesAndBills() throws Exception {
        String url="jdbc:h2:mem:fee-upgrade-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try(var source=new HikariDataSource()) {
            source.setJdbcUrl(url);source.setUsername("sa");source.setPassword("");
            Flyway.configure().dataSource(source).target("37").load().migrate();
            try(var connection=source.getConnection();var sql=connection.createStatement()) {
                sql.executeUpdate("INSERT INTO app_user(username,password_hash,full_name,identity_number) VALUES('migration-user','unused','测试用户','migration-user')");
                sql.executeUpdate("INSERT INTO payment_standard(request_key,name,category_code,unit,unit_price_cents,created_by,reason,status) VALUES('old-standard','旧电价','ELECTRICITY','度',61,1,'历史价格','ACTIVE')");
                sql.executeUpdate("INSERT INTO dorm_building(campus_id,code,name,provenance) VALUES(1,'TEST','宿舍','DEMO')");
                sql.executeUpdate("INSERT INTO dorm_room(building_id,code,name,floor_label) VALUES(1,'101','101','1层')");
                sql.executeUpdate("INSERT INTO dorm_utility_meter(room_id,category_code,meter_code,standard_id,balance_quantity) VALUES(1,'ELECTRICITY','OLD-METER',1,12.345)");
                sql.executeUpdate("INSERT INTO payment_bill(request_key,order_no,student_id,created_by,title,amount_cents,status,trade_no,paid_at,category_code,standard_id,standard_name,unit_price_cents,quantity) VALUES('old-bill','old-order',1,1,'旧电费',1234,'PAID','old-trade',CURRENT_TIMESTAMP,'ELECTRICITY',1,'旧电价',61,'20.23')");
            }
            var flyway=Flyway.configure().dataSource(source).load();flyway.migrate();flyway.migrate();
            try(var connection=source.getConnection();var sql=connection.createStatement()) {
                try(var row=sql.executeQuery("SELECT name,unit_price_cents,status,effective_from,effective_to FROM payment_standard WHERE id=1")) {
                    assertThat(row.next()).isTrue();assertThat(row.getString(1)).isEqualTo("旧电价");assertThat(row.getLong(2)).isEqualTo(61);assertThat(row.getString(3)).isEqualTo("ACTIVE");assertThat(row.getDate(4)).isNull();assertThat(row.getDate(5)).isNull();
                }
                try(var row=sql.executeQuery("SELECT balance_quantity FROM dorm_utility_meter WHERE id=1")) {assertThat(row.next()).isTrue();assertThat(row.getBigDecimal(1)).isEqualByComparingTo("12.345");}
                try(var row=sql.executeQuery("SELECT amount_cents,status,trade_no,standard_name,unit_price_cents FROM payment_bill WHERE id=1")) {
                    assertThat(row.next()).isTrue();assertThat(row.getLong(1)).isEqualTo(1234);assertThat(row.getString(2)).isEqualTo("PAID");assertThat(row.getString(3)).isEqualTo("old-trade");assertThat(row.getString(4)).isEqualTo("旧电价");assertThat(row.getLong(5)).isEqualTo(61);
                }
                try(var row=sql.executeQuery("SELECT enabled FROM payment_category WHERE code='ELECTRICITY'")) {assertThat(row.next()).isTrue();assertThat(row.getBoolean(1)).isTrue();}
            }
        }
    }
    @Test void upgradingExistingBillsPreservesFinancialDataAndAssignsOtherCategory() throws Exception {
        String url = "jdbc:h2:mem:category-upgrade-" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (var dataSource = new HikariDataSource()) {
        dataSource.setJdbcUrl(url);
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        Flyway.configure().dataSource(dataSource).target("34").load().migrate();
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO app_user(username,password_hash,full_name,identity_number) VALUES('legacy','unused','旧账单测试','demo-legacy')");
            sql.executeUpdate("INSERT INTO payment_bill(request_key,order_no,student_id,created_by,title,amount_cents,status,trade_no,paid_at) "
                + "VALUES('legacy-paid-request','legacy-paid-order',1,1,'原有已付账单',1234,'PAID','legacy-trade',CURRENT_TIMESTAMP)");
            sql.executeUpdate("INSERT INTO payment_bill(request_key,order_no,student_id,created_by,title,amount_cents) "
                + "VALUES('legacy-unpaid-request','legacy-unpaid-order',1,1,'原有待付账单',5678)");
        }
        var flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        flyway.migrate();
        try (var connection = dataSource.getConnection(); var sql = connection.createStatement()) {
            try (var rows = sql.executeQuery("SELECT category_code,title,amount_cents,status,trade_no,paid_at FROM payment_bill ORDER BY id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("category_code")).isEqualTo("OTHER");
                assertThat(rows.getString("title")).isEqualTo("原有已付账单");
                assertThat(rows.getLong("amount_cents")).isEqualTo(1234);
                assertThat(rows.getString("status")).isEqualTo("PAID");
                assertThat(rows.getString("trade_no")).isEqualTo("legacy-trade");
                assertThat(rows.getTimestamp("paid_at")).isNotNull();
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("category_code")).isEqualTo("OTHER");
                assertThat(rows.getString("title")).isEqualTo("原有待付账单");
                assertThat(rows.getLong("amount_cents")).isEqualTo(5678);
                assertThat(rows.getString("status")).isEqualTo("UNPAID");
                assertThat(rows.next()).isFalse();
            }
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM payment_category")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(8);
            }
        }
        }
    }
}
