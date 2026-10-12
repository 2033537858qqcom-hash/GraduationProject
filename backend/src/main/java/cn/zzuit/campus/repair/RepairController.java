package cn.zzuit.campus.repair;

import static cn.zzuit.campus.repair.RepairModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController @RequestMapping("/api/repairs")
public class RepairController {
    private final RepairService service;
    public RepairController(RepairService service) {this.service=service;}
    @GetMapping("/summary") Summary summary(@AuthenticationPrincipal CampusPrincipal u) {return service.summary(u);}
    @GetMapping("/{view:mine|dispatch|work}") Page<Item> list(@AuthenticationPrincipal CampusPrincipal u,@PathVariable String view,@RequestParam(required=false) String status,@RequestParam(required=false) Boolean cancellationPending,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {return service.list(u,view,status,cancellationPending,page,pageSize);}
    @PostMapping("/mine/{id}/cancellations") Detail cancellation(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody CancelAction i) {return service.cancel(u,"mine",id,"request",i);}
    @PostMapping("/mine/{id}/cancellations/current/withdraw") Detail withdrawCancellation(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody CancelAction i) {return service.cancel(u,"mine",id,"withdraw",i);}
    @PostMapping("/dispatch/{id}/cancellation/decide") Detail decideCancellation(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody CancelAction i) {return service.cancel(u,"dispatch",id,"decide",i);}
    @GetMapping("/dispatch/statistics") Statistics statistics(@AuthenticationPrincipal CampusPrincipal u,@RequestParam(required=false) java.time.LocalDate from,@RequestParam(required=false) java.time.LocalDate to) {return service.statistics(u,from,to);}
    @GetMapping("/{view:mine|dispatch|work}/{id}") Detail detail(@AuthenticationPrincipal CampusPrincipal u,@PathVariable String view,@PathVariable long id) {return service.detail(u,view,id);}
    @PostMapping("/mine") Detail create(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody Draft i) {return service.save(u,null,i);}
    @PutMapping("/mine/{id}") Detail save(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody Draft i) {return service.save(u,id,i);}
    @PostMapping("/{view:mine|dispatch|work}/{id}/{action:submit|withdraw|return|reject|assign|reassign|accept|progress|unable|complete|confirm|reopen|evaluation|priority}") Detail transition(@AuthenticationPrincipal CampusPrincipal u,@PathVariable String view,@PathVariable long id,@PathVariable String action,@Valid @RequestBody Action i) {return service.transition(u,view,id,action,i);}
    @GetMapping("/dispatch/{id}/technicians") List<Technician> technicians(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id) {return service.technicians(u,id);}
    @GetMapping("/{view:mine|dispatch|work}/{id}/history") Page<History> history(@AuthenticationPrincipal CampusPrincipal u,@PathVariable String view,@PathVariable long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize) {return service.history(u,view,id,page,pageSize);}
    @PostMapping("/{view:mine|dispatch|work}/{id}/attachments") Detail upload(@AuthenticationPrincipal CampusPrincipal u,@PathVariable String view,@PathVariable long id,@Valid @ModelAttribute Upload i,@RequestParam MultipartFile file) throws java.io.IOException {return service.upload(u,view,id,i,file);}
    @PostMapping("/{view:mine|dispatch|work}/{id}/attachments/{image}/remove") Detail remove(@AuthenticationPrincipal CampusPrincipal u,@PathVariable String view,@PathVariable long id,@PathVariable long image,@Valid @RequestBody Action i) {return service.remove(u,view,id,image,i);}
    @GetMapping("/{view:mine|dispatch|work}/{id}/attachments/{image}") ResponseEntity<byte[]> download(@AuthenticationPrincipal CampusPrincipal u,@PathVariable String view,@PathVariable long id,@PathVariable long image) {
        var file=service.download(u,view,id,image);return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.type())).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").body(file.bytes());
    }
}
