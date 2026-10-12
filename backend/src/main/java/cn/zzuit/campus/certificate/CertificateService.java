package cn.zzuit.campus.certificate;

import static cn.zzuit.campus.certificate.CertificateModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.notice.NoticeWriter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CertificateService {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final NoticeWriter notices;
    private final CertificatePdf pdf;
    private static final String SELECT="SELECT r.*,f.document_number,f.issued_at FROM certificate_request r LEFT JOIN certificate_result f ON f.request_id=r.id";
    private static final String REVIEWER="u.enabled=TRUE AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_code='STUDENT_AFFAIRS')";
    public CertificateService(JdbcClient jdbc,IdentityService identities,NoticeWriter notices,CertificatePdf pdf) {
        this.jdbc=jdbc; this.identities=identities; this.notices=notices; this.pdf=pdf;
    }
    private void lock() { jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update(); }
    private void permission(CampusPrincipal user,String view) {
        if(!List.of("mine","review").contains(view)) bad("证明视图无效");
        identities.requirePermission(user,view.equals("mine")?"certificate:apply":"certificate:review");
    }
    private long department(CampusPrincipal user) {
        return jdbc.sql("SELECT u.department_id FROM app_user u JOIN department d ON d.id=u.department_id AND d.kind='COLLEGE' WHERE u.id=:id")
            .param("id",user.id()).query(Long.class).optional().orElseThrow(()->new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","证明处理岗位须归属学院"));
    }
    private Identity currentIdentity(long student) {
        return jdbc.sql("""
            SELECT u.department_id,u.class_group_id,u.full_name,u.identity_number,d.name department_name,m.name major_name,cg.name class_name,c.name campus_name
            FROM app_user u JOIN department d ON d.id=u.department_id AND d.kind='COLLEGE'
            JOIN class_group cg ON cg.id=u.class_group_id JOIN major m ON m.id=cg.major_id AND m.department_id=u.department_id
            JOIN campus c ON c.id=u.campus_id
            WHERE u.id=:id AND u.enabled=TRUE AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_code='STUDENT')
            """).param("id",student).query((r,n)->new Identity(r.getLong("department_id"),r.getLong("class_group_id"),r.getString("full_name"),r.getString("identity_number"),r.getString("department_name"),r.getString("major_name"),r.getString("class_name"),r.getString("campus_name")))
            .optional().orElseThrow(()->new ApiException(HttpStatus.CONFLICT,"IDENTITY_INCOMPLETE","学生登记资料不完整或身份已变化，请核对学院、专业、班级和校区"));
    }
    private List<Long> reviewers(long academy,long applicant) {
        return jdbc.sql("SELECT u.id FROM app_user u WHERE "+REVIEWER+" AND u.department_id=:d AND u.id<>:s ORDER BY u.id")
            .param("d",academy).param("s",applicant).query(Long.class).list();
    }
    public Eligibility eligibility(CampusPrincipal user) {
        permission(user,"mine"); var identity=currentIdentity(user.id()); boolean available=!reviewers(identity.departmentId(),user.id()).isEmpty();
        return new Eligibility(identity,available,available?"由所属学院当前学生事务人员处理。":"所属学院尚无有效学生事务处理人员，可保存草稿，暂不能提交。");
    }
    private Request find(long id) { return jdbc.sql(SELECT+" WHERE r.id=:id").param("id",id).query(this::row).optional().orElseThrow(this::missing); }
    public Request detail(CampusPrincipal user,String view,long id) {
        permission(user,view); var request=find(id);
        if(view.equals("mine")) { if(request.studentId()!=user.id()) throw missing(); }
        else if(request.status().equals("DRAFT") || request.studentId()==user.id() || request.identity().departmentId()!=department(user)) throw missing();
        return request;
    }
    public Page<Request> list(CampusPrincipal user,String view,String status,int page,int size) {
        permission(user,view); pagination(page,size); status(status);
        var params=new HashMap<String,Object>(); params.put("user",user.id());
        String where=" WHERE r.student_id=:user";
        if(view.equals("review")) { where=" WHERE r.status<>'DRAFT' AND r.student_id<>:user AND r.department_id=:d"; params.put("d",department(user)); }
        if(status!=null) { where+=" AND r.status=:status"; params.put("status",status); }
        long total=jdbc.sql("SELECT COUNT(*) FROM certificate_request r"+where).params(params).query(Long.class).single();
        var rows=jdbc.sql(SELECT+where+" ORDER BY r.updated_at DESC,r.id DESC LIMIT :limit OFFSET :offset").params(params).param("limit",size).param("offset",((long)page-1)*size).query(this::row).list();
        return new Page<>(rows,total,page,size);
    }
    @Transactional public Request save(CampusPrincipal user,Long id,Draft input) {
        lock(); permission(user,"mine"); var identity=currentIdentity(user.id());
        if(!Boolean.TRUE.equals(input.identityConfirmed())) bad("请确认本人登记资料");
        if(input.expectedIdentity()!=null && !input.expectedIdentity().equals(identity)) conflict("登记资料已变化，请重新核对本人资料并确认后保存草稿");
        if(id==null) {
            if(input.version()!=0) stale();
            var prior=jdbc.sql(SELECT+" WHERE r.student_id=:u AND r.request_key=:key").param("u",user.id()).param("key",input.requestKey()).query(this::row).optional();
            if(prior.isPresent()) {
                var old=prior.get();
                if(old.version()!=0 || !old.purpose().equals(input.purpose().trim()) || !old.identity().equals(identity)) conflict("重复请求与原草稿不一致，请打开已有记录核对");
                return old;
            }
            var key=new GeneratedKeyHolder();
            jdbc.sql("""
                INSERT INTO certificate_request(student_id,request_key,department_id,class_group_id,student_name,identity_number,department_name,major_name,class_name,campus_name,purpose,delivery_method,identity_confirmed)
                VALUES(:u,:key,:d,:g,:name,:number,:dn,:major,:class,:campus,:purpose,'DIGITAL',TRUE)
                """).param("u",user.id()).param("key",input.requestKey()).params(snapshot(identity)).param("purpose",input.purpose().trim()).update(key,"id");
            id=key.getKey().longValue(); capture(user,id,"CREATED","保存证明草稿并确认登记资料");
        } else {
            var old=detail(user,"mine",id); version(old.version(),input.version()); editable(old);
            if(!old.requestKey().equals(input.requestKey())) bad("申请标识不可修改");
            jdbc.sql("""
                UPDATE certificate_request SET department_id=:d,class_group_id=:g,student_name=:name,identity_number=:number,
                department_name=:dn,major_name=:major,class_name=:class,campus_name=:campus,purpose=:purpose,
                identity_confirmed=TRUE,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id
                """).params(snapshot(identity)).param("purpose",input.purpose().trim()).param("id",id).update();
            capture(user,id,"UPDATED","保存资料，旧版保留");
        }
        return find(id);
    }
    @Transactional public Request transition(CampusPrincipal user,long id,String action,Action input) {
        lock(); var request=detail(user,"mine",id); version(request.version(),input.version()); String next;
        if(action.equals("submit")) {
            editable(request);
            if(!currentIdentity(user.id()).equals(request.identity())) conflict("登记资料已变化，请重新编辑、确认并保存草稿后提交");
            if(reviewers(request.identity().departmentId(),user.id()).isEmpty()) conflict("所属学院尚无可处理本申请的学生事务人员");
            next="SUBMITTED";
        } else if(action.equals("withdraw")) {
            if(!List.of("DRAFT","SUBMITTED","RETURNED").contains(request.status())) conflict("当前记录已关闭，不能撤回"); next="WITHDRAWN";
        } else { bad("证明操作无效"); return null; }
        change(id,next,null); capture(user,id,next,input.comment());
        if(next.equals("SUBMITTED") || !request.status().equals("DRAFT")) {
            for(long reviewer:reviewers(request.identity().departmentId(),user.id())) notices.write(reviewer,next.equals("SUBMITTED")?"新的证明申请待办":"证明申请已撤回","CERTIFICATE",id,"REVIEW",next.equals("SUBMITTED")?"待审核，请进入学院证明申请处理核对。":"学生已撤回，原待办关闭。");
        }
        notices.write(user.id(),next.equals("SUBMITTED")?"证明申请已提交":"证明申请已撤回","CERTIFICATE",id,"MINE",next.equals("SUBMITTED")?"可在我的办事中查询进度。":"原记录保留，重新办理请新建申请。");
        return find(id);
    }
    @Transactional public Request decide(CampusPrincipal user,long id,Decision input) {
        lock(); var request=detail(user,"review",id); version(request.version(),input.version());
        if(!request.status().equals("SUBMITTED")) conflict("申请不在待审核状态，请刷新待办");
        String next=switch(input.action()) { case "APPROVE" -> "COMPLETED"; case "RETURN" -> "RETURNED"; default -> "REJECTED"; };
        if(next.equals("COMPLETED")) {
            if(!currentIdentity(request.studentId()).equals(request.identity())) conflict("学生当前登记资料与申请快照不一致，请退回重新确认");
            LocalDateTime issued=LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
            String number="DEMO-"+issued.format(DateTimeFormatter.BASIC_ISO_DATE)+"-"+String.format(Locale.ROOT,"%06d",id);
            byte[] bytes;
            try { bytes=pdf.generate(request,number,issued,actor(user.id())); }
            catch(java.io.IOException ex) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"RESULT_UNAVAILABLE","证明生成失败，本次处理未生效，请稍后重试"); }
            jdbc.sql("INSERT INTO certificate_result(request_id,document_number,file_name,file_bytes,issued_at) VALUES(:id,:n,:name,:bytes,:time)")
                .param("id",id).param("n",number).param("name","在校证明-演示-"+number+".pdf").param("bytes",bytes).param("time",Timestamp.valueOf(issued)).update();
        }
        change(id,next,input.comment().trim()); capture(user,id,next,input.comment());
        notices.write(request.studentId(),"证明处理结果已更新","CERTIFICATE",id,"MINE",switch(next) { case "COMPLETED" -> "办理完成，可下载受控演示证明。"; case "RETURNED" -> "请查看处理意见并补充资料后重新提交。"; default -> "申请未通过，请查看处理意见。"; });
        return find(id);
    }
    public Result result(CampusPrincipal user,String view,long id) {
        var request=detail(user,view,id); if(!request.status().equals("COMPLETED")) throw missing();
        return jdbc.sql("SELECT file_name,file_bytes FROM certificate_result WHERE request_id=:id").param("id",id)
            .query((r,n)->new Result(r.getString(1),r.getBytes(2))).optional().orElseThrow(this::missing);
    }
    public Page<Revision> history(CampusPrincipal user,String view,long id,int page,int size) {
        detail(user,view,id); pagination(page,size);
        long total=jdbc.sql("SELECT COUNT(*) FROM certificate_revision WHERE request_id=:id").param("id",id).query(Long.class).single();
        var rows=jdbc.sql("SELECT * FROM certificate_revision WHERE request_id=:id ORDER BY id DESC LIMIT :limit OFFSET :offset")
            .param("id",id).param("limit",size).param("offset",((long)page-1)*size).query((r,n)->new Revision(r.getLong("id"),r.getInt("request_version"),r.getString("actor_name"),r.getString("action"),r.getString("comment"),r.getString("status"),r.getString("student_name"),r.getString("identity_number"),r.getString("department_name"),r.getString("major_name"),r.getString("class_name"),r.getString("campus_name"),r.getString("purpose"),r.getString("delivery_method"),r.getTimestamp("created_at").toLocalDateTime())).list();
        return new Page<>(rows,total,page,size);
    }
    private void change(long id,String status,String comment) {
        jdbc.sql("UPDATE certificate_request SET status=:s,processing_comment=COALESCE(:c,processing_comment),version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
            .param("id",id).param("s",status).param("c",comment).update();
    }
    private void capture(CampusPrincipal user,long id,String action,String comment) {
        jdbc.sql("""
            INSERT INTO certificate_revision(request_id,request_version,actor_name,action,comment,status,student_name,identity_number,department_name,major_name,class_name,campus_name,purpose,delivery_method)
            SELECT id,version,:actor,:action,:comment,status,student_name,identity_number,department_name,major_name,class_name,campus_name,purpose,delivery_method FROM certificate_request WHERE id=:id
            """).param("actor",actor(user.id())).param("action",action).param("comment",comment.trim()).param("id",id).update();
    }
    private Map<String,Object> snapshot(Identity identity) {
        return Map.of("d",identity.departmentId(),"g",identity.classGroupId(),"name",identity.studentName(),"number",identity.identityNumber(),"dn",identity.departmentName(),"major",identity.majorName(),"class",identity.className(),"campus",identity.campusName());
    }
    private String actor(long id) { return jdbc.sql("SELECT full_name FROM app_user WHERE id=:id").param("id",id).query(String.class).single(); }
    private Request row(ResultSet r,int n) throws SQLException {
        var identity=new Identity(r.getLong("department_id"),r.getLong("class_group_id"),r.getString("student_name"),r.getString("identity_number"),r.getString("department_name"),r.getString("major_name"),r.getString("class_name"),r.getString("campus_name"));
        var issued=r.getTimestamp("issued_at");
        return new Request(r.getLong("id"),r.getString("request_key"),r.getLong("student_id"),identity,r.getString("purpose"),r.getString("delivery_method"),r.getBoolean("identity_confirmed"),r.getString("status"),r.getString("processing_comment"),r.getInt("version"),r.getTimestamp("created_at").toLocalDateTime(),r.getTimestamp("updated_at").toLocalDateTime(),r.getString("document_number"),issued==null?null:issued.toLocalDateTime());
    }
    private void editable(Request request) { if(!List.of("DRAFT","RETURNED").contains(request.status())) conflict("只有草稿或待补交记录可修改"); }
    private void status(String status) { if(status!=null && !List.of("DRAFT","SUBMITTED","RETURNED","COMPLETED","REJECTED","WITHDRAWN").contains(status)) bad("证明状态无效"); }
    private void pagination(int page,int size) { if(page<1 || page>1000000 || size<1 || size>100) bad("分页参数无效"); }
    private void version(int actual,int expected) { if(actual!=expected) stale(); }
    private void stale() { throw new ApiException(HttpStatus.CONFLICT,"VERSION_CONFLICT","记录已变化，请刷新后核对最新状态"); }
    private void bad(String message) { throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CERTIFICATE",message); }
    private void conflict(String message) { throw new ApiException(HttpStatus.CONFLICT,"CERTIFICATE_CONFLICT",message); }
    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"CERTIFICATE_NOT_FOUND","证明记录不存在或当前无权访问"); }
}
