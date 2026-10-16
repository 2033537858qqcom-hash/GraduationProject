package cn.zzuit.campus.repair;

import static cn.zzuit.campus.repair.RepairSettingsModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepairSettingsService {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private static final List<Category> CATEGORIES=List.of(new Category("WATER","给排水"),new Category("ELECTRICITY","电气照明"),
        new Category("NETWORK","网络"),new Category("FURNITURE","家具"),new Category("DOOR_WINDOW","门窗"),new Category("EQUIPMENT","设备"),new Category("OTHER","其他"));
    private static final String LOCATION_SELECT="""
        SELECT l.*,c.name campus_name,r.name room_name,r.campus_id room_campus,r.enabled room_enabled,
            r.building_name room_building,r.room_number room_number,b.name building_name,b.campus_id building_campus,b.verification_status,
            dr.name dorm_name,dr.enabled dorm_enabled,db.name dorm_building,db.campus_id dorm_campus,db.enabled dorm_building_enabled
        FROM repair_location l JOIN campus c ON c.id=l.campus_id
        LEFT JOIN classroom_resource r ON r.id=l.classroom_resource_id LEFT JOIN building b ON b.id=l.building_id
        LEFT JOIN dorm_room dr ON dr.id=l.dorm_room_id LEFT JOIN dorm_building db ON db.id=dr.building_id
        """;
    // Shared by detail actions, work counts and reminders. Snapshot identity prevents a later re-entry reviving old work.
    static final String RESIDENCY_SQL="""
        (NOT EXISTS(SELECT 1 FROM repair_location dl WHERE dl.id=r.location_id AND dl.kind='DORM_ROOM') OR
          EXISTS(SELECT 1 FROM housing_assignment ha JOIN dorm_bed hb ON hb.id=ha.bed_id
            JOIN repair_location dl ON dl.dorm_room_id=hb.room_id
            WHERE ha.id=r.housing_assignment_id AND ha.status='CURRENT' AND ha.current_student_id=r.reporter_id
              AND dl.id=r.location_id AND EXISTS(SELECT 1 FROM user_role hur JOIN role_permission hrp ON hrp.role_code=hur.role_code
                WHERE hur.user_id=r.reporter_id AND hrp.permission_code='housing:read')))
        """;
    private static final String SCOPE_SELECT="""
        SELECT s.*,u.full_name user_name,u.enabled user_enabled,c.name campus_name,l.name location_name,
            EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code
                WHERE ur.user_id=s.user_id AND rp.permission_code=CASE WHEN s.capability='DISPATCH' THEN 'repair:dispatch' ELSE 'repair:work' END) has_permission
        FROM repair_staff_scope s JOIN app_user u ON u.id=s.user_id JOIN campus c ON c.id=s.campus_id
        LEFT JOIN repair_location l ON l.id=s.location_id
        """;
    public RepairSettingsService(JdbcClient jdbc,IdentityService identities) { this.jdbc=jdbc;this.identities=identities; }
    private void manage(CampusPrincipal user) { identities.requirePermission(user,"repair:configure"); }
    private void lock(CampusPrincipal user) { jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update();manage(user); }
    private List<Campus> campuses() { return jdbc.sql("SELECT id,name FROM campus ORDER BY id").query((r,n)->new Campus(r.getLong(1),r.getString(2))).list(); }
    public PublicCatalog publicCatalog(CampusPrincipal user) { identities.requirePermission(user,"repair:apply");return new PublicCatalog(campuses(),CATEGORIES); }
    public Location applicationLocation(CampusPrincipal user,long id) {
        identities.requirePermission(user,"repair:apply");var result=location(id);
        if(result.dormRoomId()!=null && currentAssignment(user,result.dormRoomId())==null)throw missing("报修地点不存在或当前无权使用");
        if(!result.available())bad("地点已停用或来源资料不完整，请选择当前开放地点");return result;
    }
    public PublicLocation publicLocation(CampusPrincipal user,long id) {return publicLocation(applicationLocation(user,id));}
    private PublicLocation publicLocation(Location l) {return new PublicLocation(l.id(),l.campusId(),l.campusName(),l.name(),l.kind(),l.detailHint(),l.provenance());}
    public Long currentAssignment(CampusPrincipal user,long room) {
        var fresh=identities.current(user.id()).orElseThrow(()->missing("账号不存在"));
        if(!fresh.permissions().contains("housing:read"))return null;
        return jdbc.sql("SELECT h.id FROM housing_assignment h JOIN dorm_bed b ON b.id=h.bed_id WHERE h.current_student_id=:u AND h.status='CURRENT' AND b.room_id=:room")
            .param("u",user.id()).param("room",room).query(Long.class).optional().orElse(null);
    }
    public DormEligibility myDorm(CampusPrincipal user) {
        identities.requirePermission(user,"repair:apply");identities.requirePermission(user,"housing:read");
        var room=jdbc.sql("SELECT b.room_id FROM housing_assignment h JOIN dorm_bed b ON b.id=h.bed_id WHERE h.current_student_id=:u AND h.status='CURRENT'")
            .param("u",user.id()).query(Long.class).optional();
        if(room.isEmpty())return new DormEligibility(false,null,"暂无当前入住记录，请先联系宿管核对住宿信息。");
        var location=jdbc.sql(LOCATION_SELECT+" WHERE l.dorm_room_id=:room").param("room",room.get()).query(this::locationRow).optional();
        if(location.isEmpty() || !location.get().available())return new DormEligibility(true,null,"当前房间尚未开放报修，请联系宿管或后勤核对地点配置。");
        return new DormEligibility(true,publicLocation(location.get()),"为本人当前入住的房间报修，提交后由后勤分诊。");
    }
    public Catalog catalog(CampusPrincipal user) {
        manage(user);
        var buildings=jdbc.sql("SELECT id,campus_id,name,verification_status FROM building ORDER BY campus_id,id LIMIT 1000")
            .query((r,n)->new Building(r.getLong(1),r.getLong(2),r.getString(3),r.getString(4))).list();
        var rooms=jdbc.sql("SELECT * FROM classroom_resource ORDER BY campus_id,id LIMIT 1000")
            .query((r,n)->new Room(r.getLong("id"),r.getLong("campus_id"),r.getString("name"),r.getString("building_name"),r.getString("room_number"),r.getString("origin"),
                r.getBoolean("enabled") && notBlank(r.getString("building_name")) && notBlank(r.getString("room_number")))).list();
        var people=jdbc.sql("""
            SELECT DISTINCT u.id,u.full_name,u.enabled,d.name department_name FROM app_user u
            LEFT JOIN department d ON d.id=u.department_id JOIN user_role ur ON ur.user_id=u.id
            JOIN role_permission rp ON rp.role_code=ur.role_code WHERE rp.permission_code IN ('repair:dispatch','repair:work') ORDER BY u.id LIMIT 1000
            """).query((r,n)->new Person(r.getLong("id"),r.getString("full_name"),r.getString("department_name"),
                identities.permissions(r.getLong("id")).stream().filter(p->p.equals("repair:dispatch") || p.equals("repair:work"))
                    .map(p->p.equals("repair:dispatch")?"DISPATCH":"WORK").toList(),r.getBoolean("enabled"))).list();
        var dormRooms=jdbc.sql("SELECT r.*,b.campus_id,b.name building_name,b.provenance,b.enabled building_enabled FROM dorm_room r JOIN dorm_building b ON b.id=r.building_id ORDER BY b.campus_id,b.id,r.id LIMIT 1000")
            .query((r,n)->new DormRoom(r.getLong("id"),r.getLong("campus_id"),r.getString("name"),r.getString("building_name"),r.getString("floor_label"),r.getString("provenance"),r.getBoolean("enabled") && r.getBoolean("building_enabled"))).list();
        return new Catalog(campuses(),CATEGORIES,buildings,rooms,people,dormRooms);
    }
    public Page<Location> locations(CampusPrincipal user,Long campus,String q,int page,int size) {
        manage(user);pagination(page,size);String where=campus==null?"":" WHERE l.campus_id=:campus";
        var params=new HashMap<String,Object>();if(campus!=null)params.put("campus",campus);
        if(q.length()>100)bad("地点搜索最多100个字符");
        if(!q.isBlank()) {where+=(where.isEmpty()?" WHERE ":" AND ")+"(l.name LIKE :q ESCAPE '!' OR l.code LIKE :q ESCAPE '!')";params.put("q","%"+q.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%");}
        long total=jdbc.sql("SELECT COUNT(*) FROM repair_location l"+where).params(params).query(Long.class).single();
        var rows=jdbc.sql(LOCATION_SELECT+where+" ORDER BY l.id DESC LIMIT :limit OFFSET :offset").params(params).param("limit",size).param("offset",((long)page-1)*size).query(this::locationRow).list();
        return new Page<>(rows,total,page,size);
    }
    public Page<PublicLocation> publicLocations(CampusPrincipal user,Long campus,int page,int size) {
        identities.requirePermission(user,"repair:apply");pagination(page,size);
        String where=" WHERE l.enabled=TRUE AND (l.classroom_resource_id IS NULL OR (r.campus_id=l.campus_id AND r.enabled=TRUE AND TRIM(r.building_name)<>'' AND TRIM(r.room_number)<>'')) AND (l.building_id IS NULL OR b.campus_id=l.campus_id)";
        where+=" AND (l.dorm_room_id IS NULL OR (dr.enabled=TRUE AND db.enabled=TRUE AND db.campus_id=l.campus_id AND :housing=TRUE AND EXISTS(SELECT 1 FROM housing_assignment h JOIN dorm_bed bed ON bed.id=h.bed_id WHERE h.current_student_id=:user AND h.status='CURRENT' AND bed.room_id=l.dorm_room_id)))";
        if(campus!=null)where+=" AND l.campus_id=:campus";
        var params=new HashMap<String,Object>();if(campus!=null)params.put("campus",campus);
        params.put("user",user.id());params.put("housing",identities.current(user.id()).orElseThrow().permissions().contains("housing:read"));
        String from=" FROM repair_location l LEFT JOIN classroom_resource r ON r.id=l.classroom_resource_id LEFT JOIN building b ON b.id=l.building_id";
        from+=" LEFT JOIN dorm_room dr ON dr.id=l.dorm_room_id LEFT JOIN dorm_building db ON db.id=dr.building_id";
        long total=jdbc.sql("SELECT COUNT(*)"+from+where).params(params).query(Long.class).single();
        var rows=jdbc.sql(LOCATION_SELECT+where+" ORDER BY l.campus_id,l.id LIMIT :limit OFFSET :offset").params(params).param("limit",size).param("offset",((long)page-1)*size)
            .query((r,n)->new PublicLocation(r.getLong("id"),r.getLong("campus_id"),r.getString("campus_name"),r.getString("name"),r.getString("kind"),r.getString("detail_hint"),r.getString("provenance"))).list();
        return new Page<>(rows,total,page,size);
    }
    private Location location(long id) { return jdbc.sql(LOCATION_SELECT+" WHERE l.id=:id").param("id",id).query(this::locationRow).optional().orElseThrow(()->missing("报修地点不存在")); }
    @Transactional public Location createLocation(CampusPrincipal user,LocationInput input) {
        lock(user);if(input.version()!=0)stale();validateLocation(input);
        if(jdbc.sql("SELECT COUNT(*) FROM repair_location WHERE campus_id=:c AND code=:code OR classroom_resource_id=:room OR dorm_room_id=:dorm")
            .param("c",input.campusId()).param("code",input.code()).param("room",input.classroomResourceId()).param("dorm",input.dormRoomId()).query(Long.class).single()>0)conflict("地点编号或关联房间已建档，请核对已有地点");
        var key=new GeneratedKeyHolder();jdbc.sql("INSERT INTO repair_location(campus_id,code,name,kind,classroom_resource_id,building_id,detail_hint,provenance,dorm_room_id) VALUES(:campus,:code,:name,:kind,:room,:building,:hint,:origin,:dorm)")
            .params(locationFields(input)).update(key,"id");var result=location(Objects.requireNonNull(key.getKey()).longValue());
        audit(user,"LOCATION_CREATE","REPAIR_LOCATION",result.id(),locationSummary(result),input.reason());return result;
    }
    @Transactional public Location updateLocation(CampusPrincipal user,long id,LocationInput input) {
        lock(user);var old=location(id);version(old.version(),input.version());
        if(old.campusId()!=input.campusId() || !old.code().equals(input.code()) || !old.kind().equals(input.kind())
            || !Objects.equals(old.classroomResourceId(),input.classroomResourceId()) || !Objects.equals(old.buildingId(),input.buildingId()) || !Objects.equals(old.dormRoomId(),input.dormRoomId()))conflict("已建档地点的校区、编号和关联资源不能更换，请另建地点");
        // An unavailable source can still be annotated; enabling is checked separately.
        if(jdbc.sql("UPDATE repair_location SET name=:name,detail_hint=:hint,provenance=:origin,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:v")
            .param("name",input.name().trim()).param("hint",input.detailHint()==null?"":input.detailHint().trim()).param("origin",input.provenance()).param("id",id).param("v",input.version()).update()!=1)stale();
        var result=location(id);audit(user,"LOCATION_UPDATE","REPAIR_LOCATION",id,locationSummary(old)+" → "+locationSummary(result),input.reason());return result;
    }
    @Transactional public Location locationState(CampusPrincipal user,long id,StateInput input) {
        lock(user);var old=location(id);version(old.version(),input.version());if(old.enabled()==input.enabled())conflict("地点已经处于目标状态");
        if(input.enabled())validateSource(old.campusId(),old.kind(),old.classroomResourceId(),old.buildingId(),old.dormRoomId());
        if(jdbc.sql("UPDATE repair_location SET enabled=:s,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:v").param("s",input.enabled()).param("id",id).param("v",input.version()).update()!=1)stale();
        var result=location(id);audit(user,input.enabled()?"LOCATION_ENABLE":"LOCATION_DISABLE","REPAIR_LOCATION",id,locationSummary(result),input.reason());return result;
    }
    private void validateLocation(LocationInput input) { campus(input.campusId());validateSource(input.campusId(),input.kind(),input.classroomResourceId(),input.buildingId(),input.dormRoomId()); }
    private void validateSource(long campus,String kind,Long room,Long building,Long dorm) {
        if(kind.equals("DORM_ROOM")!=(dorm!=null) || (kind.equals("DORM_ROOM") && (room!=null || building!=null)))bad("宿舍报修须关联唯一房间，不能混用其他地点来源");
        if(dorm!=null && jdbc.sql("SELECT COUNT(*) FROM dorm_room r JOIN dorm_building b ON b.id=r.building_id WHERE r.id=:id AND b.campus_id=:c AND r.enabled=TRUE AND b.enabled=TRUE").param("id",dorm).param("c",campus).query(Long.class).single()!=1)bad("宿舍房间不存在、已停用或与校区不一致");
        if(kind.equals("CLASSROOM")!=(room!=null) || (kind.equals("BUILDING_COMMON") && building==null) || (kind.equals("CAMPUS_PUBLIC") && building!=null))bad("地点类型与关联资源不一致");
        if(room!=null && jdbc.sql("SELECT COUNT(*) FROM classroom_resource WHERE id=:id AND campus_id=:c AND enabled=TRUE AND TRIM(building_name)<>'' AND TRIM(room_number)<>''").param("id",room).param("c",campus).query(Long.class).single()!=1)bad("所选教室不存在、校区不一致、已停用或位置资料未完善");
        if(building!=null && jdbc.sql("SELECT COUNT(*) FROM building WHERE id=:id AND campus_id=:c").param("id",building).param("c",campus).query(Long.class).single()!=1)bad("楼栋不存在或与所选校区不一致");
    }
    public Page<Scope> scopes(CampusPrincipal user,int page,int size) {
        manage(user);pagination(page,size);long total=jdbc.sql("SELECT COUNT(*) FROM repair_staff_scope").query(Long.class).single();
        return new Page<>(jdbc.sql(SCOPE_SELECT+" ORDER BY s.id DESC LIMIT :limit OFFSET :offset").param("limit",size).param("offset",((long)page-1)*size).query(this::scopeRow).list(),total,page,size);
    }
    public Responsibilities responsibilities(CampusPrincipal user) {
        var fresh=identities.current(user.id()).orElseThrow(()->missing("账号不存在"));
        if(fresh.permissions().contains("repair:dispatch"))identities.requirePermission(user,"repair:dispatch");
        else identities.requirePermission(user,"repair:work");
        var scopes=jdbc.sql(SCOPE_SELECT+" WHERE s.user_id=:u ORDER BY s.capability,s.campus_id,s.category,s.id").param("u",user.id()).query(this::scopeRow).list();
        return new Responsibilities(CATEGORIES,scopes);
    }
    private Scope scope(long id) { return jdbc.sql(SCOPE_SELECT+" WHERE s.id=:id").param("id",id).query(this::scopeRow).optional().orElseThrow(()->missing("负责范围不存在")); }
    @Transactional public Scope createScope(CampusPrincipal user,ScopeInput input) {
        lock(user);if(input.version()!=0)stale();validateScope(input);uniqueScope(input,0);
        var key=new GeneratedKeyHolder();jdbc.sql("INSERT INTO repair_staff_scope(user_id,capability,campus_id,category,location_id) VALUES(:u,:ability,:campus,:category,:location)")
            .params(scopeFields(input)).update(key,"id");var result=scope(Objects.requireNonNull(key.getKey()).longValue());
        audit(user,"SCOPE_CREATE","REPAIR_SCOPE",result.id(),scopeSummary(result),input.reason());return result;
    }
    @Transactional public Scope updateScope(CampusPrincipal user,long id,ScopeInput input) {
        lock(user);var old=scope(id);version(old.version(),input.version());validateScope(input);uniqueScope(input,id);
        var params=scopeFields(input);params.put("id",id);params.put("v",input.version());
        if(jdbc.sql("UPDATE repair_staff_scope SET user_id=:u,capability=:ability,campus_id=:campus,category=:category,location_id=:location,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:v").params(params).update()!=1)stale();
        var result=scope(id);audit(user,"SCOPE_UPDATE","REPAIR_SCOPE",id,scopeSummary(old)+" → "+scopeSummary(result),input.reason());return result;
    }
    @Transactional public Scope scopeState(CampusPrincipal user,long id,StateInput input) {
        lock(user);var old=scope(id);version(old.version(),input.version());if(old.enabled()==input.enabled())conflict("负责范围已经处于目标状态");
        if(input.enabled())validateScope(new ScopeInput(old.userId(),old.capability(),old.campusId(),old.category(),old.locationId(),old.version(),input.reason()));
        if(jdbc.sql("UPDATE repair_staff_scope SET enabled=:s,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:v").param("s",input.enabled()).param("id",id).param("v",input.version()).update()!=1)stale();
        var result=scope(id);audit(user,input.enabled()?"SCOPE_ENABLE":"SCOPE_DISABLE","REPAIR_SCOPE",id,scopeSummary(result),input.reason());return result;
    }
    private void validateScope(ScopeInput input) {
        campus(input.campusId());String permission=input.capability().equals("DISPATCH")?"repair:dispatch":"repair:work";
        var person=identities.current(input.userId()).orElseThrow(()->new ApiException(HttpStatus.BAD_REQUEST,"REPAIR_SETTINGS_INVALID","人员不存在"));
        if(!person.permissions().contains(permission))bad("所选人员未启用或没有对应后勤／维修岗位权限");
        if(input.locationId()!=null && location(input.locationId()).campusId()!=input.campusId())bad("具体地点与负责校区不一致");
    }
    private void uniqueScope(ScopeInput input,long except) {
        if(jdbc.sql("SELECT COUNT(*) FROM repair_staff_scope WHERE id<>:except AND user_id=:u AND capability=:a AND campus_id=:c AND category=:cat AND (location_id=:l OR (location_id IS NULL AND :l IS NULL))")
            .param("except",except).param("u",input.userId()).param("a",input.capability()).param("c",input.campusId()).param("cat",input.category()).param("l",input.locationId()).query(Long.class).single()>0)conflict("相同负责范围已建档，请编辑或重新启用已有记录");
    }
    /** A stopped location still has historical work; callers validate availability only for new submissions. */
    public boolean matches(CampusPrincipal user,String capability,long campus,String category,long location) {
        if(!List.of("DISPATCH","WORK").contains(capability) || CATEGORIES.stream().noneMatch(c->c.code().equals(category)))bad("负责范围查询参数无效");
        identities.requirePermission(user,capability.equals("DISPATCH")?"repair:dispatch":"repair:work");
        if(location(location).campusId()!=campus)return false;
        return jdbc.sql("SELECT COUNT(*) FROM repair_staff_scope WHERE user_id=:u AND capability=:a AND campus_id=:c AND category=:cat AND enabled=TRUE AND (location_id IS NULL OR location_id=:l)")
            .param("u",user.id()).param("a",capability).param("c",campus).param("cat",category).param("l",location).query(Long.class).single()>0;
    }
    public Page<Audit> history(CampusPrincipal user,String type,long id,int page,int size) {
        manage(user);pagination(page,size);if(type.equals("LOCATION"))location(id);else if(type.equals("SCOPE"))scope(id);else bad("记录类型无效");
        String entity=type.equals("LOCATION")?"REPAIR_LOCATION":"REPAIR_SCOPE";
        long total=jdbc.sql("SELECT COUNT(*) FROM foundation_audit WHERE entity_type=:t AND entity_id=:id").param("t",entity).param("id",id).query(Long.class).single();
        var rows=jdbc.sql("SELECT * FROM foundation_audit WHERE entity_type=:t AND entity_id=:id ORDER BY id DESC LIMIT :limit OFFSET :offset")
            .param("t",entity).param("id",id).param("limit",size).param("offset",((long)page-1)*size).query((r,n)->new Audit(r.getLong("id"),r.getString("actor_name"),r.getString("action"),r.getString("entity_type"),r.getLong("entity_id"),r.getString("summary"),r.getString("reason"),r.getTimestamp("created_at").toLocalDateTime())).list();
        return new Page<>(rows,total,page,size);
    }
    private Location locationRow(ResultSet r,int n) throws SQLException {
        boolean roomReady=r.getObject("classroom_resource_id")==null || (r.getObject("room_campus")!=null && r.getLong("room_campus")==r.getLong("campus_id") && r.getBoolean("room_enabled") && notBlank(r.getString("room_building")) && notBlank(r.getString("room_number")));
        boolean buildingReady=r.getObject("building_id")==null || (r.getObject("building_campus")!=null && r.getLong("building_campus")==r.getLong("campus_id"));
        boolean dormReady=r.getObject("dorm_room_id")==null || (r.getObject("dorm_campus")!=null && r.getLong("dorm_campus")==r.getLong("campus_id") && r.getBoolean("dorm_enabled") && r.getBoolean("dorm_building_enabled"));
        boolean available=r.getBoolean("enabled") && roomReady && buildingReady && dormReady;
        String message=!r.getBoolean("enabled")?"地点已停用":!roomReady?"关联教室已停用、位置未完善或校区已改变":!buildingReady?"关联楼栋校区已改变":!dormReady?"关联宿舍已停用或校区已改变":"可用于后续报修";
        return new Location(r.getLong("id"),r.getLong("campus_id"),r.getString("campus_name"),r.getString("code"),r.getString("name"),r.getString("kind"),r.getObject("classroom_resource_id",Long.class),r.getObject("building_id",Long.class),
            r.getString("dorm_name")!=null?r.getString("dorm_building")+" · "+r.getString("dorm_name"):r.getString("room_name")!=null?r.getString("room_name"):r.getString("building_name"),r.getString("detail_hint"),r.getString("provenance"),r.getBoolean("enabled"),available,message,r.getString("verification_status"),r.getInt("version"),r.getTimestamp("updated_at").toLocalDateTime(),r.getObject("dorm_room_id",Long.class));
    }
    private Scope scopeRow(ResultSet r,int n) throws SQLException {
        boolean effective=r.getBoolean("enabled") && r.getBoolean("user_enabled") && r.getBoolean("has_permission");
        return new Scope(r.getLong("id"),r.getLong("user_id"),r.getString("user_name"),r.getString("capability"),r.getLong("campus_id"),r.getString("campus_name"),r.getString("category"),r.getObject("location_id",Long.class),r.getString("location_name"),r.getBoolean("enabled"),effective,
            !r.getBoolean("enabled")?"范围已停用":!r.getBoolean("user_enabled")?"账号已停用":!r.getBoolean("has_permission")?"对应岗位权限已移除":"当前有效",r.getInt("version"),r.getTimestamp("updated_at").toLocalDateTime());
    }
    private HashMap<String,Object> locationFields(LocationInput i) {var p=new HashMap<String,Object>();p.put("campus",i.campusId());p.put("code",i.code());p.put("name",i.name().trim());p.put("kind",i.kind());p.put("room",i.classroomResourceId());p.put("building",i.buildingId());p.put("dorm",i.dormRoomId());p.put("hint",i.detailHint()==null?"":i.detailHint().trim());p.put("origin",i.provenance());return p;}
    private HashMap<String,Object> scopeFields(ScopeInput i) {var p=new HashMap<String,Object>();p.put("u",i.userId());p.put("ability",i.capability());p.put("campus",i.campusId());p.put("category",i.category());p.put("location",i.locationId());return p;}
    private void audit(CampusPrincipal u,String action,String type,long id,String summary,String reason) {
        String name=jdbc.sql("SELECT full_name FROM app_user WHERE id=:id").param("id",u.id()).query(String.class).single();
        jdbc.sql("INSERT INTO foundation_audit(actor_id,actor_name,action,entity_type,entity_id,summary,reason) VALUES(:u,:n,:a,:t,:id,:s,:r)")
            .param("u",u.id()).param("n",name).param("a",action).param("t",type).param("id",id).param("s",summary).param("r",reason.trim()).update();
    }
    private String locationSummary(Location l) { return l.campusName()+"；"+l.code()+"；"+l.name()+"；"+l.kind()+"；"+(l.enabled()?"开放":"停用")+"；"+l.detailHint()+"；"+l.provenance(); }
    private String scopeSummary(Scope s) { return s.userName()+"；"+s.capability()+"；"+s.campusName()+"；"+s.category()+"；"+(s.locationName()==null?"该校区全部开放地点":s.locationName())+"；"+(s.enabled()?"启用":"停用"); }
    private void campus(long id) {if(jdbc.sql("SELECT COUNT(*) FROM campus WHERE id=:id").param("id",id).query(Long.class).single()!=1)bad("校区不存在");}
    private boolean notBlank(String value) {return value!=null && !value.isBlank();}
    private void pagination(int page,int size) {if(page<1 || page>1000000 || size<1 || size>100)bad("页码须大于零，每页最多100条");}
    private void version(int actual,int expected) {if(actual!=expected)stale();}
    private void stale() {conflict("配置已变化，请刷新后重新操作");}
    private void bad(String message) {throw new ApiException(HttpStatus.BAD_REQUEST,"REPAIR_SETTINGS_INVALID",message);}
    private void conflict(String message) {throw new ApiException(HttpStatus.CONFLICT,"REPAIR_SETTINGS_CONFLICT",message);}
    private ApiException missing(String message) {return new ApiException(HttpStatus.NOT_FOUND,"REPAIR_SETTINGS_NOT_FOUND",message);}
}
