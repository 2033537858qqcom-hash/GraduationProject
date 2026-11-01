package cn.zzuit.campus.payment;

import static cn.zzuit.campus.payment.FinanceModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/finance/payments")
public class FinanceController {
    private final PaymentManagementService finance;
    private final PaymentConfigurationService configuration;
    private final PaymentService payments;
    private final PaymentReconciliation reconciliation;
    public FinanceController(PaymentManagementService finance,PaymentConfigurationService configuration,PaymentService payments,PaymentReconciliation reconciliation) { this.finance=finance;this.configuration=configuration;this.payments=payments;this.reconciliation=reconciliation; }
    @PostMapping("/reconciliation/preview") PaymentReconciliation.Preview preview(@AuthenticationPrincipal CampusPrincipal user,@RequestBody PaymentReconciliation.Input input) { return reconciliation.compare(user,input); }
    @GetMapping("/reconciliation/template") ResponseEntity<byte[]> template(@AuthenticationPrincipal CampusPrincipal user) {
        configuration.catalog(user);
        return ResponseEntity.ok().contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=payment-statement-template.csv")
            .body("\uFEFF订单号,金额（元）,交易号,支付状态\r\n".getBytes(StandardCharsets.UTF_8));
    }
    @GetMapping("/catalog") Catalog catalog(@AuthenticationPrincipal CampusPrincipal user) { return configuration.catalog(user); }
    @GetMapping("/categories") List<PaymentService.Category> categories(@AuthenticationPrincipal CampusPrincipal user) { return payments.categories(user); }
    @PostMapping("/standards") Standard standard(@AuthenticationPrincipal CampusPrincipal user,@RequestBody StandardInput input) { return configuration.createStandard(user,input); }
    @PostMapping("/standards/{id}/actions") Standard standardAction(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@RequestBody Action input) { return configuration.standardAction(user,id,input); }
    @GetMapping("/bases") List<Basis> bases(@AuthenticationPrincipal CampusPrincipal user) { return configuration.bases(user); }
    @PostMapping("/bases/{id}/return") Basis returnBasis(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@RequestBody Action input) { return configuration.returnBasis(user,id,input); }
    @GetMapping Page<PaymentService.Bill> list(@AuthenticationPrincipal CampusPrincipal user,
        @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String categoryCode,@RequestParam(defaultValue="") String status,
        @RequestParam(defaultValue="") String billingPeriod,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return finance.list(user,q,categoryCode,status,billingPeriod,page,pageSize);
    }
    @PostMapping PaymentService.Bill create(@AuthenticationPrincipal CampusPrincipal user,@RequestBody BillInput input) { return finance.create(user,input); }
    @PostMapping("/batch") List<PaymentService.Bill> batch(@AuthenticationPrincipal CampusPrincipal user,@RequestBody BatchInput input) { return finance.batch(user,input); }
    @PostMapping("/batch/preview") BatchPreview batchPreview(@AuthenticationPrincipal CampusPrincipal user,@RequestBody BatchInput input) { return finance.previewBatch(user,input); }
    @PutMapping("/{id}") PaymentService.Bill update(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@RequestBody BillInput input) { return finance.update(user,id,input); }
    @GetMapping("/{id}") PaymentService.Bill get(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id) { return finance.get(user,id); }
    @GetMapping("/{id}/detail") Detail detail(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id) { return finance.detail(user,id); }
    @GetMapping("/{id}/history") List<History> history(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id) { return finance.history(user,id); }
    @PostMapping("/{id}/actions") PaymentService.Bill action(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@RequestBody Action input) { return finance.action(user,id,input); }
    @PostMapping("/{id}/requests") Request request(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@RequestBody RequestInput input) { return finance.createRequest(user,id,input,false); }
    @GetMapping("/requests") List<Request> requests(@AuthenticationPrincipal CampusPrincipal user) { return finance.requests(user,null); }
    @PostMapping("/requests/{id}/actions") Request requestAction(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@RequestBody Action input) { return finance.requestAction(user,id,input); }
    @GetMapping("/export") ResponseEntity<byte[]> export(@AuthenticationPrincipal CampusPrincipal user,
        @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String categoryCode,@RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String billingPeriod) {
        return ResponseEntity.ok().contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=payment-ledger.csv")
            .body(finance.export(user,q,categoryCode,status,billingPeriod).getBytes(StandardCharsets.UTF_8));
    }
}
