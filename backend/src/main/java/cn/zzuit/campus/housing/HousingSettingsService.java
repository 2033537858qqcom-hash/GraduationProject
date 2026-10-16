package cn.zzuit.campus.housing;

import static cn.zzuit.campus.housing.HousingModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HousingSettingsService {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private static final String BUILDING_FROM=" FROM dorm_building b JOIN campus c ON c.id=b.campus_id ";
    private static final String BUILDING_SELECT="SELECT b.*,c.name campus_name,(SELECT COUNT(*) FROM dorm_room r WHERE r.building_id=b.id) room_count,(SELECT COUNT(*) FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id WHERE r.building_id=b.id AND d.status='OCCUPIED') occupied_count"+BUILDING_FROM;
    private static final String ROOM_FROM=" FROM dorm_room r JOIN dorm_building b ON b.id=r.building_id JOIN campus c ON c.id=b.campus_id ";
    private static final String ROOM_SELECT="SELECT r.*,b.name building_name,b.campus_id,b.enabled building_enabled,c.name campus_name,(SELECT COUNT(*) FROM dorm_bed d WHERE d.room_id=r.id) bed_count,(SELECT COUNT(*) FROM dorm_bed d WHERE d.room_id=r.id AND d.status='OCCUPIED') occupied_count"+ROOM_FROM;
    private static final String BED_FROM="""
         FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id JOIN dorm_building b ON b.id=r.building_id JOIN campus c ON c.id=b.campus_id
         LEFT JOIN housing_assignment a ON a.current_bed_id=d.id LEFT JOIN app_user u ON u.id=a.student_id
        """;
    private static final String BED_SELECT="SELECT d.*,r.name room_name,r.floor_label,r.enabled room_enabled,b.id building_id,b.enabled building_enabled,a.id assignment_id,a.version assignment_version,u.username occupant_username,u.full_name occupant_name "+BED_FROM;
    private static final String SCOPE_FROM="""
         FROM dorm_manager_scope s JOIN app_user u ON u.id=s.user_id JOIN dorm_building b ON b.id=s.building_id JOIN campus c ON c.id=b.campus_id
        """;
    private static final String SCOPE_SELECT="SELECT s.*,u.full_name user_name,u.enabled user_enabled,b.name building_name,b.enabled building_enabled,b.campus_id,c.name campus_name,EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code AND rp.permission_code='housing:assign' WHERE ur.user_id=s.user_id) has_permission"+SCOPE_FROM;
    public HousingSettingsService(JdbcClient jdbc,IdentityService identities) {this.jdbc=jdbc;this.identities=identities;}
    private void manage(CampusPrincipal user) {identities.requirePermission(user,"housing:configure");}
    private void lock(CampusPrincipal user) {jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update();manage(user);}

    public Catalog catalog(CampusPrincipal user) {
        manage(user);
        var campuses=jdbc.sql("SELECT id,name FROM campus ORDER BY id").query((r,n)->new Campus(r.getLong(1),r.getString(2))).list();
        var buildings=jdbc.sql("SELECT id,campus_id,name,enabled FROM dorm_building ORDER BY campus_id,code,id").query((r,n)->new BuildingOption(r.getLong(1),r.getLong(2),r.getString(3),r.getBoolean(4))).list();
        var managers=jdbc.sql("""
            SELECT u.id,u.full_name,d.name,u.enabled FROM app_user u LEFT JOIN department d ON d.id=u.department_id
            WHERE EXISTS(SELECT 1 FROM user_role ur JOIN role_permission rp ON rp.role_code=ur.role_code AND rp.permission_code='housing:assign' WHERE ur.user_id=u.id)
            ORDER BY u.full_name,u.id
            """).query((r,n)->new ManagerOption(r.getLong(1),r.getString(2),r.getString(3),r.getBoolean(4))).list();
        return new Catalog(campuses,buildings,managers);
    }
    public Page<Building> buildings(CampusPrincipal user,Long campus,String q,int page,int size) {
        manage(user);pagination(page,size);if(q.length()>100) bad("搜索关键词最多 100 个字符");
        String where=" WHERE 1=1";var params=new HashMap<String,Object>();
        if(campus!=null) {where+=" AND b.campus_id=:campus";params.put("campus",campus);}
        if(!q.isBlank()) {where+=" AND (b.name LIKE :q ESCAPE '!' OR b.code LIKE :q ESCAPE '!')";params.put("q",like(q));}
        long total=jdbc.sql("SELECT COUNT(*)"+BUILDING_FROM+where).params(params).query(Long.class).single();
        var items=jdbc.sql(BUILDING_SELECT+where+" ORDER BY b.campus_id,b.code,b.id LIMIT :limit OFFSET :offset").params(params).param("limit",size).param("offset",offset(page,size)).query(this::buildingRow).list();
        return new Page<>(items,total,page,size);
    }
    public Page<Room> rooms(CampusPrincipal user,long buildingId,int page,int size) {
        manage(user);pagination(page,size);building(buildingId);
        long total=jdbc.sql("SELECT COUNT(*)"+ROOM_FROM+"WHERE r.building_id=:id").param("id",buildingId).query(Long.class).single();
        var items=jdbc.sql(ROOM_SELECT+"WHERE r.building_id=:id ORDER BY r.floor_label,r.code,r.id LIMIT :limit OFFSET :offset").param("id",buildingId).param("limit",size).param("offset",offset(page,size)).query(this::roomRow).list();
        return new Page<>(items,total,page,size);
    }
    public Page<Bed> beds(CampusPrincipal user,long roomId,int page,int size) {
        manage(user);pagination(page,size);room(roomId);
        long total=jdbc.sql("SELECT COUNT(*) FROM dorm_bed WHERE room_id=:id").param("id",roomId).query(Long.class).single();
        var items=jdbc.sql(BED_SELECT+"WHERE d.room_id=:id ORDER BY d.code,d.id LIMIT :limit OFFSET :offset").param("id",roomId).param("limit",size).param("offset",offset(page,size)).query(this::bedRow).list();
        return new Page<>(items,total,page,size);
    }
    public Page<Scope> scopes(CampusPrincipal user,int page,int size) {
        manage(user);pagination(page,size);
        long total=jdbc.sql("SELECT COUNT(*) FROM dorm_manager_scope").query(Long.class).single();
        var items=jdbc.sql(SCOPE_SELECT+"ORDER BY s.id DESC LIMIT :limit OFFSET :offset").param("limit",size).param("offset",offset(page,size)).query(this::scopeRow).list();
        return new Page<>(items,total,page,size);
    }
    public Page<Assignment> assignments(CampusPrincipal user,long buildingId,int page,int size) {
        manage(user);pagination(page,size);building(buildingId);
        String where=" WHERE d.room_id IN (SELECT id FROM dorm_room WHERE building_id=:building)";
        long total=jdbc.sql("SELECT COUNT(*) FROM housing_assignment a JOIN dorm_bed d ON d.id=a.bed_id"+where).param("building",buildingId).query(Long.class).single();
        var items=jdbc.sql("SELECT a.*,r.building_id,(SELECT i.batch_id FROM housing_import_row i WHERE i.assignment_id=a.id) import_batch_id FROM housing_assignment a JOIN dorm_bed d ON d.id=a.bed_id JOIN dorm_room r ON r.id=d.room_id"+where+" ORDER BY a.id DESC LIMIT :limit OFFSET :offset")
            .param("building",buildingId).param("limit",size).param("offset",offset(page,size)).query(this::assignmentRow).list();
        return new Page<>(items,total,page,size);
    }

    @Transactional public Building createBuilding(CampusPrincipal user,BuildingInput input) {
        lock(user);if(input.version()!=0) stale();campus(input.campusId());uniqueBuilding(input,-1);
        var key=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO dorm_building(campus_id,code,name,provenance) VALUES(:campus,:code,:name,:origin)").params(buildingFields(input)).update(key,"id");
        var created=building(key.getKey().longValue());audit(user,"BUILDING_CREATE","DORM_BUILDING",created.id(),buildingSummary(created),input.reason());return created;
    }
    @Transactional public Building updateBuilding(CampusPrincipal user,long id,BuildingInput input) {
        lock(user);var old=building(id);version(old.version(),input.version());campus(input.campusId());
        if(old.campusId()!=input.campusId() || !old.code().equals(input.code())) conflict("楼栋校区和编号建档后不能修改");
        uniqueBuilding(input,id);
        if(jdbc.sql("UPDATE dorm_building SET name=:name,provenance=:origin,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version").params(buildingFields(input)).param("id",id).param("version",input.version()).update()!=1) stale();
        var changed=building(id);audit(user,"BUILDING_UPDATE","DORM_BUILDING",id,buildingSummary(old)+" → "+buildingSummary(changed),input.reason());return changed;
    }
    @Transactional public Building buildingState(CampusPrincipal user,long id,StateInput input) {
        lock(user);var old=building(id);version(old.version(),input.version());
        if(old.enabled()==input.enabled()) conflict("楼栋已经处于目标状态");
        if(!input.enabled() && occupiedBuilding(id)) conflict("楼栋仍有在住学生，不能停用");
        if(jdbc.sql("UPDATE dorm_building SET enabled=:enabled,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version").param("enabled",input.enabled()).param("id",id).param("version",input.version()).update()!=1) stale();
        var changed=building(id);audit(user,input.enabled()?"BUILDING_ENABLE":"BUILDING_DISABLE","DORM_BUILDING",id,buildingSummary(changed),input.reason());return changed;
    }
    @Transactional public Room createRoom(CampusPrincipal user,RoomInput input) {
        lock(user);if(input.version()!=0) stale();building(input.buildingId());uniqueRoom(input,-1);
        var key=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO dorm_room(building_id,code,name,floor_label) VALUES(:building,:code,:name,:floor)").params(roomFields(input)).update(key,"id");
        var created=room(key.getKey().longValue());audit(user,"ROOM_CREATE","DORM_ROOM",created.id(),roomSummary(created),input.reason());return created;
    }
    @Transactional public Room updateRoom(CampusPrincipal user,long id,RoomInput input) {
        lock(user);var old=room(id);version(old.version(),input.version());
        if(old.buildingId()!=input.buildingId() || !old.code().equals(input.code())) conflict("房间所属楼栋和编号建档后不能修改");
        uniqueRoom(input,id);
        if(jdbc.sql("UPDATE dorm_room SET name=:name,floor_label=:floor,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version").params(roomFields(input)).param("id",id).param("version",input.version()).update()!=1) stale();
        var changed=room(id);audit(user,"ROOM_UPDATE","DORM_ROOM",id,roomSummary(old)+" → "+roomSummary(changed),input.reason());return changed;
    }
    @Transactional public Room roomState(CampusPrincipal user,long id,StateInput input) {
        lock(user);var old=room(id);version(old.version(),input.version());
        if(old.enabled()==input.enabled()) conflict("房间已经处于目标状态");
        if(!input.enabled() && occupiedRoom(id)) conflict("房间仍有在住学生，不能停用");
        if(jdbc.sql("UPDATE dorm_room SET enabled=:enabled,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version").param("enabled",input.enabled()).param("id",id).param("version",input.version()).update()!=1) stale();
        var changed=room(id);audit(user,input.enabled()?"ROOM_ENABLE":"ROOM_DISABLE","DORM_ROOM",id,roomSummary(changed),input.reason());return changed;
    }
    @Transactional public Bed createBed(CampusPrincipal user,BedInput input) {
        lock(user);if(input.version()!=0) stale();room(input.roomId());uniqueBed(input,-1);
        var key=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO dorm_bed(room_id,code,label) VALUES(:room,:code,:label)").params(bedFields(input)).update(key,"id");
        var created=bed(key.getKey().longValue());audit(user,"BED_CREATE","DORM_BED",created.id(),bedSummary(created),input.reason());return created;
    }
    @Transactional public Bed updateBed(CampusPrincipal user,long id,BedInput input) {
        lock(user);var old=bed(id);version(old.version(),input.version());
        if(old.roomId()!=input.roomId() || !old.code().equals(input.code())) conflict("床位所属房间和编号建档后不能修改");
        uniqueBed(input,id);
        if(jdbc.sql("UPDATE dorm_bed SET label=:label,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version").params(bedFields(input)).param("id",id).param("version",input.version()).update()!=1) stale();
        var changed=bed(id);audit(user,"BED_UPDATE","DORM_BED",id,bedSummary(old)+" → "+bedSummary(changed),input.reason());return changed;
    }
    @Transactional public Bed bedState(CampusPrincipal user,long id,StateInput input) {
        lock(user);var old=bed(id);version(old.version(),input.version());
        if(old.enabled()==input.enabled()) conflict("床位已经处于目标状态");
        if(!input.enabled() && (old.assignmentId()!=null || "OCCUPIED".equals(old.status()) || "RESERVED".equals(old.status()))) conflict("在住或已预留的床位不能停用");
        if(jdbc.sql("UPDATE dorm_bed SET enabled=:enabled,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version").param("enabled",input.enabled()).param("id",id).param("version",input.version()).update()!=1) stale();
        var changed=bed(id);audit(user,input.enabled()?"BED_ENABLE":"BED_DISABLE","DORM_BED",id,bedSummary(changed),input.reason());return changed;
    }
    @Transactional public Bed maintenance(CampusPrincipal user,long id,MaintenanceInput input) {
        lock(user);var old=bed(id);version(old.version(),input.version());
        if(old.status().equals(input.status())) conflict("床位已经处于目标状态");
        if(old.assignmentId()!=null || "OCCUPIED".equals(old.status()) || "RESERVED".equals(old.status())) conflict("在住或已预留的床位不能改为维护，请先完成退宿或取消预留");
        if(jdbc.sql("UPDATE dorm_bed SET status=:status,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status IN ('VACANT','MAINTENANCE')").param("status",input.status()).param("id",id).param("version",input.version()).update()!=1) stale();
        var changed=bed(id);audit(user,"BED_"+input.status(),"DORM_BED",id,bedSummary(changed),input.reason());return changed;
    }
    @Transactional public Scope createScope(CampusPrincipal user,ScopeInput input) {
        lock(user);if(input.version()!=0) stale();building(input.buildingId());manager(input.userId());
        if(jdbc.sql("SELECT COUNT(*) FROM dorm_manager_scope WHERE user_id=:u AND building_id=:b").param("u",input.userId()).param("b",input.buildingId()).query(Long.class).single()>0) conflict("该人员已有此楼栋的负责记录，请直接启用或停用");
        var key=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO dorm_manager_scope(user_id,building_id) VALUES(:user,:building)").param("user",input.userId()).param("building",input.buildingId()).update(key,"id");
        var created=scope(key.getKey().longValue());audit(user,"SCOPE_CREATE","DORM_SCOPE",created.id(),scopeSummary(created),input.reason());return created;
    }
    @Transactional public Scope scopeState(CampusPrincipal user,long id,StateInput input) {
        lock(user);var old=scope(id);version(old.version(),input.version());
        if(old.enabled()==input.enabled()) conflict("负责范围已经处于目标状态");
        if(input.enabled()) manager(old.userId());
        if(jdbc.sql("UPDATE dorm_manager_scope SET enabled=:enabled,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version").param("enabled",input.enabled()).param("id",id).param("version",input.version()).update()!=1) stale();
        var changed=scope(id);audit(user,input.enabled()?"SCOPE_ENABLE":"SCOPE_DISABLE","DORM_SCOPE",id,scopeSummary(changed),input.reason());return changed;
    }
    public Page<Audit> history(CampusPrincipal user,String type,long id,int page,int size) {
        manage(user);pagination(page,size);
        String entity=switch(type) {case "BUILDING"->"DORM_BUILDING";case "ROOM"->"DORM_ROOM";case "BED"->"DORM_BED";case "SCOPE"->"DORM_SCOPE";case "STAY"->"HOUSING_STAY";default->"";};
        if(entity.isEmpty()) bad("记录类型无效");
        if(type.equals("BUILDING")) building(id);else if(type.equals("ROOM")) room(id);else if(type.equals("BED")) bed(id);else if(type.equals("SCOPE")) scope(id);else stayExists(id);
        long total=jdbc.sql("SELECT COUNT(*) FROM foundation_audit WHERE entity_type=:t AND entity_id=:id").param("t",entity).param("id",id).query(Long.class).single();
        var rows=jdbc.sql("SELECT * FROM foundation_audit WHERE entity_type=:t AND entity_id=:id ORDER BY id DESC LIMIT :limit OFFSET :offset").param("t",entity).param("id",id).param("limit",size).param("offset",offset(page,size))
            .query((r,n)->new Audit(r.getLong("id"),r.getString("actor_name"),r.getString("action"),r.getString("entity_type"),r.getLong("entity_id"),r.getString("summary"),r.getString("reason"),r.getTimestamp("created_at").toLocalDateTime())).list();
        return new Page<>(rows,total,page,size);
    }

    private Building building(long id) {return jdbc.sql(BUILDING_SELECT+"WHERE b.id=:id").param("id",id).query(this::buildingRow).optional().orElseThrow(()->missing("宿舍楼不存在"));}
    private Room room(long id) {return jdbc.sql(ROOM_SELECT+"WHERE r.id=:id").param("id",id).query(this::roomRow).optional().orElseThrow(()->missing("宿舍房间不存在"));}
    private Bed bed(long id) {return jdbc.sql(BED_SELECT+"WHERE d.id=:id").param("id",id).query(this::bedRow).optional().orElseThrow(()->missing("床位不存在"));}
    private Scope scope(long id) {return jdbc.sql(SCOPE_SELECT+"WHERE s.id=:id").param("id",id).query(this::scopeRow).optional().orElseThrow(()->missing("楼栋负责记录不存在"));}
    private void stayExists(long id) {if(jdbc.sql("SELECT COUNT(*) FROM housing_assignment WHERE id=:id").param("id",id).query(Long.class).single()==0) throw missing("住宿记录不存在");}
    private boolean occupiedBuilding(long id) {return jdbc.sql("SELECT COUNT(*) FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id WHERE r.building_id=:id AND (d.status IN ('OCCUPIED','RESERVED') OR EXISTS(SELECT 1 FROM housing_assignment a WHERE a.current_bed_id=d.id))").param("id",id).query(Long.class).single()>0;}
    private boolean occupiedRoom(long id) {return jdbc.sql("SELECT COUNT(*) FROM dorm_bed d WHERE d.room_id=:id AND (d.status IN ('OCCUPIED','RESERVED') OR EXISTS(SELECT 1 FROM housing_assignment a WHERE a.current_bed_id=d.id))").param("id",id).query(Long.class).single()>0;}
    private void campus(long id) {if(jdbc.sql("SELECT COUNT(*) FROM campus WHERE id=:id").param("id",id).query(Long.class).single()!=1) bad("校区不存在");}
    private void manager(long id) {
        var person=identities.current(id).orElseThrow(()->new ApiException(HttpStatus.BAD_REQUEST,"HOUSING_SETTINGS_INVALID","人员不存在或未启用"));
        if(!person.permissions().contains("housing:assign")) bad("所选人员未启用或没有宿管岗位权限");
    }
    private void uniqueBuilding(BuildingInput input,long except) {if(jdbc.sql("SELECT COUNT(*) FROM dorm_building WHERE id<>:except AND campus_id=:campus AND code=:code").param("except",except).param("campus",input.campusId()).param("code",input.code()).query(Long.class).single()>0) conflict("同一校区已有相同楼栋编号");}
    private void uniqueRoom(RoomInput input,long except) {if(jdbc.sql("SELECT COUNT(*) FROM dorm_room WHERE id<>:except AND building_id=:building AND code=:code").param("except",except).param("building",input.buildingId()).param("code",input.code()).query(Long.class).single()>0) conflict("同一楼栋已有相同房间编号");}
    private void uniqueBed(BedInput input,long except) {if(jdbc.sql("SELECT COUNT(*) FROM dorm_bed WHERE id<>:except AND room_id=:room AND code=:code").param("except",except).param("room",input.roomId()).param("code",input.code()).query(Long.class).single()>0) conflict("同一房间已有相同床位编号");}
    private HashMap<String,Object> buildingFields(BuildingInput i) {var p=new HashMap<String,Object>();p.put("campus",i.campusId());p.put("code",i.code());p.put("name",i.name().trim());p.put("origin",i.provenance());return p;}
    private HashMap<String,Object> roomFields(RoomInput i) {var p=new HashMap<String,Object>();p.put("building",i.buildingId());p.put("code",i.code());p.put("name",i.name().trim());p.put("floor",i.floorLabel().trim());return p;}
    private HashMap<String,Object> bedFields(BedInput i) {var p=new HashMap<String,Object>();p.put("room",i.roomId());p.put("code",i.code());p.put("label",i.label().trim());return p;}
    private Building buildingRow(ResultSet r,int n) throws SQLException {return new Building(r.getLong("id"),r.getLong("campus_id"),r.getString("campus_name"),r.getString("code"),r.getString("name"),r.getString("provenance"),r.getBoolean("enabled"),r.getInt("version"),r.getLong("room_count"),r.getLong("occupied_count"),r.getTimestamp("updated_at").toLocalDateTime());}
    private Room roomRow(ResultSet r,int n) throws SQLException {return new Room(r.getLong("id"),r.getLong("building_id"),r.getLong("campus_id"),r.getString("campus_name"),r.getString("building_name"),r.getBoolean("building_enabled"),r.getString("code"),r.getString("name"),r.getString("floor_label"),r.getBoolean("enabled"),r.getInt("version"),r.getLong("bed_count"),r.getLong("occupied_count"),r.getTimestamp("updated_at").toLocalDateTime());}
    private Bed bedRow(ResultSet r,int n) throws SQLException {return new Bed(r.getLong("id"),r.getLong("room_id"),r.getLong("building_id"),r.getString("room_name"),r.getString("floor_label"),r.getString("code"),r.getString("label"),r.getString("status"),r.getBoolean("enabled"),r.getBoolean("room_enabled"),r.getBoolean("building_enabled"),r.getInt("version"),r.getObject("assignment_id",Long.class),r.getObject("assignment_version",Integer.class),r.getString("occupant_username"),r.getString("occupant_name"),r.getTimestamp("updated_at").toLocalDateTime());}
    private Scope scopeRow(ResultSet r,int n) throws SQLException {
        boolean effective=r.getBoolean("enabled") && r.getBoolean("user_enabled") && r.getBoolean("has_permission") && r.getBoolean("building_enabled");
        String message=!r.getBoolean("enabled")?"范围已停用":!r.getBoolean("user_enabled")?"账号已停用":!r.getBoolean("has_permission")?"对应岗位权限已移除":!r.getBoolean("building_enabled")?"楼栋已停用":"当前有效";
        return new Scope(r.getLong("id"),r.getLong("user_id"),r.getString("user_name"),r.getLong("building_id"),r.getLong("campus_id"),r.getString("campus_name"),r.getString("building_name"),r.getBoolean("enabled"),effective,message,r.getInt("version"),r.getTimestamp("updated_at").toLocalDateTime());
    }
    private Assignment assignmentRow(ResultSet r,int n) throws SQLException {
        var ended=r.getTimestamp("ended_at");
        return new Assignment(r.getLong("id"),r.getString("student_username"),r.getString("student_name"),r.getLong("bed_id"),r.getLong("building_id"),r.getString("campus_name"),r.getString("building_name"),r.getString("room_name"),r.getString("floor_label"),r.getString("bed_label"),r.getString("status"),r.getInt("version"),r.getTimestamp("started_at").toLocalDateTime(),ended==null?null:ended.toLocalDateTime(),r.getString("end_reason"),r.getObject("import_batch_id",Long.class));
    }
    private void audit(CampusPrincipal user,String action,String type,long id,String summary,String reason) {
        String name=jdbc.sql("SELECT full_name FROM app_user WHERE id=:id").param("id",user.id()).query(String.class).single();
        jdbc.sql("INSERT INTO foundation_audit(actor_id,actor_name,action,entity_type,entity_id,summary,reason) VALUES(:u,:n,:a,:t,:id,:s,:r)")
            .param("u",user.id()).param("n",name).param("a",action).param("t",type).param("id",id).param("s",summary).param("r",reason.trim()).update();
    }
    private String buildingSummary(Building b) {return b.campusName()+"；"+b.code()+"；"+b.name()+"；"+(b.enabled()?"启用":"停用")+"；"+b.provenance();}
    private String roomSummary(Room r) {return r.buildingName()+"；"+r.code()+"；"+r.name()+"；"+r.floorLabel()+"；"+(r.enabled()?"启用":"停用");}
    private String bedSummary(Bed b) {return b.roomName()+"；"+b.code()+"；"+b.label()+"；"+b.status()+"；"+(b.enabled()?"启用":"停用");}
    private String scopeSummary(Scope s) {return s.userName()+"；"+s.campusName()+"；"+s.buildingName()+"；"+(s.enabled()?"启用":"停用");}
    private String like(String q) {return "%"+q.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
    private long offset(int page,int size) {return ((long)page-1)*size;}
    private void pagination(int page,int size) {if(page<1 || page>1000000 || size<1 || size>100) bad("页码须大于零，每页最多 100 条");}
    private void version(int actual,int expected) {if(actual!=expected) stale();}
    private void stale() {conflict("宿舍配置已变化，请刷新后重新操作");}
    private void bad(String message) {throw new ApiException(HttpStatus.BAD_REQUEST,"HOUSING_SETTINGS_INVALID",message);}
    private void conflict(String message) {throw new ApiException(HttpStatus.CONFLICT,"HOUSING_SETTINGS_CONFLICT",message);}
    private ApiException missing(String message) {return new ApiException(HttpStatus.NOT_FOUND,"HOUSING_SETTINGS_NOT_FOUND",message);}
}
