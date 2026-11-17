package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/assistant")
public class AgentController {
    private final AgentService service;
    private final AgentGuides guides;
    private final IdentityService identities;
    private final AgentVisionService vision;
    @Autowired
    public AgentController(AgentService service,AgentGuides guides,IdentityService identities,AgentVisionService vision) { this.service=service; this.guides=guides; this.identities=identities; this.vision=vision; }
    public AgentController(AgentService service,AgentGuides guides,IdentityService identities) { this(service,guides,identities,null); }
    @GetMapping("/capabilities") Capabilities capabilities(@AuthenticationPrincipal CampusPrincipal user) { return service.capabilities(user); }
    @GetMapping("/briefing") Briefing briefing(@AuthenticationPrincipal CampusPrincipal user) { return service.briefing(user); }
    @GetMapping("/metrics") RunMetrics metrics(@AuthenticationPrincipal CampusPrincipal user) { return service.metrics(user); }
    @GetMapping("/metrics/overview") OverviewMetrics overviewMetrics(@AuthenticationPrincipal CampusPrincipal user) { return service.overviewMetrics(user); }
    @PostMapping("/runs/{runId}/feedback") FeedbackSummary feedback(@AuthenticationPrincipal CampusPrincipal user,@PathVariable String runId,@Valid @RequestBody FeedbackInput input) { return service.submitFeedback(user,runId,input); }
    @PostMapping("/repair/analyze-photo") AgentVisionService.PhotoAnalysis analyzeRepairPhoto(@AuthenticationPrincipal CampusPrincipal user,@RequestParam MultipartFile file,@RequestParam(required=false) String note) { return vision.analyzeRepairPhoto(user,file,note); }
    @PostMapping("/query") Answer query(@AuthenticationPrincipal CampusPrincipal user,@Valid @RequestBody Query input) { return service.query(user,input); }
    @PostMapping("/leave/prepare") LeavePreview prepareLeave(@AuthenticationPrincipal CampusPrincipal user,@Valid @RequestBody PrepareLeave input) { return service.prepareLeave(user,input); }
    @PostMapping("/certificate/prepare") CertificatePreview prepareCertificate(@AuthenticationPrincipal CampusPrincipal user,@Valid @RequestBody PrepareCertificate input) { return service.prepareCertificate(user,input); }
    @PostMapping("/repair/prepare") RepairPreview prepareRepair(@AuthenticationPrincipal CampusPrincipal user,@Valid @RequestBody PrepareRepair input) { return service.prepareRepair(user,input); }
    @PostMapping("/housing/prepare") HousingPreview prepareHousing(@AuthenticationPrincipal CampusPrincipal user,@Valid @RequestBody PrepareHousing input) { return service.prepareHousing(user,input); }
    @GetMapping("/guides/{slug}") Guide guide(@AuthenticationPrincipal CampusPrincipal user,@PathVariable String slug) { identities.requirePermission(user,"agent:query"); return guides.detail(slug); }
}
