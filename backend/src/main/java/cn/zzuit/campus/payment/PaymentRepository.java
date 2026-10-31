package cn.zzuit.campus.payment;

import cn.zzuit.campus.common.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class PaymentRepository {
    static final String BILL_QUERY = """
        SELECT b.*, c.name AS category_name,u.username AS student_username,u.full_name AS student_name,
        u.campus_id AS student_campus_id,creator.full_name AS creator_name
        FROM payment_bill b JOIN payment_category c ON c.code=b.category_code
        JOIN app_user u ON u.id=b.student_id JOIN app_user creator ON creator.id=b.created_by
        """;
    final JdbcClient jdbc;
    public PaymentRepository(JdbcClient jdbc) { this.jdbc=jdbc; }
    PaymentService.Bill load(long id) {
        return jdbc.sql(BILL_QUERY+" WHERE b.id=:id").param("id",id).query(this::bill).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"BILL_NOT_FOUND","账单不存在"));
    }
    PaymentService.Bill lock(long id) {
        jdbc.sql("SELECT id FROM payment_bill WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"BILL_NOT_FOUND","账单不存在"));
        return load(id);
    }
    PaymentService.Bill bill(ResultSet r,int ignored) throws SQLException {
        return new PaymentService.Bill(r.getLong("id"),r.getString("order_no"),r.getLong("student_id"),r.getString("title"),
            r.getString("category_code"),r.getString("category_name"),r.getLong("amount_cents"),r.getString("status"),r.getString("trade_no"),
            r.getString("workflow_status"),r.getString("billing_period"),r.getObject("due_date",LocalDate.class),r.getString("basis"),
            r.getObject("standard_id",Long.class),r.getString("standard_name"),r.getObject("unit_price_cents",Long.class),r.getString("quantity"),
            r.getObject("source_basis_id",Long.class),r.getLong("created_by"),r.getString("creator_name"),r.getString("student_username"),
            r.getString("student_name"),r.getObject("student_campus_id",Long.class),time(r,"created_at"),time(r,"published_at"),time(r,"paid_at"),
            r.getString("refund_status"),r.getObject("pending_request_id",Long.class),time(r,"payment_started_at"),r.getInt("version"),
            r.getString("last_sync_error"),time(r,"last_checked_at"));
    }
    static LocalDateTime time(ResultSet r,String name) throws SQLException {
        var value=r.getTimestamp(name); return value==null?null:value.toLocalDateTime();
    }
}
