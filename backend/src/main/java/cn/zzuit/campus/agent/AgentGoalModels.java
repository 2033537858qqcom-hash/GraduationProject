package cn.zzuit.campus.agent;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.*;
import cn.zzuit.campus.agent.AgentModels.*;

/** Explicit task plans and tool results; hidden model reasoning is never persisted. */
public final class AgentGoalModels {
 private AgentGoalModels(){}
 public record Create(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,
     @NotBlank @Size(max=1000) String goal,@NotNull Boolean externalConsent){}
 public record Action(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@Min(0) int version,
     @NotBlank @Pattern(regexp="CONTINUE|REVISE|CANCEL|AUTHORIZE_SUBMIT|CANCEL_TASK") String action,
     @Size(max=1000) String message,Boolean externalConsent,@Pattern(regexp="[a-z][a-z0-9_-]{0,39}") String taskKey,
     Boolean confirmed,@Pattern(regexp="[a-f0-9]{64}") String proposalHash){}
 public static class Task {
     public String key,kind,label,target="DRAFT";
     public List<String> dependsOn=List.of();
     public Long missionId;
     public AgentMissionModels.Fields fields;
     public RepairFields repairFields;
     public LeaveFields leaveFields;
     public CertificateFields certificateFields;
     public boolean cancelled,submissionVerified,watching,trackingComplete;
     public String submissionHash,lastBusinessStatus;
     public Integer submittedVersion;
     public LocalDateTime lastCheckedAt;
     public int fieldsInstructionCount=-1;
     public String lastReadFingerprint;
     public int unchangedReads;
 }
 public static class State {
     public List<Task> tasks=new ArrayList<>();
     public List<String> instructions=new ArrayList<>();
     public List<Map<String,Object>> observations=new ArrayList<>();
     public String message="正在理解目标并选择工具。",focusTaskKey,currentUserDirective="";
     public int failures;
     public int planEpoch,lastPlanEpoch=-1;
     public boolean directivePending;
     public Map<String,Guide> guidesRead=new LinkedHashMap<>();
 }
 public record TaskView(String key,String kind,String label,String target,List<String> dependsOn,boolean cancelled,
     AgentMissionModels.View mission,String submissionHash,boolean submissionVerified,boolean watching,SubmissionPreview submissionPreview,
     String lastBusinessStatus,boolean trackingComplete,LocalDateTime lastCheckedAt){}
 public record SubmissionField(String label,String value){}
 public record SubmissionPreview(String kind,long businessId,String status,int version,List<SubmissionField> fields,
     String proposalHash,boolean canSubmit,List<String> issues,String targetPath){}
 public record Event(int number,String tool,String taskKey,String status,String message,Integer promptTokens,Integer completionTokens,LocalDateTime createdAt){}
 public record Summary(long id,String requestKey,String goal,String stage,int version,LocalDateTime updatedAt){}
 public record View(long id,long sessionId,String goal,String stage,int version,List<String> instructions,List<TaskView> tasks,
     String message,String focusTaskKey,List<Event> events,boolean canContinue,LocalDateTime updatedAt){}
}
