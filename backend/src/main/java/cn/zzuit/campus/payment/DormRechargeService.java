package cn.zzuit.campus.payment;

import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Demo ledger only; no device supplier is contacted. */
@Service
public class DormRechargeService {
    public record MeterInput(long roomId,long standardId,String meterCode) {}
    public record RechargeInput(String requestKey,long meterId,String mode,String value,Long expectedUnitPriceCents) {}
    public record MeterUpdate(long standardId,boolean enabled,int version,String reason) {}
    public record Meter(long id,long roomId,String building,String room,String meterCode,String category,String standardName,long unitPriceCents,String unit,BigDecimal balance,boolean enabled,String standardStatus,int version,long standardId,boolean standardAvailable) {}
    public record Room(long id,String building,String name) {}
    public record Recharge(long id,long billId,String payer,String status,long amountCents,BigDecimal quantity,String category,String createdAt,String creditedAt,String reversedAt) {}
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final PaymentAccess access;
    private final PaymentService payments;
    private final PaymentRepository repository;
    private final PaymentEvents events;
    private static final String METER_SQL="SELECT m.*,r.name room_name,b.name building_name,s.category_code,s.name standard_name,s.unit_price_cents,s.unit,s.status standard_status,s.effective_from,s.effective_to,c.enabled category_enabled,s.campus_id standard_campus,s.major_id standard_major,b.campus_id FROM dorm_utility_meter m JOIN dorm_room r ON r.id=m.room_id JOIN dorm_building b ON b.id=r.building_id JOIN payment_standard s ON s.id=m.standard_id JOIN payment_category c ON c.code=s.category_code ";
    public DormRechargeService(JdbcClient jdbc,IdentityService identities,PaymentAccess access,PaymentService payments,PaymentRepository repository,PaymentEvents events) {
        this.jdbc=jdbc;this.identities=identities;this.access=access;this.payments=payments;this.repository=repository;this.events=events;
    }
    private Meter meter(java.sql.ResultSet r,int n) throws java.sql.SQLException { return new Meter(r.getLong("id"),r.getLong("room_id"),r.getString("building_name"),r.getString("room_name"),r.getString("meter_code"),r.getString("category_code"),r.getString("standard_name"),r.getLong("unit_price_cents"),r.getString("unit"),r.getBigDecimal("balance_quantity"),r.getBoolean("enabled"),r.getString("standard_status"),r.getInt("version"),r.getLong("standard_id"),r.getBoolean("category_enabled") && PaymentConfigurationService.available(r.getString("standard_status"),r.getObject("effective_from",java.time.LocalDate.class),r.getObject("effective_to",java.time.LocalDate.class))); }
    public Map<String,Object> catalog(CampusPrincipal user) {
        access.finance(user);
        var rooms=jdbc.sql("SELECT r.id,b.name building,r.name FROM dorm_room r JOIN dorm_building b ON b.id=r.building_id WHERE r.enabled=TRUE AND b.enabled=TRUE ORDER BY b.name,r.name").query((r,n)->new Room(r.getLong("id"),r.getString("building"),r.getString("name"))).list();
        return Map.of("rooms",rooms,"meters",jdbc.sql(METER_SQL+"ORDER BY m.id DESC").query(this::meter).list());
    }
    @Transactional public Meter createMeter(CampusPrincipal user,MeterInput input) {
        access.require(user,"payment:manage");
        var code=PaymentAccess.text(input.meterCode(),1,64,"表计编号");
        var valid=jdbc.sql("SELECT COUNT(*) FROM dorm_room r JOIN dorm_building b ON b.id=r.building_id JOIN payment_standard s ON s.id=:standard WHERE r.id=:room AND r.enabled=TRUE AND b.enabled=TRUE AND s.status='ACTIVE' AND s.category_code IN ('WATER','ELECTRICITY') AND s.major_id IS NULL AND (s.campus_id IS NULL OR s.campus_id=b.campus_id)").param("room",input.roomId()).param("standard",input.standardId()).query(Long.class).single();
        if(valid!=1) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_METER","房间或收费标准不可用，表计须使用适用于该校区的已生效水电标准");
        String kind=jdbc.sql("SELECT category_code FROM payment_standard WHERE id=:id").param("id",input.standardId()).query(String.class).single();
        access.activeCategory(kind);
        var dates=jdbc.sql("SELECT status,effective_from,effective_to FROM payment_standard WHERE id=:id").param("id",input.standardId()).query((r,n)->PaymentConfigurationService.available(r.getString(1),r.getObject(2,java.time.LocalDate.class),r.getObject(3,java.time.LocalDate.class))).single();
        if(!dates) throw PaymentService.error(HttpStatus.CONFLICT,"STANDARD_NOT_ACTIVE","收费标准尚未到生效日期或已过期");
        var key=new GeneratedKeyHolder();jdbc.sql("INSERT INTO dorm_utility_meter(room_id,standard_id,meter_code,category_code) VALUES(:room,:standard,:code,:kind)").param("room",input.roomId()).param("standard",input.standardId()).param("code",code).param("kind",kind).update(key,"id");
        var result=jdbc.sql(METER_SQL+"WHERE m.id=:id").param("id",key.getKey().longValue()).query(this::meter).single();
        events.history(user.id(),"DORM_METER",result.id(),"CREATE",null,null,"绑定宿舍表计",result);return result;
    }
    @Transactional public Meter updateMeter(CampusPrincipal user,long id,MeterUpdate input) {
        access.require(user,"payment:manage");String reason=PaymentAccess.text(input.reason(),5,500,"调整原因");
        var old=jdbc.sql(METER_SQL+"WHERE m.id=:id").param("id",id).query(this::meter).optional().orElseThrow(()->PaymentService.error(HttpStatus.NOT_FOUND,"METER_NOT_FOUND","表计不存在"));
        PaymentAccess.version(old.version(),input.version());
        long valid=jdbc.sql("SELECT COUNT(*) FROM payment_standard s JOIN dorm_room r ON r.id=:room JOIN dorm_building b ON b.id=r.building_id WHERE s.id=:standard AND s.category_code=:category AND s.major_id IS NULL AND (s.campus_id IS NULL OR s.campus_id=b.campus_id) AND (s.status='ACTIVE' OR :enabled=FALSE)").param("room",old.roomId()).param("standard",input.standardId()).param("category",old.category()).param("enabled",input.enabled()).query(Long.class).single();
        if(valid!=1) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_METER","收费标准分类或适用范围不符");
        if(input.enabled()) {
            access.activeCategory(old.category());
            var current=jdbc.sql("SELECT status,effective_from,effective_to FROM payment_standard WHERE id=:id").param("id",input.standardId()).query((r,n)->PaymentConfigurationService.available(r.getString(1),r.getObject(2,java.time.LocalDate.class),r.getObject(3,java.time.LocalDate.class))).single();
            if(!current) throw PaymentService.error(HttpStatus.CONFLICT,"STANDARD_NOT_ACTIVE","收费标准尚未到生效日期或已过期");
        }
        if(!jdbc.sql("SELECT unit FROM payment_standard WHERE id=:id").param("id",input.standardId()).query(String.class).single().equals(old.unit())) throw PaymentService.error(HttpStatus.BAD_REQUEST,"METER_UNIT_MISMATCH","新旧收费标准的计量单位必须一致");
        jdbc.sql("UPDATE dorm_utility_meter SET standard_id=:s,enabled=:e,version=version+1 WHERE id=:id AND version=:v").param("s",input.standardId()).param("e",input.enabled()).param("id",id).param("v",input.version()).update();
        var result=jdbc.sql(METER_SQL+"WHERE m.id=:id").param("id",id).query(this::meter).single();
        events.history(user.id(),"DORM_METER",id,"UPDATE",null,null,reason,result);return result;
    }
    private long currentRoom(CampusPrincipal user) {
        identities.requirePermission(user,"portal:read");payments.requireDemo();
        return jdbc.sql("SELECT r.id FROM housing_assignment a JOIN dorm_bed d ON d.id=a.bed_id JOIN dorm_room r ON r.id=d.room_id JOIN dorm_building b ON b.id=r.building_id WHERE a.current_student_id=:user AND a.status='CURRENT' AND r.enabled=TRUE AND b.enabled=TRUE").param("user",user.id()).query(Long.class).optional().orElseThrow(()->PaymentService.error(HttpStatus.NOT_FOUND,"NO_CURRENT_DORM","没有可用的当前宿舍入住记录"));
    }
    public Map<String,Object> mine(CampusPrincipal user) {
        long room=currentRoom(user);
        var meters=jdbc.sql(METER_SQL+"WHERE m.room_id=:room ORDER BY m.id").param("room",room).query(this::meter).list();
        var history=jdbc.sql("SELECT x.id,x.bill_id,u.full_name,b.workflow_status,b.refund_status,b.status,x.amount_cents,x.quantity,m.category_code,x.created_at,x.credited_at,x.reversed_at FROM dorm_utility_recharge x JOIN dorm_utility_meter m ON m.id=x.meter_id JOIN payment_bill b ON b.id=x.bill_id JOIN app_user u ON u.id=x.payer_id WHERE m.room_id=:room ORDER BY x.id DESC LIMIT 50").param("room",room).query((r,n)->new Recharge(r.getLong("id"),r.getLong("bill_id"),r.getString("full_name"),r.getString("refund_status").equals("REFUNDED")?"REFUNDED":List.of("VOIDED","WAIVED").contains(r.getString("workflow_status"))?"CLOSED":r.getString("status"),r.getLong("amount_cents"),r.getBigDecimal("quantity"),r.getString("category_code"),r.getTimestamp("created_at").toString(),r.getTimestamp("credited_at")==null?null:r.getTimestamp("credited_at").toString(),r.getTimestamp("reversed_at")==null?null:r.getTimestamp("reversed_at").toString())).list();
        return Map.of("meters",meters,"history",history);
    }
    public DormRechargeQuote quote(CampusPrincipal user,RechargeInput input) {
        long room=currentRoom(user);
        var m=jdbc.sql(METER_SQL+"WHERE m.id=:id AND m.room_id=:room AND m.enabled=TRUE AND s.status='ACTIVE'").param("id",input.meterId()).param("room",room).query(this::meter).optional().orElseThrow(()->PaymentService.error(HttpStatus.NOT_FOUND,"METER_NOT_FOUND","宿舍表计或收费标准不可用"));
        return calculate(m,input);
    }
    private DormRechargeQuote calculate(Meter meter,RechargeInput input) {
        if(!meter.standardAvailable()) throw PaymentService.error(HttpStatus.CONFLICT,"STANDARD_NOT_ACTIVE","收费标准尚未到生效日期或已过期");
        try {
            if(input.value()==null || !input.value().matches("(?:0|[1-9]\\d{0,6})(?:\\.\\d{1,3})?")) throw new IllegalArgumentException();
            if(input.expectedUnitPriceCents()!=null && input.expectedUnitPriceCents()!=meter.unitPriceCents()) throw PaymentService.error(HttpStatus.CONFLICT,"PRICE_CHANGED","收费标准已变化，请重新核对充值金额");
            if("AMOUNT".equals(input.mode())) {
                if(!input.value().matches("(?:0|[1-9]\\d{0,6})(?:\\.\\d{1,2})?")) throw new IllegalArgumentException();
                return DormRechargeQuote.byAmount(meter.unitPriceCents(),new BigDecimal(input.value()).movePointRight(2).longValueExact());
            }
            if("QUANTITY".equals(input.mode())) return DormRechargeQuote.byQuantity(meter.unitPriceCents(),new BigDecimal(input.value()));
            throw new IllegalArgumentException();
        } catch(cn.zzuit.campus.common.ApiException ex) { throw ex; }
        catch(RuntimeException invalid) { throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_RECHARGE","请填写有效的充值金额（最多两位小数）或用量（最多三位小数）"); }
    }
    @Transactional public PaymentService.Bill create(CampusPrincipal user,RechargeInput input) {
        PaymentAccess.key(input.requestKey());
        jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update();
        long room=currentRoom(user);
        var replay=jdbc.sql("SELECT bill_id,payer_id,meter_id,input_mode,input_value FROM dorm_utility_recharge WHERE request_key=:key").param("key",input.requestKey()).query((r,n)->new Object[]{r.getLong(1),r.getLong(2),r.getLong(3),r.getString(4),r.getString(5)}).optional();
        if(replay.isPresent()) {
            var p=replay.get();
            if(!p[1].equals(user.id()) || !p[2].equals(input.meterId()) || !java.util.Objects.equals(p[3],input.mode()) || !java.util.Objects.equals(p[4],input.value())) throw PaymentService.error(HttpStatus.CONFLICT,"RECHARGE_KEY_CONFLICT","重复请求内容不一致");
            return repository.load((Long)p[0]);
        }
        var m=jdbc.sql(METER_SQL+"WHERE m.id=:id AND m.room_id=:room AND m.enabled=TRUE AND s.status='ACTIVE'").param("id",input.meterId()).param("room",room).query(this::meter).optional().orElseThrow(()->PaymentService.error(HttpStatus.NOT_FOUND,"METER_NOT_FOUND","宿舍表计或收费标准不可用"));
        DormRechargeQuote quote=calculate(m,input);
        String title=m.building()+" "+m.room()+" "+(m.category().equals("WATER")?"水费":"电费")+"预充值";
        if(title.length()>120) title=title.substring(0,120);
        var key=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO payment_bill(request_key,order_no,student_id,created_by,title,amount_cents,category_code,workflow_status,billing_period,basis,standard_id,standard_name,unit_price_cents,quantity,published_at) VALUES(:key,:order,:user,:user,:title,:amount,:category,'PUBLISHED','宿舍预充值',:basis,:standard,:name,:price,:quantity,CURRENT_TIMESTAMP)")
            .param("key",input.requestKey()).param("order",UUID.randomUUID().toString().replace("-","")).param("user",user.id()).param("title",title).param("amount",quote.amountCents()).param("category",m.category()).param("basis","宿舍演示预充值，设备未接入；"+m.building()+" "+m.room()+"；表计 "+m.meterCode()).param("standard",inputStandard(m.id())).param("name",m.standardName()).param("price",m.unitPriceCents()).param("quantity",quote.quantity().toPlainString()).update(key,"id");
        long bill=key.getKey().longValue();
        jdbc.sql("INSERT INTO dorm_utility_recharge(request_key,meter_id,bill_id,payer_id,unit_price_cents,amount_cents,quantity,input_mode,input_value) VALUES(:key,:meter,:bill,:payer,:price,:amount,:quantity,:mode,:value)").param("key",input.requestKey()).param("meter",m.id()).param("bill",bill).param("payer",user.id()).param("price",m.unitPriceCents()).param("amount",quote.amountCents()).param("quantity",quote.quantity()).param("mode",input.mode()).param("value",input.value()).update();
        var result=repository.load(bill);events.history(user.id(),"BILL",bill,"RECHARGE_CREATE",null,null,"宿舍演示预充值",result);return result;
    }
    private long inputStandard(long meterId) { return jdbc.sql("SELECT standard_id FROM dorm_utility_meter WHERE id=:id").param("id",meterId).query(Long.class).single(); }
    static void credit(JdbcClient jdbc,long billId) {
        var pending=jdbc.sql("SELECT meter_id,quantity FROM dorm_utility_recharge WHERE bill_id=:id AND credited_at IS NULL FOR UPDATE").param("id",billId).query((r,n)->new Object[]{r.getLong(1),r.getBigDecimal(2)}).optional();
        if(pending.isEmpty()) return;
        jdbc.sql("UPDATE dorm_utility_meter SET balance_quantity=balance_quantity+:quantity,version=version+1 WHERE id=:id").param("quantity",pending.get()[1]).param("id",pending.get()[0]).update();
        jdbc.sql("UPDATE dorm_utility_recharge SET credited_at=CURRENT_TIMESTAMP WHERE bill_id=:id AND credited_at IS NULL").param("id",billId).update();
    }
    static void requirePayable(JdbcClient jdbc,CampusPrincipal user,long billId) {
        var room=jdbc.sql("SELECT m.room_id FROM dorm_utility_recharge x JOIN dorm_utility_meter m ON m.id=x.meter_id JOIN dorm_room r ON r.id=m.room_id JOIN dorm_building b ON b.id=r.building_id WHERE x.bill_id=:id AND m.enabled=TRUE AND r.enabled=TRUE AND b.enabled=TRUE").param("id",billId).query(Long.class).optional();
        if(jdbc.sql("SELECT COUNT(*) FROM dorm_utility_recharge WHERE bill_id=:id").param("id",billId).query(Long.class).single()==0) return;
        jdbc.sql("SELECT id FROM housing_assignment WHERE current_student_id=:u AND status='CURRENT' FOR UPDATE").param("u",user.id()).query(Long.class).optional();
        if(room.isEmpty() || jdbc.sql("SELECT COUNT(*) FROM housing_assignment a JOIN dorm_bed d ON d.id=a.bed_id WHERE a.current_student_id=:u AND a.status='CURRENT' AND d.room_id=:room").param("u",user.id()).param("room",room.orElse(-1L)).query(Long.class).single()==0) throw PaymentService.error(HttpStatus.CONFLICT,"DORM_RECHARGE_UNAVAILABLE","入住或表计状态已变化，请联系财务核对原充值订单");
    }
    static void reverse(JdbcClient jdbc,long billId) {
        var pending=jdbc.sql("SELECT meter_id,quantity FROM dorm_utility_recharge WHERE bill_id=:id AND credited_at IS NOT NULL AND reversed_at IS NULL FOR UPDATE").param("id",billId).query((r,n)->new Object[]{r.getLong(1),r.getBigDecimal(2)}).optional();
        if(pending.isEmpty()) return;
        if(jdbc.sql("UPDATE dorm_utility_meter SET balance_quantity=balance_quantity-:quantity,version=version+1 WHERE id=:id AND balance_quantity>=:quantity").param("quantity",pending.get()[1]).param("id",pending.get()[0]).update()!=1) throw PaymentService.error(HttpStatus.CONFLICT,"RECHARGE_REVERSE_FAILED","充值余额冲正待核对，请保留原退款申请");
        jdbc.sql("UPDATE dorm_utility_recharge SET reversed_at=CURRENT_TIMESTAMP WHERE bill_id=:id AND reversed_at IS NULL").param("id",billId).update();
    }
}
