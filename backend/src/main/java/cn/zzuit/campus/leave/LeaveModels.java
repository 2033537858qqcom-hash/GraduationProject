package cn.zzuit.campus.leave;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class LeaveModels {
    private LeaveModels() {}
    public record Page<T>(List<T> items,long total,int page,int pageSize) {}
    public record Request(long id,String requestKey,long studentId,String studentName,String identityNumber,long classGroupId,String className,
        String reasonType,String reason,String contact,LocalDateTime startsAt,LocalDateTime endsAt,String status,String processingComment,int version,
        Long fileId,String fileName,LocalDateTime createdAt,LocalDateTime updatedAt) {}
    public record Revision(long id,int version,String actorName,String action,String comment,String status,String reasonType,
        String reason,String contact,LocalDateTime startsAt,LocalDateTime endsAt,Long fileId,String fileName,LocalDateTime createdAt) {}
    public record Draft(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,
        @NotBlank @Pattern(regexp="PERSONAL|SICK|OTHER") String reasonType,@NotBlank @Size(max=2000) String reason,
        @NotBlank @Size(max=80) String contact,@NotNull LocalDateTime startsAt,@NotNull LocalDateTime endsAt,@Min(0) int version) {}
    public record Action(@Min(0) int version,@NotBlank @Size(max=1000) String comment) {}
    public record Decision(@Min(0) int version,@NotBlank @Pattern(regexp="APPROVE|RETURN|REJECT") String action,@NotBlank @Size(max=1000) String comment) {}
    public record Assignment(long classGroupId,String className,String departmentName,Long counselorId,String counselorName,int version) {}
    public record Counselor(long id,String name,String departmentName,long departmentId) {}
    public record AssignmentCatalog(List<Assignment> assignments,List<Counselor> counselors) {}
    public record Assign(Long counselorId,@Min(0) int version,@NotBlank @Size(max=200) String reason) {}
    public record Eligibility(Long classGroupId,String className,String counselorName,boolean canSubmit,String message,int maxDays) {}
    public record Event(long requestId,LocalDateTime startsAt,LocalDateTime endsAt) {}
    public record Attachment(String name,String type,byte[] bytes) {}
}
