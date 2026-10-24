package cn.zzuit.campus.leave;

import static cn.zzuit.campus.leave.LeaveModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LeaveService {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final cn.zzuit.campus.notice.NoticeWriter notices;
    private static final String SELECT="SELECT r.*,f.file_name FROM leave_request r LEFT JOIN leave_file f ON f.id=r.file_id";
    private static final String COUNSELOR_VALID="""
        JOIN app_user u ON u.id=a.counselor_id AND u.enabled=TRUE
        JOIN class_group cg ON cg.id=a.class_group_id JOIN major m ON m.id=cg.major_id AND m.department_id=u.department_id
        WHERE EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_code='COUNSELOR')
        AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_code='TEACHER')
        """;
    public LeaveService(JdbcClient jdbc,IdentityService identities,cn.zzuit.campus.notice.NoticeWriter notices) { this.jdbc=jdbc; this.identities=identities; this.notices=notices; }
    // Same lock order as foundation changes: current identity and reviewer scope cannot change mid-write.
    private void lock() { jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update(); }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
    private void permission(CampusPrincipal user,String view) {
        if(!List.of("mine","review").contains(view)) bad("请假视图无效");
        identities.requirePermission(user,view.equals("mine")?"leave:apply":"leave:review");
    }
    private long counselor(long group) {
        return jdbc.sql("SELECT u.id FROM counselor_assignment a "+COUNSELOR_VALID+" AND a.class_group_id=:g")
            .param("g",group).query(Long.class).optional().orElse(0L);
    }
    private Request find(long id) { return jdbc.sql(SELECT+" WHERE r.id=:id").param("id",id).query(this::row).optional().orElseThrow(this::missing); }
    public Request detail(CampusPrincipal user,String view,long id) {
        permission(user,view); var request=find(id);
        if(view.equals("mine")) { if(request.studentId()!=user.id()) throw missing(); }
        else if(request.status().equals("DRAFT") || request.studentId()==user.id() || counselor(request.classGroupId())!=user.id()) throw missing();
        return request;
    }
    public Page<Request> list(CampusPrincipal user,String view,String status,int page,int size) {
        permission(user,view); pagination(page,size);
        if(status!=null && !List.of("DRAFT","SUBMITTED","RETURNED","APPROVED","RETURN_PENDING","COMPLETED","REJECTED","WITHDRAWN").contains(status)) bad("请假状态无效");
        var params=new HashMap<String,Object>(); params.put("user",user.id());
        String where=view.equals("mine")?" WHERE r.student_id=:user":" WHERE r.status<>'DRAFT' AND r.student_id<>:user AND r.class_group_id IN (SELECT a.class_group_id FROM counselor_assignment a "+COUNSELOR_VALID+" AND u.id=:user)";
        if(status!=null) { where+=" AND r.status=:status"; params.put("status",status); }
        long total=jdbc.sql("SELECT COUNT(*) FROM leave_request r"+where).params(params).query(Long.class).single();
        var items=jdbc.sql(SELECT+where+" ORDER BY r.updated_at DESC,r.id DESC LIMIT :limit OFFSET :offset").params(params).param("limit",size).param("offset",((long)page-1)*size).query(this::row).list();
        return new Page<>(items,total,page,size);
    }
    public Eligibility eligibility(CampusPrincipal user) {
        permission(user,"mine"); Long group=jdbc.sql("SELECT class_group_id FROM app_user WHERE id=:u").param("u",user.id()).query(Long.class).optional().orElse(null);
        String name=group==null?null:jdbc.sql("SELECT name FROM class_group WHERE id=:g").param("g",group).query(String.class).single();
        long reviewer=group==null?0:counselor(group);
        String reviewerName=reviewer==0?null:actor(reviewer);
        return new Eligibility(group,name,reviewerName,reviewer>0,group==null?"当前账号未分配行政班，请联系管理员核对。":reviewer==0?"行政班尚未配置有效辅导员，可保存草稿，暂不能提交。":"申请将由当前负责班级的辅导员处理。",30);
    }
    private void validate(Draft input) {
        var issues=draftIssues(input);
        if(!issues.isEmpty()) bad(issues.getFirst());
    }
    // Both the ordinary form and the assistant preview use the same business checks.
    public List<String> draftIssues(Draft input) {
        var issues=new ArrayList<String>();
        if(!List.of("PERSONAL","SICK","OTHER").contains(input.reasonType()==null?"":input.reasonType())) issues.add("请选择请假类型");
        if(input.reason()==null || input.reason().isBlank() || input.reason().length()>2000) issues.add("请填写 1—2000 字的请假事由");
        if(input.startsAt()==null || input.endsAt()==null) issues.add("请补齐开始和结束时间");
        else {
            if(!input.endsAt().isAfter(input.startsAt())) issues.add("结束时间须晚于开始时间");
            if(Duration.between(input.startsAt(),input.endsAt()).compareTo(Duration.ofDays(30))>0) issues.add("演示流程单次最多申请 30 天；正式规则须由学校确认");
        }
        if(input.contact()==null || input.contact().length()>80 || !input.contact().trim().matches("(?:\\+?[0-9][0-9 -]{6,23}|[^\\s@]+@[^\\s@]+\\.[^\\s@]+)")) issues.add("请填写可联系的手机号码或邮箱");
        return List.copyOf(issues);
    }
    private void editable(Request request) { if(!List.of("DRAFT","RETURNED").contains(request.status())) conflict("只有草稿或待补交申请可修改，已拒绝或撤回请新建申请"); }
    private void overlap(long student,long exclude,LocalDateTime start,LocalDateTime end) {
        if(jdbc.sql("SELECT COUNT(*) FROM leave_request WHERE student_id=:u AND id<>:id AND status IN ('SUBMITTED','RETURNED','APPROVED','RETURN_PENDING') AND starts_at<:end AND ends_at>:start")
            .param("u",student).param("id",exclude).param("start",Timestamp.valueOf(start)).param("end",Timestamp.valueOf(end)).query(Long.class).single()>0) conflict("该时间段与本人待审核、待补交或已通过请假重叠，请先核对已有申请");
    }
    @Transactional public Request save(CampusPrincipal user,Long id,Draft input) {
        lock(); permission(user,"mine"); validate(input); var eligibility=eligibility(user);
        if(eligibility.classGroupId()==null) conflict("未分配行政班，无法建档请假申请");
        if(id==null) {
            if(input.version()!=0) stale();
            var prior=jdbc.sql(SELECT+" WHERE r.student_id=:u AND r.request_key=:key").param("u",user.id()).param("key",input.requestKey()).query(this::row).optional();
            if(prior.isPresent()) {
                var r=prior.get();
                if(r.version()!=0 || !r.reasonType().equals(input.reasonType()) || !r.reason().equals(input.reason().trim()) || !r.contact().equals(input.contact().trim()) || !r.startsAt().equals(input.startsAt()) || !r.endsAt().equals(input.endsAt())) conflict("重复保存请求与原草稿不一致，请打开已有记录核对");
                return r;
            }
            var profile=identities.profile(user.id()); var key=new GeneratedKeyHolder();
            jdbc.sql("INSERT INTO leave_request(student_id,request_key,class_group_id,student_name,identity_number,class_name,reason_type,reason,contact,starts_at,ends_at) VALUES(:u,:key,:group,:name,:number,:class,:type,:reason,:contact,:start,:end)")
                .param("u",user.id()).param("key",input.requestKey()).param("group",eligibility.classGroupId()).param("name",profile.fullName()).param("number",profile.identityNumber()).param("class",eligibility.className())
                .param("type",input.reasonType()).param("reason",input.reason().trim()).param("contact",input.contact().trim()).param("start",Timestamp.valueOf(input.startsAt())).param("end",Timestamp.valueOf(input.endsAt())).update(key,"id");
            id=key.getKey().longValue(); capture(user,id,"CREATED","保存请假草稿");
        } else {
            var old=detail(user,"mine",id); version(old.version(),input.version()); editable(old);
            if(!jdbc.sql("SELECT request_key FROM leave_request WHERE id=:id").param("id",id).query(String.class).single().equals(input.requestKey())) bad("申请标识不可修改");
            if(old.status().equals("RETURNED")) overlap(user.id(),id,input.startsAt(),input.endsAt());
            jdbc.sql("UPDATE leave_request SET reason_type=:type,reason=:reason,contact=:contact,starts_at=:start,ends_at=:end,class_group_id=:group,class_name=:class,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                .param("type",input.reasonType()).param("reason",input.reason().trim()).param("contact",input.contact().trim()).param("start",Timestamp.valueOf(input.startsAt())).param("end",Timestamp.valueOf(input.endsAt()))
                .param("group",eligibility.classGroupId()).param("class",eligibility.className()).param("id",id).update();
            capture(user,id,"UPDATED","保存申请资料，旧版保留");
        }
        return find(id);
    }
    @Transactional public Request transition(CampusPrincipal user,long id,String action,Action input) {
        lock(); var request=detail(user,"mine",id); version(request.version(),input.version()); String state;
        if(action.equals("submit")) {
            editable(request);
            if(!request.startsAt().isAfter(now())) conflict("开始时间已到，请更新为未来的时间后再提交");
            var current=eligibility(user);
            if(!current.canSubmit()) conflict(current.message());
            if(!Objects.equals(current.classGroupId(),request.classGroupId())) conflict("行政班已变化，请重新保存草稿后再提交");
            if(counselor(request.classGroupId())==user.id()) conflict("不能把本人的申请交给自己处理，请管理员调整负责关系");
            overlap(user.id(),id,request.startsAt(),request.endsAt()); state="SUBMITTED";
        } else if(action.equals("withdraw")) {
            if(!List.of("DRAFT","SUBMITTED","RETURNED").contains(request.status())) conflict("当前状态不能撤回，已通过请假须另行申请变更");
            state="WITHDRAWN";
        } else { bad("操作无效"); return null; }
        change(id,state); capture(user,id,state.equals("SUBMITTED")?"SUBMITTED":"WITHDRAWN",input.comment());
        if(state.equals("SUBMITTED")) notice(counselor(request.classGroupId()),"新的请假待办",id,"待审核，请进入班级请假处理核对。");
        else if(!request.status().equals("DRAFT") && counselor(request.classGroupId())>0) notice(counselor(request.classGroupId()),"请假申请已撤回",id,"学生已撤回，原审核待办已关闭。");
        notice(user.id(),state.equals("SUBMITTED")?"请假申请已提交":"请假申请已撤回",id,state.equals("SUBMITTED")?"可在我的请假中查询处理进度。":"原记录保留，重新办理请新建申请。"); return find(id);
    }
    @Transactional public Request decide(CampusPrincipal user,long id,Decision input) {
        lock(); var request=detail(user,"review",id); version(request.version(),input.version());
        if(!request.status().equals("SUBMITTED")) conflict("申请已不在待审核状态，请刷新待办");
        String state=switch(input.action()) { case "APPROVE" -> "APPROVED"; case "RETURN" -> "RETURNED"; default -> "REJECTED"; };
        if(state.equals("APPROVED")) {
            if(!request.endsAt().isAfter(now())) conflict("请假时间已结束，不能通过过期申请，可退回学生核对");
            overlap(request.studentId(),id,request.startsAt(),request.endsAt());
            jdbc.sql("INSERT INTO leave_calendar(request_id,student_id,starts_at,ends_at) VALUES(:id,:u,:start,:end)")
                .param("id",id).param("u",request.studentId()).param("start",Timestamp.valueOf(request.startsAt())).param("end",Timestamp.valueOf(request.endsAt())).update();
        }
        change(id,state);
        jdbc.sql("UPDATE leave_request SET processing_comment=:comment WHERE id=:id").param("comment",input.comment().trim()).param("id",id).update();
        capture(user,id,state,input.comment());
        notice(request.studentId(),"请假处理结果已更新",id,switch(state) { case "APPROVED" -> "申请已通过，本人请假日程已生成。"; case "RETURNED" -> "申请待补交，请查看处理意见，补充后重新提交。"; default -> "申请未通过，请查看处理意见。"; }); return find(id);
    }
    @Transactional public Request reportReturn(CampusPrincipal user,long id,Action input) {
        lock(); var request=detail(user,"mine",id); version(request.version(),input.version());
        if(!request.status().equals("APPROVED")) conflict("只有已批准且未申报销假的请假可以申报返校");
        if(now().isBefore(request.startsAt())) conflict("请假尚未开始，不能申报返校；行程取消请联系辅导员办理变更");
        long reviewer=counselor(request.classGroupId());
        if(reviewer==0 || reviewer==user.id()) conflict("当前没有可确认销假的辅导员，请联系管理员核对负责关系");
        jdbc.sql("UPDATE leave_request SET returned_at=:time WHERE id=:id").param("time",Timestamp.valueOf(now())).param("id",id).update();
        change(id,"RETURN_PENDING"); capture(user,id,"RETURN_REPORTED",input.comment());
        notice(reviewer,"学生返校待确认销假",id,"学生已申报返校，请核对后确认销假或退回说明。");
        notice(user.id(),"返校申报已提交",id,"请等待负责班级的辅导员确认销假，可在原申请查看进度。");
        return find(id);
    }
    @Transactional public Request close(CampusPrincipal user,long id,ClosureDecision input) {
        lock(); var request=detail(user,"review",id); version(request.version(),input.version());
        if(!request.status().equals("RETURN_PENDING")) conflict("当前申请不在待确认销假状态");
        boolean confirmed=input.action().equals("CONFIRM");
        jdbc.sql("UPDATE leave_request SET processing_comment=:comment,closed_at=:closed WHERE id=:id")
            .param("comment",input.comment().trim()).param("closed",confirmed?Timestamp.valueOf(now()):null).param("id",id).update();
        if(confirmed) {
            // Keep the approved request snapshot intact; shorten only the derived absence calendar.
            var returned=jdbc.sql("SELECT returned_at FROM leave_request WHERE id=:id").param("id",id).query(LocalDateTime.class).single();
            jdbc.sql("UPDATE leave_calendar SET ends_at=:end WHERE request_id=:id AND ends_at>:end")
                .param("end",Timestamp.valueOf(returned)).param("id",id).update();
        } else jdbc.sql("UPDATE leave_request SET returned_at=NULL WHERE id=:id").param("id",id).update();
        change(id,confirmed?"COMPLETED":"APPROVED");
        capture(user,id,confirmed?"RETURN_CONFIRMED":"RETURN_DECLINED",input.comment());
        notice(request.studentId(),confirmed?"请假已确认销假":"返校申报需要重新核对",id,confirmed?"辅导员已确认返校，本次请假已结束，原申请和办理记录保留。":"销假未确认："+input.comment().trim()+"；请核对后重新申报返校。");
        return find(id);
    }
    private void change(long id,String status) { jdbc.sql("UPDATE leave_request SET status=:s,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("s",status).param("id",id).update(); }
    private String actor(long id) { return jdbc.sql("SELECT full_name FROM app_user WHERE id=:id").param("id",id).query(String.class).single(); }
    private void capture(CampusPrincipal user,long id,String action,String comment) {
        jdbc.sql("INSERT INTO leave_revision(request_id,request_version,actor_name,action,comment,status,reason_type,reason,contact,starts_at,ends_at,file_id) SELECT id,version,:actor,:action,:comment,status,reason_type,reason,contact,starts_at,ends_at,file_id FROM leave_request WHERE id=:id")
            .param("actor",actor(user.id())).param("action",action).param("comment",comment.trim()).param("id",id).update();
    }
    private void notice(long user,String title,long id,String body) {
        long owner=jdbc.sql("SELECT student_id FROM leave_request WHERE id=:id").param("id",id).query(Long.class).single();
        notices.write(user,title,"LEAVE",id,user==owner?"MINE":"REVIEW",body);
    }
    public Page<Revision> history(CampusPrincipal user,String view,long id,int page,int size) {
        detail(user,view,id); pagination(page,size); long total=jdbc.sql("SELECT COUNT(*) FROM leave_revision WHERE request_id=:id").param("id",id).query(Long.class).single();
        var items=jdbc.sql("SELECT r.*,f.file_name FROM leave_revision r LEFT JOIN leave_file f ON f.id=r.file_id WHERE r.request_id=:id ORDER BY r.id DESC LIMIT :limit OFFSET :offset")
            .param("id",id).param("limit",size).param("offset",((long)page-1)*size).query((r,n)->new Revision(r.getLong("id"),r.getInt("request_version"),r.getString("actor_name"),r.getString("action"),r.getString("comment"),r.getString("status"),r.getString("reason_type"),r.getString("reason"),r.getString("contact"),r.getTimestamp("starts_at").toLocalDateTime(),r.getTimestamp("ends_at").toLocalDateTime(),r.getObject("file_id",Long.class),r.getString("file_name"),r.getTimestamp("created_at").toLocalDateTime())).list();
        return new Page<>(items,total,page,size);
    }
    @Transactional public Request attach(CampusPrincipal user,long id,int expected,MultipartFile file) throws java.io.IOException {
        lock(); var request=detail(user,"mine",id); version(request.version(),expected); editable(request);
        if(file.isEmpty() || file.getSize()>2*1024*1024) bad("附件须为 1 字节至 2 MB 的 PDF 或 UTF-8 文本");
        byte[] bytes=file.getBytes(); String type;
        if(bytes.length>=5 && new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) type="application/pdf";
        else {
            try { java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)); } catch(java.nio.charset.CharacterCodingException ex) { bad("文本附件必须使用 UTF-8 编码"); }
            if(file.getOriginalFilename()==null || !file.getOriginalFilename().toLowerCase(Locale.ROOT).endsWith(".txt")) bad("仅支持 PDF 或 UTF-8 .txt 附件"); type="text/plain";
        }
        String name=file.getOriginalFilename()==null?"请假材料.pdf":file.getOriginalFilename().replaceAll("[\\\\/\\p{Cntrl}]","_"); if(name.length()>160) name=name.substring(name.length()-160);
        var key=new GeneratedKeyHolder(); jdbc.sql("INSERT INTO leave_file(request_id,file_name,file_type,file_bytes) VALUES(:id,:name,:type,:bytes)").param("id",id).param("name",name).param("type",type).param("bytes",bytes).update(key,"id");
        jdbc.sql("UPDATE leave_request SET file_id=:file,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id").param("file",key.getKey().longValue()).param("id",id).update();
        capture(user,id,"FILE","补充申请材料，旧版附件保留"); return find(id);
    }
    public Attachment download(CampusPrincipal user,String view,long fileId) {
        permission(user,view); long id=jdbc.sql("SELECT request_id FROM leave_file WHERE id=:id").param("id",fileId).query(Long.class).optional().orElseThrow(this::missing);
        detail(user,view,id);
        return jdbc.sql("SELECT file_name,file_type,file_bytes FROM leave_file WHERE id=:id").param("id",fileId).query((r,n)->new Attachment(r.getString(1),r.getString(2),r.getBytes(3))).single();
    }
    public List<Event> calendar(CampusPrincipal user,LocalDate from,LocalDate to) {
        permission(user,"mine"); if(!to.isAfter(from) || to.isAfter(from.plusDays(370))) bad("日程查询须为 1—370 天的时间区间");
        return jdbc.sql("SELECT * FROM leave_calendar WHERE student_id=:u AND starts_at<:end AND ends_at>:start ORDER BY starts_at,request_id")
            .param("u",user.id()).param("start",Timestamp.valueOf(from.atStartOfDay())).param("end",Timestamp.valueOf(to.atStartOfDay())).query((r,n)->new Event(r.getLong("request_id"),r.getTimestamp("starts_at").toLocalDateTime(),r.getTimestamp("ends_at").toLocalDateTime())).list();
    }
    public AssignmentCatalog assignments(CampusPrincipal user) {
        identities.requirePermission(user,"foundation:manage");
        var assignments=jdbc.sql("SELECT cg.id,cg.name,d.name department_name,a.counselor_id,u.full_name counselor_name,COALESCE(a.version,0) version FROM class_group cg JOIN major m ON m.id=cg.major_id JOIN department d ON d.id=m.department_id LEFT JOIN counselor_assignment a ON a.class_group_id=cg.id LEFT JOIN app_user u ON u.id=a.counselor_id ORDER BY cg.id")
            .query(this::assignmentRow).list();
        var counselors=jdbc.sql("SELECT u.id,u.full_name,d.name,u.department_id FROM app_user u JOIN department d ON d.id=u.department_id WHERE u.enabled=TRUE AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_code='TEACHER') AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_code='COUNSELOR') ORDER BY u.id")
            .query((r,n)->new Counselor(r.getLong(1),r.getString(2),r.getString(3),r.getLong(4))).list();
        return new AssignmentCatalog(assignments,counselors);
    }
    @Transactional public Assignment assign(CampusPrincipal user,long group,Assign input) {
        lock(); identities.requirePermission(user,"foundation:manage");
        var current=assignments(user).assignments().stream().filter(a->a.classGroupId()==group).findFirst().orElseThrow(this::missing); version(current.version(),input.version());
        if(Objects.equals(current.counselorId(),input.counselorId())) conflict("负责关系未变化");
        if(input.counselorId()==null) {
            if(jdbc.sql("SELECT COUNT(*) FROM leave_request WHERE class_group_id=:g AND status IN ('SUBMITTED','RETURNED','APPROVED','RETURN_PENDING')").param("g",group).query(Long.class).single()>0) conflict("班级仍有待审核或待补交申请，请先移交给有效辅导员，不能清空负责关系");
        } else if(jdbc.sql("SELECT COUNT(*) FROM app_user u JOIN class_group cg ON cg.id=:g JOIN major m ON m.id=cg.major_id WHERE u.id=:id AND u.enabled=TRUE AND u.department_id=m.department_id AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_code='TEACHER') AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_code='COUNSELOR')")
            .param("g",group).param("id",input.counselorId()).query(Long.class).single()!=1) bad("须选择本学院启用的教师及辅导员岗位人员");
        if(input.counselorId()!=null && jdbc.sql("SELECT COUNT(*) FROM leave_request WHERE class_group_id=:g AND student_id=:u AND status IN ('SUBMITTED','RETURNED','APPROVED','RETURN_PENDING')").param("g",group).param("u",input.counselorId()).query(Long.class).single()>0) conflict("不能将待处理的本人申请移交给申请人，请选择其他辅导员");
        if(jdbc.sql("SELECT COUNT(*) FROM counselor_assignment WHERE class_group_id=:g").param("g",group).query(Long.class).single()==0)
            jdbc.sql("INSERT INTO counselor_assignment(class_group_id,counselor_id,version) VALUES(:g,:id,1)").param("g",group).param("id",input.counselorId()).update();
        else jdbc.sql("UPDATE counselor_assignment SET counselor_id=:id,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE class_group_id=:g").param("id",input.counselorId()).param("g",group).update();
        String summary=current.className()+"："+Objects.toString(current.counselorName(),"未分配")+" → "+(input.counselorId()==null?"未分配":actor(input.counselorId()));
        jdbc.sql("INSERT INTO foundation_audit(actor_id,actor_name,action,entity_type,entity_id,summary,reason) VALUES(:u,:name,'COUNSELOR_ASSIGN','CLASS_GROUP',:g,:summary,:reason)").param("u",user.id()).param("name",actor(user.id())).param("g",group).param("summary",summary).param("reason",input.reason().trim()).update();
        if(input.counselorId()!=null) jdbc.sql("INSERT INTO user_notice(user_id,title,body) VALUES(:u,'负责班级已更新',:body)").param("u",input.counselorId()).param("body",current.className()+"的负责关系已更新，请进入班级请假处理查看当前待办。").update();
        return assignments(user).assignments().stream().filter(a->a.classGroupId()==group).findFirst().orElseThrow(this::missing);
    }
    private Assignment assignmentRow(ResultSet r,int n) throws SQLException { return new Assignment(r.getLong("id"),r.getString("name"),r.getString("department_name"),r.getObject("counselor_id",Long.class),r.getString("counselor_name"),r.getInt("version")); }
    private Request row(ResultSet r,int n) throws SQLException { return new Request(r.getLong("id"),r.getString("request_key"),r.getLong("student_id"),r.getString("student_name"),r.getString("identity_number"),r.getLong("class_group_id"),r.getString("class_name"),r.getString("reason_type"),r.getString("reason"),r.getString("contact"),r.getTimestamp("starts_at").toLocalDateTime(),r.getTimestamp("ends_at").toLocalDateTime(),r.getString("status"),r.getString("processing_comment"),r.getInt("version"),r.getObject("file_id",Long.class),r.getString("file_name"),r.getTimestamp("created_at").toLocalDateTime(),r.getTimestamp("updated_at").toLocalDateTime()); }
    private void pagination(int page,int size) { if(page<1 || page>1000000 || size<1 || size>100) bad("页码须大于零，每页最多 100 条"); }
    private void version(int current,int expected) { if(current!=expected) stale(); }
    private void stale() { conflict("申请或负责关系已更新，请刷新核对后操作"); }
    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"LEAVE_NOT_FOUND","申请、材料或负责关系不存在"); }
    private void bad(String message) { throw new ApiException(HttpStatus.BAD_REQUEST,"LEAVE_INVALID",message); }
    private void conflict(String message) { throw new ApiException(HttpStatus.CONFLICT,"LEAVE_CONFLICT",message); }
}
