package cn.zzuit.campus.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class PaymentCategoryMigrationTest {
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
