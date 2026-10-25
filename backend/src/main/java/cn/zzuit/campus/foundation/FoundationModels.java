package cn.zzuit.campus.foundation;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class FoundationModels {
    private FoundationModels() {}
    public record Campus(long id,String name) {}
    public record Department(long id,String name,String kind,int version) {}
    public record Major(long id,long departmentId,String name,int version) {}
    public record ClassGroup(long id,long majorId,String name,int version) {}
    public record Permission(String code,String description) {}
    public record Role(String code,String name,String scope,List<Permission> permissions) {}
    public record Catalog(List<Campus> campuses,List<Department> departments,List<Major> majors,List<ClassGroup> classGroups,List<Role> roles) {}
    public record Account(long id,String username,String fullName,String identityNumber,Long campusId,String campusName,
                          Long departmentId,String departmentName,Long classGroupId,String className,boolean enabled,boolean demo,int version,List<String> roles,boolean passwordChangeRequired) {}
    public record Page<T>(List<T> items,long total,int page,int pageSize) {}
    public record Audit(long id,String actorName,String action,String entityType,long entityId,String summary,String reason,LocalDateTime createdAt) {}
    public record NewAccount(@NotBlank @Pattern(regexp="[A-Za-z0-9][A-Za-z0-9_.-]{2,63}") String username,
        @NotBlank @Size(max=100) String fullName,@NotBlank @Size(max=64) String identityNumber,
        @NotNull @Positive Long campusId,@NotNull @Positive Long departmentId,@Positive Long classGroupId,
        @NotEmpty @Size(max=10) List<@NotBlank @Size(max=40) String> roles,@NotBlank @Size(min=10,max=128) String initialPassword) {}
    public record AccountUpdate(@NotBlank @Size(max=100) String fullName,@NotBlank @Size(max=64) String identityNumber,
        @NotNull @Positive Long campusId,@NotNull @Positive Long departmentId,@Positive Long classGroupId,
        @NotEmpty @Size(max=10) List<@NotBlank @Size(max=40) String> roles,@Min(0) int version,@NotBlank @Size(max=200) String reason) {}
    public record StateChange(@NotNull Boolean enabled,@Min(0) int version,@NotBlank @Size(max=200) String reason,boolean emergency) {}
    public record DepartmentInput(@NotBlank @Size(max=100) String name,@Pattern(regexp="COLLEGE|ADMIN") @NotBlank String kind,@Min(0) Integer version) {}
    public record MajorInput(@NotBlank @Size(max=100) String name,@Positive long departmentId,@Min(0) Integer version) {}
    public record ClassGroupInput(@NotBlank @Size(max=100) String name,@Positive long majorId,@Min(0) Integer version) {}
}
