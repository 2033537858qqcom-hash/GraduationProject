package cn.zzuit.campus.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;
import tools.jackson.databind.JsonNode;

public final class AgentModels {
    private AgentModels() {}
    public enum Mode { LOCAL, DEEPSEEK }
    public record Context(@NotBlank @Pattern(regexp="LEAVE_MINE|LEAVE_REVIEW|CERTIFICATE_MINE|CERTIFICATE_REVIEW|REPAIR_MINE|REPAIR_DISPATCH|REPAIR_WORK|HOUSING_MINE|RESERVATION_MINE") String type,@Positive long id) {}
    public record Query(@NotBlank @Size(max=1000) String message,@NotNull Mode mode,boolean externalConsent,@Valid Context context) {}
    public record LeaveFields(@Size(max=20) String reasonType,@Size(max=2000) String reason,@Size(max=80) String contact,
        @Size(max=10) String startDate,@Size(max=5) String startTime,@Size(max=10) String endDate,@Size(max=5) String endTime) {}
    public record PrepareLeave(@NotBlank @Size(max=1000) String message,@NotNull Mode mode,boolean externalConsent,@NotNull @Valid LeaveFields fields) {}
    public record LeavePreview(String runId,Mode mode,boolean modelUsed,LeaveFields fields,List<String> missing,List<String> issues,
        boolean ready,String summary,String duration,Guide source,String eligibilityMessage,LocalDateTime preparedAt) {}
    public record CertificateFields(@Size(max=300) String purpose) {}
    public record PrepareCertificate(@NotBlank @Size(max=1000) String message,@NotNull Mode mode,boolean externalConsent,@NotNull @Valid CertificateFields fields) {}
    public record CertificatePreview(String runId,Mode mode,boolean modelUsed,CertificateFields fields,
        cn.zzuit.campus.certificate.CertificateModels.Identity identity,List<String> missing,List<String> issues,
        boolean ready,String summary,Guide source,boolean canSubmit,String eligibilityMessage,LocalDateTime preparedAt) {}
    public record RepairFields(@Size(max=20) String category,@Size(max=100) String title,@Size(max=2000) String description,
        @Positive Long locationId,@Size(max=300) String locationDetail,@Size(max=80) String contact,@Size(max=100) String contactWindow) {}
    public record PrepareRepair(@NotBlank @Size(max=1000) String message,@NotNull Mode mode,boolean externalConsent,@NotNull @Valid RepairFields fields) {}
    public record RepairPreview(String runId,Mode mode,boolean modelUsed,RepairFields fields,List<String> missing,List<String> issues,
        boolean ready,String summary,Guide source,String eligibilityMessage,String locationName,LocalDateTime preparedAt) {}
    public record Guide(String slug,String title,String body,int version,LocalDateTime updatedAt,String targetPath) {}
    public record HousingFields(@Size(max=500) String reason,@Size(max=200) String preferredArea,@Size(max=500) String materialNote) {}
    public record PrepareHousing(@NotBlank @Size(max=1000) String message,@NotNull Mode mode,boolean externalConsent,@NotNull @Valid HousingFields fields) {}
    public record HousingPreview(String runId,Mode mode,boolean modelUsed,HousingFields fields,List<String> missing,List<String> issues,
        boolean ready,String summary,Guide source,String eligibilityMessage,String currentPlace,String stayToken,LocalDateTime preparedAt) {}
    public record Fact(String key,String title,String description,String targetPath) {}
    public record ToolResult(String name,String title,String status,String message,long total,List<Fact> items,List<Guide> sources,String targetPath) {}
    public record Call(String name,JsonNode arguments) {}
    public record Plan(List<Call> calls,Integer promptTokens,Integer completionTokens) {}
    public record Capability(String name,String title) {}
    public record Capabilities(boolean deepseekReady,String model,List<Capability> tools,String message) {}
    public record Answer(String runId,Mode mode,boolean modelUsed,String summary,List<ToolResult> results,LocalDateTime queriedAt,Integer promptTokens,Integer completionTokens) {}
}
