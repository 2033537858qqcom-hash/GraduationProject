package cn.zzuit.campus.payment;

import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.notice.NoticeWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class PaymentEvents {
    private final JdbcClient jdbc;
    private final NoticeWriter notices;
    private final JsonMapper json=JsonMapper.builder().build();
    public PaymentEvents(JdbcClient jdbc,NoticeWriter notices) { this.jdbc=jdbc;this.notices=notices; }
    static String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    boolean repeated(CampusPrincipal actor,String key,String type,long id,String action,Object command) {
        if(key==null || !key.matches("[A-Za-z0-9-]{20,80}")) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_REQUEST_KEY","请求标识无效，请重新打开表单");
        var previous=jdbc.sql("SELECT entity_type,entity_id,actor_id,action,command_hash FROM payment_history WHERE request_key=:key")
            .param("key",key).query((r,n)->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)}).optional();
        if(previous.isEmpty()) return false;
        var p=previous.get();
        if(!p[0].equals(type) || !p[1].equals(Long.toString(id)) || !p[2].equals(Long.toString(actor.id())) || !p[3].equals(action) || !p[4].equals(hash(command)))
            throw new ApiException(HttpStatus.CONFLICT,"REQUEST_CONFLICT","请求标识已用于其他操作");
        return true;
    }
    void history(Long actor,String type,long id,String action,String key,Object command,String reason,Object snapshot) {
        jdbc.sql("INSERT INTO payment_history(entity_type,entity_id,action,actor_id,request_key,command_hash,reason,snapshot) VALUES(:type,:id,:action,:actor,:key,:hash,:reason,:snapshot)")
            .param("type",type).param("id",id).param("action",action).param("actor",actor).param("key",key)
            .param("hash",command==null?null:hash(command)).param("reason",reason).param("snapshot",json.writeValueAsString(snapshot)).update();
    }
    void student(long student,String title,long bill,String message) { notices.write(student,title,"PAYMENT",bill,"MINE",message); }
    void source(long actor,String title,long basis,String message) { notices.write(actor,title,"PAYMENT_BASIS",basis,"SOURCE",message); }
    void finance(String permission,long excluding,String title,String type,long id,String message) {
        jdbc.sql("SELECT DISTINCT u.id FROM app_user u JOIN user_role ur ON ur.user_id=u.id JOIN role_permission rp ON rp.role_code=ur.role_code WHERE u.enabled=TRUE AND u.must_change_password=FALSE AND rp.permission_code=:p AND u.id<>:excluded")
            .param("p",permission).param("excluded",excluding).query(Long.class).list()
            .forEach(user -> {
                if(id>0) notices.write(user,title,type,id,"FINANCE",message);
                else jdbc.sql("INSERT INTO user_notice(user_id,title,body) VALUES(:user,:title,:body)").param("user",user).param("title",title).param("body",message).update();
            });
    }
}
