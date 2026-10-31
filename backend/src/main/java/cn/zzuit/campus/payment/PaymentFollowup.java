package cn.zzuit.campus.payment;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(name="app.payment.followup.enabled",havingValue="true",matchIfMissing=true)
public class PaymentFollowup {
    private static final Logger log=LoggerFactory.getLogger(PaymentFollowup.class);
    private final PaymentRepository repository;
    private final PaymentService payments;
    private final PaymentManagementService finance;
    private final TransactionTemplate tx;
    private final Environment environment;
    public PaymentFollowup(PaymentRepository repository,PaymentService payments,PaymentManagementService finance,TransactionTemplate tx,Environment environment) {
        this.repository=repository;this.payments=payments;this.finance=finance;this.tx=tx;this.environment=environment;
    }
    @Scheduled(fixedDelayString="${app.payment.followup.interval-ms:60000}",initialDelayString="${app.payment.followup.initial-delay-ms:60000}")
    public void reconcile() {
        if(!environment.getProperty("app.demo.enabled",Boolean.class,false)) return;
        if(payments.enabled() && payments.remote()) {
            for(long id:repository.jdbc.sql("SELECT id FROM payment_bill WHERE status='UNPAID' AND payment_started_at IS NOT NULL AND (last_checked_at IS NULL OR last_checked_at<:before) ORDER BY COALESCE(last_checked_at,created_at),id LIMIT 50")
                .param("before",Timestamp.valueOf(LocalDateTime.now().minusMinutes(1))).query(Long.class).list()) {
                try { payments.synchronize(id,false); }
                catch(RuntimeException failure) { log.warn("Payment followup failed: billId={}, failureType={}",id,failure.getClass().getSimpleName()); }
            }
            for(long id:repository.jdbc.sql("SELECT id FROM payment_request WHERE status='PROCESSING' AND last_attempt_at<:before ORDER BY id LIMIT 20")
                .param("before",Timestamp.valueOf(LocalDateTime.now().minusMinutes(5))).query(Long.class).list()) {
                try { finance.executeRequest(id); }
                catch(RuntimeException failure) { log.warn("Approved finance request recovery failed: requestId={}, failureType={}",id,failure.getClass().getSimpleName()); }
            }
        }
    }
    @Scheduled(cron="0 0 9 * * *",zone="Asia/Shanghai")
    public void remindOverdue() {
        if(!environment.getProperty("app.demo.enabled",Boolean.class,false)) return;
        var ids=repository.jdbc.sql("SELECT id FROM payment_bill WHERE workflow_status='PUBLISHED' AND status='UNPAID' AND due_date<CURRENT_DATE AND pending_request_id IS NULL").query(Long.class).list();
        for(long id:ids) tx.executeWithoutResult(ignored->{
            var bill=repository.lock(id);if(bill.workflowStatus().equals("PUBLISHED") && bill.status().equals("UNPAID") && bill.pendingRequestId()==null) finance.remind(bill);
        });
    }
}
