package cn.zzuit.campus.payment;

import static cn.zzuit.campus.payment.FinanceModels.*;

import cn.zzuit.campus.identity.CampusPrincipal;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentConfigurationService {
    private final JdbcClient jdbc;
    private final PaymentAccess access;
    private final PaymentService payments;
    private final PaymentEvents events;
    static final String STANDARD_SQL="SELECT s.*,c.name AS category_name,u.full_name AS creator_name FROM payment_standard s JOIN payment_category c ON c.code=s.category_code JOIN app_user u ON u.id=s.created_by";
    static final String BASIS_SQL="SELECT s.*,c.name AS category_name,u.username,u.full_name AS student_name,creator.full_name AS creator_name FROM payment_basis s JOIN payment_category c ON c.code=s.category_code JOIN app_user u ON u.id=s.student_id JOIN app_user creator ON creator.id=s.created_by";
    public PaymentConfigurationService(JdbcClient jdbc,PaymentAccess access,PaymentService payments,PaymentEvents events) { this.jdbc=jdbc;this.access=access;this.payments=payments;this.events=events; }
    public Catalog catalog(CampusPrincipal user) {
        access.finance(user);
        return new Catalog(payments.catalog(),standards(),people("payment:manage"),people("payment:review"),
            jdbc.sql("SELECT id,name FROM campus ORDER BY id").query((r,n)->new Person(r.getLong(1),r.getString(2))).list(),
            jdbc.sql("SELECT id,name FROM major ORDER BY id").query((r,n)->new Person(r.getLong(1),r.getString(2))).list(),user.permissions());
    }
    private List<Person> people(String permission) {
        return jdbc.sql("SELECT DISTINCT u.id,u.full_name FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN role_permission rp ON rp.role_code=ur.role_code WHERE u.enabled=TRUE AND u.must_change_password=FALSE AND rp.permission_code=:p ORDER BY u.id")
            .param("p",permission).query((r,n)->new Person(r.getLong(1),r.getString(2))).list();
    }
    List<Standard> standards() { return jdbc.sql(STANDARD_SQL+" ORDER BY s.id DESC").query(this::standardRow).list(); }
    Standard standard(long id) { return jdbc.sql(STANDARD_SQL+" WHERE s.id=:id").param("id",id).query(this::standardRow).optional()
        .orElseThrow(()->PaymentService.error(HttpStatus.NOT_FOUND,"STANDARD_NOT_FOUND","收费标准不存在")); }
    private Standard standardRow(ResultSet r,int n) throws SQLException {
        return new Standard(r.getLong("id"),r.getString("name"),r.getString("category_code"),r.getString("category_name"),r.getString("unit"),r.getLong("unit_price_cents"),
            r.getObject("campus_id",Long.class),r.getObject("major_id",Long.class),r.getString("status"),r.getLong("created_by"),r.getString("creator_name"),r.getInt("version"),r.getString("reason"),PaymentRepository.time(r,"created_at"));
    }
    @Transactional public Standard createStandard(CampusPrincipal user,StandardInput input) {
        access.require(user,"payment:manage");PaymentAccess.key(input.requestKey());
        var existing=jdbc.sql("SELECT id FROM payment_standard WHERE request_key=:key").param("key",input.requestKey()).query(Long.class).optional();
        if(existing.isPresent() && events.repeated(user,input.requestKey(),"STANDARD",existing.get(),"CREATE",input)) return standard(existing.get());
        PaymentAccess.text(input.requestKey(),20,80,"请求标识");access.category(input.categoryCode());access.otherReviewer(user.id());
        String name=PaymentAccess.text(input.name(),1,120,"标准名称"),unit=PaymentAccess.text(input.unit(),1,20,"计价单位"),reason=PaymentAccess.text(input.reason(),5,500,"收费依据");
        if(input.unitPriceCents()<1 || input.unitPriceCents()>100000000L) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_PRICE","单价须为0.01—1000000元");
        if(input.campusId()!=null && jdbc.sql("SELECT COUNT(*) FROM campus WHERE id=:id").param("id",input.campusId()).query(Long.class).single()!=1) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_SCOPE","校区不存在");
        if(input.majorId()!=null && jdbc.sql("SELECT COUNT(*) FROM major WHERE id=:id").param("id",input.majorId()).query(Long.class).single()!=1) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_SCOPE","专业不存在");
        var key=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO payment_standard(request_key,name,category_code,unit,unit_price_cents,campus_id,major_id,created_by,reason) VALUES(:key,:name,:category,:unit,:price,:campus,:major,:actor,:reason)")
            .param("key",input.requestKey()).param("name",name).param("category",input.categoryCode()).param("unit",unit).param("price",input.unitPriceCents())
            .param("campus",input.campusId()).param("major",input.majorId()).param("actor",user.id()).param("reason",reason).update(key,"id");
        var result=standard(key.getKey().longValue());events.history(user.id(),"STANDARD",result.id(),"CREATE",input.requestKey(),input,reason,result);
        events.finance("payment:review",user.id(),"收费标准待复核","PAYMENT_BASIS",0,"请在财务工作台复核新收费标准。");return result;
    }
    @Transactional public Standard standardAction(CampusPrincipal user,long id,Action input) {
        boolean retire="RETIRE".equals(input.action());access.require(user,retire?"payment:manage":"payment:review");
        jdbc.sql("SELECT id FROM payment_standard WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).optional().orElseThrow(()->PaymentService.error(HttpStatus.NOT_FOUND,"STANDARD_NOT_FOUND","收费标准不存在"));
        var standard=standard(id);
        if(events.repeated(user,input.requestKey(),"STANDARD",id,input.action(),input)) return standard;
        PaymentAccess.version(standard.version(),input.version());String reason=PaymentAccess.text(input.reason(),5,500,"处理说明");
        if(!retire && standard.createdBy()==user.id()) throw PaymentService.error(HttpStatus.FORBIDDEN,"SELF_REVIEW","不能复核本人经办的收费标准");
        String state;
        if(retire && standard.status().equals("ACTIVE")) state="RETIRED";
        else if(standard.status().equals("PENDING_REVIEW") && "APPROVE".equals(input.action())) state="ACTIVE";
        else if(standard.status().equals("PENDING_REVIEW") && "RETURN".equals(input.action())) state="RETURNED";
        else throw PaymentService.error(HttpStatus.CONFLICT,"INVALID_STANDARD_STATE","收费标准当前不可执行该操作");
        jdbc.sql("UPDATE payment_standard SET status=:state,reviewed_by=:actor,version=version+1 WHERE id=:id").param("state",state).param("actor",user.id()).param("id",id).update();
        var result=standard(id);events.history(user.id(),"STANDARD",id,input.action(),input.requestKey(),input,reason,result);return result;
    }
    public List<Basis> bases(CampusPrincipal user) {
        boolean finance=user.permissions().contains("payment:manage") || user.permissions().contains("payment:review");
        if(finance) access.finance(user);else access.require(user,"payment:source");
        var query=jdbc.sql(BASIS_SQL+(finance?"":" WHERE s.created_by=:actor")+" ORDER BY s.id DESC");
        if(!finance) query=query.param("actor",user.id());return query.query(this::basisRow).list();
    }
    Basis basis(long id) { return jdbc.sql(BASIS_SQL+" WHERE s.id=:id").param("id",id).query(this::basisRow).optional()
        .orElseThrow(()->PaymentService.error(HttpStatus.NOT_FOUND,"BASIS_NOT_FOUND","收费依据不存在")); }
    private Basis basisRow(ResultSet r,int n) throws SQLException {
        return new Basis(r.getLong("id"),r.getString("username"),r.getString("student_name"),r.getString("category_code"),r.getString("category_name"),r.getString("billing_period"),
            r.getString("quantity"),r.getString("basis"),r.getString("source_reference"),r.getLong("created_by"),r.getString("creator_name"),r.getString("status"),r.getObject("bill_id",Long.class),r.getString("review_comment"),r.getInt("version"),PaymentRepository.time(r,"created_at"));
    }
    @Transactional public Basis createBasis(CampusPrincipal user,BasisInput input) {
        access.require(user,"payment:source");PaymentAccess.key(input.requestKey());
        var existing=jdbc.sql("SELECT id FROM payment_basis WHERE request_key=:key").param("key",input.requestKey()).query(Long.class).optional();
        if(existing.isPresent() && events.repeated(user,input.requestKey(),"BASIS",existing.get(),"CREATE",input)) return basis(existing.get());
        PaymentAccess.text(input.requestKey(),20,80,"请求标识");access.category(input.categoryCode());
        var student=jdbc.sql("SELECT u.id,u.campus_id,u.department_id FROM app_user u JOIN user_role ur ON ur.user_id=u.id AND ur.role_code='STUDENT' WHERE u.username=:name AND u.enabled=TRUE AND u.demo=TRUE")
            .param("name",PaymentAccess.text(input.username(),1,64,"学生账号")).query((r,n)->new Long[]{r.getLong(1),r.getObject(2,Long.class),r.getObject(3,Long.class)}).optional()
            .orElseThrow(()->PaymentService.error(HttpStatus.NOT_FOUND,"STUDENT_NOT_FOUND","未找到可用的演示学生"));
        String category=input.categoryCode(),reference=null;
        if(user.permissions().contains("academic:manage") && List.of("TUITION","TEXTBOOK").contains(category)) {
            var department=jdbc.sql("SELECT department_id FROM app_user WHERE id=:id").param("id",user.id()).query(Long.class).single();
            if(department.equals(student[2])) reference="学院教学档案 · 学生 #"+student[0];
        }
        if(user.permissions().contains("housing:assign") && List.of("ACCOMMODATION","WATER","ELECTRICITY","UTILITIES").contains(category)) {
            reference=jdbc.sql("SELECT a.building_name,a.room_name,a.id FROM housing_assignment a JOIN dorm_bed bed ON bed.id=a.bed_id JOIN dorm_room room ON room.id=bed.room_id JOIN dorm_building b ON b.id=room.building_id JOIN dorm_manager_scope s ON s.building_id=b.id AND s.enabled=TRUE WHERE a.student_id=:student AND a.status='CURRENT' AND b.enabled=TRUE AND s.user_id=:actor")
                .param("student",student[0]).param("actor",user.id()).query((r,n)->"入住记录 #"+r.getLong(3)+" · "+r.getString(1)+" "+r.getString(2)).optional().orElse(reference);
        }
        if(user.permissions().contains("repair:dispatch") && List.of("WATER","ELECTRICITY","UTILITIES").contains(category) &&
            jdbc.sql("SELECT COUNT(*) FROM repair_staff_scope WHERE user_id=:u AND enabled=TRUE AND capability='DISPATCH' AND campus_id=:campus").param("u",user.id()).param("campus",student[1]).query(Long.class).single()>0)
            reference="后勤校区负责范围 · 校区 #"+student[1];
        if(reference==null) throw PaymentService.error(HttpStatus.FORBIDDEN,"BASIS_OUT_OF_SCOPE","该学生或收费分类不在当前岗位负责范围内");
        String quantity=PaymentAccess.quantity(input.quantity()==null?"1":input.quantity()).stripTrailingZeros().toPlainString();
        if(List.of("WATER","ELECTRICITY").contains(category)) {
            var before=reading(input.previousReading());var after=reading(input.currentReading());var used=after.subtract(before);
            if(used.signum()<=0) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_READING","本期读数必须大于上期读数");
            quantity=PaymentAccess.quantity(used.stripTrailingZeros().toPlainString()).toPlainString();reference+=" · 读数 "+before.toPlainString()+" → "+after.toPlainString();
        }
        String period=PaymentAccess.text(input.billingPeriod(),1,80,"收费账期"),description=PaymentAccess.text(input.basis(),10,1000,"收费依据");
        if(input.correctionOf()!=null) {
            jdbc.sql("SELECT id FROM payment_basis WHERE id=:id FOR UPDATE").param("id",input.correctionOf()).query(Long.class).optional().orElseThrow(()->PaymentService.error(HttpStatus.NOT_FOUND,"BASIS_NOT_FOUND","原收费依据不存在"));
            var old=basis(input.correctionOf());
            if(old.createdBy()!=user.id()) throw PaymentService.error(HttpStatus.FORBIDDEN,"FORBIDDEN","不能修改他人提供的收费依据");
            if(!old.status().equals("RETURNED")) throw PaymentService.error(HttpStatus.CONFLICT,"BASIS_ALREADY_REVISED","原依据不在待补充状态");
            reference+=" · 修正原依据 #"+old.id();
        }
        var key=new GeneratedKeyHolder();jdbc.sql("INSERT INTO payment_basis(request_key,student_id,category_code,billing_period,quantity,basis,source_reference,created_by,replaces_basis_id) VALUES(:key,:student,:category,:period,:quantity,:basis,:reference,:actor,:old)")
            .param("key",input.requestKey()).param("student",student[0]).param("category",category).param("period",period).param("quantity",quantity).param("basis",description).param("reference",reference).param("actor",user.id()).param("old",input.correctionOf()).update(key,"id");
        if(input.correctionOf()!=null) jdbc.sql("UPDATE payment_basis SET status='REPLACED',version=version+1 WHERE id=:id").param("id",input.correctionOf()).update();
        var result=basis(key.getKey().longValue());events.history(user.id(),"BASIS",result.id(),"CREATE",input.requestKey(),input,description,result);
        events.finance("payment:manage",0,"有新的收费依据待核对","PAYMENT_BASIS",result.id(),"请核对业务部门提供的收费依据后建单。");return result;
    }
    private BigDecimal reading(String value) {
        if(value==null || !value.matches("(?:0|[1-9]\\d{0,8})(?:\\.\\d{1,3})?")) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_READING","请填写最多三位小数的非负表计读数");return new BigDecimal(value);
    }
    @Transactional public Basis returnBasis(CampusPrincipal user,long id,Action input) {
        access.require(user,"payment:manage");jdbc.sql("SELECT id FROM payment_basis WHERE id=:id FOR UPDATE").param("id",id).query(Long.class).single();
        var value=basis(id);if(events.repeated(user,input.requestKey(),"BASIS",id,"RETURN",input)) return value;
        PaymentAccess.version(value.version(),input.version());
        if(!value.status().equals("SUBMITTED")) throw PaymentService.error(HttpStatus.CONFLICT,"INVALID_BASIS_STATE","收费依据已处理");
        String reason=PaymentAccess.text(input.reason(),5,500,"退回说明");
        jdbc.sql("UPDATE payment_basis SET status='RETURNED',review_comment=:reason,version=version+1 WHERE id=:id").param("reason",reason).param("id",id).update();
        var result=basis(id);events.history(user.id(),"BASIS",id,"RETURN",input.requestKey(),input,reason,result);
        events.source(value.createdBy(),"收费依据已退回",id,reason);return result;
    }
}
