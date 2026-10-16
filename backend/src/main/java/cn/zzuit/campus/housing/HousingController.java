package cn.zzuit.campus.housing;

import static cn.zzuit.campus.housing.HousingModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class HousingController {
    private final HousingService service;
    public HousingController(HousingService service) {this.service=service;}
    @GetMapping("/api/housing/mine") Mine mine(@AuthenticationPrincipal CampusPrincipal u) {return service.mine(u);}
    @GetMapping("/api/housing/responsibilities") Responsibilities responsibilities(@AuthenticationPrincipal CampusPrincipal u) {return service.responsibilities(u);}
    @GetMapping("/api/housing/beds") Page<Bed> beds(@AuthenticationPrincipal CampusPrincipal u,@RequestParam long buildingId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="100") int pageSize) {return service.beds(u,buildingId,page,pageSize);}
    @GetMapping("/api/housing/history") Page<Assignment> history(@AuthenticationPrincipal CampusPrincipal u,@RequestParam long buildingId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {return service.history(u,buildingId,page,pageSize);}
    @GetMapping("/api/housing/students") StudentMatch student(@AuthenticationPrincipal CampusPrincipal u,@RequestParam(defaultValue="") String username) {return service.student(u,username);}
    @PostMapping("/api/housing/assignments") Assignment checkIn(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody CheckIn i) {return service.checkIn(u,i);}
    @PostMapping("/api/housing/assignments/{id}/checkout") Assignment checkOut(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody CheckOut i) {return service.checkOut(u,id,i);}
}
