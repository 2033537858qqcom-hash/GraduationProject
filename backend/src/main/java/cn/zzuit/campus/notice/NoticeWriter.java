package cn.zzuit.campus.notice;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class NoticeWriter {
    private final JdbcClient jdbc;
    public NoticeWriter(JdbcClient jdbc) { this.jdbc=jdbc; }
    public void write(long recipient,String title,String type,long requestId,String view,String message) {
        jdbc.sql("INSERT INTO user_notice(user_id,title,body,source_type,source_id,source_view) VALUES(:u,:t,:b,:type,:id,:view)")
            .param("u",recipient).param("t",title).param("b","申请 #"+requestId+" · "+message)
            .param("type",type).param("id",requestId).param("view",view).update();
    }
}
