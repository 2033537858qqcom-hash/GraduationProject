package cn.zzuit.campus.payment;

import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {
    private final PaymentService payments;
    private final PaymentManagementService finance;
    private final PaymentReceiptPdf receipts;
    public PaymentController(PaymentService payments,PaymentManagementService finance,PaymentReceiptPdf receipts) { this.payments=payments;this.finance=finance;this.receipts=receipts; }
    @GetMapping("/api/payments/categories")
    List<PaymentService.Category> categories(@AuthenticationPrincipal CampusPrincipal user) { return payments.categories(user); }
    @GetMapping("/api/payments/mine") List<PaymentService.Bill> mine(@AuthenticationPrincipal CampusPrincipal user) { return payments.mine(user); }
    @PostMapping("/api/payments/mine/{id}/pay") Map<String,String> pay(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id) {
        return payments.start(user,id);
    }
    @GetMapping("/api/payments/mine/{id}") PaymentService.Bill status(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id) {
        return payments.status(user,id);
    }
    public record RefundInput(String requestKey,String reason,int version) {}
    @PostMapping("/api/payments/mine/{id}/refund") FinanceModels.Request refund(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@RequestBody RefundInput input) {
        return finance.createRequest(user,id,new FinanceModels.RequestInput(input.requestKey(),"REFUND",input.reason(),input.version()),true);
    }
    @GetMapping("/api/payments/mine/{id}/history") List<FinanceModels.History> history(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id) {
        payments.owned(user,id);return finance.historyRows(id);
    }
    @GetMapping("/api/payments/mine/{id}/receipt") ResponseEntity<byte[]> receipt(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id) throws java.io.IOException {
        var bill=payments.status(user,id);
        if(!bill.status().equals("PAID")) throw PaymentService.error(org.springframework.http.HttpStatus.CONFLICT,"RECEIPT_UNAVAILABLE","支付平台尚未确认收款，暂不可下载缴费凭证");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=payment-"+id+".pdf").body(receipts.generate(bill));
    }
    @PostMapping(value="/api/payments/alipay/notify",consumes=MediaType.APPLICATION_FORM_URLENCODED_VALUE,produces=MediaType.TEXT_PLAIN_VALUE)
    String notify(HttpServletRequest request) {
        var params=new HashMap<String,String>();
        for(var entry:request.getParameterMap().entrySet()) {
            if(entry.getValue().length!=1) return "failure";
            params.put(entry.getKey(),entry.getValue()[0]);
        }
        return payments.notification(params)?"success":"failure";
    }
}
