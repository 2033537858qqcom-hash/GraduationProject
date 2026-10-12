package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/assistant")
public class AgentController {
    private final AgentService service;
    private final AgentGuides guides;
    private final IdentityService identities;
    public AgentController(AgentService service,AgentGuides guides,IdentityService identities) { this.service=service; this.guides=guides; this.identities=identities; }
    @GetMapping("/capabilities") Capabilities capabilities(@AuthenticationPrincipal CampusPrincipal user) { return service.capabilities(user); }
    @PostMapping("/query") Answer query(@AuthenticationPrincipal CampusPrincipal user,@Valid @RequestBody Query input) { return service.query(user,input); }
    @PostMapping("/leave/prepare") LeavePreview prepareLeave(@AuthenticationPrincipal CampusPrincipal user,@Valid @RequestBody PrepareLeave input) { return service.prepareLeave(user,input); }
    @PostMapping("/certificate/prepare") CertificatePreview prepareCertificate(@AuthenticationPrincipal CampusPrincipal user,@Valid @RequestBody PrepareCertificate input) { return service.prepareCertificate(user,input); }
    @GetMapping("/guides/{slug}") Guide guide(@AuthenticationPrincipal CampusPrincipal user,@PathVariable String slug) { identities.requirePermission(user,"agent:query"); return guides.detail(slug); }
}
