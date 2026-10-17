package cn.zzuit.campus.reservation;

import java.time.LocalDateTime;
import java.util.List;

public class ReservationDtos {

    public record VenueSummary(
        long id,
        String code,
        String name,
        String category,
        String placeId,
        String buildingName,
        String locationDetail,
        Integer capacity,
        String availableEquipment,
        String rules,
        boolean enabled
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
        long actorId,
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

    public record ReviewReservationCommand(
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
        String identityNumber
    ) {}

    public record VenueManagementItem(
        long id,
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
        List<VenueManagerItem> managers
    ) {}

    public record AssignManagerCommand(
        long userId
    ) {}
}
