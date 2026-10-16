package cn.zzuit.campus.repair;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class RepairSettingsModels {
    private RepairSettingsModels() {}
    public record Page<T>(List<T> items,long total,int page,int pageSize) {}
    public record Category(String code,String name) {}
    public record Campus(long id,String name) {}
    public record Building(long id,long campusId,String name,String verificationStatus) {}
    public record Room(long id,long campusId,String name,String buildingName,String roomNumber,String origin,boolean available) {}
    public record Person(long id,String name,String departmentName,List<String> capabilities,boolean enabled) {}
    public record DormRoom(long id,long campusId,String name,String buildingName,String floorLabel,String provenance,boolean available) {}
    public record Catalog(List<Campus> campuses,List<Category> categories,List<Building> buildings,List<Room> rooms,List<Person> people,List<DormRoom> dormRooms) {}
    public record PublicCatalog(List<Campus> campuses,List<Category> categories) {}
    public record Location(long id,long campusId,String campusName,String code,String name,String kind,Long classroomResourceId,
        Long buildingId,String sourceName,String detailHint,String provenance,boolean enabled,boolean available,String availabilityMessage,
        String buildingVerificationStatus,int version,LocalDateTime updatedAt,Long dormRoomId) {}
    public record PublicLocation(long id,long campusId,String campusName,String name,String kind,String detailHint,String provenance) {}
    public record DormEligibility(boolean occupied,PublicLocation location,String message) {}
    public record Scope(long id,long userId,String userName,String capability,long campusId,String campusName,String category,
        Long locationId,String locationName,boolean enabled,boolean effective,String effectivenessMessage,int version,LocalDateTime updatedAt) {}
    public record Responsibilities(List<Category> categories,List<Scope> scopes) {}
    public record LocationInput(@Positive long campusId,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,64}") String code,
        @NotBlank @Size(max=100) String name,@NotBlank @Pattern(regexp="CLASSROOM|BUILDING_COMMON|CAMPUS_PUBLIC|DORM_ROOM") String kind,
        @Positive Long classroomResourceId,@Positive Long buildingId,@Size(max=300) String detailHint,
        @NotBlank @Pattern(regexp="DEMO|CONFIGURED") String provenance,@Min(0) int version,@NotBlank @Size(max=200) String reason,@Positive Long dormRoomId) {
        public LocationInput(long campusId,String code,String name,String kind,Long classroomResourceId,Long buildingId,String detailHint,String provenance,int version,String reason) {
            this(campusId,code,name,kind,classroomResourceId,buildingId,detailHint,provenance,version,reason,null);
        }
    }
    public record ScopeInput(@Positive long userId,@NotBlank @Pattern(regexp="DISPATCH|WORK") String capability,@Positive long campusId,
        @NotBlank @Pattern(regexp="WATER|ELECTRICITY|NETWORK|FURNITURE|DOOR_WINDOW|EQUIPMENT|OTHER") String category,
        @Positive Long locationId,@Min(0) int version,@NotBlank @Size(max=200) String reason) {}
    public record StateInput(@NotNull Boolean enabled,@Min(0) int version,@NotBlank @Size(max=200) String reason) {}
    public record Audit(long id,String actorName,String action,String entityType,long entityId,String summary,String reason,LocalDateTime createdAt) {}
}
