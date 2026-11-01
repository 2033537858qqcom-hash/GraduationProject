package cn.zzuit.campus.payment;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class FinanceModels {
    private FinanceModels() {}
    public record BillInput(String requestKey,String username,String categoryCode,String title,String billingPeriod,
        LocalDate dueDate,Long standardId,String quantity,Long amountCents,String basis,Long basisId,Integer version,String reason) {}
    public record StandardInput(String requestKey,String name,String categoryCode,String unit,long unitPriceCents,
        Long campusId,Long majorId,String reason) {}
    public record BatchInput(String requestKey,List<String> usernames,BillInput template) {}
    public record BatchPreviewRow(String username,String studentName,Long amountCents,String errorCode,String error) {}
    public record BatchPreview(List<BatchPreviewRow> items,int validCount,long totalAmountCents,boolean canCreate) {}
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
    public record BasisInput(String requestKey,String username,String categoryCode,String billingPeriod,String quantity,
        String basis,String previousReading,String currentReading,Long correctionOf) {}
    public record Action(String action,int version,String requestKey,String reason,Long assigneeId) {}
    public record RequestInput(String requestKey,String type,String reason,int version) {}
    public record Standard(long id,String name,String categoryCode,String categoryName,String unit,long unitPriceCents,
        Long campusId,Long majorId,String status,long createdBy,String creatorName,int version,String reason,LocalDateTime createdAt) {}
    public record Basis(long id,String username,String studentName,String categoryCode,String categoryName,
        String billingPeriod,String quantity,String basis,String sourceReference,long createdBy,String creatorName,
        String status,Long billId,String reviewComment,int version,LocalDateTime createdAt) {}
    public record Request(long id,long billId,String type,String reason,String status,long requestedBy,String requesterName,
        Long reviewedBy,String gatewayRequestNo,String lastError,int version,LocalDateTime createdAt) {}
    public record History(long id,String action,String actorName,String reason,String snapshot,LocalDateTime createdAt) {}
    public record Summary(long total,long unpaidCount,long paidCount,long overdueCount,long pendingReviewCount,
        long unpaidAmountCents,long paidAmountCents,long refundedAmountCents) {}
    public record Page<T>(List<T> items,long total,int page,int pageSize,Summary summary) {}
    public record Detail(PaymentService.Bill bill,List<History> history,List<Request> requests,List<String> allowedActions) {}
    public record Person(long id,String name) {}
    public record Catalog(List<PaymentService.Category> categories,List<Standard> standards,List<Person> operators,
        List<Person> reviewers,List<Person> campuses,List<Person> majors,List<String> permissions) {}
}
