package cn.zzuit.campus.identity;

import cn.zzuit.campus.common.ApiException;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CredentialService {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final PasswordEncoder passwords;
    public CredentialService(JdbcClient jdbc,IdentityService identities,PasswordEncoder passwords) {
        this.jdbc=jdbc; this.identities=identities; this.passwords=passwords;
    }
    private void lock() { jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update(); }
    @Transactional public void change(CampusPrincipal user,ChangePassword input) {
        lock();
        var current=identities.current(user.id()).orElseThrow(()->new ApiException(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","请重新登录"));
        if (current.securityVersion()!=user.securityVersion()) throw new ApiException(HttpStatus.UNAUTHORIZED,"AUTH_REQUIRED","凭据或权限已变化，请重新登录");
        if (input.currentPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72 || !passwords.matches(input.currentPassword(),current.password()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CURRENT_PASSWORD","当前密码不正确");
        validateReplacement(input.newPassword(),current.password());
        jdbc.sql("UPDATE app_user SET password_hash=:hash,must_change_password=FALSE,security_version=security_version+1,version=version+1 WHERE id=:id")
            .param("hash",passwords.encode(input.newPassword())).param("id",user.id()).update();
        audit(user,user.id(),"USER_PASSWORD_CHANGE","本人修改密码，原有会话已失效","本人验证当前密码后修改");
    }
    @Transactional public ResetResult reset(CampusPrincipal user,long target,ResetPassword input) {
        lock(); identities.requirePermission(user,"foundation:manage");
        if (target==user.id()) throw new ApiException(HttpStatus.BAD_REQUEST,"USE_SELF_PASSWORD_CHANGE","修改自己的密码请到个人资料验证当前密码");
        var old=jdbc.sql("SELECT password_hash,enabled,version FROM app_user WHERE id=:id").param("id",target)
            .query((r,n)->new Target(r.getString(1),r.getBoolean(2),r.getInt(3))).optional()
            .orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"ACCOUNT_NOT_FOUND","账号不存在"));
        if (!old.enabled()) throw new ApiException(HttpStatus.CONFLICT,"ACCOUNT_DISABLED","请先确认账号启用状态，再重置密码");
        if (old.version()!=input.version()) throw new ApiException(HttpStatus.CONFLICT,"ACCOUNT_VERSION_CHANGED","账号资料已变化，请刷新后重新核对");
        validateReplacement(input.newPassword(),old.hash());
        jdbc.sql("UPDATE app_user SET password_hash=:hash,must_change_password=TRUE,security_version=security_version+1,version=version+1 WHERE id=:id")
            .param("hash",passwords.encode(input.newPassword())).param("id",target).update();
        audit(user,target,"USER_PASSWORD_RESET","管理员重置凭据，原有会话已失效，下次登录须修改初始密码",input.reason().trim());
        return new ResetResult(target,old.version()+1,true);
    }
    private void validateReplacement(String value,String oldHash) {
        PasswordPolicy.validate(value);
        if (passwords.matches(value,oldHash)) throw new ApiException(HttpStatus.BAD_REQUEST,"PASSWORD_REUSED","新密码不能与当前密码相同");
    }
    private void audit(CampusPrincipal user,long id,String action,String summary,String reason) {
        jdbc.sql("INSERT INTO foundation_audit(actor_id,actor_name,action,entity_type,entity_id,summary,reason) VALUES(:actor,:name,:action,'USER',:id,:summary,:reason)")
            .param("actor",user.id()).param("name",identities.profile(user.id()).fullName()).param("action",action)
            .param("id",id).param("summary",summary).param("reason",reason).update();
    }
    private record Target(String hash,boolean enabled,int version) {}
    public record ChangePassword(@NotBlank @Size(max=128) String currentPassword,@NotBlank @Size(min=10,max=128) String newPassword) {}
    public record ResetPassword(@NotNull @Min(0) Integer version,@NotBlank @Size(min=10,max=128) String newPassword,@NotBlank @Size(max=200) String reason) {}
    public record ResetResult(long id,int version,boolean passwordChangeRequired) {}
}
