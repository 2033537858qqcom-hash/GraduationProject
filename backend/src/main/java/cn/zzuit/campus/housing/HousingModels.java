package cn.zzuit.campus.housing;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class HousingModels {
    private HousingModels() {}
    public record Page<T>(List<T> items,long total,int page,int pageSize) {}
    public record Campus(long id,String name) {}
    public record ManagerOption(long id,String name,String departmentName,boolean enabled) {}
    public record BuildingOption(long id,long campusId,String name,boolean enabled) {}
    public record Catalog(List<Campus> campuses,List<BuildingOption> buildings,List<ManagerOption> managers) {}
    public record Building(long id,long campusId,String campusName,String code,String name,String provenance,boolean enabled,int version,long roomCount,long occupiedCount,LocalDateTime updatedAt) {}
    public record Room(long id,long buildingId,long campusId,String campusName,String buildingName,boolean buildingEnabled,String code,String name,String floorLabel,boolean enabled,int version,long bedCount,long occupiedCount,LocalDateTime updatedAt) {}
    public record Bed(long id,long roomId,long buildingId,String roomName,String floorLabel,String code,String label,String status,boolean enabled,boolean roomEnabled,boolean buildingEnabled,int version,Long assignmentId,Integer assignmentVersion,String occupantUsername,String occupantName,LocalDateTime updatedAt) {}
    public record Scope(long id,long userId,String userName,long buildingId,long campusId,String campusName,String buildingName,boolean enabled,boolean effective,String effectivenessMessage,int version,LocalDateTime updatedAt) {}
    public record BuildingInput(@Positive long campusId,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String code,@NotBlank @Size(max=100) String name,@NotBlank @Pattern(regexp="DEMO|CONFIGURED") String provenance,@Min(0) int version,@NotBlank @Size(max=200) String reason) {}
    public record RoomInput(@Positive long buildingId,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String code,@NotBlank @Size(max=100) String name,@NotBlank @Size(max=20) String floorLabel,@Min(0) int version,@NotBlank @Size(max=200) String reason) {}
    public record BedInput(@Positive long roomId,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,32}") String code,@NotBlank @Size(max=40) String label,@Min(0) int version,@NotBlank @Size(max=200) String reason) {}
    public record ScopeInput(@Positive long userId,@Positive long buildingId,@Min(0) int version,@NotBlank @Size(max=200) String reason) {}
    public record StateInput(@NotNull Boolean enabled,@Min(0) int version,@NotBlank @Size(max=200) String reason) {}
    public record MaintenanceInput(@NotBlank @Pattern(regexp="VACANT|MAINTENANCE") String status,@Min(0) int version,@NotBlank @Size(max=200) String reason) {}
    public record Audit(long id,String actorName,String action,String entityType,long entityId,String summary,String reason,LocalDateTime createdAt) {}
    public record CurrentStay(String campusName,String buildingName,String roomName,String floorLabel,String bedLabel,LocalDateTime startedAt,List<String> managers) {}
    public record StayHistory(long id,String campusName,String buildingName,String roomName,String floorLabel,String bedLabel,LocalDateTime startedAt,LocalDateTime endedAt,String endReason) {}
    public record Mine(CurrentStay current,List<StayHistory> history) {}
    public record Responsibilities(List<Scope> scopes,long occupied,long vacant) {}
    public record StudentMatch(long id,String username,String fullName,boolean housed) {}
    public record CheckIn(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@Positive long studentId,@Positive long bedId,@NotBlank @Size(max=200) String reason) {}
    public record CheckOut(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@Min(0) int version,@NotBlank @Size(max=200) String reason) {}
    public record Assignment(long id,String studentUsername,String studentName,long bedId,long buildingId,String campusName,String buildingName,String roomName,String floorLabel,String bedLabel,String status,int version,LocalDateTime startedAt,LocalDateTime endedAt,String endReason,Long importBatchId) {}
}
