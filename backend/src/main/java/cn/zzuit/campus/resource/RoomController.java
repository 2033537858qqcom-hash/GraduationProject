package cn.zzuit.campus.resource;

import static cn.zzuit.campus.resource.RoomModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/resources")
public class RoomController {
    private final RoomService service;
    public RoomController(RoomService service) { this.service=service; }
    @GetMapping Page<Room> list(@AuthenticationPrincipal CampusPrincipal user,@RequestParam(defaultValue="") String q,
        @RequestParam(required=false) Long campusId,@RequestParam(required=false) String type,@RequestParam(required=false) Boolean enabled,
        @RequestParam(required=false) Boolean ready,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return service.list(user,q,campusId,type,enabled,ready,page,pageSize);
    }
    @GetMapping("/catalog") Catalog catalog(@AuthenticationPrincipal CampusPrincipal user) { return service.catalog(user); }
    @GetMapping("/{id}") Room detail(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id) { return service.detail(user,id); }
    @PostMapping Room create(@AuthenticationPrincipal CampusPrincipal user,@Valid @RequestBody RoomInput input) { return service.create(user,input); }
    @PutMapping("/{id}") Room update(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@Valid @RequestBody RoomInput input) { return service.update(user,id,input); }
    @PostMapping("/{id}/state") Room state(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,@Valid @RequestBody StateInput input) { return service.state(user,id,input); }
    @GetMapping("/{id}/history") Page<Audit> history(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.history(user,id,page,pageSize); }
    @GetMapping("/{id}/occupancy") Page<Occupancy> occupancy(@AuthenticationPrincipal CampusPrincipal user,@PathVariable long id,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) { return service.occupancy(user,id,page,pageSize); }
}
