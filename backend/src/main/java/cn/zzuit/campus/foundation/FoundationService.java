package cn.zzuit.campus.foundation;

import static cn.zzuit.campus.foundation.FoundationModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.teaching.TeachingService;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FoundationService {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final PasswordEncoder passwords;
    private final TeachingService teaching;
    private final ResponsibilityService responsibilities;
    private static final String ACCOUNT_SELECT="""
        SELECT u.id,u.username,u.full_name,u.identity_number,u.campus_id,u.department_id,u.class_group_id,u.enabled,u.demo,u.version,u.must_change_password,
            cp.name campus_name,d.name department_name,cg.name class_name
        FROM app_user u LEFT JOIN campus cp ON cp.id=u.campus_id LEFT JOIN department d ON d.id=u.department_id
        LEFT JOIN class_group cg ON cg.id=u.class_group_id
        """;
    public FoundationService(JdbcClient jdbc,IdentityService identities,PasswordEncoder passwords,TeachingService teaching,ResponsibilityService responsibilities) {
        this.jdbc=jdbc; this.identities=identities; this.passwords=passwords; this.teaching=teaching; this.responsibilities=responsibilities;
    }
    private void permission(CampusPrincipal user) { identities.requirePermission(user,"foundation:manage"); }
    private void lock(CampusPrincipal user) {
        jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update();
        permission(user);
    }
    public Catalog catalog(CampusPrincipal user) {
        permission(user);
        var campuses=jdbc.sql("SELECT id,name FROM campus ORDER BY id").query((r,n)->new Campus(r.getLong(1),r.getString(2))).list();
        var departments=jdbc.sql("SELECT * FROM department ORDER BY id").query(this::departmentRow).list();
        var majors=jdbc.sql("SELECT * FROM major ORDER BY id").query(this::majorRow).list();
        var groups=jdbc.sql("SELECT * FROM class_group ORDER BY id").query(this::groupRow).list();
        var roles=jdbc.sql("SELECT * FROM app_role ORDER BY code").query((r,n)-> {
            String code=r.getString("code");
            var permissions=jdbc.sql("SELECT p.code,p.description FROM permission p JOIN role_permission rp ON rp.permission_code=p.code WHERE rp.role_code=:r ORDER BY p.code")
                .param("r",code).query((p,index)->new Permission(p.getString(1),p.getString(2))).list();
            String scope=switch(code) {
                case "STUDENT" -> "本人学习与个人信息";
                case "TEACHER" -> "本人有效授课教学班";
                case "COUNSELOR" -> "叠加教师身份；仅所分配行政班的请假申请与材料";
                case "STUDENT_AFFAIRS" -> "所属学院已提交的证明申请与演示结果，禁止自审";
                case "REPAIR_DISPATCHER" -> "配置的校区、类别及具体报修地点范围，不能浏览学生草稿";
                case "REPAIR_TECHNICIAN" -> "当前分配给本人且负责范围有效的维修任务";
                case "ACADEMIC_STAFF" -> "所属学院的教学信息";
                case "CONTENT_EDITOR" -> "官网内容编辑与本人草稿";
                case "CONTENT_REVIEWER" -> "官网内容审核，禁止自审";
                case "ADMIN" -> "账号与组织管理；教学、审批岗位需另行分配";
                default -> "岗位工作台；具体事项随业务模块接入";
            };
            return new Role(code,r.getString("name"),scope,permissions);
        }).list();
        return new Catalog(campuses,departments,majors,groups,roles);
    }
    public Page<Account> users(CampusPrincipal user,String q,String role,Long departmentId,Boolean enabled,int page,int size) {
        permission(user); pagination(page,size);
        if(q.length()>100) bad("搜索关键词最多 100 个字符");
        String where=" WHERE 1=1"; var params=new HashMap<String,Object>();
        if(!q.isBlank()) { where+=" AND (u.username LIKE :q ESCAPE '!' OR u.full_name LIKE :q ESCAPE '!' OR u.identity_number LIKE :q ESCAPE '!')"; params.put("q","%"+q.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%"); }
        if(role!=null && !role.isBlank()) { where+=" AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_code=:role)"; params.put("role",role); }
        if(departmentId!=null) { where+=" AND u.department_id=:department"; params.put("department",departmentId); }
        if(enabled!=null) { where+=" AND u.enabled=:enabled"; params.put("enabled",enabled); }
        long total=jdbc.sql("SELECT COUNT(*) FROM app_user u"+where).params(params).query(Long.class).single();
        var items=jdbc.sql(ACCOUNT_SELECT+where+" ORDER BY u.id DESC LIMIT :limit OFFSET :offset").params(params).param("limit",size).param("offset",((long)page-1)*size).query(this::accountRow).list();
        return new Page<>(items,total,page,size);
    }
    @Transactional public Account create(CampusPrincipal user,NewAccount input) {
        lock(user); validateAssignment(input.campusId(),input.departmentId(),input.classGroupId(),input.roles());
        cn.zzuit.campus.identity.PasswordPolicy.validate(input.initialPassword());
        var key=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,class_group_id,demo,must_change_password) VALUES(:name,:hash,:full,:number,:campus,:department,:group,TRUE,TRUE)")
            .param("name",input.username()).param("hash",passwords.encode(input.initialPassword())).param("full",input.fullName().trim()).param("number",input.identityNumber().trim())
            .param("campus",input.campusId()).param("department",input.departmentId()).param("group",input.classGroupId()).update(key,"id");
        long id=key.getKey().longValue(); roles(id,input.roles()); var created=account(id);
        audit(user,"USER_CREATE","USER",id,snapshot(created),"管理员建档"); return created;
    }
    @Transactional public Account update(CampusPrincipal user,long id,AccountUpdate input) {
        lock(user); teaching.lockWrites(); var old=account(id); version(old.version(),input.version());
        validateAssignment(input.campusId(),input.departmentId(),input.classGroupId(),input.roles());
        protectAdmin(user,old,old.enabled(),input.roles());
        if((!Objects.equals(old.departmentId(),input.departmentId()) || !input.roles().containsAll(List.of("TEACHER","COUNSELOR"))) && hasCounselorAssignments(id)) conflict("该人员仍负责行政班，请先移交或清空负责关系，再调整学院或辅导员岗位");
        if((!Objects.equals(old.classGroupId(),input.classGroupId()) || !input.roles().contains("STUDENT")) && pendingLeave(id)) conflict("该学生仍有待审核或待补交请假，请先处理或撤回，再变更行政班或学生身份");
        if((!Objects.equals(old.classGroupId(),input.classGroupId()) || !Objects.equals(old.departmentId(),input.departmentId()) || !input.roles().contains("STUDENT")) && pendingCertificate(id)) conflict("该学生仍有未完成证明，请先处理或撤回，再变更行政班、学院或学生身份");
        if(old.enabled() && old.roles().contains("STUDENT_AFFAIRS") && (!input.roles().contains("STUDENT_AFFAIRS") || !Objects.equals(old.departmentId(),input.departmentId()))) protectCertificateReviewer(id,old.departmentId());
        if(!Objects.equals(old.departmentId(),input.departmentId()) && jdbc.sql("SELECT (SELECT COUNT(*) FROM teaching_member WHERE student_id=:u AND active=TRUE)+(SELECT COUNT(*) FROM teaching_instructor WHERE teacher_id=:u AND active=TRUE)")
            .param("u",id).query(Long.class).single()>0) conflict("该人员仍有有效教学关系，请先调整教学关系再变更学院归属");
        responsibilities.requireRoleHandoff(id,input.roles(),!Objects.equals(old.departmentId(),input.departmentId()));
        boolean changedAccess=!new HashSet<>(old.roles()).equals(new HashSet<>(input.roles())) || !Objects.equals(old.departmentId(),input.departmentId())
            || !Objects.equals(old.classGroupId(),input.classGroupId()) || !Objects.equals(old.campusId(),input.campusId());
        if(jdbc.sql("UPDATE app_user SET full_name=:full,identity_number=:number,campus_id=:campus,department_id=:department,class_group_id=:group,version=version+1,security_version=security_version+:security WHERE id=:id AND version=:v")
            .param("full",input.fullName().trim()).param("number",input.identityNumber().trim()).param("campus",input.campusId()).param("department",input.departmentId()).param("group",input.classGroupId())
            .param("security",changedAccess?1:0).param("id",id).param("v",input.version()).update()!=1) stale();
        roles(id,input.roles()); var updated=account(id);
        audit(user,"USER_UPDATE","USER",id,snapshot(old)+" → "+snapshot(updated),input.reason().trim()); return updated;
    }
    @Transactional public Account state(CampusPrincipal user,long id,StateChange input) {
        lock(user); var old=account(id); version(old.version(),input.version()); protectAdmin(user,old,input.enabled(),old.roles());
        if(old.enabled()==input.enabled()) conflict("账号已经处于目标状态");
        if(input.enabled() && input.emergency()) bad("紧急停用不能用于启用账号");
        if(!input.enabled() && !input.emergency()) responsibilities.requireNormalDisable(user,id);
        if(!input.enabled() && !input.emergency() && hasCounselorAssignments(id)) conflict("该人员仍负责行政班，请先移交或清空负责关系，再停用账号");
        if(!input.enabled() && !input.emergency() && old.roles().contains("STUDENT_AFFAIRS")) protectCertificateReviewer(id,old.departmentId());
        if(jdbc.sql("UPDATE app_user SET enabled=:enabled,version=version+1,security_version=security_version+1 WHERE id=:id AND version=:v")
            .param("enabled",input.enabled()).param("id",id).param("v",input.version()).update()!=1) stale();
        var updated=account(id); audit(user,input.enabled()?"USER_ENABLE":input.emergency()?"USER_EMERGENCY_DISABLE":"USER_DISABLE","USER",id,snapshot(old)+" → "+snapshot(updated),input.reason().trim()); return updated;
    }
    private void validateAssignment(long campus,long department,Long group,List<String> roles) {
        if(new HashSet<>(roles).size()!=roles.size()) bad("角色不能重复");
        for(String role:roles) if(jdbc.sql("SELECT COUNT(*) FROM app_role WHERE code=:r").param("r",role).query(Long.class).single()==0) bad("所选角色不存在");
        if(jdbc.sql("SELECT COUNT(*) FROM campus WHERE id=:c").param("c",campus).query(Long.class).single()==0) bad("所选校区不存在");
        var dept=jdbc.sql("SELECT * FROM department WHERE id=:d").param("d",department).query(this::departmentRow).optional().orElseThrow(()->invalid("所选组织不存在"));
        if(roles.contains("COUNSELOR") && !roles.contains("TEACHER")) bad("辅导员岗位须同时具有教师身份");
        if(roles.stream().anyMatch(r->List.of("STUDENT","TEACHER","ACADEMIC_STAFF","COUNSELOR","STUDENT_AFFAIRS").contains(r)) && !dept.kind().equals("COLLEGE")) bad("学生、教师、教务、辅导员和学生事务岗位须归属学院");
        if(roles.contains("STUDENT") && group==null) bad("学生须分配行政班");
        if(!roles.contains("STUDENT") && group!=null) bad("只有学生身份可分配行政班");
        if(group!=null && jdbc.sql("SELECT COUNT(*) FROM class_group cg JOIN major m ON m.id=cg.major_id WHERE cg.id=:g AND m.department_id=:d")
            .param("g",group).param("d",department).query(Long.class).single()==0) bad("行政班不属于所选学院");
    }
    private void protectAdmin(CampusPrincipal actor,Account old,boolean enabled,List<String> roles) {
        if(old.id()==actor.id() && (!enabled || !roles.contains("ADMIN"))) conflict("不能停用当前登录管理员或移除自己的管理员身份");
        if(old.enabled() && old.roles().contains("ADMIN") && (!enabled || !roles.contains("ADMIN")) && jdbc.sql("SELECT COUNT(*) FROM app_user u JOIN user_role ur ON ur.user_id=u.id WHERE u.enabled=TRUE AND ur.role_code='ADMIN'").query(Long.class).single()<=1)
            conflict("至少须保留一名启用的系统管理员");
    }
    private boolean hasCounselorAssignments(long id) { return jdbc.sql("SELECT COUNT(*) FROM counselor_assignment WHERE counselor_id=:id").param("id",id).query(Long.class).single()>0; }
    private boolean pendingLeave(long id) { return jdbc.sql("SELECT COUNT(*) FROM leave_request WHERE student_id=:id AND status IN ('SUBMITTED','RETURNED','APPROVED','RETURN_PENDING')").param("id",id).query(Long.class).single()>0; }
    private boolean pendingCertificate(long id) { return jdbc.sql("SELECT COUNT(*) FROM certificate_request WHERE student_id=:id AND status IN ('SUBMITTED','RETURNED')").param("id",id).query(Long.class).single()>0; }
    private void protectCertificateReviewer(long id,Long department) {
        if(department==null) return;
        if(jdbc.sql("""
            SELECT COUNT(*) FROM certificate_request r WHERE r.department_id=:d AND r.status IN ('SUBMITTED','RETURNED')
            AND NOT EXISTS(SELECT 1 FROM app_user u JOIN user_role ur ON ur.user_id=u.id AND ur.role_code='STUDENT_AFFAIRS'
                WHERE u.enabled=TRUE AND u.department_id=r.department_id AND u.id<>:id AND u.id<>r.student_id)
            """).param("d",department).param("id",id).query(Long.class).single()>0)
            conflict("学院仍有未完成证明，请先配置其他可处理人员或关闭申请，再调整最后一名学生事务人员");
    }
    private void roles(long id,List<String> roles) {
        jdbc.sql("DELETE FROM user_role WHERE user_id=:u").param("u",id).update();
        for(String role:roles) jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,:r)").param("u",id).param("r",role).update();
    }
    private Account account(long id) { return jdbc.sql(ACCOUNT_SELECT+" WHERE u.id=:u").param("u",id).query(this::accountRow).optional().orElseThrow(this::missing); }
    private Account accountRow(ResultSet r,int n) throws SQLException {
        long id=r.getLong("id");
        return new Account(id,r.getString("username"),r.getString("full_name"),r.getString("identity_number"),r.getObject("campus_id",Long.class),r.getString("campus_name"),
            r.getObject("department_id",Long.class),r.getString("department_name"),r.getObject("class_group_id",Long.class),r.getString("class_name"),r.getBoolean("enabled"),r.getBoolean("demo"),r.getInt("version"),identities.roles(id),r.getBoolean("must_change_password"));
    }
    @Transactional public Department department(CampusPrincipal user,Long id,DepartmentInput input) {
        lock(user); Department old=null;
        if(id==null) {
            var key=new GeneratedKeyHolder(); jdbc.sql("INSERT INTO department(name,kind) VALUES(:n,:k)").param("n",input.name().trim()).param("k",input.kind()).update(key,"id"); id=key.getKey().longValue();
        } else {
            old=jdbc.sql("SELECT * FROM department WHERE id=:id").param("id",id).query(this::departmentRow).optional().orElseThrow(this::missing); version(old.version(),input.version());
            if(old.kind().equals("COLLEGE") && !input.kind().equals("COLLEGE") && jdbc.sql("SELECT (SELECT COUNT(*) FROM major WHERE department_id=:d)+(SELECT COUNT(*) FROM teaching_class WHERE department_id=:d)+(SELECT COUNT(*) FROM app_user u JOIN user_role ur ON ur.user_id=u.id WHERE u.department_id=:d AND ur.role_code IN ('STUDENT','TEACHER','ACADEMIC_STAFF','COUNSELOR','STUDENT_AFFAIRS'))")
                .param("d",id).query(Long.class).single()>0) conflict("学院仍关联专业、教学班或师生，不能改为管理部门");
            if(jdbc.sql("UPDATE department SET name=:n,kind=:k,version=version+1 WHERE id=:id AND version=:v").param("n",input.name().trim()).param("k",input.kind()).param("id",id).param("v",input.version()).update()!=1) stale();
        }
        var result=jdbc.sql("SELECT * FROM department WHERE id=:id").param("id",id).query(this::departmentRow).single();
        audit(user,old==null?"DEPARTMENT_CREATE":"DEPARTMENT_UPDATE","DEPARTMENT",id,(old==null?"新建组织":old.name()+"（"+kindName(old.kind())+"）")+" → "+result.name()+"（"+kindName(result.kind())+"）","组织目录维护"); return result;
    }
    @Transactional public Major major(CampusPrincipal user,Long id,MajorInput input) {
        lock(user);
        if(jdbc.sql("SELECT COUNT(*) FROM department WHERE id=:d AND kind='COLLEGE'").param("d",input.departmentId()).query(Long.class).single()==0) bad("专业须归属有效学院");
        Major old=null;
        if(id==null) { var key=new GeneratedKeyHolder(); jdbc.sql("INSERT INTO major(department_id,name,demo) VALUES(:d,:n,TRUE)").param("d",input.departmentId()).param("n",input.name().trim()).update(key,"id"); id=key.getKey().longValue(); }
        else {
            old=jdbc.sql("SELECT * FROM major WHERE id=:id").param("id",id).query(this::majorRow).optional().orElseThrow(this::missing); version(old.version(),input.version());
            if(old.departmentId()!=input.departmentId() && jdbc.sql("SELECT COUNT(*) FROM class_group WHERE major_id=:m").param("m",id).query(Long.class).single()>0) conflict("专业已关联行政班，不能直接变更所属学院");
            if(jdbc.sql("UPDATE major SET name=:n,department_id=:d,version=version+1 WHERE id=:id AND version=:v").param("n",input.name().trim()).param("d",input.departmentId()).param("id",id).param("v",input.version()).update()!=1) stale();
        }
        var result=jdbc.sql("SELECT * FROM major WHERE id=:id").param("id",id).query(this::majorRow).single();
        audit(user,old==null?"MAJOR_CREATE":"MAJOR_UPDATE","MAJOR",id,(old==null?"新建专业":old.name()+"（"+organizationName("department",old.departmentId())+"）")+" → "+result.name()+"（"+organizationName("department",result.departmentId())+"）","组织目录维护"); return result;
    }
    @Transactional public ClassGroup group(CampusPrincipal user,Long id,ClassGroupInput input) {
        lock(user); if(jdbc.sql("SELECT COUNT(*) FROM major WHERE id=:id").param("id",input.majorId()).query(Long.class).single()==0) bad("所选专业不存在"); ClassGroup old=null;
        if(id==null) { var key=new GeneratedKeyHolder(); jdbc.sql("INSERT INTO class_group(major_id,name) VALUES(:m,:n)").param("m",input.majorId()).param("n",input.name().trim()).update(key,"id"); id=key.getKey().longValue(); }
        else {
            old=jdbc.sql("SELECT * FROM class_group WHERE id=:id").param("id",id).query(this::groupRow).optional().orElseThrow(this::missing); version(old.version(),input.version());
            if(old.majorId()!=input.majorId() && jdbc.sql("SELECT (SELECT COUNT(*) FROM app_user WHERE class_group_id=:g)+(SELECT COUNT(*) FROM counselor_assignment WHERE class_group_id=:g AND counselor_id IS NOT NULL)").param("g",id).query(Long.class).single()>0) conflict("行政班已关联账号或辅导员，不能直接变更所属专业");
            if(jdbc.sql("UPDATE class_group SET name=:n,major_id=:m,version=version+1 WHERE id=:id AND version=:v").param("n",input.name().trim()).param("m",input.majorId()).param("id",id).param("v",input.version()).update()!=1) stale();
        }
        var result=jdbc.sql("SELECT * FROM class_group WHERE id=:id").param("id",id).query(this::groupRow).single();
        audit(user,old==null?"CLASS_GROUP_CREATE":"CLASS_GROUP_UPDATE","CLASS_GROUP",id,(old==null?"新建行政班":old.name()+"（"+organizationName("major",old.majorId())+"）")+" → "+result.name()+"（"+organizationName("major",result.majorId())+"）","组织目录维护"); return result;
    }
    public Page<Audit> audits(CampusPrincipal user,String type,Long id,int page,int size) {
        permission(user); pagination(page,size); String where=" WHERE 1=1"; var params=new HashMap<String,Object>();
        if(type!=null && !type.isBlank()) { if(!List.of("USER","DEPARTMENT","MAJOR","CLASS_GROUP","REPAIR_LOCATION","REPAIR_SCOPE","VENUE").contains(type)) bad("记录类型无效"); where+=" AND entity_type=:type"; params.put("type",type); }
        if(id!=null) { where+=" AND entity_id=:id"; params.put("id",id); }
        long total=jdbc.sql("SELECT COUNT(*) FROM foundation_audit"+where).params(params).query(Long.class).single();
        var rows=jdbc.sql("SELECT * FROM foundation_audit"+where+" ORDER BY id DESC LIMIT :limit OFFSET :offset").params(params).param("limit",size).param("offset",((long)page-1)*size)
            .query((r,n)->new Audit(r.getLong("id"),r.getString("actor_name"),r.getString("action"),r.getString("entity_type"),r.getLong("entity_id"),r.getString("summary"),r.getString("reason"),r.getTimestamp("created_at").toLocalDateTime())).list();
        return new Page<>(rows,total,page,size);
    }
    private Department departmentRow(ResultSet r,int n) throws SQLException { return new Department(r.getLong("id"),r.getString("name"),r.getString("kind"),r.getInt("version")); }
    private Major majorRow(ResultSet r,int n) throws SQLException { return new Major(r.getLong("id"),r.getLong("department_id"),r.getString("name"),r.getInt("version")); }
    private ClassGroup groupRow(ResultSet r,int n) throws SQLException { return new ClassGroup(r.getLong("id"),r.getLong("major_id"),r.getString("name"),r.getInt("version")); }
    private String kindName(String kind) { return kind.equals("COLLEGE")?"学院":"管理部门"; }
    private String organizationName(String table,long id) { return jdbc.sql("SELECT name FROM "+table+" WHERE id=:id").param("id",id).query(String.class).single(); }
    private String snapshot(Account account) {
        var roleNames=account.roles().stream().map(r->jdbc.sql("SELECT name FROM app_role WHERE code=:r").param("r",r).query(String.class).single()).toList();
        return account.username()+" / "+account.fullName()+" / 学工号 "+account.identityNumber()+" / "+account.campusName()+" / "+account.departmentName()+" / "+(account.className()==null?"无行政班":account.className())+" / "+String.join("、",roleNames)+" / "+(account.enabled()?"启用":"停用");
    }
    private void audit(CampusPrincipal user,String action,String type,long id,String summary,String reason) {
        String name=jdbc.sql("SELECT full_name FROM app_user WHERE id=:u").param("u",user.id()).query(String.class).single();
        jdbc.sql("INSERT INTO foundation_audit(actor_id,actor_name,action,entity_type,entity_id,summary,reason) VALUES(:u,:n,:a,:t,:id,:s,:r)")
            .param("u",user.id()).param("n",name).param("a",action).param("t",type).param("id",id).param("s",summary).param("r",reason).update();
    }
    private void pagination(int page,int size) { if(page<1 || page>1000000 || size<1 || size>100) bad("页码须大于零，每页最多 100 条"); }
    private void version(int current,Integer expected) { if(expected==null) bad("编辑记录须提供版本号"); if(current!=expected) stale(); }
    private void stale() { conflict("记录版本已变化，请刷新列表后重新编辑"); }
    private ApiException invalid(String message) { return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_ASSIGNMENT",message); }
    private void bad(String message) { throw invalid(message); }
    private void conflict(String message) { throw new ApiException(HttpStatus.CONFLICT,"FOUNDATION_CONFLICT",message); }
    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"FOUNDATION_NOT_FOUND","账号或组织记录不存在"); }
}
