package cn.zzuit.payservice;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PaymentController {
    private final PaymentStore store;
    public PaymentController(PaymentStore store) { this.store = store; }
    public record CreateRequest(String orderNo, String amount, String subject) {}
    public record RefundRequest(String requestNo,String amount,String reason) {}
    public record CloseRequest(String amount,String subject) {}

    @PostMapping("/internal/payments")
    PaymentStore.Order create(@RequestBody CreateRequest in) { return store.create(in.orderNo(), in.amount(), in.subject()); }

    @GetMapping("/internal/payments/{orderNo}")
    PaymentStore.Order status(@PathVariable String orderNo) {
        return store.status(orderNo).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    @PostMapping("/internal/payments/{orderNo}/close")
    PaymentStore.Order close(@PathVariable String orderNo,@RequestBody CloseRequest input) { return store.closeOrder(orderNo,input.amount(),input.subject()); }
    @PostMapping("/internal/payments/{orderNo}/refunds")
    PaymentStore.Refund refund(@PathVariable String orderNo,@RequestBody RefundRequest input) { return store.refund(orderNo,input.requestNo(),input.amount(),input.reason()); }

    @PostMapping(value = "/api/alipay/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    String notify(HttpServletRequest request) {
        var params = new HashMap<String, String>();
        for (Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
            if (e.getValue().length != 1) return "failure";
            params.put(e.getKey(), e.getValue()[0]);
        }
        return store.notification(params) ? "success" : "failure";
    }
}
