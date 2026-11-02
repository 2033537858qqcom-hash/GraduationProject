package cn.zzuit.campus.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;
import cn.zzuit.campus.agent.AgentModels.*;

/** User-visible state and minimized model observations; no model-generated reasoning is stored. */
public final class AgentMissionModels {
    private AgentMissionModels() {}
    public record Fields(@Pattern(regexp="EAST|WEST|ALL") String campus,@Size(max=19) String startsAt,@Size(max=19) String endsAt,
        @Min(1) @Max(10000) Integer participants,@Size(max=40) String keyword,@Size(max=200) String activityTheme,
        @Pattern(regexp="ACADEMIC|SPORTS|CULTURE|OTHER") String activityType,@Size(max=30) String contactPhone) {}
    public record Create(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@NotBlank @Size(max=1000) String goal,
        @NotNull Mode mode,@NotNull Boolean externalConsent,@Valid Fields fields,@Pattern(regexp="AUTO|RESERVATION|REPAIR|LEAVE|CERTIFICATE") String kind,@Valid RepairFields repairFields,@Valid LeaveFields leaveFields,@Valid CertificateFields certificateFields) {}
    public record Action(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@Min(0) int version,
        @NotBlank @Pattern(regexp="REVISE|CONTINUE|SELECT|CONFIRM|CANCEL") String action,@Valid Fields fields,
        @Size(max=50) String optionKey,Boolean confirmed,@Pattern(regexp="[a-f0-9]{64}") String proposalHash,@Valid RepairFields repairFields,@Valid LeaveFields leaveFields,@Valid CertificateFields certificateFields) {}
    public record Option(String key,long venueId,String name,String location,Integer capacity,int venueVersion) {}
    public record Observation(String action,String status,long count,List<String> missing) {}
    public record Step(int number,String action,String status,String message,Observation observation,Integer promptTokens,Integer completionTokens,LocalDateTime createdAt) {}
    public record Business(long id,String status,int version,String targetPath) {}
    public record Summary(long id,String requestKey,String goal,Mode mode,String stage,int version,LocalDateTime updatedAt,String kind) {}
    public record View(long id,long sessionId,String goal,Mode mode,String stage,int version,Fields fields,List<String> missing,
        List<Option> options,String selectedKey,String proposalHash,List<Fact> conflicts,String message,List<Step> steps,Business business,boolean canContinue,LocalDateTime updatedAt,String kind,RepairFields repairFields,RepairPreview repairPreview,List<cn.zzuit.campus.repair.RepairSettingsModels.PublicLocation> repairLocations,LeaveFields leaveFields,LeavePreview leavePreview,CertificateFields certificateFields,CertificatePreview certificatePreview) {}
    public static class State {
        public Fields fields;
        public String kind="RESERVATION";
        public RepairFields repairFields;
        public RepairPreview repairPreview;
        public LeaveFields leaveFields;
        public LeavePreview leavePreview;
        public CertificateFields certificateFields;
        public CertificatePreview certificatePreview;
        public boolean certificateChecked,certificateIdentityChecked;
        public boolean leaveChecked,leaveEligibilityChecked;
        public String leaveEligibilityHash;
        public String repairLocationHash;
        public boolean repairChecked,repairLocationChecked;
        public List<String> missing=List.of();
        public List<Option> options=List.of();
        public List<Fact> conflicts=List.of();
        public String selectedKey,proposalHash;
        public boolean scheduleChecked,venuesChecked,proposalReady,confirmed,verified;
        public long conflictCount;
        public String scheduleStatus="",venueStatus="",message="";
        public Long draftId;
    }
}
