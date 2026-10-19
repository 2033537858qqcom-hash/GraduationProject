package cn.zzuit.campus.reservation;

import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.reservation.ReservationDtos.*;
import java.time.LocalDate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final VenueReservationService reservations;
    private final ReservationPassPdf passPdf;

    public ReservationController(VenueReservationService reservations, ReservationPassPdf passPdf) {
        this.reservations = reservations;
        this.passPdf = passPdf;
    }

    @GetMapping("/{id}/pass")
    public ResponseEntity<byte[]> pass(@AuthenticationPrincipal CampusPrincipal user, @PathVariable long id,
                                      @RequestParam(defaultValue = "false") boolean inline) throws IOException {
        var detail = reservations.getPassDetail(user, id);
        var disposition = inline ? ContentDisposition.inline() : ContentDisposition.attachment();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename("场地借用凭证-" + id + ".pdf", StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff").body(passPdf.generate(detail));
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

    @GetMapping("/venues/manage")
    public List<VenueManagementItem> listVenuesForManagement(
        @AuthenticationPrincipal CampusPrincipal user
    ) {
        return reservations.listVenuesForManagement(user);
    }

    @GetMapping("/reviewers/candidates")
    public List<VenueManagerItem> getReviewerCandidates(
        @AuthenticationPrincipal CampusPrincipal user
    ) {
        return reservations.getReviewerCandidates(user);
    }

    @PostMapping("/venues/{id}/managers")
    public Map<String, Object> addVenueManager(
        @AuthenticationPrincipal CampusPrincipal user,
        @PathVariable long id,
        @RequestBody AssignManagerCommand cmd
    ) {
        reservations.addVenueManager(user, id, cmd.userId());
        return Map.of("success", true, "message", "已指定场地责任审批人");
    }

    @DeleteMapping("/venues/{id}/managers/{userId}")
    public Map<String, Object> removeVenueManager(
        @AuthenticationPrincipal CampusPrincipal user,
        @PathVariable long id,
        @PathVariable long userId
    ) {
        reservations.removeVenueManager(user, id, userId);
        return Map.of("success", true, "message", "已移除场地责任审批人");
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
