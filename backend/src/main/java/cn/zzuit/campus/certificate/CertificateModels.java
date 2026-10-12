package cn.zzuit.campus.certificate;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class CertificateModels {
    private CertificateModels() {}
    public record Page<T>(List<T> items,long total,int page,int pageSize) {}
    public record Identity(long departmentId,long classGroupId,String studentName,String identityNumber,
        String departmentName,String majorName,String className,String campusName) {}
    public record Eligibility(Identity identity,boolean canSubmit,String message) {}
    public record Request(long id,String requestKey,long studentId,Identity identity,String purpose,String deliveryMethod,
        boolean identityConfirmed,String status,String processingComment,int version,LocalDateTime createdAt,
        LocalDateTime updatedAt,String documentNumber,LocalDateTime issuedAt) {}
    public record Draft(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,
        @NotBlank @Size(max=300) String purpose,@NotBlank @Pattern(regexp="DIGITAL") String deliveryMethod,
        @NotNull @AssertTrue Boolean identityConfirmed,@Min(0) int version,Identity expectedIdentity) {}
    public record Action(@Min(0) int version,@NotBlank @Size(max=1000) String comment) {}
    public record Decision(@Min(0) int version,@NotBlank @Pattern(regexp="APPROVE|RETURN|REJECT") String action,
        @NotBlank @Size(max=1000) String comment) {}
    public record Revision(long id,int version,String actorName,String action,String comment,String status,
        String studentName,String identityNumber,String departmentName,String majorName,String className,
        String campusName,String purpose,String deliveryMethod,LocalDateTime createdAt) {}
    public record Result(String name,byte[] bytes) {}
}
