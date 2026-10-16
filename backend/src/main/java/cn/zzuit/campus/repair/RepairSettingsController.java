package cn.zzuit.campus.repair;

import static cn.zzuit.campus.repair.RepairSettingsModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class RepairSettingsController {
    private final RepairSettingsService service;
    public RepairSettingsController(RepairSettingsService service) {this.service=service;}
    @GetMapping("/api/repairs/catalog") PublicCatalog publicCatalog(@AuthenticationPrincipal CampusPrincipal u) {return service.publicCatalog(u);}
    @GetMapping("/api/repairs/my-dorm") DormEligibility myDorm(@AuthenticationPrincipal CampusPrincipal u) {return service.myDorm(u);}
    @GetMapping("/api/repairs/locations/{id}") PublicLocation publicLocation(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id) {return service.publicLocation(u,id);}
    @GetMapping("/api/repairs/locations") Page<PublicLocation> publicLocations(@AuthenticationPrincipal CampusPrincipal u,@RequestParam(required=false) Long campusId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {return service.publicLocations(u,campusId,page,pageSize);}
    @GetMapping("/api/repairs/responsibilities") Responsibilities responsibilities(@AuthenticationPrincipal CampusPrincipal u) {return service.responsibilities(u);}
    @GetMapping("/api/admin/repair-settings/catalog") Catalog catalog(@AuthenticationPrincipal CampusPrincipal u) {return service.catalog(u);}
    @GetMapping("/api/admin/repair-settings/locations") Page<Location> locations(@AuthenticationPrincipal CampusPrincipal u,@RequestParam(required=false) Long campusId,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {return service.locations(u,campusId,q,page,pageSize);}
    @PostMapping("/api/admin/repair-settings/locations") Location createLocation(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody LocationInput i) {return service.createLocation(u,i);}
    @PutMapping("/api/admin/repair-settings/locations/{id}") Location updateLocation(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody LocationInput i) {return service.updateLocation(u,id,i);}
    @PostMapping("/api/admin/repair-settings/locations/{id}/state") Location locationState(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody StateInput i) {return service.locationState(u,id,i);}
    @GetMapping("/api/admin/repair-settings/scopes") Page<Scope> scopes(@AuthenticationPrincipal CampusPrincipal u,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {return service.scopes(u,page,pageSize);}
    @PostMapping("/api/admin/repair-settings/scopes") Scope createScope(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody ScopeInput i) {return service.createScope(u,i);}
    @PutMapping("/api/admin/repair-settings/scopes/{id}") Scope updateScope(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody ScopeInput i) {return service.updateScope(u,id,i);}
    @PostMapping("/api/admin/repair-settings/scopes/{id}/state") Scope scopeState(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody StateInput i) {return service.scopeState(u,id,i);}
    @GetMapping("/api/admin/repair-settings/history") Page<Audit> history(@AuthenticationPrincipal CampusPrincipal u,@RequestParam String type,@RequestParam long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {return service.history(u,type,id,page,pageSize);}
}
