package cn.zzuit.campus.certificate;

import static cn.zzuit.campus.certificate.CertificateModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/certificates")
public class CertificateController {
    private final CertificateService service;
    public CertificateController(CertificateService service) { this.service=service; }
    @GetMapping("/mine/eligibility") Eligibility eligibility(@AuthenticationPrincipal CampusPrincipal u) { return service.eligibility(u); }
    @GetMapping("/{view}") Page<Request> list(@AuthenticationPrincipal CampusPrincipal u,@PathVariable String view,@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.list(u,view,status,page,pageSize); }
    @GetMapping("/{view}/{id}") Request detail(@AuthenticationPrincipal CampusPrincipal u,@PathVariable String view,@PathVariable long id) { return service.detail(u,view,id); }
    @GetMapping("/{view}/{id}/history") Page<Revision> history(@AuthenticationPrincipal CampusPrincipal u,@PathVariable String view,@PathVariable long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize) { return service.history(u,view,id,page,pageSize); }
    @PostMapping("/mine") Request create(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody Draft input) { return service.save(u,null,input); }
    @PutMapping("/mine/{id}") Request update(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody Draft input) { return service.save(u,id,input); }
    @PostMapping("/mine/{id}/{action}") Request transition(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@PathVariable String action,@Valid @RequestBody Action input) { return service.transition(u,id,action,input); }
    @PostMapping("/review/{id}/decision") Request decision(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody Decision input) { return service.decide(u,id,input); }
    @GetMapping("/{view}/{id}/result") ResponseEntity<byte[]> result(@AuthenticationPrincipal CampusPrincipal u,@PathVariable String view,@PathVariable long id) {
        var result=service.result(u,view,id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(result.name(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").body(result.bytes());
    }
}
