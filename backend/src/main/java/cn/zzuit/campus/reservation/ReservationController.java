package cn.zzuit.campus.reservation;

import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.reservation.ReservationDtos.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final VenueReservationService reservations;

    public ReservationController(VenueReservationService reservations) {
        this.reservations = reservations;
    }

    // ==================== 场地与设备资源目录 ====================

    @GetMapping("/venues")
    public List<VenueSummary> listVenues(
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String placeId,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false, defaultValue = "true") Boolean enabledOnly
    ) {
        return reservations.listVenues(category, placeId, keyword, enabledOnly);
    }

    @GetMapping("/venues/{id}")
    public VenueSummary getVenue(@PathVariable long id) {
        return reservations.getVenue(id);
    }

    @PutMapping("/venues/{id}")
    public Map<String, Object> updateVenue(
        @AuthenticationPrincipal CampusPrincipal user,
        @PathVariable long id,
        @RequestBody VenueUpdateCommand cmd
    ) {
        reservations.updateVenue(user, id, cmd);
        return Map.of("success", true, "message", "场地资源信息已更新");
    }

    @GetMapping("/venues/{id}/schedule")
    public List<TimeSlot> getVenueSchedule(
        @PathVariable long id,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return reservations.getVenueSchedule(id, date);
    }

    @PostMapping("/check-conflict")
    public ConflictResult checkConflict(@RequestBody CheckConflictCommand cmd) {
        return reservations.checkConflict(cmd);
    }

    // ==================== 我的借用申请 (学生/申请人) ====================

    @GetMapping("/mine")
    public ReservationPage listMine(
        @AuthenticationPrincipal CampusPrincipal user,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return reservations.listMine(user, status, page, pageSize);
    }

    @GetMapping("/{id}")
    public ReservationDetail getDetail(
        @AuthenticationPrincipal CampusPrincipal user,
        @PathVariable long id
    ) {
        return reservations.getDetail(user, id);
    }

    @PostMapping("/draft")
    public ReservationDetail saveDraft(
        @AuthenticationPrincipal CampusPrincipal user,
        @RequestBody CreateReservationCommand cmd
    ) {
        return reservations.saveDraft(user, cmd);
    }

    @PostMapping("/submit")
    public ReservationDetail submit(
        @AuthenticationPrincipal CampusPrincipal user,
        @RequestBody CreateReservationCommand cmd
    ) {
        return reservations.submit(user, cmd);
    }

    @PostMapping("/{id}/withdraw")
    public ReservationDetail withdraw(
        @AuthenticationPrincipal CampusPrincipal user,
        @PathVariable long id
    ) {
        return reservations.withdraw(user, id);
    }

    @PostMapping("/{id}/cancel")
    public ReservationDetail cancel(
        @AuthenticationPrincipal CampusPrincipal user,
        @PathVariable long id,
        @RequestBody(required = false) Map<String, String> body
    ) {
        String reason = body != null ? body.get("reason") : null;
        return reservations.cancel(user, id, reason);
    }

    // ==================== 借用审核 (管理人员/学生事务) ====================

    @GetMapping("/review")
    public ReservationPage listReview(
        @AuthenticationPrincipal CampusPrincipal user,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Long venueId,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize
    ) {
        return reservations.listReview(user, status, venueId, page, pageSize);
    }

    @PostMapping("/{id}/review")
    public ReservationDetail review(
        @AuthenticationPrincipal CampusPrincipal user,
        @PathVariable long id,
        @RequestBody ReviewReservationCommand cmd
    ) {
        return reservations.review(user, id, cmd);
    }
}
