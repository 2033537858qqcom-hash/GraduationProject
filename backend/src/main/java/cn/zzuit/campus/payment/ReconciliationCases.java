package cn.zzuit.campus.payment;

import cn.zzuit.campus.identity.CampusPrincipal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores investigation records only; never treats imported statements as settlement evidence. */
@Service
public class ReconciliationCases {
    public record ImportInput(String requestKey,String csv) {}
    public record Case(long id,long batchId,int line,String result,String orderNo,String amount,String tradeNo,String statementStatus,
        Long billId,Long localAmountCents,String localStatus,String explanation,String status,Long assigneeId,String assigneeName,
        String lastNote,int version,LocalDateTime createdAt,LocalDateTime updatedAt) {}
    public record Batch(long batchId,List<Case> items) {}
    public record Page(List<Case> items,long total,int page,int pageSize) {}
    public record History(String action,String actorName,String reason,LocalDateTime createdAt) {}
    private final JdbcClient jdbc;
    private final PaymentAccess access;
    private final PaymentEvents events;
    private final PaymentReconciliation reconciliation;
    private static final String CASE_SQL="SELECT c.*,u.full_name assignee_name FROM payment_reconciliation_case c LEFT JOIN app_user u ON u.id=c.assignee_id ";
    public ReconciliationCases(JdbcClient jdbc,PaymentAccess access,PaymentEvents events,PaymentReconciliation reconciliation) { this.jdbc=jdbc;this.access=access;this.events=events;this.reconciliation=reconciliation; }
    private Case row(ResultSet r,int n) throws SQLException {
        return new Case(r.getLong("id"),r.getLong("batch_id"),r.getInt("line_number"),r.getString("result"),r.getString("order_no"),r.getString("statement_amount"),r.getString("statement_trade_no"),r.getString("statement_status"),r.getObject("bill_id",Long.class),r.getObject("local_amount_cents",Long.class),r.getString("local_status"),r.getString("explanation"),r.getString("status"),r.getObject("assignee_id",Long.class),r.getString("assignee_name"),r.getString("last_note"),r.getInt("version"),PaymentRepository.time(r,"created_at"),PaymentRepository.time(r,"updated_at"));
    }
    private Case get(long id) { return jdbc.sql(CASE_SQL+"WHERE c.id=:id").param("id",id).query(this::row).optional().orElseThrow(()->PaymentService.error(HttpStatus.NOT_FOUND,"RECON_CASE_NOT_FOUND","对账事项不存在")); }
    private List<Case> batchItems(long id) { return jdbc.sql(CASE_SQL+"WHERE c.batch_id=:id ORDER BY c.line_number").param("id",id).query(this::row).list(); }
    private static String limit(String value,int max) {
        if(value==null) return "";
        if(value.length()<=max) return value;
        int end=Character.isHighSurrogate(value.charAt(max-1))?max-1:max;
        return value.substring(0,end);
    }
    @Transactional public Batch create(CampusPrincipal user,ImportInput input) {
        access.require(user,"payment:manage");PaymentAccess.key(input.requestKey());
        var previous=jdbc.sql("SELECT id FROM payment_reconciliation_batch WHERE request_key=:key").param("key",input.requestKey()).query(Long.class).optional();
        if(previous.isPresent() && events.repeated(user,input.requestKey(),"RECON_BATCH",previous.get(),"CREATE",input)) return new Batch(previous.get(),batchItems(previous.get()));
        var preview=reconciliation.compare(user,new PaymentReconciliation.Input(input.csv()));
        var anomalies=preview.items().stream().filter(item->!item.result().equals("MATCH")).toList();
        if(anomalies.isEmpty()) throw PaymentService.error(HttpStatus.BAD_REQUEST,"NO_RECON_DIFFERENCES","本次没有需要跟踪的差异");
        var key=new GeneratedKeyHolder();jdbc.sql("INSERT INTO payment_reconciliation_batch(request_key,created_by) VALUES(:key,:user)").param("key",input.requestKey()).param("user",user.id()).update(key,"id");
        long batch=key.getKey().longValue();
        for(var item:anomalies) jdbc.sql("INSERT INTO payment_reconciliation_case(batch_id,line_number,result,order_no,statement_amount,statement_trade_no,statement_status,bill_id,local_amount_cents,local_status,explanation) VALUES(:batch,:line,:result,:order,:amount,:trade,:status,:bill,:localAmount,:localStatus,:explanation)")
            .param("batch",batch).param("line",item.line()).param("result",item.result()).param("order",limit(item.orderNo(),96)).param("amount",limit(item.amount(),40)).param("trade",limit(item.tradeNo(),96)).param("status",limit(item.statementStatus(),80)).param("bill",item.billId()).param("localAmount",item.localAmountCents()).param("localStatus",limit(item.localStatus(),32)).param("explanation",limit(item.explanation(),500)).update();
        var result=new Batch(batch,batchItems(batch));
        events.history(user.id(),"RECON_BATCH",batch,"CREATE",input.requestKey(),input,"保存对账差异，共 "+anomalies.size()+" 项",java.util.Map.of("batchId",batch,"count",anomalies.size()));
        for(var item:result.items()) events.history(user.id(),"RECON_CASE",item.id(),"CREATE",null,null,"从对账结果建立待核查事项",item);
        return result;
    }
    public Page list(CampusPrincipal user,int page,int size,String status) {
        access.finance(user);if(page<1 || page>100000 || size<1 || size>50) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_PAGE","分页参数无效");
        if(status==null) status="";
        if(!status.isEmpty() && !List.of("OPEN","IN_PROGRESS","PENDING_REVIEW","CLOSED").contains(status)) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_RECON_STATUS","请选择有效的对账事项状态");
        String where=status.isEmpty()?"":" WHERE c.status=:status";
        var count=jdbc.sql("SELECT COUNT(*) FROM payment_reconciliation_case c"+where);
        var query=jdbc.sql(CASE_SQL+where+" ORDER BY c.id DESC LIMIT :limit OFFSET :offset").param("limit",size).param("offset",((long)page-1)*size);
        if(!status.isEmpty()) { count=count.param("status",status);query=query.param("status",status); }
        long total=count.query(Long.class).single();
        var items=query.query(this::row).list();
        return new Page(items,total,page,size);
    }
    public Case detail(CampusPrincipal user,long id) { access.finance(user);return get(id); }
    public List<History> history(CampusPrincipal user,long id) {
        access.finance(user);get(id);
        return jdbc.sql("SELECT h.action,h.reason,h.created_at,u.full_name FROM payment_history h LEFT JOIN app_user u ON u.id=h.actor_id WHERE h.entity_type='RECON_CASE' AND h.entity_id=:id ORDER BY h.id DESC").param("id",id).query((r,n)->new History(r.getString("action"),r.getString("full_name"),r.getString("reason"),PaymentRepository.time(r,"created_at"))).list();
    }
    @Transactional public Case act(CampusPrincipal user,long id,FinanceModels.Action input) {
        if(input.action()==null || !List.of("START","TAKE_OVER","SUBMIT","CLOSE","RETURN","REOPEN").contains(input.action())) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_RECON_ACTION","请选择有效的核查操作");
        boolean review=List.of("CLOSE","RETURN").contains(input.action());access.require(user,review?"payment:review":"payment:manage");
        jdbc.sql("SELECT id FROM payment_reconciliation_case WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).optional().orElseThrow(()->PaymentService.error(HttpStatus.NOT_FOUND,"RECON_CASE_NOT_FOUND","对账事项不存在"));
        var old=get(id);if(events.repeated(user,input.requestKey(),"RECON_CASE",id,input.action(),input)) return old;
        PaymentAccess.version(old.version(),input.version());String note=PaymentAccess.text(input.reason(),5,500,"核查说明");
        String state;
        switch(input.action()) {
            case "START" -> { if(!old.status().equals("OPEN")) throw changed(); state="IN_PROGRESS"; }
            case "TAKE_OVER" -> { if(!old.status().equals("IN_PROGRESS") || java.util.Objects.equals(old.assigneeId(),user.id())) throw changed();state="IN_PROGRESS"; }
            case "SUBMIT" -> { if(!old.status().equals("IN_PROGRESS") || !java.util.Objects.equals(old.assigneeId(),user.id())) throw changed();access.otherReviewer(user.id());state="PENDING_REVIEW"; }
            case "CLOSE","RETURN" -> { if(!old.status().equals("PENDING_REVIEW")) throw changed();if(java.util.Objects.equals(old.assigneeId(),user.id())) throw PaymentService.error(HttpStatus.FORBIDDEN,"SELF_REVIEW","不能复核本人核查的对账事项");state=input.action().equals("CLOSE")?"CLOSED":"IN_PROGRESS"; }
            case "REOPEN" -> { if(!old.status().equals("CLOSED")) throw changed();state="OPEN"; }
            default -> throw changed();
        }
        Long assignee=List.of("START","TAKE_OVER").contains(input.action())?user.id():input.action().equals("REOPEN")?null:old.assigneeId();
        if(jdbc.sql("UPDATE payment_reconciliation_case SET status=:status,assignee_id=:assignee,last_note=:note,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version").param("status",state).param("assignee",assignee).param("note",note).param("id",id).param("version",old.version()).update()!=1) throw changed();
        var result=get(id);events.history(user.id(),"RECON_CASE",id,input.action(),input.requestKey(),input,note,result);return result;
    }
    private cn.zzuit.campus.common.ApiException changed() { return PaymentService.error(HttpStatus.CONFLICT,"RECON_CASE_CHANGED","事项状态或处理人已变化，请刷新后重试"); }
}
