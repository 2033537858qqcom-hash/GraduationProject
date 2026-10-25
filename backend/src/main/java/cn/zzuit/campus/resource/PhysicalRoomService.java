package cn.zzuit.campus.resource;

import cn.zzuit.campus.common.ApiException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Shared room rules. All writers hold foundation_write_lock before teaching_write_lock. */
@Service
public class PhysicalRoomService {
    private final JdbcClient jdbc;
    public PhysicalRoomService(JdbcClient jdbc) { this.jdbc=jdbc; }
    private record Room(long campus,boolean ready,int capacity) {}
    private Room room(long id) {
        return jdbc.sql("SELECT campus_id,enabled,building_name,room_number,teaching_capacity,exam_capacity FROM classroom_resource WHERE id=:id").param("id",id)
            .query((r,n)->new Room(r.getLong(1),r.getBoolean(2)&&r.getString(3)!=null&&r.getString(4)!=null&&r.getObject(5)!=null&&r.getObject(6)!=null,r.getInt(5)))
            .optional().orElseThrow(()->new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CLASSROOM","关联的教学教室不存在"));
    }
    public void validateBinding(long campus,String category,Integer capacity,Long roomId) {
        if(roomId==null)return;
        var room=room(roomId);
        if(!category.equals("VENUE") || room.campus()!=campus)throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CLASSROOM_LINK","仅可将场地关联到同一校区的教学教室，设备不能关联教室");
        if(!room.ready() || (capacity!=null&&capacity>room.capacity()))throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CLASSROOM_LINK","教室已停用、资料未完整或场地容量超过教室容量");
    }
    public Long linkedRoom(long venueId) {
        return jdbc.sql("SELECT classroom_resource_id FROM venue_resource WHERE id=:id AND classroom_resource_id IS NOT NULL").param("id",venueId).query(Long.class).optional().orElse(null);
    }
    public void requireUsable(long venueId,Integer participants) {
        Long id=linkedRoom(venueId);if(id==null)return;var room=room(id);
        if(!room.ready() || (participants!=null&&participants>room.capacity()))throw new ApiException(HttpStatus.CONFLICT,"CLASSROOM_UNAVAILABLE","关联教室已停用、资料未完整或容量不足，请调整后再办理");
    }
    public List<Slot> teachingSlots(Long roomId,LocalDateTime start,LocalDateTime end) {
        if(roomId==null)return List.of();
        return jdbc.sql("""
            SELECT starts_at,ends_at,'LESSON' kind FROM teaching_lesson WHERE room_id=:room AND starts_at<:end AND ends_at>:start
            UNION ALL SELECT starts_at,ends_at,'EXAM' kind FROM academic_exam
            WHERE room_id=:room AND status='PUBLISHED' AND starts_at<:end AND ends_at>:start
            """).param("room",roomId).param("start",start).param("end",end)
            .query((r,n)->new Slot(r.getTimestamp(1).toLocalDateTime(),r.getTimestamp(2).toLocalDateTime(),r.getString(3))).list();
    }
    public void requireNoTeaching(long venueId,LocalDateTime start,LocalDateTime end) {
        if(!teachingSlots(linkedRoom(venueId),start,end).isEmpty())throw new ApiException(HttpStatus.CONFLICT,"ROOM_OCCUPIED","该物理教室已有课程或已发布考试占用");
    }
    public void requireNoReservations(long roomId,LocalDateTime start,LocalDateTime end) {
        if(jdbc.sql("""
            SELECT COUNT(*) FROM venue_reservation r JOIN venue_resource v ON v.id=r.venue_id
            WHERE v.classroom_resource_id=:room AND r.status IN ('SUBMITTED','APPROVED','IN_USE')
              AND ((r.starts_at<:end AND r.ends_at>:start) OR (r.status='IN_USE' AND r.ends_at<=CURRENT_TIMESTAMP))
            """).param("room",roomId).param("start",start).param("end",end).query(Long.class).single()>0)
            throw new ApiException(HttpStatus.CONFLICT,"ROOM_OCCUPIED","该物理教室已有场地预约或尚未结束核销的使用记录");
    }
    public boolean hasReservations(long roomId) {
        return jdbc.sql("""
            SELECT COUNT(*) FROM venue_reservation r JOIN venue_resource v ON v.id=r.venue_id
            WHERE v.classroom_resource_id=:room AND (r.status='IN_USE' OR (r.status IN ('SUBMITTED','APPROVED') AND r.ends_at>CURRENT_TIMESTAMP))
            """).param("room",roomId).query(Long.class).single()>0;
    }
    public void requireCapacity(long roomId,int capacity) {
        if(jdbc.sql("SELECT COUNT(*) FROM venue_resource WHERE classroom_resource_id=:room AND capacity>:cap").param("room",roomId).param("cap",capacity).query(Long.class).single()>0)
            throw new ApiException(HttpStatus.CONFLICT,"LINKED_VENUE_CAPACITY","新教室容量低于关联预约资源容量，请先调整预约资源容量");
    }
    public record Slot(LocalDateTime startsAt,LocalDateTime endsAt,String kind) {}
}
