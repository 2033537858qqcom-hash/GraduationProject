package cn.zzuit.campus.resource;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class RoomModels {
    private RoomModels() {}
    public record Room(long id,long campusId,String campusName,String code,String name,String buildingName,String roomNumber,
        String floorLabel,String type,Integer teachingCapacity,Integer examCapacity,boolean enabled,String origin,int version,
        LocalDateTime updatedAt,boolean schedulable,boolean referenced,long upcomingLessonCount,long publishedExamCount,long draftExamCount) {}
    public record Campus(long id,String name) {}
    public record Catalog(List<Campus> campuses) {}
    public record Page<T>(List<T> items,long total,int page,int pageSize) {}
    public record RoomInput(@Positive long campusId,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String code,
        @NotBlank @Size(max=100) String name,@NotBlank @Size(max=100) String buildingName,@NotBlank @Size(max=50) String roomNumber,
        @Size(max=30) String floorLabel,@NotBlank @Pattern(regexp="LECTURE|LAB|MULTIPURPOSE|OTHER") String type,
        @NotNull @Min(1) @Max(1000) Integer teachingCapacity,@NotNull @Min(1) @Max(1000) Integer examCapacity,
        @Min(0) int version,@NotBlank @Size(max=480) String reason) {}
    public record StateInput(@NotNull Boolean enabled,@Min(0) int version,@NotBlank @Size(max=480) String reason) {}
    public record Occupancy(long id,String kind,long classId,String className,String courseName,String classroom,
        LocalDateTime startsAt,LocalDateTime endsAt,long memberCount) {}
    public record Audit(long id,String action,int version,String reason,String detail,String actorName,LocalDateTime createdAt) {}
}
