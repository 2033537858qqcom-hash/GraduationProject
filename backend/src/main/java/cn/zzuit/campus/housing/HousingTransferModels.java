package cn.zzuit.campus.housing;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class HousingTransferModels {
    private HousingTransferModels() {}
    public record Transfer(long id,String status,String studentUsername,String studentName,String sourcePlace,long sourceAssignmentId,int sourceVersion,
        String reason,String preferredArea,String materialNote,String reviewComment,String targetPlace,Long targetBedId,Long targetBuildingId,
        LocalDateTime reserveUntil,boolean cancelRequested,int version,LocalDateTime updatedAt,List<String> allowedActions) {}
    public record DraftInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@Min(0) int version,
        @NotBlank @Size(max=500) String reason,@NotBlank @Size(max=200) String preferredArea,@NotBlank @Size(max=500) String materialNote) {}
    public record ActionInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@Min(0) int version,@Size(max=200) String comment) {}
    public record ReserveInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@Min(0) int version,@Positive long bedId,@NotBlank @Size(max=200) String reason) {}
    public record ExtendInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@Min(0) int version,@NotBlank @Size(max=200) String reason,@NotNull LocalDateTime reserveUntil) {}
}
