package cn.zzuit.campus.reservation;

import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.notice.NoticeWriter;
import cn.zzuit.campus.reservation.ReservationDtos.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VenueReservationService {

    // vm must refer to a venue_manager row. Use the same live eligibility rule for
    // routing, handoff protection and configuration warnings.
    private static final String ELIGIBLE_REVIEWER = """
        EXISTS (SELECT 1 FROM app_user review_user
            JOIN user_role review_role ON review_role.user_id = review_user.id
            JOIN role_permission review_permission ON review_permission.role_code = review_role.role_code
            WHERE review_user.id = vm.user_id AND review_user.enabled = TRUE
              AND review_permission.permission_code = 'reservation:review')
        """;

    // r is a reservation row. In-use bookings always need a human handover,
    // including after their planned end; unused expired approvals do not.
    private static final String NEEDS_OWNER = """
        (r.status = 'SUBMITTED' OR (r.status = 'APPROVED' AND r.ends_at > CURRENT_TIMESTAMP)
            OR r.status = 'IN_USE')
        """;

    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final NoticeWriter noticeWriter;
    private final ReservationPlaceCatalog placeCatalog;
    private final cn.zzuit.campus.resource.PhysicalRoomService physicalRooms;
    private static final String VENUE_SELECT="SELECT v.*,c.name campus_name,cr.name classroom_name FROM venue_resource v JOIN campus c ON c.id=v.campus_id LEFT JOIN classroom_resource cr ON cr.id=v.classroom_resource_id";

    public VenueReservationService(JdbcClient jdbc, IdentityService identities, NoticeWriter noticeWriter,ReservationPlaceCatalog placeCatalog,cn.zzuit.campus.resource.PhysicalRoomService physicalRooms) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.noticeWriter = noticeWriter;
        this.placeCatalog = placeCatalog; this.physicalRooms=physicalRooms;
    }

    private void lock() {
        jdbc.sql("UPDATE foundation_write_lock SET revision = revision + 1 WHERE id = 1").update();
    }

    private void lock(CampusPrincipal user, String permission) {
        lock();
        // Check after taking the shared account/scope lock, before opening a
        // consistent-read snapshot (also required for MySQL REPEATABLE READ).
        identities.requirePermission(user, permission);
    }

    public boolean isVenueManager(long userId, long venueId) {
        return jdbc.sql("SELECT COUNT(*) FROM venue_manager WHERE venue_id = :vid AND user_id = :uid")
            .param("vid", venueId)
            .param("uid", userId)
            .query(Long.class)
            .single() > 0;
    }

    public List<Long> getManagedVenueIds(long userId) {
        return jdbc.sql("SELECT venue_id FROM venue_manager WHERE user_id = :uid")
            .param("uid", userId)
            .query(Long.class)
            .list();
    }

    public List<Long> getVenueReviewerIds(long venueId) {
        return jdbc.sql("""
            SELECT vm.user_id FROM venue_manager vm
            WHERE vm.venue_id = :venueId AND
            """ + ELIGIBLE_REVIEWER)
            .param("venueId", venueId)
            .query(Long.class)
            .list();
    }

    // ==================== 场地与设备目录 ====================

    public List<VenueSummary> listVenues(String category, String placeId, String keyword, Boolean enabledOnly) {
        StringBuilder sql = new StringBuilder(VENUE_SELECT+" WHERE 1=1");
        Map<String, Object> params = new HashMap<>();

        if (Boolean.TRUE.equals(enabledOnly)) {
            sql.append(" AND v.enabled = TRUE");
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND v.category = :category");
            params.put("category", category.trim());
        }
        if (placeId != null && !placeId.isBlank()) {
            sql.append(" AND v.place_id = :placeId");
            params.put("placeId", placeId.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (v.name LIKE :kw OR v.building_name LIKE :kw OR v.location_detail LIKE :kw OR v.code LIKE :kw)");
            params.put("kw", "%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY v.id ASC");

        return jdbc.sql(sql.toString()).params(params).query((r, n) -> new VenueSummary(
            r.getLong("id"),r.getLong("campus_id"),r.getString("campus_name"),
            r.getString("code"),
            r.getString("name"),
            r.getString("category"),
            r.getString("place_id"),
            r.getString("building_name"),
            r.getString("location_detail"),
            r.getObject("capacity", Integer.class),
            r.getString("available_equipment"),
            r.getString("rules"),
            r.getBoolean("enabled"),r.getObject("classroom_resource_id",Long.class),r.getString("classroom_name"),r.getInt("version")
        )).list();
    }

    public VenueSummary getVenue(long id) {
        return jdbc.sql(VENUE_SELECT+" WHERE v.id = :id").param("id", id)
            .query((r, n) -> new VenueSummary(
                r.getLong("id"),r.getLong("campus_id"),r.getString("campus_name"),
                r.getString("code"),
                r.getString("name"),
                r.getString("category"),
                r.getString("place_id"),
                r.getString("building_name"),
                r.getString("location_detail"),
                r.getObject("capacity", Integer.class),
                r.getString("available_equipment"),
                r.getString("rules"),
                r.getBoolean("enabled"),r.getObject("classroom_resource_id",Long.class),r.getString("classroom_name"),r.getInt("version")
            )).optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VENUE_NOT_FOUND", "借用场地或设备资源不存在"));
    }

    public CreationCatalog creationCatalog(CampusPrincipal user) {
        identities.requirePermission(user,"reservation:manage");
        return new CreationCatalog(jdbc.sql("SELECT id,code,name FROM campus ORDER BY id")
            .query((r,n)->new CampusChoice(r.getLong(1),r.getString(2),r.getString(3))).list(),placeCatalog.places(),jdbc.sql("SELECT * FROM classroom_resource WHERE building_name IS NOT NULL AND room_number IS NOT NULL AND teaching_capacity IS NOT NULL AND exam_capacity IS NOT NULL ORDER BY campus_id,code")
            .query((r,n)->new ClassroomChoice(r.getLong("id"),r.getLong("campus_id"),r.getString("name"),r.getString("building_name"),r.getString("room_number"),r.getInt("teaching_capacity"),r.getBoolean("enabled"))).list());
    }

    @Transactional public VenueSummary createVenue(CampusPrincipal user,VenueCreateCommand input) {
        lock(user,"reservation:manage");
        String campusCode=jdbc.sql("SELECT code FROM campus WHERE id=:id").param("id",input.campusId()).query(String.class).optional()
            .orElseThrow(()->new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CAMPUS","所选校区不存在"));
        String place=placeCatalog.validatedId(campusCode,input.placeId());
        physicalRooms.validateBinding(input.campusId(),input.category(),input.capacity(),input.classroomResourceId());
        requireUniqueClassroom(input.classroomResourceId(),0);
        var owners=input.managerUserIds();
        if (new java.util.HashSet<>(owners).size()!=owners.size()) throw new ApiException(HttpStatus.BAD_REQUEST,"DUPLICATE_VENUE_OWNER","负责人不能重复");
        if (input.enabled() && owners.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"NO_VENUE_REVIEWER","开放资源前请至少配置一名有效办理人员");
        if (!owners.isEmpty() && jdbc.sql("""
            SELECT COUNT(DISTINCT u.id) FROM app_user u JOIN user_role ur ON ur.user_id=u.id
            JOIN role_permission rp ON rp.role_code=ur.role_code
            WHERE u.id IN (:ids) AND u.enabled=TRUE AND rp.permission_code='reservation:review'
            """).param("ids",owners).query(Long.class).single()!=owners.size())
            throw new ApiException(HttpStatus.BAD_REQUEST,"USER_NOT_REVIEWER","所选负责人不存在、已停用或缺少办理权限");
        if(jdbc.sql("SELECT COUNT(*) FROM venue_resource WHERE campus_id=:campus AND UPPER(code)=UPPER(:code)")
            .param("campus",input.campusId()).param("code",input.code()).query(Long.class).single()>0)
            throw new ApiException(HttpStatus.CONFLICT,"VENUE_CODE_EXISTS","同一校区的资源编号已存在");
        var key=new GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO venue_resource(campus_id,code,name,category,place_id,building_name,location_detail,capacity,available_equipment,rules,enabled,classroom_resource_id)
            VALUES(:campus,:code,:name,:category,:place,:building,:location,:capacity,:equipment,:rules,:enabled,:room)
            """).param("campus",input.campusId()).param("code",input.code()).param("name",input.name().trim()).param("category",input.category())
            .param("place",place).param("building",input.buildingName().trim()).param("location",input.locationDetail().trim()).param("capacity",input.capacity())
            .param("equipment",input.availableEquipment()==null?"":input.availableEquipment().trim()).param("rules",input.rules()==null?"":input.rules().trim())
            .param("enabled",input.enabled()).param("room",input.classroomResourceId()).update(key,"id");
        long id=key.getKey().longValue();
        for(long owner:owners) addVenueManager(user,id,owner);
        jdbc.sql("INSERT INTO foundation_audit(actor_id,actor_name,action,entity_type,entity_id,summary,reason) VALUES(:actor,:name,'VENUE_CREATE','VENUE',:id,:summary,'资源人员建档')")
            .param("actor",user.id()).param("name",identities.profile(user.id()).fullName()).param("id",id)
            .param("summary",input.code()+" / "+input.name().trim()+" / "+campusCode+" / "+input.locationDetail().trim()+" / "+(input.enabled()?"已开放":"待开放")+" / 负责人 "+owners.size()+" 人").update();
        return getVenue(id);
    }

    @Transactional
    public void updateVenue(CampusPrincipal user, long id, VenueUpdateCommand cmd) {
        lock(user, "reservation:manage");
        var venue = getVenue(id);
        if (Boolean.TRUE.equals(cmd.enabled()) && getVenueReviewerIds(id).isEmpty())
            throw new ApiException(HttpStatus.CONFLICT,"NO_VENUE_REVIEWER","开放资源前请先配置有效办理人员");
        if (cmd.capacity()!=null) physicalRooms.requireUsable(id,cmd.capacity());
        if (cmd.capacity()!=null && cmd.capacity()<1) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_CAPACITY","容量须大于零");
        if (cmd.name()!=null && (cmd.name().isBlank() || cmd.name().length()>150)) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_VENUE_NAME","请填写有效场地名称");
        if (Boolean.FALSE.equals(cmd.enabled()) && jdbc.sql("SELECT COUNT(*) FROM venue_reservation WHERE venue_id=:v AND status='IN_USE'").param("v",id).query(Long.class).single()>0)
            throw new ApiException(HttpStatus.CONFLICT,"VENUE_IN_USE","场地仍在使用中，请先核对并登记使用结束后再停用");
        if (cmd.capacity()!=null && venue.category().equals("VENUE") && jdbc.sql("SELECT COUNT(*) FROM venue_reservation WHERE venue_id=:v AND (status='IN_USE' OR (status='APPROVED' AND ends_at>CURRENT_TIMESTAMP)) AND expected_participants>:cap")
                .param("v",id).param("cap",cmd.capacity()).query(Long.class).single()>0)
            throw new ApiException(HttpStatus.CONFLICT,"ACTIVE_RESERVATION_CAPACITY","新容量低于已批准或使用中预约的人数，请先处理受影响预约");
        jdbc.sql("""
            UPDATE venue_resource
            SET name = :name,
                capacity = :capacity,
                available_equipment = :eqp,
                rules = :rules,
                enabled = :enabled,
                version = version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """)
            .param("id", id)
            .param("name", cmd.name() != null ? cmd.name().trim() : venue.name())
            .param("capacity", cmd.capacity() != null ? cmd.capacity() : venue.capacity())
            .param("eqp", cmd.availableEquipment() != null ? cmd.availableEquipment().trim() : venue.availableEquipment())
            .param("rules", cmd.rules() != null ? cmd.rules().trim() : venue.rules())
            .param("enabled", cmd.enabled() != null ? cmd.enabled() : venue.enabled())
            .update();
        if (Boolean.FALSE.equals(cmd.enabled())) {
            var affected=jdbc.sql("SELECT id,applicant_id,version FROM venue_reservation WHERE venue_id=:v AND status IN ('SUBMITTED','APPROVED')")
                .param("v",id).query((row,n)->new long[]{row.getLong(1),row.getLong(2),row.getInt(3)}).list();
            var actor=identities.profile(user.id());
            for (var item:affected) {
                jdbc.sql("UPDATE venue_reservation SET status='CANCELLED',processing_comment='场地临时停用，未开始的预约已取消',version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
                    .param("id",item[0]).update();
                recordAction(item[0],(int)item[2]+1,user.id(),actor.fullName(),"RESOURCE_DISABLED","CANCELLED","场地临时停用，未开始的预约已取消");
                noticeWriter.write(item[1],"场地停用，预约已取消","RESERVATION",item[0],"MINE","【"+venue.name()+"】暂时停用，请重新选择其他场地；原凭证已失效。");
            }
        }
    }

    public List<VenueManagementItem> listVenuesForManagement(CampusPrincipal user) {
        identities.requirePermission(user, "reservation:manage");
        var venues = listVenues(null, null, null, false);
        return venues.stream().map(v -> {
            var managers = jdbc.sql("""
                SELECT u.id, u.username, u.full_name, COALESCE(d.name, '公共服务部门') AS dept_name,
                       u.identity_number,
                """ + ELIGIBLE_REVIEWER + " AS eligible " + """
                FROM venue_manager vm
                JOIN app_user u ON u.id = vm.user_id
                LEFT JOIN department d ON d.id = u.department_id
                WHERE vm.venue_id = :venueId
                ORDER BY u.id ASC
                """)
                .param("venueId", v.id())
                .query((r, n) -> new VenueManagerItem(
                    r.getLong("id"),
                    r.getString("username"),
                    r.getString("full_name"),
                    r.getString("dept_name"),
                    r.getString("identity_number"),
                    r.getBoolean("eligible")
                )).list();
            var pending = jdbc.sql("""
                SELECT COALESCE(SUM(CASE WHEN status = 'SUBMITTED' THEN 1 ELSE 0 END), 0) AS pending_review,
                       COALESCE(SUM(CASE WHEN status = 'SUBMITTED' THEN unattended ELSE 0 END), 0) AS unattended_review,
                       COALESCE(SUM(CASE WHEN status = 'APPROVED' THEN 1 ELSE 0 END), 0) AS pending_usage,
                       COALESCE(SUM(CASE WHEN status = 'IN_USE' THEN 1 ELSE 0 END), 0) AS active_use,
                       COALESCE(SUM(unattended), 0) AS unattended_tasks
                FROM (
                    SELECT r.status, CASE WHEN EXISTS (
                        SELECT 1 FROM venue_manager vm
                        WHERE vm.venue_id = r.venue_id AND vm.user_id <> r.applicant_id AND
                """ + ELIGIBLE_REVIEWER + """
                    ) THEN 0 ELSE 1 END AS unattended
                    FROM venue_reservation r WHERE r.venue_id = :venueId AND
                """ + NEEDS_OWNER + ") responsibility")
                .param("venueId", v.id())
                .query((r, n) -> new long[] {r.getLong("pending_review"), r.getLong("unattended_review"),
                    r.getLong("pending_usage"), r.getLong("active_use"), r.getLong("unattended_tasks")}).single();
            return new VenueManagementItem(
                v.id(),v.campusId(),v.campusName(),
                v.code(),
                v.name(),
                v.category(),
                v.placeId(),
                v.buildingName(),
                v.locationDetail(),
                v.capacity(),
                v.availableEquipment(),
                v.rules(),
                v.enabled(),
                managers,
                (int) managers.stream().filter(VenueManagerItem::eligible).count(),
                pending[0],
                pending[1],
                pending[2],
                pending[3],
                pending[4],v.classroomResourceId(),v.classroomName(),v.version()
            );
        }).toList();
    }

    public List<VenueManagerItem> getReviewerCandidates(CampusPrincipal user) {
        identities.requirePermission(user, "reservation:manage");
        return jdbc.sql("""
            SELECT DISTINCT u.id, u.username, u.full_name, COALESCE(d.name, '公共服务部门') AS dept_name,
                   u.identity_number
            FROM app_user u
            JOIN user_role ur ON ur.user_id = u.id
            JOIN role_permission rp ON rp.role_code = ur.role_code
            LEFT JOIN department d ON d.id = u.department_id
            WHERE rp.permission_code = 'reservation:review' AND u.enabled = TRUE
            ORDER BY u.id ASC
            """)
            .query((r, n) -> new VenueManagerItem(
                r.getLong("id"),
                r.getString("username"),
                r.getString("full_name"),
                r.getString("dept_name"),
                r.getString("identity_number"),
                true
            )).list();
    }

    @Transactional
    public void addVenueManager(CampusPrincipal user, long venueId, long managerUserId) {
        lock(user, "reservation:manage");
        getVenue(venueId);

        var hasReview = jdbc.sql("""
            SELECT COUNT(*) FROM app_user u
            JOIN user_role ur ON ur.user_id = u.id
            JOIN role_permission rp ON rp.role_code = ur.role_code
            WHERE u.id = :uid AND rp.permission_code = 'reservation:review' AND u.enabled = TRUE
            """)
            .param("uid", managerUserId)
            .query(Long.class).single() > 0;
        if (!hasReview) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "USER_NOT_REVIEWER", "所选用户不具备借用审批权限，无法指定为审批人");
        }

        boolean exists = isVenueManager(managerUserId, venueId);
        if (!exists) {
            jdbc.sql("INSERT INTO venue_manager (venue_id, user_id) VALUES (:vid, :uid)")
                .param("vid", venueId)
                .param("uid", managerUserId)
                .update();
            // New responsibility includes every unfinished stage. Never notify a
            // person about approving their own application or on an idempotent add.
            var pending = jdbc.sql("""
                SELECT r.id, r.venue_name, r.activity_theme, r.status FROM venue_reservation r
                WHERE r.venue_id = :vid AND r.applicant_id <> :uid AND
                """ + NEEDS_OWNER).param("vid", venueId).param("uid", managerUserId)
                .query((r, n) -> Map.of("id", r.getLong("id"), "status", r.getString("status"), "message",
                    "【" + r.getString("venue_name") + "】" + r.getString("activity_theme"))).list();
            for (var task : pending) {
                String title = switch ((String) task.get("status")) {
                    case "APPROVED" -> "新增负责场地有待使用预约";
                    case "IN_USE" -> "新增负责场地有使用中预约待核销";
                    default -> "新增负责场地有待审批申请";
                };
                noticeWriter.write(managerUserId, title, "RESERVATION",
                    (Long) task.get("id"), "REVIEW", (String) task.get("message"));
            }
        }
    }

    @Transactional
    public void removeVenueManager(CampusPrincipal user, long venueId, long managerUserId) {
        lock(user, "reservation:manage");
        getVenue(venueId);
        if (!isVenueManager(managerUserId, venueId)) return;
        long stranded = jdbc.sql("""
            SELECT COUNT(*) FROM venue_reservation r
            WHERE r.venue_id = :vid AND
            """ + NEEDS_OWNER + """
              AND NOT EXISTS (SELECT 1 FROM venue_manager vm
                WHERE vm.venue_id = r.venue_id AND vm.user_id <> :uid
                  AND vm.user_id <> r.applicant_id AND
            """ + ELIGIBLE_REVIEWER + ")")
            .param("vid", venueId).param("uid", managerUserId).query(Long.class).single();
        if (stranded > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "REVIEWER_HANDOFF_REQUIRED",
                "该场地有 " + stranded + " 条未完成预约将无人办理，请先添加可接手审批、使用登记及结束核销的有效负责人，再解除指定（申请人本人不能接手）");
        }
        jdbc.sql("DELETE FROM venue_manager WHERE venue_id = :vid AND user_id = :uid")
            .param("vid", venueId)
            .param("uid", managerUserId)
            .update();
    }

    // ==================== 时段占用与冲突排查 ====================

    public List<TimeSlot> getVenueSchedule(long venueId, LocalDate date) {
        var startOfDay = date.atStartOfDay();
        var endOfDay = date.atTime(LocalTime.MAX);
        var slots=jdbc.sql("""
            SELECT starts_at, ends_at, activity_theme, status
            FROM venue_reservation
            WHERE venue_id = :venueId
              AND status IN ('SUBMITTED', 'APPROVED', 'IN_USE')
              AND ends_at > :startOfDay AND starts_at < :endOfDay
            ORDER BY starts_at ASC
            """)
            .param("venueId", venueId)
            .param("startOfDay", startOfDay)
            .param("endOfDay", endOfDay)
            .query((r, n) -> new TimeSlot(
                r.getTimestamp("starts_at").toLocalDateTime(),
                r.getTimestamp("ends_at").toLocalDateTime(),
                r.getString("activity_theme"),
                r.getString("status")
            )).list();
        return withTeachingSlots(venueId,startOfDay,endOfDay,slots);
    }

    private List<TimeSlot> withTeachingSlots(long venue,LocalDateTime start,LocalDateTime end,List<TimeSlot> reservations) {
        var result=new java.util.ArrayList<>(reservations);
        for(var slot:physicalRooms.teachingSlots(physicalRooms.linkedRoom(venue),start,end))
            result.add(new TimeSlot(slot.startsAt(),slot.endsAt(),slot.kind().equals("LESSON")?"教学课程占用":"已发布考试占用",slot.kind()));
        result.sort(java.util.Comparator.comparing(TimeSlot::startsAt));return List.copyOf(result);
    }

    private void requireUniqueClassroom(Long room,long excludingVenue) {
        if(room!=null && jdbc.sql("SELECT COUNT(*) FROM venue_resource WHERE classroom_resource_id=:room AND id<>:id")
            .param("room",room).param("id",excludingVenue).query(Long.class).single()>0)
            throw new ApiException(HttpStatus.CONFLICT,"CLASSROOM_ALREADY_LINKED","该教室已关联其他预约资源，请使用原资源，避免重复建档");
    }
    @Transactional public VenueSummary linkClassroom(CampusPrincipal user,long id,RoomLinkCommand input) {
        lock(user,"reservation:manage");var old=getVenue(id);
        if(old.version()!=input.version())throw new ApiException(HttpStatus.CONFLICT,"VENUE_VERSION_CHANGED","资源配置已变化，请刷新后核对");
        if(java.util.Objects.equals(old.classroomResourceId(),input.classroomResourceId()))return old;
        if(jdbc.sql("SELECT COUNT(*) FROM venue_reservation WHERE venue_id=:id AND (status='IN_USE' OR (status IN ('SUBMITTED','APPROVED') AND ends_at>CURRENT_TIMESTAMP))").param("id",id).query(Long.class).single()>0)
            throw new ApiException(HttpStatus.CONFLICT,"VENUE_LINK_HAS_WORK","该资源仍有未完成预约，请先处理后再调整物理教室关联");
        physicalRooms.validateBinding(old.campusId(),old.category(),old.capacity(),input.classroomResourceId());requireUniqueClassroom(input.classroomResourceId(),id);
        jdbc.sql("UPDATE venue_resource SET classroom_resource_id=:room,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
            .param("room",input.classroomResourceId()).param("id",id).update();
        jdbc.sql("INSERT INTO foundation_audit(actor_id,actor_name,action,entity_type,entity_id,summary,reason) VALUES(:actor,:name,'VENUE_ROOM_LINK','VENUE',:id,:summary,:reason)")
            .param("actor",user.id()).param("name",identities.profile(user.id()).fullName()).param("id",id).param("summary",old.code()+" / 教学教室关联 "+old.classroomResourceId()+" → "+input.classroomResourceId()).param("reason",input.reason().trim()).update();
        return getVenue(id);
    }

    public ConflictResult checkConflict(CheckConflictCommand cmd) {
        if (cmd.startsAt() == null || cmd.endsAt() == null) {
            return new ConflictResult(true, "借用起止时间不能为空", List.of());
        }
        if (!cmd.endsAt().isAfter(cmd.startsAt())) {
            return new ConflictResult(true, "借用结束时间必须晚于开始时间", List.of());
        }
        if (cmd.startsAt().isBefore(LocalDateTime.now())) {
            return new ConflictResult(true, "借用开始时间不能早于当前时间", List.of());
        }
        var durationMinutes = java.time.Duration.between(cmd.startsAt(), cmd.endsAt()).toMinutes();
        if (durationMinutes < 30) {
            return new ConflictResult(true, "单次借用预约时长至少为30分钟", List.of());
        }
        if (durationMinutes > 14 * 60) {
            return new ConflictResult(true, "单次借用预约时长不得超过14小时", List.of());
        }

        physicalRooms.requireUsable(cmd.venueId(),null);
        var conflicting = jdbc.sql("""
            SELECT starts_at, ends_at, activity_theme, status
            FROM venue_reservation
            WHERE venue_id = :venueId
              AND status IN ('SUBMITTED', 'APPROVED', 'IN_USE')
              AND (:excludeId IS NULL OR id != :excludeId)
              AND ((ends_at > :startsAt AND starts_at < :endsAt) OR (status='IN_USE' AND ends_at<=CURRENT_TIMESTAMP))
            ORDER BY starts_at ASC
            """)
            .param("venueId", cmd.venueId())
            .param("startsAt", cmd.startsAt())
            .param("endsAt", cmd.endsAt())
            .param("excludeId", cmd.excludeId())
            .query((r, n) -> new TimeSlot(
                r.getTimestamp("starts_at").toLocalDateTime(),
                r.getTimestamp("ends_at").toLocalDateTime(),
                r.getString("activity_theme"),
                r.getString("status")
            )).list();

        var all=withTeachingSlots(cmd.venueId(),cmd.startsAt(),cmd.endsAt(),conflicting);
        if (!all.isEmpty()) {
            return new ConflictResult(true, "所选时段已有预约、课程或已发布考试占用，请选择其他时段或场地", all);
        }
        return new ConflictResult(false, "该时段可预约借用", List.of());
    }

    // ==================== 个人预约管理 (申请人视角) ====================

    public ReservationPage listMine(CampusPrincipal user, String status, int page, int pageSize) {
        identities.requirePermission(user, "reservation:apply");
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGING", "分页参数无效");
        }

        StringBuilder where = new StringBuilder(" WHERE r.applicant_id = :userId");
        Map<String, Object> params = new HashMap<>();
        params.put("userId", user.id());

        if (status != null && !status.isBlank()) {
            where.append(" AND r.status = :status");
            params.put("status", status.trim());
        }

        long total = jdbc.sql("SELECT COUNT(*) FROM venue_reservation r" + where)
            .params(params).query(Long.class).single();

        var items = jdbc.sql("""
            SELECT r.*, v.category
            FROM venue_reservation r
            JOIN venue_resource v ON v.id = r.venue_id
            """ + where + " ORDER BY r.updated_at DESC, r.id DESC LIMIT :limit OFFSET :offset")
            .params(params)
            .param("limit", pageSize)
            .param("offset", ((long) page - 1) * pageSize)
            .query((res, n) -> mapItem(res)).list();

        return new ReservationPage(items, total, page, pageSize);
    }

    private record RawReservation(
        long id,
        long applicantId,
        String requestKey,
        long venueId,
        String venueName,
        String category,
        String placeId,
        String locationDetail,
        String applicantName,
        String identityNumber,
        String contactPhone,
        String departmentName,
        String className,
        String activityTheme,
        String activityType,
        int expectedParticipants,
        String borrowEquipment,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String status,
        String processingComment,
        int version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {}

    public ReservationDetail getPassDetail(CampusPrincipal user, long id) {
        var detail = getDetail(user, id);
        identities.requirePermission(user, detail.applicantId() == user.id() ? "reservation:apply" : "reservation:review");
        if (!List.of("APPROVED","IN_USE").contains(detail.status()) || !getVenue(detail.venueId()).enabled() || !detail.endsAt().isAfter(LocalDateTime.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_PASS_UNAVAILABLE", "仅有效时段内未结束、场地可用的已批准预约可以导出凭证，请刷新查看最新状态");
        }
        return detail;
    }

    public ReservationDetail getDetail(CampusPrincipal user, long id) {
        var res = jdbc.sql("""
            SELECT r.*, v.category
            FROM venue_reservation r
            JOIN venue_resource v ON v.id = r.venue_id
            WHERE r.id = :id
            """).param("id", id).query((r, n) -> new RawReservation(
                r.getLong("id"),
                r.getLong("applicant_id"),
                r.getString("request_key"),
                r.getLong("venue_id"),
                r.getString("venue_name"),
                r.getString("category"),
                r.getString("place_id"),
                r.getString("location_detail"),
                r.getString("applicant_name"),
                r.getString("identity_number"),
                r.getString("contact_phone"),
                r.getString("department_name"),
                r.getString("class_name"),
                r.getString("activity_theme"),
                r.getString("activity_type"),
                r.getInt("expected_participants"),
                r.getString("borrow_equipment"),
                r.getTimestamp("starts_at").toLocalDateTime(),
                r.getTimestamp("ends_at").toLocalDateTime(),
                r.getString("status"),
                r.getString("processing_comment"),
                r.getInt("version"),
                r.getTimestamp("created_at").toLocalDateTime(),
                r.getTimestamp("updated_at").toLocalDateTime()
            )).optional()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", "借用申请不存在"));

        long applicantId = res.applicantId();
        boolean isOwner = (applicantId == user.id());

        // 1. 草稿隐私保护：仅申请人本人可查看草稿，审核人或管理员也无权读取他人草稿
        if ("DRAFT".equals(res.status()) && !isOwner) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "草稿仅允许申请人本人查看");
        }

        // 2. 非草稿状态：仅申请人本人，或具备该场地审批权限的有效负责人可查看
        if (!isOwner) {
            var perms = identities.permissions(user.id());
            boolean hasReviewPerm = perms.contains("reservation:review");
            boolean isResponsible = isVenueManager(user.id(), res.venueId());
            if (!hasReviewPerm || !isResponsible) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权查看该借用申请记录");
            }
        }

        var actions = jdbc.sql("""
            SELECT * FROM venue_reservation_action
            WHERE reservation_id = :id
            ORDER BY id ASC
            """).param("id", id).query((r, n) -> new ReservationActionDto(
                r.getLong("id"),
                r.getLong("reservation_id"),
                r.getInt("reservation_version"),
                r.getObject("actor_id", Long.class),
                r.getString("actor_name"),
                r.getString("action"),
                r.getString("status"),
                r.getString("comment"),
                r.getTimestamp("created_at").toLocalDateTime()
            )).list();

        return new ReservationDetail(
            res.id(),
            applicantId,
            res.requestKey(),
            res.venueId(),
            res.venueName(),
            res.category(),
            res.placeId(),
            res.locationDetail(),
            res.applicantName(),
            res.identityNumber(),
            res.contactPhone(),
            res.departmentName(),
            res.className(),
            res.activityTheme(),
            res.activityType(),
            res.expectedParticipants(),
            res.borrowEquipment(),
            res.startsAt(),
            res.endsAt(),
            res.status(),
            res.processingComment(),
            res.version(),
            res.createdAt(),
            res.updatedAt(),
            actions
        );
    }

    @Transactional
    public ReservationDetail saveDraft(CampusPrincipal user, CreateReservationCommand cmd) {
        lock(user, "reservation:apply");

        var profile = identities.profile(user.id());
        var venue = getVenue(cmd.venueId());
        validateCapacity(venue,cmd.expectedParticipants());
        if (!venue.enabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VENUE_DISABLED", "该场地目前暂停对外借用开放");
        }

        var existing = jdbc.sql("SELECT id, status, version FROM venue_reservation WHERE applicant_id = :u AND request_key = :k")
            .param("u", user.id()).param("k", cmd.requestKey())
            .query((r, n) -> Map.of("id", r.getLong(1), "status", r.getString(2), "version", r.getInt(3)))
            .optional();

        long reservationId;
        int nextVersion = 0;
        if (existing.isPresent()) {
            var current = existing.get();
            if (!"DRAFT".equals(current.get("status")) && !"RETURNED".equals(current.get("status"))) {
                throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_IMMUTABLE", "当前申请状态不允许编辑草稿");
            }
            reservationId = (Long) current.get("id");
            nextVersion = ((Integer) current.get("version")) + 1;
            jdbc.sql("""
                UPDATE venue_reservation
                SET venue_id = :venueId,
                    venue_name = :venueName,
                    place_id = :placeId,
                    location_detail = :locationDetail,
                    contact_phone = :contactPhone,
                    activity_theme = :theme,
                    activity_type = :type,
                    expected_participants = :participants,
                    borrow_equipment = :equipment,
                    starts_at = :startsAt,
                    ends_at = :endsAt,
                    status = 'DRAFT',
                    version = :version,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("id", reservationId)
                .param("venueId", venue.id())
                .param("venueName", venue.name())
                .param("placeId", venue.placeId())
                .param("locationDetail", venue.locationDetail())
                .param("contactPhone", cmd.contactPhone() != null ? cmd.contactPhone().trim() : "")
                .param("theme", cmd.activityTheme() != null ? cmd.activityTheme().trim() : "")
                .param("type", cmd.activityType() != null ? cmd.activityType().trim() : "ACADEMIC")
                .param("participants", Math.max(1, cmd.expectedParticipants()))
                .param("equipment", cmd.borrowEquipment() != null ? cmd.borrowEquipment().trim() : "")
                .param("startsAt", cmd.startsAt())
                .param("endsAt", cmd.endsAt())
                .param("version", nextVersion)
                .update();
        } else {
            var keyHolder = new GeneratedKeyHolder();
            jdbc.sql("""
                INSERT INTO venue_reservation (
                    applicant_id, request_key, venue_id, applicant_name, identity_number,
                    contact_phone, department_name, class_name, venue_name, place_id,
                    location_detail, activity_theme, activity_type, expected_participants,
                    borrow_equipment, starts_at, ends_at, status, version
                ) VALUES (
                    :applicantId, :requestKey, :venueId, :applicantName, :identityNumber,
                    :contactPhone, :departmentName, :className, :venueName, :placeId,
                    :locationDetail, :theme, :type, :participants,
                    :equipment, :startsAt, :endsAt, 'DRAFT', 0
                )
                """)
                .param("applicantId", user.id())
                .param("requestKey", cmd.requestKey().trim())
                .param("venueId", venue.id())
                .param("applicantName", profile.fullName())
                .param("identityNumber", profile.identityNumber())
                .param("contactPhone", cmd.contactPhone() != null ? cmd.contactPhone().trim() : "")
                .param("departmentName", profile.departmentName() != null ? profile.departmentName() : "公共服务部门")
                .param("className", profile.className())
                .param("venueName", venue.name())
                .param("placeId", venue.placeId())
                .param("locationDetail", venue.locationDetail())
                .param("theme", cmd.activityTheme() != null ? cmd.activityTheme().trim() : "")
                .param("type", cmd.activityType() != null ? cmd.activityType().trim() : "ACADEMIC")
                .param("participants", Math.max(1, cmd.expectedParticipants()))
                .param("equipment", cmd.borrowEquipment() != null ? cmd.borrowEquipment().trim() : "")
                .param("startsAt", cmd.startsAt())
                .param("endsAt", cmd.endsAt())
                .update(keyHolder, "id");
            reservationId = keyHolder.getKey().longValue();
        }

        recordAction(reservationId, nextVersion, user.id(), profile.fullName(), "SAVE", "DRAFT", "保存场地借用草稿");
        return getDetail(user, reservationId);
    }

    @Transactional
    public ReservationDetail submit(CampusPrincipal user, CreateReservationCommand cmd) {
        lock(user, "reservation:apply");

        if (cmd.activityTheme() == null || cmd.activityTheme().trim().length() < 2) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_THEME", "请填写借用活动主题（至少2个字）");
        }
        if (cmd.contactPhone() == null || cmd.contactPhone().trim().length() < 7) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PHONE", "请提供有效的活动联系人电话");
        }

        var conflict = checkConflict(new CheckConflictCommand(cmd.venueId(), cmd.startsAt(), cmd.endsAt(), null));
        if (conflict.hasConflict()) {
            throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_TIME_CONFLICT", conflict.message());
        }

        var profile = identities.profile(user.id());
        var venue = getVenue(cmd.venueId());
        validateCapacity(venue,cmd.expectedParticipants());
        if (!venue.enabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VENUE_DISABLED", "该场地目前暂停借用");
        }

        // 校验目标场地是否配置了有效审批负责人（TASK-01）
        var reviewers = getVenueReviewerIds(venue.id()).stream().filter(id -> id != user.id()).toList();
        if (reviewers.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_VENUE_REVIEWER", "该场地暂无其他有效审批负责人，无法受理借用，请联系资源管理人员配置后再提交（申请人不能自审）");
        }

        var existing = jdbc.sql("SELECT id, status, version FROM venue_reservation WHERE applicant_id = :u AND request_key = :k")
            .param("u", user.id()).param("k", cmd.requestKey())
            .query((r, n) -> Map.of("id", r.getLong(1), "status", r.getString(2), "version", r.getInt(3)))
            .optional();

        long reservationId;
        int nextVersion = 0;
        if (existing.isPresent()) {
            var current = existing.get();
            if (!"DRAFT".equals(current.get("status")) && !"RETURNED".equals(current.get("status"))) {
                throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_IMMUTABLE", "当前申请状态不允许再次提交，请刷新查看办理结果；新活动请新建申请");
            }
            reservationId = (Long) current.get("id");
            nextVersion = ((Integer) current.get("version")) + 1;
            jdbc.sql("""
                UPDATE venue_reservation
                SET venue_id = :venueId,
                    venue_name = :venueName,
                    place_id = :placeId,
                    location_detail = :locationDetail,
                    contact_phone = :contactPhone,
                    activity_theme = :theme,
                    activity_type = :type,
                    expected_participants = :participants,
                    borrow_equipment = :equipment,
                    starts_at = :startsAt,
                    ends_at = :endsAt,
                    status = 'SUBMITTED',
                    version = :version,
                    processing_comment = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id
                """)
                .param("id", reservationId)
                .param("venueId", venue.id())
                .param("venueName", venue.name())
                .param("placeId", venue.placeId())
                .param("locationDetail", venue.locationDetail())
                .param("contactPhone", cmd.contactPhone().trim())
                .param("theme", cmd.activityTheme().trim())
                .param("type", cmd.activityType() != null ? cmd.activityType().trim() : "ACADEMIC")
                .param("participants", Math.max(1, cmd.expectedParticipants()))
                .param("equipment", cmd.borrowEquipment() != null ? cmd.borrowEquipment().trim() : "")
                .param("startsAt", cmd.startsAt())
                .param("endsAt", cmd.endsAt())
                .param("version", nextVersion)
                .update();
        } else {
            var keyHolder = new GeneratedKeyHolder();
            jdbc.sql("""
                INSERT INTO venue_reservation (
                    applicant_id, request_key, venue_id, applicant_name, identity_number,
                    contact_phone, department_name, class_name, venue_name, place_id,
                    location_detail, activity_theme, activity_type, expected_participants,
                    borrow_equipment, starts_at, ends_at, status, version
                ) VALUES (
                    :applicantId, :requestKey, :venueId, :applicantName, :identityNumber,
                    :contactPhone, :departmentName, :className, :venueName, :placeId,
                    :locationDetail, :theme, :type, :participants,
                    :equipment, :startsAt, :endsAt, 'SUBMITTED', 0
                )
                """)
                .param("applicantId", user.id())
                .param("requestKey", cmd.requestKey().trim())
                .param("venueId", venue.id())
                .param("applicantName", profile.fullName())
                .param("identityNumber", profile.identityNumber())
                .param("contactPhone", cmd.contactPhone().trim())
                .param("departmentName", profile.departmentName() != null ? profile.departmentName() : "公共服务部门")
                .param("className", profile.className())
                .param("venueName", venue.name())
                .param("placeId", venue.placeId())
                .param("locationDetail", venue.locationDetail())
                .param("theme", cmd.activityTheme().trim())
                .param("type", cmd.activityType() != null ? cmd.activityType().trim() : "ACADEMIC")
                .param("participants", Math.max(1, cmd.expectedParticipants()))
                .param("equipment", cmd.borrowEquipment() != null ? cmd.borrowEquipment().trim() : "")
                .param("startsAt", cmd.startsAt())
                .param("endsAt", cmd.endsAt())
                .update(keyHolder, "id");
            reservationId = keyHolder.getKey().longValue();
        }

        recordAction(reservationId, nextVersion, user.id(), profile.fullName(), "SUBMIT", "SUBMITTED", "提交场地借用申请");

        // 向该场地的真实有效负责人发送待办通知（排除申请人本人）
        for (long reviewerId : reviewers) {
            if (reviewerId != user.id()) {
                noticeWriter.write(reviewerId, "收到新的场地借用申请待审批", "RESERVATION", reservationId, "REVIEW",
                    "【" + venue.name() + "】" + cmd.activityTheme().trim());
            }
        }

        return getDetail(user, reservationId);
    }

    @Transactional
    public ReservationDetail withdraw(CampusPrincipal user, long id) {
        lock(user, "reservation:apply");
        var detail = getDetail(user, id);
        if (detail.applicantId() != user.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "只能撤回本人的借用申请");
        }
        if (!"SUBMITTED".equals(detail.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_WITHDRAW", "只有审核中的申请才可撤回");
        }

        int nextVersion = detail.version() + 1;
        jdbc.sql("UPDATE venue_reservation SET status = 'WITHDRAWN', version = :v, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("v", nextVersion).param("id", id).update();

        recordAction(id, nextVersion, user.id(), detail.applicantName(), "WITHDRAW", "WITHDRAWN", "申请人主动撤回借用申请");
        return getDetail(user, id);
    }

    @Transactional
    public ReservationDetail cancel(CampusPrincipal user, long id, String reason) {
        lock();
        var detail = getDetail(user, id);
        identities.requirePermission(user, detail.applicantId() == user.id() ? "reservation:apply" : "reservation:review");
        var perms = identities.permissions(user.id());
        boolean hasReviewPerm = perms.contains("reservation:review");
        boolean isResponsible = isVenueManager(user.id(), detail.venueId());
        boolean isStaff = hasReviewPerm && isResponsible;
        if (!isStaff && detail.applicantId() != user.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权取消该借用申请");
        }
        if (!"APPROVED".equals(detail.status()) && !"SUBMITTED".equals(detail.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CANNOT_CANCEL", "当前借用状态不支持取消操作");
        }

        var profile = identities.profile(user.id());
        int nextVersion = detail.version() + 1;
        String comment = (reason != null && !reason.isBlank()) ? reason.trim() : "借用预约已取消，时段已释放";

        jdbc.sql("""
            UPDATE venue_reservation
            SET status = 'CANCELLED',
                processing_comment = :comment,
                version = :v,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = :id
            """)
            .param("comment", comment)
            .param("v", nextVersion)
            .param("id", id)
            .update();

        recordAction(id, nextVersion, user.id(), profile.fullName(), "CANCEL", "CANCELLED", comment);

        if (isStaff && detail.applicantId() != user.id()) {
            noticeWriter.write(detail.applicantId(), "场地借用预定已被工作人员取消", "RESERVATION", id, "MINE",
                "【" + detail.venueName() + "】" + comment);
        }
        return getDetail(user, id);
    }

    // ==================== 审批与管理 (管理员/事务人员视角) ====================

    public ReservationPage listReview(CampusPrincipal user, String status, Long venueId, int page, int pageSize) {
        identities.requirePermission(user, "reservation:review");
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGING", "分页参数无效");
        }

        var managedVenueIds = getManagedVenueIds(user.id());
        if (managedVenueIds.isEmpty()) {
            return new ReservationPage(List.of(), 0, page, pageSize);
        }

        if (venueId != null && venueId > 0 && !managedVenueIds.contains(venueId)) {
            return new ReservationPage(List.of(), 0, page, pageSize);
        }

        StringBuilder where = new StringBuilder(" WHERE r.status != 'DRAFT' AND r.applicant_id <> :reviewerId");
        Map<String, Object> params = new HashMap<>();
        params.put("reviewerId", user.id());

        if (venueId != null && venueId > 0) {
            where.append(" AND r.venue_id = :venueId");
            params.put("venueId", venueId);
        } else {
            where.append(" AND r.venue_id IN (:managedVenueIds)");
            params.put("managedVenueIds", managedVenueIds);
        }

        if (status != null && !status.isBlank()) {
            where.append(" AND r.status = :status");
            params.put("status", status.trim());
        }

        long total = jdbc.sql("SELECT COUNT(*) FROM venue_reservation r" + where)
            .params(params).query(Long.class).single();

        var items = jdbc.sql("""
            SELECT r.*, v.category
            FROM venue_reservation r
            JOIN venue_resource v ON v.id = r.venue_id
            """ + where + " ORDER BY r.updated_at DESC, r.id DESC LIMIT :limit OFFSET :offset")
            .params(params)
            .param("limit", pageSize)
            .param("offset", ((long) page - 1) * pageSize)
            .query((res, n) -> mapItem(res)).list();

        return new ReservationPage(items, total, page, pageSize);
    }

    @Transactional
    public ReservationDetail review(CampusPrincipal user, long id, ReviewReservationCommand cmd) {
        lock(user, "reservation:review");

        var detail = getDetail(user, id);

        // 1. 禁止自审（AUTH-03）
        if (detail.applicantId() == user.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CANNOT_SELF_REVIEW", "操作者不得审核本人的借用申请");
        }

        // 2. 负责范围校验（AUTH-02）
        if (!isVenueManager(user.id(), detail.venueId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权审批非本人负责的场地与设备借用申请");
        }

        if (cmd.version() == null || cmd.version() != detail.version()) {
            throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_VERSION_CHANGED", "申请材料或处理状态已更新，请重新核对最新资料后再作决定");
        }

        if (!"SUBMITTED".equals(detail.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_NOT_PENDING", "当前申请不在待审批状态");
        }

        String action = cmd.action() != null ? cmd.action().trim().toUpperCase() : "";
        if (!List.of("APPROVE", "RETURN", "REJECT").contains(action)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_ACTION", "审批动作必须是 APPROVE、RETURN 或 REJECT");
        }

        var profile = identities.profile(user.id());
        int nextVersion = detail.version() + 1;
        String comment = cmd.comment() != null ? cmd.comment().trim() : "";

        if ("APPROVE".equals(action)) {
            var venue=getVenue(detail.venueId());
            if (!venue.enabled()) throw new ApiException(HttpStatus.CONFLICT,"VENUE_DISABLED","场地已停用，不能批准使用");
            validateCapacity(venue,detail.expectedParticipants());
            // 审批通过时二次确认时段冲突
            var conflict = checkConflict(new CheckConflictCommand(detail.venueId(), detail.startsAt(), detail.endsAt(), id));
            if (conflict.hasConflict()) {
                throw new ApiException(HttpStatus.CONFLICT, "RESERVATION_TIME_CONFLICT", "该场地时段刚刚已被其他申请占用：" + conflict.message());
            }

            int updated = jdbc.sql("""
                UPDATE venue_reservation
                SET status = 'APPROVED',
                    processing_comment = :comment,
                    version = :v,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND version = :curV AND status = 'SUBMITTED'
                """)
                .param("comment", comment.isBlank() ? "审批通过，请按时使用" : comment)
                .param("v", nextVersion)
                .param("id", id)
                .param("curV", detail.version())
                .update();

            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "申请状态已被其他人处理或已发生变更");
            }

            recordAction(id, nextVersion, user.id(), profile.fullName(), "APPROVE", "APPROVED", comment.isBlank() ? "审批通过" : comment);
            noticeWriter.write(detail.applicantId(), "场地借用申请已审批通过", "RESERVATION", id, "MINE",
                "【" + detail.venueName() + "】已获准借用，请准时入场并爱护设施。");
        } else if ("RETURN".equals(action)) {
            if (comment.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "COMMENT_REQUIRED", "退回申请时必须填写退回理由");
            }
            int updated = jdbc.sql("""
                UPDATE venue_reservation
                SET status = 'RETURNED',
                    processing_comment = :comment,
                    version = :v,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND version = :curV AND status = 'SUBMITTED'
                """)
                .param("comment", comment)
                .param("v", nextVersion)
                .param("id", id)
                .param("curV", detail.version())
                .update();

            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "申请状态已被其他人处理或已发生变更");
            }

            recordAction(id, nextVersion, user.id(), profile.fullName(), "RETURN", "RETURNED", comment);
            noticeWriter.write(detail.applicantId(), "场地借用申请已退回补充材料", "RESERVATION", id, "MINE",
                "【" + detail.venueName() + "】退回说明：" + comment);
        } else { // REJECT
            if (comment.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "COMMENT_REQUIRED", "拒绝申请时必须填写拒绝原因");
            }
            int updated = jdbc.sql("""
                UPDATE venue_reservation
                SET status = 'REJECTED',
                    processing_comment = :comment,
                    version = :v,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = :id AND version = :curV AND status = 'SUBMITTED'
                """)
                .param("comment", comment)
                .param("v", nextVersion)
                .param("id", id)
                .param("curV", detail.version())
                .update();

            if (updated == 0) {
                throw new ApiException(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", "申请状态已被其他人处理或已发生变更");
            }

            recordAction(id, nextVersion, user.id(), profile.fullName(), "REJECT", "REJECTED", comment);
            noticeWriter.write(detail.applicantId(), "场地借用申请未通过审批", "RESERVATION", id, "MINE",
                "【" + detail.venueName() + "】审核说明：" + comment);
        }

        return getDetail(user, id);
    }

    private void validateCapacity(VenueSummary venue,int participants) {
        physicalRooms.requireUsable(venue.id(),participants);
        if (participants<1) throw new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PARTICIPANTS","预计人数须大于零");
        if (venue.category().equals("VENUE") && venue.capacity()!=null && participants>venue.capacity())
            throw new ApiException(HttpStatus.CONFLICT,"VENUE_CAPACITY_EXCEEDED","预计人数超过场地容量（"+venue.capacity()+" 人），请选择合适场地或调整人数");
    }

    @Transactional
    public ReservationDetail usage(CampusPrincipal user,long id,UsageCommand cmd) {
        lock(user,"reservation:review");
        var detail=getDetail(user,id);
        if (detail.applicantId()==user.id() || !isVenueManager(user.id(),detail.venueId()))
            throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","仅负责该场地且非申请人本人的办理人员可以登记使用");
        if (detail.version()!=cmd.version()) throw new ApiException(HttpStatus.CONFLICT,"CONCURRENT_MODIFICATION","记录已更新，请刷新后操作");
        boolean start=cmd.action().equals("START");
        if (!(start?"APPROVED":"IN_USE").equals(detail.status())) throw new ApiException(HttpStatus.CONFLICT,"INVALID_USAGE_STATE","当前状态不能执行该使用操作");
        if (start) {
            if (jdbc.sql("SELECT COUNT(*) FROM venue_reservation WHERE venue_id=:v AND id<>:id AND status='IN_USE'")
                    .param("v",detail.venueId()).param("id",id).query(Long.class).single()>0)
                throw new ApiException(HttpStatus.CONFLICT,"VENUE_STILL_IN_USE","上一场使用尚未结束核销，请先完成现场交接");
            physicalRooms.requireNoTeaching(detail.venueId(),detail.startsAt(),detail.endsAt());
            var now=LocalDateTime.now();
            if (now.isBefore(detail.startsAt()) || !now.isBefore(detail.endsAt())) throw new ApiException(HttpStatus.CONFLICT,"OUTSIDE_USAGE_TIME","请在批准的使用时段内登记入场");
            var venue=getVenue(detail.venueId());
            if (!venue.enabled()) throw new ApiException(HttpStatus.CONFLICT,"VENUE_DISABLED","场地已停用，不能登记使用");
            validateCapacity(venue,detail.expectedParticipants());
        }
        String state=start?"IN_USE":"COMPLETED";
        jdbc.sql("UPDATE venue_reservation SET status=:state,processing_comment=:comment,version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id")
            .param("state",state).param("comment",cmd.comment().trim()).param("id",id).update();
        recordAction(id,detail.version()+1,user.id(),identities.profile(user.id()).fullName(),cmd.action(),state,cmd.comment().trim());
        noticeWriter.write(detail.applicantId(),start?"场地已登记开始使用":"场地使用已结束核销","RESERVATION",id,"MINE",cmd.comment().trim());
        return getDetail(user,id);
    }

    private void recordAction(long reservationId, int version, long actorId, String actorName, String action, String status, String comment) {
        jdbc.sql("""
            INSERT INTO venue_reservation_action (
                reservation_id, reservation_version, actor_id, actor_name, action, status, comment
            ) VALUES (
                :reservationId, :version, :actorId, :actorName, :action, :status, :comment
            )
            """)
            .param("reservationId", reservationId)
            .param("version", version)
            .param("actorId", actorId)
            .param("actorName", actorName)
            .param("action", action)
            .param("status", status)
            .param("comment", comment)
            .update();
    }

    private ReservationItem mapItem(java.sql.ResultSet res) throws java.sql.SQLException {
        return new ReservationItem(
            res.getLong("id"),
            res.getLong("applicant_id"),
            res.getString("applicant_name"),
            res.getString("request_key"),
            res.getLong("venue_id"),
            res.getString("venue_name"),
            res.getString("category"),
            res.getString("place_id"),
            res.getString("location_detail"),
            res.getString("activity_theme"),
            res.getString("activity_type"),
            res.getInt("expected_participants"),
            res.getString("borrow_equipment"),
            res.getTimestamp("starts_at").toLocalDateTime(),
            res.getTimestamp("ends_at").toLocalDateTime(),
            res.getString("status"),
            res.getString("processing_comment"),
            res.getInt("version"),
            res.getTimestamp("created_at").toLocalDateTime(),
            res.getTimestamp("updated_at").toLocalDateTime()
        );
    }
}
