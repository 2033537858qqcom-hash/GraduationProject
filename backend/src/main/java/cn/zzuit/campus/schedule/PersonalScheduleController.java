package cn.zzuit.campus.schedule;

import cn.zzuit.campus.identity.CampusPrincipal;
import java.time.LocalDate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/schedule")
public class PersonalScheduleController {
    private final PersonalScheduleService schedules;
    public PersonalScheduleController(PersonalScheduleService schedules) { this.schedules=schedules; }
    @GetMapping("/mine")
    public ResponseEntity<PersonalScheduleService.Schedule> mine(@AuthenticationPrincipal CampusPrincipal user,@RequestParam LocalDate from,@RequestParam LocalDate to) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(schedules.mine(user,from,to));
    }
}
