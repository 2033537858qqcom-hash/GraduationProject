package cn.zzuit.campus.payment;

import static cn.zzuit.campus.payment.FinanceModels.*;

import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared school finance queue. Preparers and independent reviewers have distinct capabilities. */
@Service
public class PaymentManagementService {
    private static final Logger log=LoggerFactory.getLogger(PaymentManagementService.class);
    private final PaymentRepository repository;
    private final JdbcClient jdbc;
    private final PaymentAccess access;
    private final PaymentConfigurationService configuration;
    private final PaymentService payments;
    private final PaymentEvents events;
    private final TransactionTemplate tx;
    public PaymentManagementService(PaymentRepository repository,PaymentAccess access,PaymentConfigurationService configuration,PaymentService payments,PaymentEvents events,TransactionTemplate tx) {
        this.repository=repository;this.jdbc=repository.jdbc;this.access=access;this.configuration=configuration;this.payments=payments;this.events=events;this.tx=tx;
    }
    private record Validated(long student,String category,String title,String period,LocalDate dueDate,String basis,
        Long standard,String standardName,Long unitPrice,String quantity,long cents,Long source) {}
    private Validated validate(BillInput input,Long editing) {
        String category=input.categoryCode()==null?"OTHER":input.categoryCode();access.category(category);
        String username=PaymentAccess.text(input.username(),1,64,"学生账号");
        var student=jdbc.sql("SELECT u.id,u.campus_id,c.major_id FROM app_user u JOIN user_role ur ON ur.user_id=u.id AND ur.role_code='STUDENT' LEFT JOIN class_group c ON c.id=u.class_group_id WHERE u.username=:name AND u.enabled=TRUE AND u.demo=TRUE")
            .param("name",username).query((r,n)->new Long[]{r.getLong(1),r.getObject(2,Long.class),r.getObject(3,Long.class)}).optional()
            .orElseThrow(()->PaymentService.error(HttpStatus.NOT_FOUND,"STUDENT_NOT_FOUND","未找到可用的演示学生"));
        String title=PaymentAccess.text(input.title(),1,120,"费用名称"),period=PaymentAccess.text(input.billingPeriod(),1,80,"收费账期"),basis=PaymentAccess.text(input.basis(),10,1000,"收费依据");
        if(input.dueDate()==null || input.dueDate().isBefore(LocalDate.now())) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_DUE_DATE","请填写今天或以后的缴费截止日期");
        String quantity=PaymentAccess.quantity(input.quantity()==null?"1":input.quantity()).stripTrailingZeros().toPlainString();
        Long standardId=null,unitPrice=null;String standardName=null;long cents;
        if(input.standardId()!=null) {
            var standard=configuration.standard(input.standardId());
            if(!standard.status().equals("ACTIVE")) throw PaymentService.error(HttpStatus.CONFLICT,"STANDARD_NOT_ACTIVE","收费标准尚未复核生效或已停用");
            if(!standard.categoryCode().equals(category) || (standard.campusId()!=null && !standard.campusId().equals(student[1])) || (standard.majorId()!=null && !standard.majorId().equals(student[2])))
                throw PaymentService.error(HttpStatus.BAD_REQUEST,"STANDARD_SCOPE_MISMATCH","收费标准不适用于该学生、校区或分类");
            try { cents=new BigDecimal(quantity).multiply(BigDecimal.valueOf(standard.unitPriceCents())).setScale(0,RoundingMode.HALF_UP).longValueExact(); }
            catch(ArithmeticException invalid) { throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_AMOUNT","计算金额超出允许范围"); }
            standardId=standard.id();unitPrice=standard.unitPriceCents();standardName=standard.name();
        } else cents=input.amountCents()==null?0:input.amountCents();
        if(cents<1 || cents>100000000L) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_AMOUNT","账单金额须为0.01—1000000元");
        if(input.basisId()!=null) {
            jdbc.sql("SELECT id FROM payment_basis WHERE id=:id FOR UPDATE").param("id",input.basisId()).query(Long.class).optional()
                .orElseThrow(()->PaymentService.error(HttpStatus.NOT_FOUND,"BASIS_NOT_FOUND","收费依据不存在"));
            var source=configuration.basis(input.basisId());
            if((!source.status().equals("SUBMITTED") && (editing==null || !editing.equals(source.billId()))) || !source.username().equals(username) ||
                !source.categoryCode().equals(category) || !source.billingPeriod().equals(period) || new BigDecimal(source.quantity()).compareTo(new BigDecimal(quantity))!=0)
                throw PaymentService.error(HttpStatus.CONFLICT,"BASIS_MISMATCH","收费依据已使用或与学生、分类、账期、用量不一致");
        }
        return new Validated(student[0],category,title,period,input.dueDate(),basis,standardId,standardName,unitPrice,quantity,cents,input.basisId());
    }
    @Transactional public PaymentService.Bill create(CampusPrincipal user,BillInput input) {
        access.require(user,"payment:manage");PaymentAccess.key(input.requestKey());
        var previous=jdbc.sql("SELECT id FROM payment_bill WHERE request_key=:key").param("key",input.requestKey()).query(Long.class).optional();
        if(previous.isPresent() && events.repeated(user,input.requestKey(),"BILL",previous.get(),"CREATE",input)) return repository.load(previous.get());
        PaymentAccess.text(input.requestKey(),20,80,"请求标识");var value=validate(input,null);var key=new GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO payment_bill(request_key,order_no,student_id,created_by,title,amount_cents,category_code,workflow_status,billing_period,due_date,basis,standard_id,standard_name,unit_price_cents,quantity,source_basis_id)
            VALUES(:key,:order,:student,:actor,:title,:cents,:category,'DRAFT',:period,:due,:basis,:standard,:standardName,:unitPrice,:quantity,:source)
            """).param("key",input.requestKey()).param("order",UUID.randomUUID().toString().replace("-",""))
            .param("actor",user.id()).params(parameters(value)).update(key,"id");
        long id=key.getKey().longValue();acceptBasis(value.source(),id);var result=repository.load(id);
        events.history(user.id(),"BILL",id,"CREATE",input.requestKey(),input,value.basis(),result);return result;
    }
    @Transactional public List<PaymentService.Bill> batch(CampusPrincipal user,BatchInput input) {
        access.require(user,"payment:manage");PaymentAccess.key(input.requestKey());
        if(input.template()==null || input.usernames()==null || input.usernames().isEmpty() || input.usernames().size()>100 || input.template().basisId()!=null)
            throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_BATCH","批量建单需要1—100名学生，不能复用单条业务收费依据");
        var names=input.usernames().stream().map(name->PaymentAccess.text(name,1,64,"学生账号")).toList();
        if(new java.util.HashSet<>(names).size()!=names.size()) throw PaymentService.error(HttpStatus.BAD_REQUEST,"DUPLICATE_STUDENT","批量学生账号不能重复");
        var result=new ArrayList<PaymentService.Bill>();var v=input.template();
        for(var name:names) {
            String key=UUID.nameUUIDFromBytes((input.requestKey()+"\n"+name).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            result.add(create(user,new BillInput(key,name,v.categoryCode(),v.title(),v.billingPeriod(),v.dueDate(),v.standardId(),v.quantity(),v.amountCents(),v.basis(),null,null,null)));
        }return result;
    }
    private Map<String,Object> parameters(Validated v) {
        var p=new HashMap<String,Object>();p.put("student",v.student());p.put("title",v.title());p.put("cents",v.cents());p.put("category",v.category());p.put("period",v.period());p.put("due",v.dueDate());p.put("basis",v.basis());
        p.put("standard",v.standard());p.put("standardName",v.standardName());p.put("unitPrice",v.unitPrice());p.put("quantity",v.quantity());p.put("source",v.source());return p;
    }
    private void acceptBasis(Long source,long bill) {
        if(source!=null) jdbc.sql("UPDATE payment_basis SET status='ACCEPTED',bill_id=:bill,version=version+1 WHERE id=:source AND bill_id IS NULL").param("bill",bill).param("source",source).update();
    }
    @Transactional public PaymentService.Bill update(CampusPrincipal user,long id,BillInput input) {
        access.require(user,"payment:manage");var current=repository.lock(id);editable(current);
        if(input.version()==null) throw PaymentService.error(HttpStatus.BAD_REQUEST,"VERSION_REQUIRED","请携带当前账单版本");
        PaymentAccess.version(current.version(),input.version());var value=validate(input,id);
        if(!java.util.Objects.equals(current.sourceBasisId(),value.source())) throw PaymentService.error(HttpStatus.CONFLICT,"BASIS_IMMUTABLE","已关联的原始收费依据不能替换，请作废草稿后重新建单");
        jdbc.sql("UPDATE payment_bill SET student_id=:student,title=:title,amount_cents=:cents,category_code=:category,billing_period=:period,due_date=:due,basis=:basis,standard_id=:standard,standard_name=:standardName,unit_price_cents=:unitPrice,quantity=:quantity,version=version+1 WHERE id=:id")
            .params(parameters(value)).param("id",id).update();
        String reason=input.reason()==null?value.basis():PaymentAccess.text(input.reason(),5,500,"修改说明");
        var result=repository.load(id);events.history(user.id(),"BILL",id,"UPDATE",null,null,reason,result);return result;
    }
    private void editable(PaymentService.Bill bill) {
        if(!List.of("DRAFT","RETURNED").contains(bill.workflowStatus())) throw PaymentService.error(HttpStatus.CONFLICT,"BILL_IMMUTABLE","只有草稿或退回账单可以修改；已发布账单须走作废、减免或退款流程");
    }
    public PaymentService.Bill get(CampusPrincipal user,long id) { access.finance(user);return repository.load(id); }
    public Detail detail(CampusPrincipal user,long id) {
        var bill=get(user,id);var actions=new ArrayList<String>();
        if(user.permissions().contains("payment:manage")) {
            if(List.of("DRAFT","RETURNED").contains(bill.workflowStatus())) actions.addAll(List.of("EDIT","SUBMIT","VOID_DRAFT"));
            if(bill.workflowStatus().equals("PUBLISHED") && bill.pendingRequestId()==null && bill.status().equals("UNPAID")) actions.addAll(List.of("REMIND","REQUEST_VOID","REQUEST_WAIVER"));
            if(bill.status().equals("PAID") && bill.pendingRequestId()==null && !bill.refundStatus().equals("REFUNDED")) actions.add("REQUEST_REFUND");
        }
        if(user.permissions().contains("payment:review") && bill.workflowStatus().equals("PENDING_REVIEW") && !preparedBy(id,user.id())) actions.addAll(List.of("APPROVE","RETURN"));
        if(bill.paymentStartedAt()!=null && bill.status().equals("UNPAID")) actions.add("RECONCILE");
        return new Detail(bill,history(user,id),requests(user,id),actions);
    }
    private boolean preparedBy(long id,long actor) {
        return jdbc.sql("SELECT COUNT(*) FROM payment_history WHERE entity_type='BILL' AND entity_id=:id AND actor_id=:actor AND action IN ('CREATE','UPDATE')")
            .param("id",id).param("actor",actor).query(Long.class).single()>0 || repository.load(id).createdBy()==actor;
    }
    private void reviewerAvailable(long id) {
        if(jdbc.sql("SELECT COUNT(DISTINCT u.id) FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN role_permission rp ON rp.role_code=ur.role_code JOIN payment_bill b ON b.id=:id WHERE u.enabled=TRUE AND u.must_change_password=FALSE AND rp.permission_code='payment:review' AND u.id<>b.created_by AND NOT EXISTS(SELECT 1 FROM payment_history h WHERE h.entity_type='BILL' AND h.entity_id=b.id AND h.actor_id=u.id AND h.action IN ('CREATE','UPDATE'))")
            .param("id",id).query(Long.class).single()==0) throw PaymentService.error(HttpStatus.CONFLICT,"NO_FINANCE_REVIEWER","该账单暂无未参与经办的有效财务复核人员，请先配置岗位");
    }
    public PaymentService.Bill action(CampusPrincipal user,long id,Action input) {
        if(input.action()==null) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_PAYMENT_ACTION","请指定有效的账单操作");
        if("RECONCILE".equals(input.action())) { access.finance(user);return payments.synchronize(id,true); }
        boolean review=List.of("APPROVE","RETURN").contains(input.action());access.require(user,review?"payment:review":"payment:manage");
        return tx.execute(ignored->{
            access.require(user,review?"payment:review":"payment:manage");
            var bill=repository.lock(id);if(events.repeated(user,input.requestKey(),"BILL",id,input.action(),input)) return bill;
            PaymentAccess.version(bill.version(),input.version());String reason=PaymentAccess.text(input.reason(),5,500,"操作说明");String state=bill.workflowStatus();
            switch(input.action()) {
                case "SUBMIT" -> { editable(bill);reviewerAvailable(id);state="PENDING_REVIEW"; }
                case "APPROVE", "RETURN" -> {
                    if(!bill.workflowStatus().equals("PENDING_REVIEW")) throw PaymentService.error(HttpStatus.CONFLICT,"INVALID_BILL_STATE","账单当前不在待复核阶段");
                    if(preparedBy(id,user.id())) throw PaymentService.error(HttpStatus.FORBIDDEN,"SELF_REVIEW","不能复核本人创建或修改过的账单");
                    if(input.action().equals("APPROVE") && bill.dueDate().isBefore(LocalDate.now())) throw PaymentService.error(HttpStatus.CONFLICT,"DUE_DATE_PASSED","截止日期已过，请退回经办人员调整后重新送审");
                    state=input.action().equals("APPROVE")?"PUBLISHED":"RETURNED";
                }
                case "VOID_DRAFT" -> { editable(bill);state="VOIDED"; }
                case "REMIND" -> {
                    if(!bill.workflowStatus().equals("PUBLISHED") || !bill.status().equals("UNPAID") || bill.pendingRequestId()!=null) throw PaymentService.error(HttpStatus.CONFLICT,"BILL_NOT_REMINDABLE","仅可提醒当前正常待缴账单");
                    remind(bill);
                }
                default -> throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_PAYMENT_ACTION","不支持的账单操作");
            }
            if(!input.action().equals("REMIND")) {
                jdbc.sql("UPDATE payment_bill SET workflow_status=:state,reviewed_by=:reviewer,published_at=CASE WHEN :published=TRUE THEN CURRENT_TIMESTAMP ELSE published_at END,version=version+1 WHERE id=:id")
                    .param("state",state).param("reviewer",review?user.id():null).param("published",state.equals("PUBLISHED")).param("id",id).update();
            }
            var result=repository.load(id);events.history(user.id(),"BILL",id,input.action(),input.requestKey(),input,reason,result);
            if(input.action().equals("SUBMIT")) events.finance("payment:review",user.id(),"收费账单待复核","PAYMENT",id,bill.title()+"已送审，请核对账期、收费依据与金额。");
            if(input.action().equals("APPROVE")) events.student(bill.studentId(),"有新的应缴账单",id,bill.title()+" · "+bill.billingPeriod()+"，请核对后缴费。");
            if(input.action().equals("RETURN")) events.finance("payment:manage",0,"收费账单已退回","PAYMENT",id,reason);
            if(input.action().equals("VOID_DRAFT") && bill.sourceBasisId()!=null)
                jdbc.sql("UPDATE payment_basis SET status='SUBMITTED',bill_id=NULL,version=version+1 WHERE id=:id").param("id",bill.sourceBasisId()).update();
            return result;
        });
    }
    void remind(PaymentService.Bill bill) {
        if(jdbc.sql("SELECT COUNT(*) FROM payment_reminder WHERE bill_id=:id AND reminder_date=:day").param("id",bill.id()).param("day",LocalDate.now()).query(Long.class).single()==0) {
            jdbc.sql("INSERT INTO payment_reminder(bill_id,reminder_date) VALUES(:id,:day)").param("id",bill.id()).param("day",LocalDate.now()).update();
            events.student(bill.studentId(),"缴费提醒",bill.id(),bill.title()+"仍待缴费，截止日期为"+bill.dueDate()+"；如有疑问请联系财务人员。");
        }
    }
    public List<History> history(CampusPrincipal user,long bill) { access.finance(user);repository.load(bill);return historyRows(bill); }
    List<History> historyRows(long bill) {
        return jdbc.sql("SELECT h.*,u.full_name AS actor_name FROM payment_history h LEFT JOIN app_user u ON u.id=h.actor_id WHERE h.entity_type='BILL' AND h.entity_id=:id ORDER BY h.id DESC")
            .param("id",bill).query((r,n)->new History(r.getLong("id"),r.getString("action"),r.getString("actor_name")==null?"支付平台确认":r.getString("actor_name"),r.getString("reason"),r.getString("snapshot"),PaymentRepository.time(r,"created_at"))).list();
    }
    private static final String REQUEST_SQL="SELECT r.*,u.full_name AS requester_name FROM payment_request r JOIN app_user u ON u.id=r.requested_by";
    Request request(long id) { return jdbc.sql(REQUEST_SQL+" WHERE r.id=:id").param("id",id).query(this::requestRow).optional().orElseThrow(()->PaymentService.error(HttpStatus.NOT_FOUND,"PAYMENT_REQUEST_NOT_FOUND","财务处理申请不存在")); }
    private Request requestRow(ResultSet r,int n) throws SQLException {
        return new Request(r.getLong("id"),r.getLong("bill_id"),r.getString("type"),r.getString("reason"),r.getString("status"),r.getLong("requested_by"),r.getString("requester_name"),r.getObject("reviewed_by",Long.class),r.getString("gateway_request_no"),r.getString("last_error"),r.getInt("version"),PaymentRepository.time(r,"created_at"));
    }
    public List<Request> requests(CampusPrincipal user,Long bill) {
        access.finance(user);var query=jdbc.sql(REQUEST_SQL+(bill==null?"":" WHERE r.bill_id=:bill")+" ORDER BY r.id DESC");
        if(bill!=null) query=query.param("bill",bill);return query.query(this::requestRow).list();
    }
    @Transactional public Request createRequest(CampusPrincipal user,long id,RequestInput input,boolean student) {
        PaymentAccess.key(input.requestKey());
        if(student) { payments.owned(user,id);if(!"REFUND".equals(input.type())) throw PaymentService.error(HttpStatus.FORBIDDEN,"FORBIDDEN","学生仅可申请本人已付账单退款"); }
        else access.require(user,"payment:manage");
        if(input.type()==null || !List.of("VOID","WAIVER","REFUND").contains(input.type())) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_REQUEST_TYPE","请指定作废、减免或退款类型");
        var bill=repository.lock(id);var previous=jdbc.sql("SELECT id FROM payment_request WHERE request_key=:key").param("key",input.requestKey()).query(Long.class).optional();
        if(previous.isPresent() && events.repeated(user,input.requestKey(),"REQUEST",previous.get(),"CREATE",input)) return request(previous.get());
        PaymentAccess.text(input.requestKey(),20,80,"请求标识");PaymentAccess.version(bill.version(),input.version());String reason=PaymentAccess.text(input.reason(),5,500,"申请原因");access.otherReviewer(user.id());
        if(bill.pendingRequestId()!=null || bill.refundStatus().equals("REFUNDED")) throw PaymentService.error(HttpStatus.CONFLICT,"REQUEST_ALREADY_PENDING","账单已有处理申请或已全额退款");
        boolean refund="REFUND".equals(input.type());
        if(refund ? !bill.status().equals("PAID") : (!List.of("VOID","WAIVER").contains(input.type()) || !bill.workflowStatus().equals("PUBLISHED") || !bill.status().equals("UNPAID")))
            throw PaymentService.error(HttpStatus.CONFLICT,"REQUEST_NOT_ALLOWED","该账单状态不支持此申请");
        var key=new GeneratedKeyHolder();jdbc.sql("INSERT INTO payment_request(request_key,bill_id,type,requested_by,reason,gateway_request_no) VALUES(:key,:bill,:type,:actor,:reason,:gateway)")
            .param("key",input.requestKey()).param("bill",id).param("type",input.type()).param("actor",user.id()).param("reason",reason).param("gateway","RF-"+UUID.randomUUID().toString().replace("-","")).update(key,"id");
        long requestId=key.getKey().longValue();jdbc.sql("UPDATE payment_bill SET pending_request_id=:request,refund_status=CASE WHEN :refund=TRUE THEN 'REQUESTED' ELSE refund_status END,version=version+1 WHERE id=:id")
            .param("request",requestId).param("refund",refund).param("id",id).update();
        var result=request(requestId);events.history(user.id(),"REQUEST",requestId,"CREATE",input.requestKey(),input,reason,result);
        events.history(user.id(),"BILL",id,input.type()+"_REQUESTED",null,null,reason,repository.load(id));
        events.finance("payment:review",user.id(),"有财务处理申请待复核","PAYMENT",id,input.type()+" · "+reason);return result;
    }
    public Request requestAction(CampusPrincipal user,long id,Action input) {
        if(input.action()==null) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_PAYMENT_ACTION","请指定复核操作");
        boolean retry="RETRY".equals(input.action());if(retry) access.finance(user);else access.require(user,"payment:review");
        var reserved=tx.execute(ignored->{
            if(retry) access.finance(user);else access.require(user,"payment:review");
            var value=request(id);var bill=repository.lock(value.billId());
            jdbc.sql("SELECT id FROM payment_request WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).single();value=request(id);
            if(events.repeated(user,input.requestKey(),"REQUEST",id,input.action(),input)) return value;
            PaymentAccess.version(value.version(),input.version());String reason=PaymentAccess.text(input.reason(),5,500,"处理说明");
            if(!retry && value.requestedBy()==user.id()) throw PaymentService.error(HttpStatus.FORBIDDEN,"SELF_REVIEW","不能复核本人发起的财务申请");
            if(retry ? (!value.status().equals("FAILED") || value.reviewedBy()==null) : !value.status().equals("SUBMITTED"))
                throw PaymentService.error(HttpStatus.CONFLICT,"INVALID_REQUEST_STATE","申请当前不可执行该操作，请刷新核对");
            if(!retry && !List.of("APPROVE","REJECT").contains(input.action())) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_PAYMENT_ACTION","不支持的复核操作");
            if(!java.util.Objects.equals(bill.pendingRequestId(),id)) throw PaymentService.error(HttpStatus.CONFLICT,"REQUEST_CONFLICT","该申请已不是账单的当前处理申请");
            boolean reject=input.action().equals("REJECT");
            jdbc.sql("UPDATE payment_request SET status=:state,reviewed_by=COALESCE(reviewed_by,:actor),review_comment=:reason,last_error='',last_attempt_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=:id")
                .param("state",reject?"REJECTED":"PROCESSING").param("actor",user.id()).param("reason",reason).param("id",id).update();
            if(reject) jdbc.sql("UPDATE payment_bill SET pending_request_id=NULL,refund_status=CASE WHEN :refund=TRUE THEN 'REJECTED' ELSE refund_status END,version=version+1 WHERE id=:id")
                .param("refund",value.type().equals("REFUND")).param("id",bill.id()).update();
            else if(value.type().equals("REFUND")) jdbc.sql("UPDATE payment_bill SET refund_status='PROCESSING',version=version+1 WHERE id=:id").param("id",bill.id()).update();
            var result=request(id);events.history(user.id(),"REQUEST",id,input.action(),input.requestKey(),input,reason,result);
            events.history(user.id(),"BILL",bill.id(),value.type()+"_"+input.action(),null,null,reason,repository.load(bill.id()));
            if(reject) events.student(bill.studentId(),"财务申请处理结果",bill.id(),"申请未通过："+reason);return result;
        });
        if(!reserved.status().equals("PROCESSING")) return reserved;
        return executeRequest(id);
    }
    Request executeRequest(long id) {
        var value=request(id);var bill=repository.load(value.billId());
        try {
            if(value.type().equals("REFUND")) payments.refund(bill,value.gatewayRequestNo(),value.reason());else payments.close(bill);
        } catch(Exception failure) {
            log.warn("Finance gateway action unconfirmed: requestId={}, failureType={}",id,failure.getClass().getSimpleName());
            tx.executeWithoutResult(ignored->{
                repository.lock(bill.id());jdbc.sql("UPDATE payment_request SET status='FAILED',last_error='平台处理结果尚未确认，请保留原申请重试或查单；不能据此认定已完成',version=version+1 WHERE id=:id AND status='PROCESSING'").param("id",id).update();
                events.history(null,"BILL",bill.id(),value.type()+"_UNCONFIRMED",null,null,"平台处理结果尚未确认",request(id));
            });return request(id);
        }
        return tx.execute(ignored->{
            repository.lock(bill.id());int changed=jdbc.sql("UPDATE payment_request SET status='COMPLETED',last_error='',resolved_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=:id AND status='PROCESSING'").param("id",id).update();
            if(changed==1) {
                boolean refund=value.type().equals("REFUND");
                jdbc.sql("UPDATE payment_bill SET pending_request_id=NULL,workflow_status=CASE WHEN :refund=TRUE THEN workflow_status ELSE :state END,refund_status=CASE WHEN :refund=TRUE THEN 'REFUNDED' ELSE refund_status END,version=version+1 WHERE id=:id")
                    .param("refund",refund).param("state",value.type().equals("VOID")?"VOIDED":"WAIVED").param("id",bill.id()).update();
                events.history(null,"BILL",bill.id(),value.type()+"_COMPLETED",null,null,"复核及平台确认已完成",repository.load(bill.id()));
                events.student(bill.studentId(),"财务申请处理结果",bill.id(),value.type().equals("REFUND")?"该账单已确认全额退款。":value.type().equals("VOID")?"该账单已作废，无需继续缴费。":"该账单已减免，无需继续缴费。");
            }
            return request(id);
        });
    }
    private record Filter(String sql,Map<String,Object> params) {}
    private Filter filter(String q,String category,String state,String period) {
        String where=" WHERE 1=1";var params=new HashMap<String,Object>();
        if(q!=null && !q.isBlank()) { if(q.length()>120) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_SEARCH","检索内容过长");where+=" AND (u.username LIKE :q ESCAPE '!' OR u.full_name LIKE :q ESCAPE '!' OR b.title LIKE :q ESCAPE '!')";params.put("q","%"+q.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%"); }
        if(category!=null && !category.isBlank()) { access.category(category);where+=" AND b.category_code=:category";params.put("category",category); }
        if(period!=null && !period.isBlank()) { where+=" AND b.billing_period=:period";params.put("period",PaymentAccess.text(period,1,80,"账期")); }
        if(state!=null && !state.isBlank()) {
            if(List.of("DRAFT","PENDING_REVIEW","RETURNED","PUBLISHED","VOIDED","WAIVED").contains(state)) { where+=" AND b.workflow_status=:state";params.put("state",state); }
            else if(List.of("PAID","UNPAID").contains(state)) { where+=" AND b.status=:state AND b.workflow_status='PUBLISHED' AND b.refund_status<>'REFUNDED'";params.put("state",state); }
            else if(state.equals("REFUNDED")) where+=" AND b.refund_status='REFUNDED'";
            else if(state.equals("OVERDUE")) where+=" AND b.workflow_status='PUBLISHED' AND b.status='UNPAID' AND b.due_date<CURRENT_DATE";
            else if(state.equals("UNCONFIRMED")) where+=" AND b.last_sync_error<>''";
            else throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_PAYMENT_STATUS","账单状态筛选无效");
        }return new Filter(where,params);
    }
    public Page<PaymentService.Bill> list(CampusPrincipal user,String q,String category,String state,String period,int page,int size) {
        access.finance(user);if(page<1 || page>1000000 || size<1 || size>100) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_PAGE","分页参数无效");
        var filter=filter(q,category,state,period);String from=" FROM payment_bill b JOIN app_user u ON u.id=b.student_id"+filter.sql();
        long total=jdbc.sql("SELECT COUNT(*)"+from).params(filter.params()).query(Long.class).single();
        var items=jdbc.sql(PaymentRepository.BILL_QUERY+filter.sql()+" ORDER BY b.id DESC LIMIT :limit OFFSET :offset").params(filter.params()).param("limit",size).param("offset",((long)page-1)*size).query(repository::bill).list();
        var summary=jdbc.sql("""
            SELECT COUNT(*) AS total,
            COALESCE(SUM(CASE WHEN b.status='UNPAID' AND b.workflow_status='PUBLISHED' THEN 1 ELSE 0 END),0) AS unpaid,
            COALESCE(SUM(CASE WHEN b.status='PAID' AND b.refund_status<>'REFUNDED' THEN 1 ELSE 0 END),0) AS paid,
            COALESCE(SUM(CASE WHEN b.status='UNPAID' AND b.workflow_status='PUBLISHED' AND b.due_date<CURRENT_DATE THEN 1 ELSE 0 END),0) AS overdue,
            COALESCE(SUM(CASE WHEN b.workflow_status='PENDING_REVIEW' THEN 1 ELSE 0 END),0) AS pending,
            COALESCE(SUM(CASE WHEN b.status='UNPAID' AND b.workflow_status='PUBLISHED' THEN b.amount_cents ELSE 0 END),0) AS unpaid_amount,
            COALESCE(SUM(CASE WHEN b.status='PAID' AND b.refund_status<>'REFUNDED' THEN b.amount_cents ELSE 0 END),0) AS paid_amount,
            COALESCE(SUM(CASE WHEN b.refund_status='REFUNDED' THEN b.amount_cents ELSE 0 END),0) AS refunded_amount
            """+from).params(filter.params()).query((r,n)->new Summary(r.getLong("total"),r.getLong("unpaid"),r.getLong("paid"),r.getLong("overdue"),r.getLong("pending"),r.getLong("unpaid_amount"),r.getLong("paid_amount"),r.getLong("refunded_amount"))).single();
        return new Page<>(items,total,page,size,summary);
    }
    public String export(CampusPrincipal user,String q,String category,String state,String period) {
        access.finance(user);var filter=filter(q,category,state,period);
        var rows=jdbc.sql(PaymentRepository.BILL_QUERY+filter.sql()+" ORDER BY b.id DESC LIMIT 10001").params(filter.params()).query(repository::bill).list();
        if(rows.size()>10000) throw PaymentService.error(HttpStatus.BAD_REQUEST,"EXPORT_TOO_LARGE","导出最多一万条，请缩小账期或筛选范围");
        var csv=new StringBuilder("\uFEFF订单号,学生账号,学生姓名,收费分类,费用名称,账期,截止日期,金额（元）,办理状态,支付状态,退款状态,交易号,支付确认时间,最近核验时间,核验提示\r\n");
        for(var bill:rows) csv.append(java.util.stream.Stream.of(bill.orderNo(),bill.studentUsername(),bill.studentName(),bill.categoryName(),bill.title(),bill.billingPeriod(),bill.dueDate()==null?"":bill.dueDate().toString(),
            BigDecimal.valueOf(bill.amountCents(),2).toPlainString(),bill.workflowStatus(),bill.status(),bill.refundStatus(),bill.tradeNo()==null?"":bill.tradeNo(),
            bill.paidAt()==null?"":bill.paidAt().toString(),bill.lastCheckedAt()==null?"":bill.lastCheckedAt().toString(),bill.lastSyncError()).map(PaymentManagementService::csv).collect(java.util.stream.Collectors.joining(","))).append("\r\n");
        return csv.toString();
    }
    private static String csv(String text) {
        String value=text.replace('\r',' ').replace('\n',' ');String trimmed=value.stripLeading();
        if(!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0))>=0) value="'"+value;
        return "\""+value.replace("\"","\"\"")+"\"";
    }
    public List<cn.zzuit.campus.portal.TaskAggregationService.AggregatedTask> tasks(CampusPrincipal user) {
        access.finance(user);var tasks=new ArrayList<cn.zzuit.campus.portal.TaskAggregationService.AggregatedTask>();
        boolean manage=user.permissions().contains("payment:manage"),review=user.permissions().contains("payment:review");
        var bills=jdbc.sql(PaymentRepository.BILL_QUERY+" WHERE b.workflow_status IN ('DRAFT','RETURNED','PENDING_REVIEW') ORDER BY b.id")
            .query(repository::bill).list();
        for(var bill:bills) if((manage && !bill.workflowStatus().equals("PENDING_REVIEW")) || (review && bill.workflowStatus().equals("PENDING_REVIEW") && !preparedBy(bill.id(),user.id())))
            tasks.add(new cn.zzuit.campus.portal.TaskAggregationService.AggregatedTask("PAYMENT-"+bill.id(),"PAYMENT","财务收费",bill.studentName()+" · "+bill.title(),bill.workflowStatus(),bill.workflowStatus().equals("PENDING_REVIEW")?"待独立财务复核":"待财务经办",bill.studentName(),bill.billingPeriod(),"/finance/payments?id="+bill.id()+"&tab=details",bill.createdAt(),"NORMAL"));
        if(review) for(var standard:configuration.standards()) if(standard.status().equals("PENDING_REVIEW") && standard.createdBy()!=user.id())
            tasks.add(new cn.zzuit.campus.portal.TaskAggregationService.AggregatedTask("PAYMENT-STANDARD-"+standard.id(),"PAYMENT","财务收费",standard.name(),standard.status(),"收费标准待复核",standard.creatorName(),standard.categoryName(),"/finance/payments?tab=standards",standard.createdAt(),"NORMAL"));
        for(var request:requests(user,null)) if((review && request.status().equals("SUBMITTED") && request.requestedBy()!=user.id()) || (manage && request.status().equals("FAILED")))
            tasks.add(new cn.zzuit.campus.portal.TaskAggregationService.AggregatedTask("PAYMENT-REQUEST-"+request.id(),"PAYMENT","财务收费","账单 #"+request.billId()+" · "+request.type(),request.status(),request.status().equals("FAILED")?"平台处理待核实重试":"财务申请待复核",request.requesterName(),request.reason(),"/finance/payments?id="+request.billId()+"&tab=details",request.createdAt(),request.status().equals("FAILED")?"URGENT":"NORMAL"));
        if(manage) for(var basis:configuration.bases(user)) if(basis.status().equals("SUBMITTED"))
            tasks.add(new cn.zzuit.campus.portal.TaskAggregationService.AggregatedTask("PAYMENT-BASIS-"+basis.id(),"PAYMENT","财务收费",basis.studentName()+" · "+basis.categoryName(),basis.status(),"业务收费依据待核对",basis.creatorName(),basis.billingPeriod(),"/finance/payments?id="+basis.id()+"&tab=bases",basis.createdAt(),"NORMAL"));
        return tasks;
    }
}
