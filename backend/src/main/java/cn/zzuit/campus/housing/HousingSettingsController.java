package cn.zzuit.campus.housing;

import static cn.zzuit.campus.housing.HousingModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class HousingSettingsController {
    private final HousingSettingsService service;
    public HousingSettingsController(HousingSettingsService service) {this.service=service;}
    @GetMapping("/api/admin/housing/catalog") Catalog catalog(@AuthenticationPrincipal CampusPrincipal u) {return service.catalog(u);}
    @GetMapping("/api/admin/housing/buildings") Page<Building> buildings(@AuthenticationPrincipal CampusPrincipal u,@RequestParam(required=false) Long campusId,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {return service.buildings(u,campusId,q,page,pageSize);}
    @PostMapping("/api/admin/housing/buildings") Building createBuilding(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody BuildingInput i) {return service.createBuilding(u,i);}
    @PutMapping("/api/admin/housing/buildings/{id}") Building updateBuilding(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody BuildingInput i) {return service.updateBuilding(u,id,i);}
    @PostMapping("/api/admin/housing/buildings/{id}/state") Building buildingState(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody StateInput i) {return service.buildingState(u,id,i);}
    @GetMapping("/api/admin/housing/rooms") Page<Room> rooms(@AuthenticationPrincipal CampusPrincipal u,@RequestParam long buildingId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {return service.rooms(u,buildingId,page,pageSize);}
    @PostMapping("/api/admin/housing/rooms") Room createRoom(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody RoomInput i) {return service.createRoom(u,i);}
    @PutMapping("/api/admin/housing/rooms/{id}") Room updateRoom(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody RoomInput i) {return service.updateRoom(u,id,i);}
    @PostMapping("/api/admin/housing/rooms/{id}/state") Room roomState(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody StateInput i) {return service.roomState(u,id,i);}
    @GetMapping("/api/admin/housing/beds") Page<Bed> beds(@AuthenticationPrincipal CampusPrincipal u,@RequestParam long roomId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int pageSize) {return service.beds(u,roomId,page,pageSize);}
    @PostMapping("/api/admin/housing/beds") Bed createBed(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody BedInput i) {return service.createBed(u,i);}
    @PutMapping("/api/admin/housing/beds/{id}") Bed updateBed(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody BedInput i) {return service.updateBed(u,id,i);}
    @PostMapping("/api/admin/housing/beds/{id}/state") Bed bedState(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody StateInput i) {return service.bedState(u,id,i);}
    @PostMapping("/api/admin/housing/beds/{id}/maintenance") Bed maintenance(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody MaintenanceInput i) {return service.maintenance(u,id,i);}
    @GetMapping("/api/admin/housing/scopes") Page<Scope> scopes(@AuthenticationPrincipal CampusPrincipal u,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {return service.scopes(u,page,pageSize);}
    @PostMapping("/api/admin/housing/scopes") Scope createScope(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody ScopeInput i) {return service.createScope(u,i);}
    @PostMapping("/api/admin/housing/scopes/{id}/state") Scope scopeState(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody StateInput i) {return service.scopeState(u,id,i);}
    @GetMapping("/api/admin/housing/assignments") Page<Assignment> assignments(@AuthenticationPrincipal CampusPrincipal u,@RequestParam long buildingId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {return service.assignments(u,buildingId,page,pageSize);}
    @GetMapping("/api/admin/housing/history") Page<Audit> history(@AuthenticationPrincipal CampusPrincipal u,@RequestParam String type,@RequestParam long id,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {return service.history(u,type,id,page,pageSize);}
}
