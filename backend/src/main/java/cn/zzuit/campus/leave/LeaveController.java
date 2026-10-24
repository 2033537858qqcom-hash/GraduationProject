package cn.zzuit.campus.leave;

import static cn.zzuit.campus.leave.LeaveModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class LeaveController {
    private final LeaveService leaves;
    public LeaveController(LeaveService leaves) { this.leaves=leaves; }
    @GetMapping("/leave/mine/eligibility") Eligibility eligibility(@AuthenticationPrincipal CampusPrincipal u) { return leaves.eligibility(u); }
    @GetMapping("/leave/mine/calendar") List<Event> calendar(@AuthenticationPrincipal CampusPrincipal u,@RequestParam LocalDate from,@RequestParam LocalDate to) { return leaves.calendar(u,from,to); }
    @PostMapping("/leave/mine/{id}/return") Request reportReturn(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody Action input) { return leaves.reportReturn(u,id,input); }
    @PostMapping("/leave/review/{id}/close") Request close(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody ClosureDecision input) { return leaves.close(u,id,input); }
    @GetMapping("/leave/{view}") Page<Request> list(@AuthenticationPrincipal CampusPrincipal u,@PathVariable String view,@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return leaves.list(u,view,status,page,pageSize); }
    @GetMapping("/leave/{view}/{id}") Request detail(@AuthenticationPrincipal CampusPrincipal u,@PathVariable String view,@PathVariable long id) { return leaves.detail(u,view,id); }
    @GetMapping("/leave/{view}/{id}/history") Page<Revision> history(@AuthenticationPrincipal CampusPrincipal u,@PathVariable String view,@PathVariable long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize) { return leaves.history(u,view,id,page,pageSize); }
    @PostMapping("/leave/mine") Request create(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody Draft input) { return leaves.save(u,null,input); }
    @PutMapping("/leave/mine/{id}") Request update(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody Draft input) { return leaves.save(u,id,input); }
    @PostMapping("/leave/mine/{id}/{action}") Request transition(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@PathVariable String action,@Valid @RequestBody Action input) { return leaves.transition(u,id,action,input); }
    @PostMapping("/leave/review/{id}/decision") Request decision(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody Decision input) { return leaves.decide(u,id,input); }
    @PostMapping("/leave/mine/{id}/file") Request file(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@RequestParam int version,@RequestParam MultipartFile file) throws java.io.IOException { return leaves.attach(u,id,version,file); }
    @GetMapping("/leave/files/{id}") ResponseEntity<byte[]> download(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@RequestParam String view) {
        var file=leaves.download(u,view,id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(file.type()))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").body(file.bytes());
    }
    @GetMapping("/admin/counselors") AssignmentCatalog assignments(@AuthenticationPrincipal CampusPrincipal u) { return leaves.assignments(u); }
    @PutMapping("/admin/counselors/{classId}") Assignment assign(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long classId,@Valid @RequestBody Assign input) { return leaves.assign(u,classId,input); }
}
