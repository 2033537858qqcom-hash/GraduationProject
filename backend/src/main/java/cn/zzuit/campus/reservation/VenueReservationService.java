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

    private final JdbcClient jdbc;
    private final IdentityService identities;
    private final NoticeWriter noticeWriter;

    public VenueReservationService(JdbcClient jdbc, IdentityService identities, NoticeWriter noticeWriter) {
        this.jdbc = jdbc;
        this.identities = identities;
        this.noticeWriter = noticeWriter;
    }

    private void lock() {
        jdbc.sql("UPDATE foundation_write_lock SET revision = revision + 1 WHERE id = 1").update();
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
            SELECT u.id FROM venue_manager vm
            JOIN app_user u ON u.id = vm.user_id
            JOIN user_role ur ON ur.user_id = u.id
            JOIN role_permission rp ON rp.role_code = ur.role_code
            WHERE vm.venue_id = :venueId AND rp.permission_code = 'reservation:review' AND u.enabled = TRUE
            """)
            .param("venueId", venueId)
            .query(Long.class)
            .list();
    }

    // ==================== 场地与设备目录 ====================

    public List<VenueSummary> listVenues(String category, String placeId, String keyword, Boolean enabledOnly) {
        StringBuilder sql = new StringBuilder("SELECT * FROM venue_resource WHERE 1=1");
        Map<String, Object> params = new HashMap<>();

        if (Boolean.TRUE.equals(enabledOnly)) {
            sql.append(" AND enabled = TRUE");
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = :category");
            params.put("category", category.trim());
        }
        if (placeId != null && !placeId.isBlank()) {
            sql.append(" AND place_id = :placeId");
            params.put("placeId", placeId.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (name LIKE :kw OR building_name LIKE :kw OR location_detail LIKE :kw OR code LIKE :kw)");
            params.put("kw", "%" + keyword.trim() + "%");
        }
        sql.append(" ORDER BY id ASC");

        return jdbc.sql(sql.toString()).params(params).query((r, n) -> new VenueSummary(
            r.getLong("id"),
            r.getString("code"),
            r.getString("name"),
            r.getString("category"),
            r.getString("place_id"),
            r.getString("building_name"),
            r.getString("location_detail"),
            r.getObject("capacity", Integer.class),
            r.getString("available_equipment"),
            r.getString("rules"),
            r.getBoolean("enabled")
        )).list();
    }

    public VenueSummary getVenue(long id) {
        return jdbc.sql("SELECT * FROM venue_resource WHERE id = :id").param("id", id)
            .query((r, n) -> new VenueSummary(
                r.getLong("id"),
                r.getString("code"),
                r.getString("name"),
                r.getString("category"),
                r.getString("place_id"),
                r.getString("building_name"),
                r.getString("location_detail"),
                r.getObject("capacity", Integer.class),
                r.getString("available_equipment"),
                r.getString("rules"),
                r.getBoolean("enabled")
            )).optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "VENUE_NOT_FOUND", "借用场地或设备资源不存在"));
    }

    @Transactional
    public void updateVenue(CampusPrincipal user, long id, VenueUpdateCommand cmd) {
        identities.requirePermission(user, "reservation:manage");
        lock();
        var venue = getVenue(id);
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
    }

    // ==================== 时段占用与冲突排查 ====================

    public List<TimeSlot> getVenueSchedule(long venueId, LocalDate date) {
        var startOfDay = date.atStartOfDay();
        var endOfDay = date.atTime(LocalTime.MAX);
        return jdbc.sql("""
            SELECT starts_at, ends_at, activity_theme, status
            FROM venue_reservation
            WHERE venue_id = :venueId
              AND status IN ('SUBMITTED', 'APPROVED')
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

        var conflicting = jdbc.sql("""
            SELECT starts_at, ends_at, activity_theme, status
            FROM venue_reservation
            WHERE venue_id = :venueId
              AND status IN ('SUBMITTED', 'APPROVED')
              AND (:excludeId IS NULL OR id != :excludeId)
              AND ends_at > :startsAt AND starts_at < :endsAt
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

        if (!conflicting.isEmpty()) {
            return new ConflictResult(true, "所选时段已被其他预约申请占用，请选择其他时段或场地", conflicting);
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
                r.getLong("actor_id"),
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
        identities.requirePermission(user, "reservation:apply");
        lock();

        var profile = identities.profile(user.id());
        var venue = getVenue(cmd.venueId());
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
        identities.requirePermission(user, "reservation:apply");
        lock();

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
        if (!venue.enabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VENUE_DISABLED", "该场地目前暂停借用");
        }

        // 校验目标场地是否配置了有效审批负责人（TASK-01）
        var reviewers = getVenueReviewerIds(venue.id());
        if (reviewers.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NO_VENUE_REVIEWER", "该场地暂无有效审批负责人，无法受理借用，请联系管理员配置后再提交");
        }

        var existing = jdbc.sql("SELECT id, status, version FROM venue_reservation WHERE applicant_id = :u AND request_key = :k")
            .param("u", user.id()).param("k", cmd.requestKey())
            .query((r, n) -> Map.of("id", r.getLong(1), "status", r.getString(2), "version", r.getInt(3)))
            .optional();

        long reservationId;
        int nextVersion = 0;
        if (existing.isPresent()) {
            var current = existing.get();
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
        identities.requirePermission(user, "reservation:apply");
        lock();
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

        StringBuilder where = new StringBuilder(" WHERE r.status != 'DRAFT'");
        Map<String, Object> params = new HashMap<>();

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
        identities.requirePermission(user, "reservation:review");
        lock();

        var detail = getDetail(user, id);

        // 1. 禁止自审（AUTH-03）
        if (detail.applicantId() == user.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CANNOT_SELF_REVIEW", "操作者不得审核本人的借用申请");
        }

        // 2. 负责范围校验（AUTH-02）
        if (!isVenueManager(user.id(), detail.venueId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权审批非本人负责的场地与设备借用申请");
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
