package cn.zzuit.campus.housing;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class HousingImportModels {
    private HousingImportModels() {}
    public record Input(@Positive long buildingId,@NotBlank @Size(max=102400) String csv,@NotBlank @Size(max=160) String sourceName) {}
    public record Row(int line,String username,String studentName,String roomCode,String bedCode,String place,String reason,List<String> errors,Long assignmentId) {}
    public record Batch(Long id,String token,String status,long buildingId,String campusName,String buildingName,String sourceName,
        int total,int valid,int invalid,int ignored,boolean canConfirm,LocalDateTime expiresAt,LocalDateTime completedAt,List<Row> rows) {}
    public record Summary(long id,String token,String sourceName,int total,LocalDateTime completedAt) {}
}
