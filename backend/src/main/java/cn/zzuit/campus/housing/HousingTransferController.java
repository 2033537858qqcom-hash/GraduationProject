package cn.zzuit.campus.housing;

import static cn.zzuit.campus.housing.HousingTransferModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class HousingTransferController {
    private final HousingTransferService service;
    public HousingTransferController(HousingTransferService service) {this.service=service;}
    @GetMapping("/api/housing/transfers/mine") List<Transfer> mine(@AuthenticationPrincipal CampusPrincipal u) {return service.mine(u);}
    @GetMapping("/api/housing/transfers/mine/{id}") Transfer mineDetail(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id) {return service.mineDetail(u,id);}
    @PostMapping("/api/housing/transfers/mine") Transfer create(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody DraftInput i) {return service.save(u,null,i);}
    @PutMapping("/api/housing/transfers/mine/{id}") Transfer update(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody DraftInput i) {return service.save(u,id,i);}
    @PostMapping("/api/housing/transfers/mine/{id}/delete") void delete(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody ActionInput i) {service.deleteDraft(u,id,i);}
    @PostMapping("/api/housing/transfers/mine/{id}/submit") Transfer submit(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody ActionInput i) {return service.submit(u,id,i);}
    @PostMapping("/api/housing/transfers/mine/{id}/withdraw") Transfer withdraw(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody ActionInput i) {return service.withdraw(u,id,i);}
    @PostMapping("/api/housing/transfers/mine/{id}/supplement") Transfer supplement(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody DraftInput i) {return service.supplement(u,id,i);}
    @PostMapping("/api/housing/transfers/mine/{id}/cancel-request") Transfer cancelRequest(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody ActionInput i) {return service.requestCancel(u,id,i);}
    @GetMapping("/api/housing/transfers/review") List<Transfer> review(@AuthenticationPrincipal CampusPrincipal u,@RequestParam(defaultValue="") String status) {return service.review(u,status);}
    @GetMapping("/api/housing/transfers/review/{id}") Transfer reviewDetail(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id) {return service.reviewDetail(u,id);}
    @PostMapping("/api/housing/transfers/review/{id}/{action}") Transfer decide(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@PathVariable String action,@Valid @RequestBody ActionInput i) {return service.decide(u,id,action,i);}
    @GetMapping("/api/housing/transfers/assign") List<Transfer> assign(@AuthenticationPrincipal CampusPrincipal u,@RequestParam String queue) {return service.assignQueue(u,queue);}
    @GetMapping("/api/housing/transfers/assign/{id}") Transfer assignDetail(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id) {return service.assignDetail(u,id);}
    @PostMapping("/api/housing/transfers/assign/{id}/reserve") Transfer reserve(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody ReserveInput i) {return service.reserve(u,id,i);}
    @PostMapping("/api/housing/transfers/assign/{id}/check-in") Transfer checkIn(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody ActionInput i) {return service.checkIn(u,id,i);}
    @PostMapping("/api/housing/transfers/assign/{id}/release") Transfer release(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody ActionInput i) {return service.release(u,id,i);}
    @PostMapping("/api/housing/transfers/assign/{id}/cancel") Transfer cancel(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody ActionInput i) {return service.acceptCancel(u,id,i);}
    @PostMapping("/api/housing/transfers/assign/{id}/keep") Transfer keep(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody ActionInput i) {return service.keep(u,id,i);}
    @PostMapping("/api/housing/transfers/assign/{id}/extend") Transfer extend(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody ExtendInput i) {return service.extend(u,id,i);}
}
