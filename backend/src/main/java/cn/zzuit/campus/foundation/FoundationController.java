package cn.zzuit.campus.foundation;

import static cn.zzuit.campus.foundation.FoundationModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/foundation")
public class FoundationController {
    private final FoundationService service;
    public FoundationController(FoundationService service) { this.service=service; }
    @GetMapping("/catalog") Catalog catalog(@AuthenticationPrincipal CampusPrincipal u) { return service.catalog(u); }
    @GetMapping("/users") Page<Account> users(@AuthenticationPrincipal CampusPrincipal u,@RequestParam(defaultValue="") String q,
        @RequestParam(required=false) String role,@RequestParam(required=false) Long departmentId,@RequestParam(required=false) Boolean enabled,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.users(u,q,role,departmentId,enabled,page,pageSize); }
    @PostMapping("/users") Account create(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody NewAccount input) { return service.create(u,input); }
    @PutMapping("/users/{id}") Account update(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody AccountUpdate input) { return service.update(u,id,input); }
    @PostMapping("/users/{id}/state") Account state(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody StateChange input) { return service.state(u,id,input); }
    @PostMapping("/departments") Department department(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody DepartmentInput input) { return service.department(u,null,input); }
    @PutMapping("/departments/{id}") Department department(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody DepartmentInput input) { return service.department(u,id,input); }
    @PostMapping("/majors") Major major(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody MajorInput input) { return service.major(u,null,input); }
    @PutMapping("/majors/{id}") Major major(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody MajorInput input) { return service.major(u,id,input); }
    @PostMapping("/class-groups") ClassGroup group(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody ClassGroupInput input) { return service.group(u,null,input); }
    @PutMapping("/class-groups/{id}") ClassGroup group(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody ClassGroupInput input) { return service.group(u,id,input); }
    @GetMapping("/audit") Page<Audit> audit(@AuthenticationPrincipal CampusPrincipal u,@RequestParam(required=false) String entityType,@RequestParam(required=false) Long entityId,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.audits(u,entityType,entityId,page,pageSize); }
}
