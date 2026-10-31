package cn.zzuit.campus.payment;

import cn.zzuit.campus.identity.CampusPrincipal;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payment-bases")
public class PaymentBasisController {
    private final PaymentConfigurationService configuration;
    private final PaymentService payments;
    public PaymentBasisController(PaymentConfigurationService configuration,PaymentService payments) { this.configuration=configuration;this.payments=payments; }
    @GetMapping Map<String,Object> list(@AuthenticationPrincipal CampusPrincipal user) { return Map.of("items",configuration.bases(user)); }
    @GetMapping("/catalog") Map<String,Object> catalog(@AuthenticationPrincipal CampusPrincipal user) { return Map.of("categories",payments.categories(user)); }
    @PostMapping FinanceModels.Basis create(@AuthenticationPrincipal CampusPrincipal user,@RequestBody FinanceModels.BasisInput input) { return configuration.createBasis(user,input); }
}
