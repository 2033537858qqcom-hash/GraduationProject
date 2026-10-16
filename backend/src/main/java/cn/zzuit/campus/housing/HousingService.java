package cn.zzuit.campus.housing;

import static cn.zzuit.campus.housing.HousingModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.util.List;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HousingService {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private static final String EFFECTIVE="""
        SELECT s.building_id FROM dorm_manager_scope s
        JOIN app_user u ON u.id=s.user_id AND u.enabled=TRUE
        JOIN dorm_building b ON b.id=s.building_id AND b.enabled=TRUE
        WHERE s.user_id=:user AND s.enabled=TRUE
        AND EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code AND rp.permission_code='housing:assign' WHERE ur.user_id=s.user_id)
        """;
    private static final String BED_SQL="""
        SELECT d.*,r.name room_name,r.floor_label,r.enabled room_enabled,b.id building_id,b.enabled building_enabled,
          a.id assignment_id,a.version assignment_version,u.username occupant_username,u.full_name occupant_name
        FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id JOIN dorm_building b ON b.id=r.building_id
        LEFT JOIN housing_assignment a ON a.current_bed_id=d.id LEFT JOIN app_user u ON u.id=a.student_id
        """;
    private static final String STAY_SQL="""
        SELECT a.*,r.building_id,(SELECT i.batch_id FROM housing_import_row i WHERE i.assignment_id=a.id) import_batch_id FROM housing_assignment a JOIN dorm_bed d ON d.id=a.bed_id JOIN dorm_room r ON r.id=d.room_id
        """;
    public HousingService(JdbcClient jdbc,IdentityService identities) {this.jdbc=jdbc;this.identities=identities;}
    public void requireImportScope(CampusPrincipal user,long building) {identities.requirePermission(user,"housing:assign");requireScope(user,building);}

    public CurrentStay currentForAssistant(CampusPrincipal user) {
        identities.requirePermission(user,"housing:read");
        return jdbc.sql(STAY_SQL+"WHERE a.current_student_id=:id").param("id",user.id()).query(this::assignmentRow).optional().map(row->{
            var live=place(row.bedId());return new CurrentStay(live.campus(),live.building(),live.room(),live.floor(),live.bed(),row.startedAt(),List.of());
        }).orElse(null);
    }
    public Mine mine(CampusPrincipal user) {
        identities.requirePermission(user,"housing:read");
        var current=jdbc.sql(STAY_SQL+"WHERE a.current_student_id=:id").param("id",user.id()).query(this::assignmentRow).optional();
        CurrentStay stay=null;
        if(current.isPresent()) {
            var row=current.get();
            var managers=jdbc.sql("""
                SELECT DISTINCT u.full_name FROM dorm_manager_scope s JOIN app_user u ON u.id=s.user_id AND u.enabled=TRUE
                JOIN dorm_building b ON b.id=s.building_id AND b.enabled=TRUE
                WHERE s.building_id=:id AND s.enabled=TRUE
                AND EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code AND rp.permission_code='housing:assign' WHERE ur.user_id=s.user_id)
                ORDER BY u.full_name
                """).param("id",row.buildingId()).query(String.class).list();
            var live=place(row.bedId());
            stay=new CurrentStay(live.campus(),live.building(),live.room(),live.floor(),live.bed(),row.startedAt(),managers);
        }
        var history=jdbc.sql("SELECT * FROM housing_assignment WHERE student_id=:id AND status='ENDED' ORDER BY id DESC").param("id",user.id())
            .query((r,n)->new StayHistory(r.getLong("id"),r.getString("campus_name"),r.getString("building_name"),r.getString("room_name"),r.getString("floor_label"),r.getString("bed_label"),r.getTimestamp("started_at").toLocalDateTime(),r.getTimestamp("ended_at").toLocalDateTime(),r.getString("end_reason"))).list();
        return new Mine(stay,history);
    }
    public Responsibilities responsibilities(CampusPrincipal user) {
        identities.requirePermission(user,"housing:assign");
        var scopes=jdbc.sql("""
            SELECT s.*,u.full_name user_name,u.enabled user_enabled,b.name building_name,b.enabled building_enabled,b.campus_id,c.name campus_name,
              EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code AND rp.permission_code='housing:assign' WHERE ur.user_id=s.user_id) has_permission
            FROM dorm_manager_scope s JOIN app_user u ON u.id=s.user_id JOIN dorm_building b ON b.id=s.building_id JOIN campus c ON c.id=b.campus_id
            WHERE s.user_id=:id ORDER BY c.id,b.code,s.id
            """).param("id",user.id()).query(this::scopeRow).list();
        var counts=jdbc.sql("""
            SELECT COALESCE(SUM(CASE WHEN d.status='OCCUPIED' THEN 1 ELSE 0 END),0) occupied,
              COALESCE(SUM(CASE WHEN d.status='VACANT' AND d.enabled=TRUE AND r.enabled=TRUE AND b.enabled=TRUE THEN 1 ELSE 0 END),0) vacant
            FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id JOIN dorm_building b ON b.id=r.building_id
            WHERE b.id IN ("""+EFFECTIVE+")").param("user",user.id()).query((r,n)->new long[]{r.getLong(1),r.getLong(2)}).single();
        return new Responsibilities(scopes,counts[0],counts[1]);
    }
    public Page<Bed> beds(CampusPrincipal user,long buildingId,int page,int size) {
        identities.requirePermission(user,"housing:assign");pagination(page,size);requireScope(user,buildingId);
        long total=jdbc.sql("SELECT COUNT(*) FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id WHERE r.building_id=:id").param("id",buildingId).query(Long.class).single();
        var items=jdbc.sql(BED_SQL+"WHERE r.building_id=:id ORDER BY r.floor_label,r.code,d.code,d.id LIMIT :limit OFFSET :offset").param("id",buildingId).param("limit",size).param("offset",offset(page,size)).query(this::bedRow).list();
        return new Page<>(items,total,page,size);
    }
    public Page<Assignment> history(CampusPrincipal user,long buildingId,int page,int size) {
        identities.requirePermission(user,"housing:assign");pagination(page,size);requireScope(user,buildingId);
        String where=" WHERE r.building_id=:building";
        long total=jdbc.sql("SELECT COUNT(*) FROM housing_assignment a JOIN dorm_bed d ON d.id=a.bed_id JOIN dorm_room r ON r.id=d.room_id"+where).param("building",buildingId).query(Long.class).single();
        var items=jdbc.sql(STAY_SQL+where+" ORDER BY a.id DESC LIMIT :limit OFFSET :offset").param("building",buildingId).param("limit",size).param("offset",offset(page,size)).query(this::assignmentRow).list();
        return new Page<>(items,total,page,size);
    }
    public StudentMatch student(CampusPrincipal user,String username) {
        identities.requirePermission(user,"housing:assign");
        if(username==null || username.isBlank() || username.trim().length()>64) bad("请输入准确学号");
        return jdbc.sql("""
            SELECT u.id,u.username,u.full_name,EXISTS(SELECT 1 FROM housing_assignment a WHERE a.current_student_id=u.id) housed
            FROM app_user u WHERE u.username=:name AND u.enabled=TRUE
            AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_code='STUDENT')
            """).param("name",username.trim()).query((r,n)->new StudentMatch(r.getLong(1),r.getString(2),r.getString(3),r.getBoolean(4))).optional().orElseThrow(()->missing("未找到可办理的在校学生"));
    }
    @Transactional public Assignment checkIn(CampusPrincipal user,CheckIn input) {
        identities.requirePermission(user,"housing:assign");lock();
        var existing=jdbc.sql(STAY_SQL+"WHERE a.request_key=:key").param("key",input.requestKey()).query(this::assignmentRow).optional();
        if(existing.isPresent()) {
            var row=existing.get();
            if(row.studentUsername()==null || !sameStay(row,input)) conflict("相同操作标识对应不同的入住内容");
            requireScope(user,row.buildingId());return row;
        }
        long studentId=studentId(input.studentId());
        var target=place(input.bedId());
        if(!effective(user,target.buildingId())) throw missing("床位不存在");
        if(!target.assignable()) conflict(target.enabledChain()?"床位当前不可入住":"楼栋、房间或床位已停用");
        if(jdbc.sql("SELECT COUNT(*) FROM housing_assignment WHERE current_student_id=:id").param("id",studentId).query(Long.class).single()>0) conflict("该学生已有在住记录，不能重复入住");
        var profile=jdbc.sql("SELECT username,full_name FROM app_user WHERE id=:id").param("id",studentId).query((r,n)->new String[]{r.getString(1),r.getString(2)}).single();
        var key=new GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO housing_assignment(student_id,student_username,student_name,bed_id,status,request_key,current_student_id,current_bed_id,campus_name,building_name,room_name,floor_label,bed_label,assigned_by)
            VALUES(:student,:username,:name,:bed,'CURRENT',:request,:student,:bed,:campus,:building,:room,:floor,:label,:actor)
            """).param("student",studentId).param("username",profile[0]).param("name",profile[1]).param("bed",target.id()).param("request",input.requestKey())
            .param("campus",target.campus()).param("building",target.building()).param("room",target.room()).param("floor",target.floor()).param("label",target.bed()).param("actor",user.id()).update(key,"id");
        long id=Objects.requireNonNull(key.getKey()).longValue();
        if(jdbc.sql("UPDATE dorm_bed SET status='OCCUPIED',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='VACANT' AND enabled=TRUE").param("id",target.id()).update()!=1) conflict("床位状态已变化，请刷新后重新选择");
        notify(studentId,"住宿入住登记","已为你登记入住："+target.describe()+"。如与实际不符，请联系本楼宿管。");
        audit(user,"CHECK_IN",id,profile[0]+"；"+target.describe(),input.reason());
        return assignment(id);
    }
    @Transactional public Assignment checkOut(CampusPrincipal user,long id,CheckOut input) {
        identities.requirePermission(user,"housing:assign");lock();String reason=input.reason().trim();
        var repeated=jdbc.sql(STAY_SQL+"WHERE a.checkout_key=:key").param("key",input.requestKey()).query(this::assignmentRow).optional();
        if(repeated.isPresent()) {
            var row=repeated.get();
            if(row.id()!=id || row.endReason()==null || !row.endReason().equals(reason)) conflict("相同操作标识对应不同的退宿内容");
            requireScope(user,row.buildingId());return row;
        }
        var stay=assignment(id);requireScope(user,stay.buildingId());
        if(!"CURRENT".equals(stay.status())) conflict("该住宿已经结束");
        if(stay.version()!=input.version()) stale();
        long studentId=jdbc.sql("SELECT student_id FROM housing_assignment WHERE id=:id").param("id",id).query(Long.class).single();
        if(jdbc.sql("SELECT COUNT(*) FROM housing_transfer WHERE current_student_id=:id").param("id",studentId).query(Long.class).single()>0) conflict("该生有未结束的调宿申请，请先办结或撤回后再退宿");
        var target=place(stay.bedId());
        if(jdbc.sql("""
            UPDATE housing_assignment SET status='ENDED',current_student_id=NULL,current_bed_id=NULL,checkout_key=:key,ended_by=:actor,end_reason=:reason,ended_at=CURRENT_TIMESTAMP,version=version+1,
              campus_name=:campus,building_name=:building,room_name=:room,floor_label=:floor,bed_label=:label
            WHERE id=:id AND version=:version AND status='CURRENT'
            """).param("key",input.requestKey()).param("actor",user.id()).param("reason",reason).param("campus",target.campus()).param("building",target.building()).param("room",target.room()).param("floor",target.floor()).param("label",target.bed()).param("id",id).param("version",input.version()).update()!=1) stale();
        if(jdbc.sql("UPDATE dorm_bed SET status='VACANT',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND status='OCCUPIED'").param("id",stay.bedId()).update()!=1) conflict("床位状态已变化，退宿未完成");
        long student=jdbc.sql("SELECT student_id FROM housing_assignment WHERE id=:id").param("id",id).query(Long.class).single();
        notify(student,"住宿退宿登记","已办理退宿："+target.describe()+"。历史记录仍可在我的住宿中查看。");
        audit(user,"CHECK_OUT",id,stay.studentUsername()+"；"+target.describe(),reason);
        return assignment(id);
    }

    private boolean sameStay(Assignment row,CheckIn input) {
        Long student=jdbc.sql("SELECT student_id FROM housing_assignment WHERE id=:id").param("id",row.id()).query(Long.class).optional().orElse(-1L);
        return student==input.studentId() && row.bedId()==input.bedId();
    }
    private long studentId(long id) {
        Long found=jdbc.sql("SELECT u.id FROM app_user u WHERE u.id=:id AND u.enabled=TRUE AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_code='STUDENT')").param("id",id).query(Long.class).optional().orElse(null);
        if(found==null) throw missing("未找到可办理的在校学生");
        return found;
    }
    private void requireScope(CampusPrincipal user,long buildingId) {if(!effective(user,buildingId)) throw missing("宿舍楼不存在");}
    private boolean effective(CampusPrincipal user,long buildingId) {return jdbc.sql("SELECT COUNT(*) FROM ("+EFFECTIVE+") s WHERE s.building_id=:building").param("user",user.id()).param("building",buildingId).query(Long.class).single()>0;}
    private Place place(long bedId) {return jdbc.sql("""
        SELECT d.id,d.label,d.status,d.enabled bed_enabled,r.enabled room_enabled,b.id building_id,b.enabled building_enabled,b.name building_name,r.name room_name,r.floor_label,c.name campus_name
        FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id JOIN dorm_building b ON b.id=r.building_id JOIN campus c ON c.id=b.campus_id WHERE d.id=:id
        """).param("id",bedId).query((r,n)->new Place(r.getLong("id"),r.getLong("building_id"),r.getString("campus_name"),r.getString("building_name"),r.getString("room_name"),r.getString("floor_label"),r.getString("label"),r.getString("status"),r.getBoolean("bed_enabled"),r.getBoolean("room_enabled"),r.getBoolean("building_enabled"))).optional().orElseThrow(()->missing("床位不存在"));}
    private Assignment assignment(long id) {return jdbc.sql(STAY_SQL+"WHERE a.id=:id").param("id",id).query(this::assignmentRow).optional().orElseThrow(()->missing("住宿记录不存在"));}
    private Assignment assignmentRow(ResultSet r,int n) throws SQLException {var ended=r.getTimestamp("ended_at");return new Assignment(r.getLong("id"),r.getString("student_username"),r.getString("student_name"),r.getLong("bed_id"),r.getLong("building_id"),r.getString("campus_name"),r.getString("building_name"),r.getString("room_name"),r.getString("floor_label"),r.getString("bed_label"),r.getString("status"),r.getInt("version"),r.getTimestamp("started_at").toLocalDateTime(),ended==null?null:ended.toLocalDateTime(),r.getString("end_reason"),r.getObject("import_batch_id",Long.class));}
    private Bed bedRow(ResultSet r,int n) throws SQLException {return new Bed(r.getLong("id"),r.getLong("room_id"),r.getLong("building_id"),r.getString("room_name"),r.getString("floor_label"),r.getString("code"),r.getString("label"),r.getString("status"),r.getBoolean("enabled"),r.getBoolean("room_enabled"),r.getBoolean("building_enabled"),r.getInt("version"),r.getObject("assignment_id",Long.class),r.getObject("assignment_version",Integer.class),r.getString("occupant_username"),r.getString("occupant_name"),r.getTimestamp("updated_at").toLocalDateTime());}
    private Scope scopeRow(ResultSet r,int n) throws SQLException {
        boolean effective=r.getBoolean("enabled") && r.getBoolean("user_enabled") && r.getBoolean("has_permission") && r.getBoolean("building_enabled");
        String message=!r.getBoolean("enabled")?"范围已停用":!r.getBoolean("user_enabled")?"账号已停用":!r.getBoolean("has_permission")?"对应岗位权限已移除":!r.getBoolean("building_enabled")?"楼栋已停用":"当前有效";
        return new Scope(r.getLong("id"),r.getLong("user_id"),r.getString("user_name"),r.getLong("building_id"),r.getLong("campus_id"),r.getString("campus_name"),r.getString("building_name"),r.getBoolean("enabled"),effective,message,r.getInt("version"),r.getTimestamp("updated_at").toLocalDateTime());
    }
    private void notify(long user,String title,String body) {jdbc.sql("INSERT INTO user_notice(user_id,title,body) VALUES(:u,:t,:b)").param("u",user).param("t",title).param("b",body).update();}
    private void audit(CampusPrincipal user,String action,long id,String summary,String reason) {
        String name=jdbc.sql("SELECT full_name FROM app_user WHERE id=:id").param("id",user.id()).query(String.class).single();
        jdbc.sql("INSERT INTO foundation_audit(actor_id,actor_name,action,entity_type,entity_id,summary,reason) VALUES(:u,:n,:a,'HOUSING_STAY',:id,:s,:r)")
            .param("u",user.id()).param("n",name).param("a",action).param("id",id).param("s",summary).param("r",reason.trim()).update();
    }
    private void lock() {jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update();}
    private long offset(int page,int size) {return ((long)page-1)*size;}
    private void pagination(int page,int size) {if(page<1 || page>1000000 || size<1 || size>100) bad("页码须大于零，每页最多 100 条");}
    private void stale() {conflict("住宿记录已变化，请刷新后重新操作");}
    private void bad(String message) {throw new ApiException(HttpStatus.BAD_REQUEST,"HOUSING_INVALID",message);}
    private void conflict(String message) {throw new ApiException(HttpStatus.CONFLICT,"HOUSING_CONFLICT",message);}
    private ApiException missing(String message) {return new ApiException(HttpStatus.NOT_FOUND,"HOUSING_NOT_FOUND",message);}
    private record Place(long id,long buildingId,String campus,String building,String room,String floor,String bed,String status,boolean bedEnabled,boolean roomEnabled,boolean buildingEnabled) {
        boolean enabledChain() {return bedEnabled && roomEnabled && buildingEnabled;}
        boolean assignable() {return enabledChain() && "VACANT".equals(status);}
        String describe() {return campus+" "+building+" "+room+" "+floor+" "+bed;}
    }
}
