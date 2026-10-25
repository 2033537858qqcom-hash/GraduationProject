package cn.zzuit.campus.resource;

import static cn.zzuit.campus.resource.RoomModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.teaching.TeachingService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {
    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final TeachingService teaching;
    private final PhysicalRoomService physicalRooms;
    private static final String READY="r.enabled=TRUE AND r.building_name IS NOT NULL AND r.room_number IS NOT NULL AND r.teaching_capacity IS NOT NULL AND r.exam_capacity IS NOT NULL";
    private static final String SELECT="""
        SELECT r.*,cp.name campus_name,
          (SELECT COUNT(*) FROM teaching_lesson l WHERE l.room_id=r.id AND l.ends_at>CURRENT_TIMESTAMP) lesson_count,
          (SELECT COUNT(*) FROM academic_exam e WHERE e.room_id=r.id AND e.ends_at>CURRENT_TIMESTAMP AND e.status='PUBLISHED') exam_count,
          (SELECT COUNT(*) FROM academic_exam e WHERE e.room_id=r.id AND e.ends_at>CURRENT_TIMESTAMP AND e.status='DRAFT') draft_count,
          CASE WHEN EXISTS(SELECT 1 FROM teaching_lesson l WHERE l.room_id=r.id) OR EXISTS(SELECT 1 FROM academic_exam e WHERE e.room_id=r.id) OR EXISTS(SELECT 1 FROM venue_resource v WHERE v.classroom_resource_id=r.id) THEN TRUE ELSE FALSE END referenced
        FROM classroom_resource r JOIN campus cp ON cp.id=r.campus_id
        """;
    private static final String OCCUPANCY="""
        (SELECT id,class_id,room_id,classroom,starts_at,ends_at,'LESSON' kind FROM teaching_lesson
         UNION ALL SELECT id,class_id,room_id,classroom,starts_at,ends_at,'EXAM' kind FROM academic_exam WHERE status='PUBLISHED')
        """;
    public RoomService(JdbcClient jdbc,IdentityService identities,TeachingService teaching,PhysicalRoomService physicalRooms) { this.jdbc=jdbc; this.identities=identities; this.teaching=teaching; this.physicalRooms=physicalRooms; }
    private void manage(CampusPrincipal user) { identities.requirePermission(user,"foundation:manage"); }
    public Catalog catalog(CampusPrincipal user) {
        manage(user); return new Catalog(jdbc.sql("SELECT id,name FROM campus ORDER BY id").query((r,n)->new Campus(r.getLong(1),r.getString(2))).list());
    }
    public List<Room> academicRooms(CampusPrincipal user) {
        identities.requirePermission(user,"academic:manage"); return jdbc.sql(SELECT+" ORDER BY r.campus_id,r.building_name,r.room_number,r.id").query(this::row).list();
    }
    public Page<Room> list(CampusPrincipal user,String q,Long campus,String type,Boolean enabled,Boolean ready,int page,int size) {
        manage(user); pagination(page,size); if(q.length()>100) bad("搜索关键词最多 100 个字符");
        if(type!=null && !List.of("LECTURE","LAB","MULTIPURPOSE","OTHER").contains(type)) bad("教室类型无效");
        String where=" WHERE 1=1"; var params=new HashMap<String,Object>();
        if(!q.isBlank()) { where+=" AND (r.name LIKE :q ESCAPE '!' OR r.code LIKE :q ESCAPE '!' OR r.building_name LIKE :q ESCAPE '!')"; params.put("q","%"+q.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%"); }
        if(campus!=null) { where+=" AND r.campus_id=:campus"; params.put("campus",campus); }
        if(type!=null) { where+=" AND r.room_type=:type"; params.put("type",type); }
        if(enabled!=null) { where+=" AND r.enabled=:enabled"; params.put("enabled",enabled); }
        if(ready!=null) where+=" AND "+(ready?"("+READY+")":"NOT ("+READY+")");
        long total=jdbc.sql("SELECT COUNT(*) FROM classroom_resource r"+where).params(params).query(Long.class).single();
        var items=jdbc.sql(SELECT+where+" ORDER BY r.campus_id,r.code,r.id LIMIT :limit OFFSET :offset").params(params).param("limit",size).param("offset",((long)page-1)*size).query(this::row).list();
        return new Page<>(items,total,page,size);
    }
    public Room detail(CampusPrincipal user,long id) { manage(user); return find(id); }
    private Room find(long id) { return jdbc.sql(SELECT+" WHERE r.id=:id").param("id",id).query(this::row).optional().orElseThrow(this::missing); }

    @Transactional public Room create(CampusPrincipal user,RoomInput input) {
        teaching.lockWrites(); manage(user); validate(input); if(input.version()!=0) stale();
        var key=new GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO classroom_resource(campus_id,code,name,building_name,room_number,floor_label,room_type,teaching_capacity,exam_capacity)
            VALUES(:campus,:code,:name,:building,:number,:floor,:type,:teaching,:exam)
            """).params(fields(input)).update(key,"id");
        var created=find(key.getKey().longValue()); audit(user,created,"CREATED",input.reason(),snapshot(created)); return created;
    }
    @Transactional public Room update(CampusPrincipal user,long id,RoomInput input) {
        teaching.lockWrites(); manage(user); var old=find(id); version(old.version(),input.version()); validate(input);
        physicalRooms.requireCapacity(id,input.teachingCapacity());
        if(old.referenced() && (old.campusId()!=input.campusId() || !old.code().equals(input.code().trim())
            || (old.buildingName()!=null && !old.buildingName().equals(input.buildingName().trim()))
            || (old.roomNumber()!=null && !old.roomNumber().equals(input.roomNumber().trim())))) conflict("教室已有教学引用，校区、编号和已确定的楼栋房间不可移动；迁移记录允许补录未知楼栋及房间号");
        long over=jdbc.sql("SELECT COUNT(*) FROM "+OCCUPANCY+" o WHERE o.room_id=:room AND o.ends_at>CURRENT_TIMESTAMP AND (SELECT COUNT(*) FROM teaching_member m WHERE m.class_id=o.class_id AND m.active=TRUE)>CASE WHEN o.kind='EXAM' THEN :exam ELSE :teaching END")
            .param("room",id).param("exam",input.examCapacity()).param("teaching",input.teachingCapacity()).query(Long.class).single();
        if(over>0) conflict("新容量不足以容纳尚未结束的课程或已发布考试，请先核对教学班人数与安排");
        var params=fields(input); params.put("id",id); params.put("version",input.version());
        if(jdbc.sql("UPDATE classroom_resource SET campus_id=:campus,code=:code,name=:name,building_name=:building,room_number=:number,floor_label=:floor,room_type=:type,teaching_capacity=:teaching,exam_capacity=:exam,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version").params(params).update()!=1) stale();
        var changed=find(id); audit(user,changed,"UPDATED",input.reason(),snapshot(old)+" → "+snapshot(changed)); return changed;
    }
    @Transactional public Room state(CampusPrincipal user,long id,StateInput input) {
        teaching.lockWrites(); manage(user); var old=find(id); version(old.version(),input.version());
        if(old.enabled()==input.enabled()) conflict("教室已经处于目标状态");
        if(!input.enabled() && (old.upcomingLessonCount()+old.publishedExamCount()>0 || physicalRooms.hasReservations(id))) conflict("教室仍有课程、已发布考试或未完成预约，不能停用；请在未来占用中核对");
        if(jdbc.sql("UPDATE classroom_resource SET enabled=:enabled,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:v").param("enabled",input.enabled()).param("id",id).param("v",input.version()).update()!=1) stale();
        var changed=find(id); audit(user,changed,input.enabled()?"ENABLED":"DISABLED",input.reason(),snapshot(old)+" → "+snapshot(changed)); return changed;
    }
    /** Called only while holding the shared teaching write lock. The public input never supplies a location string. */
    public Room forScheduling(long id,long classId,boolean exam) {
        var room=find(id); if(!room.schedulable()) conflict(room.enabled()?"教室资料待补录，请管理员完善楼栋、房间号与两种容量后再安排":"所选教室已停用，请重新选择");
        long members=jdbc.sql("SELECT COUNT(*) FROM teaching_member WHERE class_id=:c AND active=TRUE").param("c",classId).query(Long.class).single();
        if(members>(exam?room.examCapacity():room.teachingCapacity())) conflict("教学班人数超过所选教室的"+(exam?"考试":"教学")+"容量，请选择足够容量的教室");
        return room;
    }
    public void requireMemberCapacity(long classId) {
        long members=jdbc.sql("SELECT COUNT(*) FROM teaching_member WHERE class_id=:c AND active=TRUE").param("c",classId).query(Long.class).single();
        // Historical locations with unknown capacities remain readable; new arrangements require completed metadata.
        long over=jdbc.sql("SELECT COUNT(*) FROM "+OCCUPANCY+" o JOIN classroom_resource r ON r.id=o.room_id WHERE o.class_id=:c AND o.ends_at>CURRENT_TIMESTAMP AND :members>=CASE WHEN o.kind='EXAM' THEN r.exam_capacity ELSE r.teaching_capacity END")
            .param("c",classId).param("members",members).query(Long.class).single();
        if(over>0) conflict("启用该学生后，教学班人数将超过已有课程或已发布考试的教室容量，请先调整安排");
    }
    public Page<Occupancy> occupancy(CampusPrincipal user,long id,int page,int size) {
        manage(user); find(id); pagination(page,size);
        String all="""
            (SELECT id,class_id,room_id,classroom,starts_at,ends_at,'LESSON' kind FROM teaching_lesson
             UNION ALL SELECT id,class_id,room_id,classroom,starts_at,ends_at,'EXAM' kind FROM academic_exam WHERE status='PUBLISHED'
             UNION ALL SELECT r.id,0 class_id,v.classroom_resource_id room_id,v.name classroom,r.starts_at,r.ends_at,
               CASE WHEN r.status='IN_USE' THEN 'RESERVATION_IN_USE' ELSE 'RESERVATION' END kind
             FROM venue_reservation r JOIN venue_resource v ON v.id=r.venue_id
             WHERE v.classroom_resource_id IS NOT NULL AND r.status IN ('SUBMITTED','APPROVED','IN_USE'))
            """;
        String where=" WHERE o.room_id=:room AND (o.ends_at>CURRENT_TIMESTAMP OR o.kind='RESERVATION_IN_USE')";
        long total=jdbc.sql("SELECT COUNT(*) FROM "+all+" o"+where).param("room",id).query(Long.class).single();
        var items=jdbc.sql("""
            SELECT o.*,COALESCE(tc.name,'校园场地预约') class_name,COALESCE(c.name,'预约占用') course_name,
              CASE WHEN o.kind LIKE 'RESERVATION%' THEN (SELECT expected_participants FROM venue_reservation WHERE id=o.id)
                ELSE (SELECT COUNT(*) FROM teaching_member m WHERE m.class_id=o.class_id AND m.active=TRUE) END member_count
            FROM
            """+all+" o LEFT JOIN teaching_class tc ON tc.id=o.class_id LEFT JOIN course c ON c.id=tc.course_id"+where+" ORDER BY o.starts_at,o.kind,o.id LIMIT :limit OFFSET :offset")
            .param("room",id).param("limit",size).param("offset",((long)page-1)*size)
            .query((r,n)->new Occupancy(r.getLong("id"),r.getString("kind"),r.getLong("class_id"),r.getString("class_name"),r.getString("course_name"),r.getString("classroom"),r.getTimestamp("starts_at").toLocalDateTime(),r.getTimestamp("ends_at").toLocalDateTime(),r.getLong("member_count"))).list();
        return new Page<>(items,total,page,size);
    }
    public Page<Audit> history(CampusPrincipal user,long id,int page,int size) {
        manage(user); find(id); pagination(page,size); long total=jdbc.sql("SELECT COUNT(*) FROM room_audit WHERE room_id=:room").param("room",id).query(Long.class).single();
        var items=jdbc.sql("SELECT * FROM room_audit WHERE room_id=:room ORDER BY id DESC LIMIT :limit OFFSET :offset").param("room",id).param("limit",size).param("offset",((long)page-1)*size)
            .query((r,n)->new Audit(r.getLong("id"),r.getString("action"),r.getInt("room_version"),r.getString("reason"),r.getString("detail"),r.getString("actor_name"),r.getTimestamp("created_at").toLocalDateTime())).list();
        return new Page<>(items,total,page,size);
    }
    private void validate(RoomInput input) {
        if(input.examCapacity()>input.teachingCapacity()) bad("考试容量不能大于教学容量");
        if(jdbc.sql("SELECT COUNT(*) FROM campus WHERE id=:id").param("id",input.campusId()).query(Long.class).single()==0) bad("校区不存在");
    }
    private HashMap<String,Object> fields(RoomInput input) {
        var fields=new HashMap<String,Object>(); fields.put("campus",input.campusId()); fields.put("code",input.code().trim()); fields.put("name",input.name().trim()); fields.put("building",input.buildingName().trim()); fields.put("number",input.roomNumber().trim()); fields.put("floor",input.floorLabel()==null?null:input.floorLabel().trim()); fields.put("type",input.type()); fields.put("teaching",input.teachingCapacity()); fields.put("exam",input.examCapacity()); return fields;
    }
    private void audit(CampusPrincipal user,Room room,String action,String reason,String detail) {
        String name=jdbc.sql("SELECT full_name FROM app_user WHERE id=:id").param("id",user.id()).query(String.class).single();
        jdbc.sql("INSERT INTO room_audit(room_id,actor_id,actor_name,action,room_version,reason,detail) VALUES(:room,:user,:name,:action,:version,:reason,:detail)").param("room",room.id()).param("user",user.id()).param("name",name).param("action",action).param("version",room.version()).param("reason",reason.trim()).param("detail",detail).update();
    }
    private String snapshot(Room r) { return "校区 "+r.campusName()+"；编号 "+r.code()+"；名称 "+r.name()+"；楼栋 "+Objects.toString(r.buildingName(),"待补录")+"；房间 "+Objects.toString(r.roomNumber(),"待补录")+"；楼层 "+Objects.toString(r.floorLabel(),"未填")+"；类型 "+r.type()+"；教学／考试容量 "+r.teachingCapacity()+"／"+r.examCapacity()+"；"+(r.enabled()?"启用":"停用")+"；V"+r.version(); }
    private Room row(ResultSet r,int n) throws SQLException {
        Integer teaching=r.getObject("teaching_capacity",Integer.class), exam=r.getObject("exam_capacity",Integer.class);
        return new Room(r.getLong("id"),r.getLong("campus_id"),r.getString("campus_name"),r.getString("code"),r.getString("name"),r.getString("building_name"),r.getString("room_number"),r.getString("floor_label"),r.getString("room_type"),teaching,exam,r.getBoolean("enabled"),r.getString("origin"),r.getInt("version"),r.getTimestamp("updated_at").toLocalDateTime(),r.getBoolean("enabled") && r.getString("building_name")!=null && r.getString("room_number")!=null && teaching!=null && exam!=null,r.getBoolean("referenced"),r.getLong("lesson_count"),r.getLong("exam_count"),r.getLong("draft_count"));
    }
    private void pagination(int page,int size) { if(page<1 || page>1000000 || size<1 || size>100) bad("页码须大于零，每页最多 100 条"); }
    private void version(int current,int expected) { if(current!=expected) stale(); }
    private void stale() { conflict("教室版本已变化，请刷新后重新操作"); }
    private ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"ROOM_NOT_FOUND","教室资源不存在"); }
    private void bad(String message) { throw new ApiException(HttpStatus.BAD_REQUEST,"ROOM_INVALID",message); }
    private void conflict(String message) { throw new ApiException(HttpStatus.CONFLICT,"ROOM_CONFLICT",message); }
}
