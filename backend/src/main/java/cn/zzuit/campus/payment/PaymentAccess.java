package cn.zzuit.campus.payment;

import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class PaymentAccess {
    private final IdentityService identities;
    private final PaymentService payments;
    private final JdbcClient jdbc;
    public PaymentAccess(IdentityService identities,PaymentService payments,JdbcClient jdbc) { this.identities=identities;this.payments=payments;this.jdbc=jdbc; }
    void require(CampusPrincipal user,String permission) {
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update();
        identities.requirePermission(user,permission);payments.requireDemo();
    }
    void finance(CampusPrincipal user) { require(user,user.permissions().contains("payment:manage")?"payment:manage":"payment:review"); }
    void otherReviewer(long author) {
        if(jdbc.sql("SELECT COUNT(DISTINCT u.id) FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN role_permission rp ON rp.role_code=ur.role_code WHERE u.enabled=TRUE AND u.must_change_password=FALSE AND u.id<>:author AND rp.permission_code='payment:review'")
            .param("author",author).query(Long.class).single()==0)
            throw PaymentService.error(HttpStatus.CONFLICT,"NO_FINANCE_REVIEWER","暂无其他有效财务复核人员，请先由账号管理员配置岗位");
    }
    static void version(int current,int expected) {
        if(current!=expected) throw PaymentService.error(HttpStatus.CONFLICT,"VERSION_CONFLICT","记录已变化，请刷新后重新核对");
    }
    static String text(String value,int min,int max,String label) {
        if(value==null || value.trim().length()<min || value.trim().length()>max)
            throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_PAYMENT_INPUT",label+"长度应为"+min+"—"+max+"个字符");
        return value.trim();
    }
    static void key(String key) {
        if(key==null || !key.matches("[A-Za-z0-9-]{20,80}")) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_REQUEST_KEY","请求标识无效，请重新打开表单");
    }
    void category(String code) {
        if(code==null || !code.matches("[A-Z_]{1,32}") || jdbc.sql("SELECT COUNT(*) FROM payment_category WHERE code=:code").param("code",code).query(Long.class).single()!=1)
            throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_CATEGORY","请选择有效的收费分类");
    }
    static java.math.BigDecimal quantity(String raw) {
        if(raw==null || !raw.matches("(?:0|[1-9]\\d{0,5})(?:\\.\\d{1,3})?"))
            throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_QUANTITY","数量应为大于零、最多三位小数的普通数字");
        var value=new java.math.BigDecimal(raw);
        if(value.signum()<=0) throw PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_QUANTITY","数量必须大于零");return value;
    }
}
