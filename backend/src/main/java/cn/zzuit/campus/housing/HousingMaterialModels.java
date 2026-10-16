package cn.zzuit.campus.housing;

import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class HousingMaterialModels {
    private HousingMaterialModels() {}
    public record Upload(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{8,80}") String requestKey,@Min(0) int version) {}
    public record Access(long id,String status,int version) {}
    public record Material(long id,String name,String type,int size,LocalDateTime createdAt) {}
    public record Submission(long id,int revision,int requestVersion,String reason,String preferredArea,String materialNote,LocalDateTime createdAt,List<Material> files) {}
    public record Bundle(int version,String status,boolean canEdit,List<Material> current,List<Submission> submissions,long total,int page,int pageSize) {}
    public record Download(String name,String type,byte[] bytes) {}
}
