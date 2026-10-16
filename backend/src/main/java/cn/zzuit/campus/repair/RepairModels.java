package cn.zzuit.campus.repair;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class RepairModels {
    private RepairModels() {}
    public record Page<T>(List<T> items,long total,int page,int pageSize) {}
    public record Draft(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@Min(0) int version,
        @Positive Long locationId,@Pattern(regexp="|WATER|ELECTRICITY|NETWORK|FURNITURE|DOOR_WINDOW|EQUIPMENT|OTHER") String category,
        @Size(max=100) String title,@Size(max=2000) String description,@Size(max=300) String locationDetail,
        @Size(max=80) String contact,@Size(max=100) String contactWindow) {}
    public record Action(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@Min(0) int version,
        @NotNull @Size(max=2000) String comment,@Positive Long technicianId,@Min(0) Integer resultRound,
        @Min(1) @Max(5) Integer stars,@Pattern(regexp="LOW|NORMAL|HIGH") String priority) {}
    public record Upload(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@Min(0) int version,
        @NotBlank @Pattern(regexp="FAULT|PROGRESS|RESULT") String purpose) {}
    public record Item(long id,String reporterName,String title,Long campusId,String campusName,Long locationId,String locationName,
        String category,String priority,String status,String technicianName,int version,LocalDateTime updatedAt,boolean cancellationPending,boolean confirmationOverdue) {}
    public record Image(long id,String name,String type,String purpose,int resultRound,LocalDateTime createdAt) {}
    public record Completion(int resultRound,String technicianName,String comment,List<Image> images,LocalDateTime createdAt) {}
    public record Evaluation(int stars,String comment,LocalDateTime createdAt) {}
    public record Detail(long id,String reporterName,String title,Long campusId,String campusName,Long locationId,String locationName,
        String category,String priority,String status,int version,String description,String locationDetail,String contact,String contactWindow,
        Long technicianId,String technicianName,int completionRound,String processingComment,List<String> allowedActions,
        List<Image> images,List<Completion> completions,Evaluation evaluation,LocalDateTime submittedAt,LocalDateTime confirmedAt,LocalDateTime createdAt,LocalDateTime updatedAt,Cancellation cancellation,boolean cancellationPending,boolean confirmationOverdue,boolean locationEligible,String locationEligibilityMessage) {}
    public record History(long id,String actorName,String action,String comment,String status,int version,String snapshot,LocalDateTime createdAt) {}
    public record Technician(long id,String name,String departmentName) {}
    public record Summary(long mineAwaitingConfirmation,long mineReturned,long dispatchPending,long workAssigned,long workInProgress,long mineCancellationPending,long dispatchCancellationPending) {}
    public record CancelAction(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@Min(0) int version,
        @Positive Long cancellationId,@Pattern(regexp="APPROVE|REJECT") String decision,@NotBlank @Size(max=2000) String comment) {}
    public record Cancellation(long id,String reason,String status,String requesterName,String decisionActorName,String decisionComment,LocalDateTime createdAt,LocalDateTime resolvedAt) {}
    public record Bucket(String code,long count) {}
    public record Statistics(java.time.LocalDate from,java.time.LocalDate to,long total,long completed,long cancelled,long cancellationPending,
        Double averageHours,long averageSampleCount,List<Bucket> statuses,List<Bucket> categories,LocalDateTime queriedAt) {}
    public record Attachment(String name,String type,byte[] bytes) {}
}
