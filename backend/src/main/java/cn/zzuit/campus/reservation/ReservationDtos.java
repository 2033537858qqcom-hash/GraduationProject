package cn.zzuit.campus.reservation;

import java.time.LocalDateTime;
import java.util.List;

public class ReservationDtos {

    public record VenueSummary(
        long id,
        long campusId,
        String campusName,
        String code,
        String name,
        String category,
        String placeId,
        String buildingName,
        String locationDetail,
        Integer capacity,
        String availableEquipment,
        String rules,
        boolean enabled,
        Long classroomResourceId,
        String classroomName,
        int version
    ) {}

    public record TimeSlot(
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String activityTheme,
        String status
    ) {}

    public record ReservationItem(
        long id,
        long applicantId,
        String applicantName,
        String requestKey,
        long venueId,
        String venueName,
        String category,
        String placeId,
        String locationDetail,
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

    public record ReservationActionDto(
        long id,
        long reservationId,
        int reservationVersion,
        Long actorId,
        String actorName,
        String action,
        String status,
        String comment,
        LocalDateTime createdAt
    ) {}

    public record ReservationDetail(
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
        LocalDateTime updatedAt,
        List<ReservationActionDto> actions
    ) {}

    public record CreateReservationCommand(
        String requestKey,
        long venueId,
        String activityTheme,
        String activityType,
        int expectedParticipants,
        String borrowEquipment,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String contactPhone
    ) {}

    public record UsageCommand(
        @jakarta.validation.constraints.Min(0) int version,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp="START|COMPLETE") String action,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=1000) String comment
    ) {}

    public record ReviewReservationCommand(
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Integer version,
        String action,
        String comment
    ) {}

    public record CheckConflictCommand(
        long venueId,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Long excludeId
    ) {}

    public record ConflictResult(
        boolean hasConflict,
        String message,
        List<TimeSlot> conflictingSlots
    ) {}

    public record VenueUpdateCommand(
        String name,
        Integer capacity,
        String availableEquipment,
        String rules,
        Boolean enabled
    ) {}

    public record VenueCreateCommand(
        @jakarta.validation.constraints.Positive long campusId,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_-]{1,63}") String code,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=120) String name,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp="VENUE|EQUIPMENT") String category,
        @jakarta.validation.constraints.Size(max=64) String placeId,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=100) String buildingName,
        @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=200) String locationDetail,
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive Integer capacity,
        @jakarta.validation.constraints.Size(max=500) String availableEquipment,
        @jakarta.validation.constraints.Size(max=1000) String rules,
        @jakarta.validation.constraints.NotNull Boolean enabled,
        @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(max=20) List<@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Positive Long> managerUserIds,
        @jakarta.validation.constraints.Positive Long classroomResourceId
    ) {}
    public record CampusChoice(long id,String code,String name) {}
    public record ClassroomChoice(long id,long campusId,String name,String buildingName,String roomNumber,int capacity,boolean enabled) {}
    public record CreationCatalog(List<CampusChoice> campuses,List<ReservationPlaceCatalog.Place> places,List<ClassroomChoice> classrooms) {}
    public record RoomLinkCommand(@jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Min(0) Integer version,@jakarta.validation.constraints.Positive Long classroomResourceId,@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=200) String reason) {}

    public record ReservationPage(
        List<ReservationItem> items,
        long total,
        int page,
        int pageSize
    ) {}

    public record VenueManagerItem(
        long userId,
        String username,
        String fullName,
        String departmentName,
        String identityNumber,
        boolean eligible
    ) {}

    public record VenueManagementItem(
        long id,
        long campusId,
        String campusName,
        String code,
        String name,
        String category,
        String placeId,
        String buildingName,
        String locationDetail,
        Integer capacity,
        String availableEquipment,
        String rules,
        boolean enabled,
        List<VenueManagerItem> managers,
        int activeReviewerCount,
        long pendingReviewCount,
        long unattendedReviewCount,
        long pendingUsageCount,
        long activeUseCount,
        long unattendedTaskCount,
        Long classroomResourceId,
        String classroomName,
        int version
    ) {}

    public record AssignManagerCommand(
        long userId
    ) {}
}
