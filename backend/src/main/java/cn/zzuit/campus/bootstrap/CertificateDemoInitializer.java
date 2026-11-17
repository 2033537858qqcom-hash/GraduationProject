package cn.zzuit.campus.bootstrap;

import cn.zzuit.campus.certificate.CertificateModels;
import cn.zzuit.campus.certificate.CertificatePdf;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @Order(40)
@ConditionalOnProperty(name="app.demo.enabled",havingValue="true")
public class CertificateDemoInitializer implements ApplicationRunner {
    private static final String SAMPLE_NUMBER = "DEMO-20261017-000001";
    private static final String SAMPLE_REQUEST_KEY = "demo-cert-20261017-000001";
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final Environment environment;
    private final CertificatePdf pdf;

    public CertificateDemoInitializer(JdbcClient jdbc,PasswordEncoder passwords,Environment environment,CertificatePdf pdf) {
        this.jdbc=jdbc; this.passwords=passwords; this.environment=environment; this.pdf=pdf;
    }

    @Override @Transactional public void run(ApplicationArguments args) {
        if(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE username='affairs1001'").query(Long.class).single()==0) {
            jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,demo) VALUES('affairs1001',:hash,'学生事务人员（演示）','DEMO-AFFAIRS-1001',1,1,TRUE)")
                .param("hash",passwords.encode(environment.getProperty("app.demo.password","Demo@2026"))).update();
            jdbc.sql("INSERT INTO user_role(user_id,role_code) SELECT id,'STUDENT_AFFAIRS' FROM app_user WHERE username='affairs1001'").update();
            // Only bind resources when creating this demo account. A restart must not
            // restore responsibilities deliberately removed by resource managers.
            jdbc.sql("""
                INSERT INTO venue_manager(venue_id,user_id)
                SELECT v.id,u.id FROM venue_resource v CROSS JOIN app_user u WHERE u.username='affairs1001'
                """).update();
            seedSampleCertificate();
        }
    }

    private void seedSampleCertificate() {
        if(jdbc.sql("SELECT COUNT(*) FROM certificate_result WHERE document_number=:n").param("n",SAMPLE_NUMBER).query(Long.class).single()>0) return;
        var student = jdbc.sql("""
            SELECT u.id,u.full_name,u.identity_number,u.department_id,d.name department_name,
                   u.class_group_id,cg.name class_name,m.name major_name,c.name campus_name
            FROM app_user u
            JOIN department d ON d.id=u.department_id
            JOIN class_group cg ON cg.id=u.class_group_id
            JOIN major m ON m.id=cg.major_id
            JOIN campus c ON c.id=u.campus_id
            WHERE u.username='20260001'
            """).query((r,n)->new Object[]{
                r.getLong("id"),r.getString("full_name"),r.getString("identity_number"),
                r.getLong("department_id"),r.getString("department_name"),
                r.getLong("class_group_id"),r.getString("class_name"),
                r.getString("major_name"),r.getString("campus_name")
            }).optional();
        if(student.isEmpty()) return;
        Object[] s = student.get();
        long studentId = (Long) s[0];
        if(jdbc.sql("SELECT COUNT(*) FROM certificate_request WHERE student_id=:u AND request_key=:k").param("u",studentId).param("k",SAMPLE_REQUEST_KEY).query(Long.class).single()>0) return;
        String studentName = (String) s[1], identityNo = (String) s[2];
        long deptId = (Long) s[3], groupId = (Long) s[5];
        String deptName = (String) s[4], className = (String) s[6], majorName = (String) s[7], campusName = (String) s[8];
        String purpose = "演示在校证明防伪核验示例";
        var key = new GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO certificate_request(student_id,request_key,department_id,class_group_id,student_name,identity_number,
                department_name,major_name,class_name,campus_name,purpose,delivery_method,identity_confirmed,status,processing_comment,version)
            VALUES(:u,:k,:d,:g,:sname,:ino,:dname,:mname,:cname,:cpname,:purpose,'DIGITAL',TRUE,'COMPLETED','演示示例证明已生成',2)
            """).param("u",studentId).param("k",SAMPLE_REQUEST_KEY).param("d",deptId).param("g",groupId)
            .param("sname",studentName).param("ino",identityNo).param("dname",deptName).param("mname",majorName)
            .param("cname",className).param("cpname",campusName).param("purpose",purpose).update(key,"id");
        long reqId = key.getKey().longValue();
        LocalDateTime issuedAt = LocalDateTime.of(2026, 10, 17, 10, 30);
        jdbc.sql("""
            INSERT INTO certificate_revision(request_id,request_version,actor_name,action,comment,status,
                student_name,identity_number,department_name,major_name,class_name,campus_name,purpose,delivery_method)
            VALUES(:id,1,:sname,'SUBMIT','提交在校证明申请','SUBMITTED',:sname,:ino,:dname,:mname,:cname,:cpname,:purpose,'DIGITAL'),
                  (:id,2,'学生事务人员（演示）','APPROVE','演示示例证明已生成','COMPLETED',:sname,:ino,:dname,:mname,:cname,:cpname,:purpose,'DIGITAL')
            """).param("id",reqId).param("sname",studentName).param("ino",identityNo)
            .param("dname",deptName).param("mname",majorName).param("cname",className)
            .param("cpname",campusName).param("purpose",purpose).update();
        var identity = new CertificateModels.Identity(deptId, groupId, studentName, identityNo, deptName, majorName, className, campusName);
        var model = new CertificateModels.Request(reqId, SAMPLE_REQUEST_KEY, studentId, identity, purpose, "DIGITAL", true, "COMPLETED", "演示示例证明已生成", 2, issuedAt, issuedAt, SAMPLE_NUMBER, issuedAt);
        byte[] bytes;
        try {
            bytes = pdf.generate(model, SAMPLE_NUMBER, issuedAt.atZone(ZoneId.of("Asia/Shanghai")).toLocalDateTime(), "学生事务人员（演示）");
        } catch (IOException ex) {
            throw new IllegalStateException("生成演示证明示例 PDF 失败", ex);
        }
        jdbc.sql("INSERT INTO certificate_result(request_id,document_number,file_name,file_bytes,issued_at) VALUES(:id,:num,:fname,:bytes,:issued)")
            .param("id",reqId).param("num",SAMPLE_NUMBER).param("fname","在校证明-演示-"+SAMPLE_NUMBER+".pdf")
            .param("bytes",bytes).param("issued",Timestamp.valueOf(issuedAt)).update();
    }
}
