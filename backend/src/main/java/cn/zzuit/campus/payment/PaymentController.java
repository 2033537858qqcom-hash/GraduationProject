package cn.zzuit.campus.payment;

import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {
    private final PaymentService payments;
    public PaymentController(PaymentService payments) { this.payments=payments; }
    public record CreateBill(String requestKey,String username,String title,long amountCents) {}
    @GetMapping("/api/payments/mine") List<PaymentService.Bill> mine(@AuthenticationPrincipal CampusPrincipal user) { return payments.mine(user); }
    @PostMapping("/api/admin/payments") PaymentService.Bill create(@AuthenticationPrincipal CampusPrincipal user,@RequestBody CreateBill input) {
        return payments.create(user,input.requestKey(),input.username(),input.title(),input.amountCents());
    }
    @PostMapping("/api/payments/mine/{id}/pay") Map<String,String> pay(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id) {
        return payments.start(user,id);
    }
    @GetMapping("/api/payments/mine/{id}") PaymentService.Bill status(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id) {
        return payments.status(user,id);
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
