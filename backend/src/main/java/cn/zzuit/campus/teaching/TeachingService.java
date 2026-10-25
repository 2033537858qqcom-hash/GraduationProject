package cn.zzuit.campus.teaching;

import static cn.zzuit.campus.teaching.TeachingModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TeachingService {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    public TeachingService(JdbcClient jdbc, IdentityService identities) { this.jdbc = jdbc; this.identities = identities; }
    private static final String CLASS_SELECT = """
        SELECT tc.id,tc.term_id,tc.course_id,tc.name,c.name course_name,c.credits,d.name department_name,
        (SELECT COUNT(*) FROM teaching_member m JOIN app_user u ON u.id=m.student_id AND u.enabled=TRUE
         WHERE m.class_id=tc.id AND m.active=TRUE) member_count
        FROM teaching_class tc JOIN course c ON c.id=tc.course_id JOIN department d ON d.id=tc.department_id
        """;

    public void permission(CampusPrincipal user, String permission) {
        identities.requirePermission(user,permission);
    }
    public List<Term> terms(CampusPrincipal user) {
        if (identities.permissions(user.id()).contains("learning:read")) permission(user,"learning:read");
        else if (identities.permissions(user.id()).contains("teaching:manage")) permission(user,"teaching:manage");
        else permission(user,"academic:manage");
        return jdbc.sql("SELECT * FROM academic_term ORDER BY starts_on DESC,id DESC")
            .query((r,n) -> new Term(r.getLong("id"),r.getString("name"),r.getDate("starts_on").toLocalDate(),r.getDate("ends_on").toLocalDate())).list();
    }
    public List<ClassInfo> classes(CampusPrincipal user, long termId, boolean teacher) {
        permission(user,teacher ? "teaching:manage" : "learning:read");
        String relation = teacher ? "teaching_instructor" : "teaching_member";
        String column = teacher ? "teacher_id" : "student_id";
        return jdbc.sql(CLASS_SELECT + " WHERE tc.term_id=:t AND EXISTS(SELECT 1 FROM " + relation
            + " x WHERE x.class_id=tc.id AND x." + column + "=:u AND x.active=TRUE) ORDER BY tc.id")
            .param("t",termId).param("u",user.id()).query(this::classInfo).list();
    }
    public void classAccess(CampusPrincipal user, long id, boolean teacher) {
        permission(user,teacher ? "teaching:manage" : "learning:read");
        String table = teacher ? "teaching_instructor" : "teaching_member";
        String column = teacher ? "teacher_id" : "student_id";
        if (jdbc.sql("SELECT COUNT(*) FROM " + table + " WHERE class_id=:c AND " + column + "=:u AND active=TRUE")
            .param("c",id).param("u",user.id()).query(Long.class).single() == 0) missing();
    }
    public void academicAccess(CampusPrincipal user, long classId) {
        permission(user,"academic:manage");
        if (jdbc.sql("SELECT COUNT(*) FROM teaching_class tc JOIN app_user u ON u.department_id=tc.department_id WHERE tc.id=:c AND u.id=:u")
            .param("c",classId).param("u",user.id()).query(Long.class).single() == 0) missing();
    }
    public ClassDetail detail(CampusPrincipal user, long id, String mode) {
        boolean academic = mode.equals("academic"), teacher = mode.equals("teacher");
        if (academic) academicAccess(user,id); else classAccess(user,id,teacher);
        var info = jdbc.sql(CLASS_SELECT + " WHERE tc.id=:c").param("c",id).query(this::classInfo).single();
        var instructors = people(id,true);
        var members = teacher || academic ? people(id,false) : List.<Person>of();
        var materials = jdbc.sql("""
            SELECT m.*,u.full_name author_name FROM course_material m JOIN app_user u ON u.id=m.author_id
            WHERE m.class_id=:c
            """ + (!academic && !teacher ? " AND m.status='PUBLISHED'" : "") + " ORDER BY m.updated_at DESC,m.id DESC")
            .param("c",id).query(this::material).list();
        var notices = jdbc.sql("SELECT n.*,u.full_name author_name FROM teaching_notice n JOIN app_user u ON u.id=n.author_id WHERE class_id=:c ORDER BY n.id DESC")
            .param("c",id).query((r,n) -> new Notice(r.getLong("id"),r.getString("title"),r.getString("body"),r.getString("author_name"),time(r,"created_at"))).list();
        return new ClassDetail(info,instructors,members,materials,notices,
            academic ? gradesForClass(id) : List.of(),academic ? examsForClass(id) : List.of());
    }
    public List<Person> people(long classId, boolean teacher) {
        return jdbc.sql("SELECT u.id,u.full_name,u.identity_number,cg.name class_name FROM app_user u JOIN "
            + (teacher ? "teaching_instructor x ON x.teacher_id=u.id" : "teaching_member x ON x.student_id=u.id")
            + " LEFT JOIN class_group cg ON cg.id=u.class_group_id WHERE x.class_id=:c AND x.active=TRUE AND u.enabled=TRUE ORDER BY u.identity_number")
            .param("c",classId).query(this::person).list();
    }
    public List<Lesson> timetable(CampusPrincipal user, long termId, LocalDate from, boolean teacher) {
        permission(user,teacher ? "teaching:manage" : "learning:read");
        var query = jdbc.sql("""
            SELECT l.*,tc.name class_name,c.name course_name,u.full_name teacher_name,cp.name campus_name
            FROM teaching_lesson l JOIN teaching_class tc ON tc.id=l.class_id JOIN course c ON c.id=tc.course_id
            JOIN app_user u ON u.id=l.teacher_id JOIN campus cp ON cp.id=l.campus_id
            JOIN teaching_instructor ti ON ti.class_id=l.class_id AND ti.teacher_id=l.teacher_id AND ti.active=TRUE
            WHERE tc.term_id=:t AND
            """ + (teacher ? "l.teacher_id=:u" : "EXISTS(SELECT 1 FROM teaching_member m WHERE m.class_id=tc.id AND m.student_id=:u AND m.active=TRUE)")
            + (from == null ? "" : " AND l.starts_at>=:s AND l.starts_at<:e") + " ORDER BY l.starts_at,l.id")
            .param("t",termId).param("u",user.id());
        if (from != null) query.param("s",Timestamp.valueOf(from.atStartOfDay())).param("e",Timestamp.valueOf(from.plusDays(7).atStartOfDay()));
        return query.query((r,n) -> new Lesson(r.getLong("id"),r.getLong("class_id"),r.getString("class_name"),r.getString("course_name"),
            r.getString("teacher_name"),r.getString("classroom"),r.getString("campus_name"),time(r,"starts_at"),time(r,"ends_at"))).list();
    }
    public List<Grade> studentGrades(CampusPrincipal user, long term) {
        permission(user,"learning:read");
        return jdbc.sql(gradeSelect() + " WHERE tc.term_id=:t AND g.student_id=:u AND g.status='PUBLISHED' AND m.active=TRUE ORDER BY c.code")
            .param("t",term).param("u",user.id()).query(this::grade).list();
    }
    public List<Exam> studentExams(CampusPrincipal user, long term) {
        permission(user,"learning:read");
        return jdbc.sql(examSelect() + " WHERE tc.term_id=:t AND e.status='PUBLISHED' AND EXISTS(SELECT 1 FROM teaching_member m WHERE m.class_id=tc.id AND m.student_id=:u AND m.active=TRUE) ORDER BY e.starts_at")
            .param("t",term).param("u",user.id()).query(this::exam).list();
    }
    public List<Grade> gradesForClass(long classId) { return jdbc.sql(gradeSelect()+" WHERE g.class_id=:c AND m.active=TRUE ORDER BY u.identity_number").param("c",classId).query(this::grade).list(); }
    public List<Exam> examsForClass(long classId) { return jdbc.sql(examSelect()+" WHERE e.class_id=:c").param("c",classId).query(this::exam).list(); }
    private String gradeSelect() { return """
        SELECT g.*,c.name course_name,c.credits,u.full_name student_name FROM academic_grade g
        JOIN teaching_class tc ON tc.id=g.class_id JOIN course c ON c.id=tc.course_id
        JOIN app_user u ON u.id=g.student_id JOIN teaching_member m ON m.class_id=g.class_id AND m.student_id=g.student_id
        """; }
    private String examSelect() { return "SELECT e.*,c.name course_name,cp.name campus_name FROM academic_exam e JOIN teaching_class tc ON tc.id=e.class_id JOIN course c ON c.id=tc.course_id JOIN campus cp ON cp.id=e.campus_id"; }

    @Transactional
    public Material saveMaterial(CampusPrincipal user, long classId, Long id, MaterialInput input) {
        lockWrites();
        classAccess(user,classId,true);
        if (id == null) {
            long newId = insert("INSERT INTO course_material(class_id,author_id,title,body) VALUES(:c,:u,:title,:body)",classId,user.id(),input.title(),input.body());
            audit(classId,user.id(),"MATERIAL_CREATE",newId,"创建课程资料草稿");
            capture(newId);
            return findMaterial(newId);
        }
        var existing = findMaterial(id);
        if (existing.classId()!=classId || author(id)!=user.id()) missing();
        if (!existing.status().equals("DRAFT")) conflict("请先转为草稿再修改资料");
        capture(id);
        int changed = jdbc.sql("UPDATE course_material SET title=:t,body=:b,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:v AND status='DRAFT'")
            .param("t",input.title()).param("b",input.body()).param("id",id).param("v",input.version()).update();
        if (changed!=1) conflict("资料已被更新，请刷新后重试");
        audit(classId,user.id(),"MATERIAL_EDIT",id,"修改课程资料草稿");
        capture(id);
        return findMaterial(id);
    }
    @Transactional
    public Material materialState(CampusPrincipal user, long id, Transition input) {
        lockWrites();
        var material = findMaterial(id);
        classAccess(user,material.classId(),true);
        if (author(id)!=user.id()) missing();
        boolean allowed = material.status().equals("DRAFT") && input.status().equals("PUBLISHED")
            || material.status().equals("PUBLISHED") && (input.status().equals("WITHDRAWN") || input.status().equals("DRAFT"))
            || material.status().equals("WITHDRAWN") && input.status().equals("DRAFT");
        if (!allowed) conflict("当前资料状态不能执行此操作");
        capture(id);
        if (jdbc.sql("UPDATE course_material SET status=:s,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:v AND status=:old")
            .param("s",input.status()).param("id",id).param("v",input.version()).param("old",material.status()).update()!=1) conflict("资料已被更新，请刷新后重试");
        audit(material.classId(),user.id(),"MATERIAL_"+input.status(),id,material.title());
        capture(id);
        return findMaterial(id);
    }
    @Transactional
    public Material attach(CampusPrincipal user, long id, int version, MultipartFile file) throws java.io.IOException {
        lockWrites();
        var material = findMaterial(id);
        classAccess(user,material.classId(),true);
        if (author(id)!=user.id()) missing();
        if (!material.status().equals("DRAFT")) conflict("附件只能在资料草稿中修改");
        capture(id);
        if (file.isEmpty() || file.getSize()>2*1024*1024) bad("附件须为 1 字节至 2 MB 的 PDF 或 UTF-8 文本");
        byte[] bytes = file.getBytes();
        String type;
        if (bytes.length>=5 && new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-")) type="application/pdf";
        else {
            try { java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)); }
            catch (java.nio.charset.CharacterCodingException ex) { bad("文本附件必须使用 UTF-8 编码"); }
            if (file.getOriginalFilename()==null || !file.getOriginalFilename().toLowerCase(java.util.Locale.ROOT).endsWith(".txt")) bad("仅支持 PDF 或 UTF-8 .txt 附件");
            type="text/plain";
        }
        String name = file.getOriginalFilename()==null ? "课程资料" : file.getOriginalFilename().replaceAll("[\\\\/\\p{Cntrl}]","_");
        if (name.length()>160) name=name.substring(name.length()-160);
        if (jdbc.sql("UPDATE course_material SET file_name=:n,file_type=:t,file_bytes=:b,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:v AND status='DRAFT'")
            .param("n",name).param("t",type).param("b",bytes).param("id",id).param("v",version).update()!=1) conflict("资料已被更新，请刷新后重试");
        audit(material.classId(),user.id(),"MATERIAL_FILE",id,"上传课程附件");
        capture(id);
        return findMaterial(id);
    }
    public Attachment download(CampusPrincipal user, long id) {
        var material=findMaterial(id);
        var permissions=identities.permissions(user.id());
        boolean teacher = permissions.contains("teaching:manage") && jdbc.sql("SELECT COUNT(*) FROM teaching_instructor WHERE class_id=:c AND teacher_id=:u AND active=TRUE")
            .param("c",material.classId()).param("u",user.id()).query(Long.class).single()>0;
        if (teacher) classAccess(user,material.classId(),true);
        else { classAccess(user,material.classId(),false); if (!material.status().equals("PUBLISHED")) missing(); }
        return jdbc.sql("SELECT file_name,file_type,file_bytes FROM course_material WHERE id=:id AND file_bytes IS NOT NULL")
            .param("id",id).query((r,n)->new Attachment(r.getString(1),r.getString(2),r.getBytes(3))).optional().orElseThrow(this::notFound);
    }
    @Transactional
    public Notice postNotice(CampusPrincipal user, long classId, NoticeInput input) {
        lockWrites();
        classAccess(user,classId,true);
        var prior=jdbc.sql("SELECT class_id,title,body,id FROM teaching_notice WHERE author_id=:u AND request_key=:k")
            .param("u",user.id()).param("k",input.requestKey()).query((r,n)->List.of(r.getString(1),r.getString(2),r.getString(3),r.getString(4))).optional();
        if (prior.isPresent()) {
            if (!prior.get().subList(0,3).equals(List.of(String.valueOf(classId),input.title(),input.body()))) conflict("重复请求的通知内容不一致");
            long priorId=Long.parseLong(prior.get().get(3));
            return detail(user,classId,"teacher").notices().stream().filter(n->n.id()==priorId).findFirst().orElseThrow(this::notFound);
        }
        jdbc.sql("INSERT INTO teaching_notice(class_id,author_id,request_key,title,body) VALUES(:c,:u,:k,:t,:b)")
            .param("c",classId).param("u",user.id()).param("k",input.requestKey()).param("t",input.title()).param("b",input.body()).update();
        long id=jdbc.sql("SELECT id FROM teaching_notice WHERE author_id=:u AND request_key=:k").param("u",user.id()).param("k",input.requestKey()).query(Long.class).single();
        notifyMembers(classId,input.title(),input.body());
        audit(classId,user.id(),"NOTICE_PUBLISH",id,"发布教学班通知");
        return detail(user,classId,"teacher").notices().stream().filter(n->n.id()==id).findFirst().orElseThrow(this::notFound);
    }
    public void notifyMembers(long classId,String title,String body) {
        for (var member : people(classId,false)) jdbc.sql("INSERT INTO user_notice(user_id,title,body) VALUES(:u,:t,:b)")
            .param("u",member.id()).param("t",title).param("b",body).update();
    }
    public void audit(long classId,long user,String action,long object,String detail) {
        jdbc.sql("INSERT INTO teaching_audit(class_id,actor_id,action,object_id,detail) VALUES(:c,:u,:a,:o,:d)")
            .param("c",classId).param("u",user).param("a",action).param("o",object).param("d",detail).update();
    }
    // A database row lock coordinates relationship revocation and scheduling/publication across instances.
    public void lockWrites() {
        jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update();
        jdbc.sql("UPDATE teaching_write_lock SET revision=revision+1 WHERE id=1").update();
    }
    private void capture(long id) {
        jdbc.sql("""
            INSERT INTO course_material_revision(material_id,version,title,body,status,file_name,file_type,file_bytes)
            SELECT id,version,title,body,status,file_name,file_type,file_bytes FROM course_material m
            WHERE m.id=:id AND NOT EXISTS(SELECT 1 FROM course_material_revision r WHERE r.material_id=m.id AND r.version=m.version)
            """).param("id",id).update();
    }
    private long insert(String sql,long classId,long user,String title,String body) {
        var key=new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.sql(sql).param("c",classId).param("u",user).param("title",title).param("body",body).update(key,"id");
        return key.getKey().longValue();
    }
    private long author(long materialId) { return jdbc.sql("SELECT author_id FROM course_material WHERE id=:id").param("id",materialId).query(Long.class).single(); }
    public Material findMaterial(long id) { return jdbc.sql("SELECT m.*,u.full_name author_name FROM course_material m JOIN app_user u ON u.id=m.author_id WHERE m.id=:id").param("id",id).query(this::material).optional().orElseThrow(this::notFound); }
    private ClassInfo classInfo(ResultSet r,int n) throws SQLException { return new ClassInfo(r.getLong("id"),r.getLong("term_id"),r.getLong("course_id"),r.getString("name"),r.getString("course_name"),r.getBigDecimal("credits"),r.getString("department_name"),r.getLong("member_count")); }
    private Person person(ResultSet r,int n) throws SQLException { return new Person(r.getLong("id"),r.getString("full_name"),r.getString("identity_number"),r.getString("class_name")); }
    private Material material(ResultSet r,int n) throws SQLException { return new Material(r.getLong("id"),r.getLong("class_id"),r.getLong("author_id"),r.getString("title"),r.getString("body"),r.getString("status"),r.getInt("version"),r.getString("author_name"),time(r,"updated_at"),r.getString("file_name")); }
    private Grade grade(ResultSet r,int n) throws SQLException { return new Grade(r.getLong("id"),r.getLong("class_id"),r.getLong("student_id"),r.getString("student_name"),r.getString("course_name"),r.getBigDecimal("credits"),r.getBigDecimal("score"),r.getString("status"),r.getInt("version"),time(r,"updated_at")); }
    private Exam exam(ResultSet r,int n) throws SQLException { return new Exam(r.getLong("id"),r.getLong("class_id"),r.getString("course_name"),r.getLong("campus_id"),r.getString("campus_name"),r.getString("classroom"),time(r,"starts_at"),time(r,"ends_at"),r.getString("status"),r.getInt("version"),time(r,"updated_at"),r.getObject("room_id",Long.class)); }
    private LocalDateTime time(ResultSet r,String field) throws SQLException { return r.getTimestamp(field).toLocalDateTime(); }
    public ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND,"TEACHING_NOT_FOUND","教学信息不存在或不在你的权限范围内"); }
    public void missing() { throw notFound(); }
    public void conflict(String message) { throw new ApiException(HttpStatus.CONFLICT,"TEACHING_CONFLICT",message); }
    public void bad(String message) { throw new ApiException(HttpStatus.BAD_REQUEST,"TEACHING_INVALID",message); }
}
