package cn.zzuit.campus.housing;

import static cn.zzuit.campus.housing.HousingImportModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/housing/imports")
public class HousingImportController {
    private final HousingImportService service;
    public HousingImportController(HousingImportService service) {this.service=service;}
    @PostMapping("/preview") Batch preview(@AuthenticationPrincipal CampusPrincipal user,@Valid @RequestBody Input input) {return service.preview(user,input);}
    @PostMapping("/{token}/confirm") Batch confirm(@AuthenticationPrincipal CampusPrincipal user,@PathVariable String token) {return service.confirm(user,token);}
    @GetMapping("/{token}") Batch receipt(@AuthenticationPrincipal CampusPrincipal user,@PathVariable String token) {return service.receipt(user,token);}
    @GetMapping List<Summary> recent(@AuthenticationPrincipal CampusPrincipal user,@RequestParam long buildingId) {return service.recent(user,buildingId);}
    @GetMapping("/template") ResponseEntity<byte[]> template(@AuthenticationPrincipal CampusPrincipal user,@RequestParam long buildingId) {
        return ResponseEntity.ok().contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("住宿入住模板.csv",StandardCharsets.UTF_8).build().toString()).body(service.template(user,buildingId));
    }
}
