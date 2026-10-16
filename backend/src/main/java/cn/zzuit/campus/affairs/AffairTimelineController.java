package cn.zzuit.campus.affairs;

import cn.zzuit.campus.identity.CampusPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class AffairTimelineController {
    private final AffairTimelineService timelines;
    public AffairTimelineController(AffairTimelineService timelines) {this.timelines=timelines;}
    @GetMapping("/api/affairs/mine/{kind}/{id}/timeline")
    ResponseEntity<AffairTimelineService.Timeline> mine(@AuthenticationPrincipal CampusPrincipal user,@PathVariable String kind,@PathVariable long id,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="10") int pageSize,@RequestParam(required=false) Integer expectedVersion) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(timelines.mine(user,kind,id,page,pageSize,expectedVersion));
    }
}
