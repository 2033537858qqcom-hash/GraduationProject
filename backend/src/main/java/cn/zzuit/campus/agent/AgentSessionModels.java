package cn.zzuit.campus.agent;

import cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.agent.AgentModels.RepairPreview;
import cn.zzuit.campus.certificate.CertificateModels.Identity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class AgentSessionModels {
    private AgentSessionModels() {}
    public record CreateSession(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey) {}
    public record Session(long id,String title,LocalDateTime createdAt,LocalDateTime updatedAt) {}
    public record Page<T>(List<T> items,long total,int page,int pageSize) {}
    public record Turn(long id,String question,Mode mode,Context context,String status,String runId,LocalDateTime createdAt) {}
    public record SessionDetail(Session session,List<Turn> turns,List<TaskSummary> tasks,boolean hasOlder) {}
    public record SessionQuery(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@NotNull @Valid Query input) {}
    public record QueryResult(Turn turn,Answer answer,boolean repeated) {}
    public record CreateTask(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@NotBlank @Pattern(regexp="LEAVE|CERTIFICATE|REPAIR|HOUSING") String kind) {}
    public record TaskSummary(long id,String kind,String stage,int version,LocalDateTime updatedAt) {}
    public record Business(long id,String kind,String status,int version,String targetPath) {}
    public record TaskView(long id,String kind,String stage,int version,LeavePreview leavePreview,CertificatePreview certificatePreview,RepairPreview repairPreview,Business business,LocalDateTime updatedAt,HousingPreview housingPreview) {}
    public record TaskLeave(@Min(0) int version,@NotNull @Valid PrepareLeave input) {}
    public record TaskCertificate(@Min(0) int version,@NotNull @Valid PrepareCertificate input) {}
    public record TaskRepair(@Min(0) int version,@NotNull @Valid PrepareRepair input) {}
    public record TaskHousing(@Min(0) int version,@NotNull @Valid PrepareHousing input) {}
    public record ConfirmTask(@Min(0) int version,@NotNull @AssertTrue Boolean confirmed,Identity expectedIdentity,@Pattern(regexp="[a-f0-9]{64}") String expectedStayToken) {}
}
