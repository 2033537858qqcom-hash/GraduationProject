package cn.zzuit.campus.teaching;

import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TimetablePdfController {
    private final TeachingService teaching;
    private final TimetableGridPdf gridPdf;

    public TimetablePdfController(TeachingService teaching, TimetableGridPdf gridPdf) {
        this.teaching = teaching;
        this.gridPdf = gridPdf;
    }

    @GetMapping("/learning/timetable/export")
    ResponseEntity<byte[]> student(
            @AuthenticationPrincipal CampusPrincipal user,
            @RequestParam long termId,
            @RequestParam LocalDate from)
            throws IOException {
        return export(user, termId, from, false);
    }

    @GetMapping("/teaching/timetable/export")
    ResponseEntity<byte[]> teacher(
            @AuthenticationPrincipal CampusPrincipal user,
            @RequestParam long termId,
            @RequestParam LocalDate from)
            throws IOException {
        return export(user, termId, from, true);
    }

    private ResponseEntity<byte[]> export(CampusPrincipal user, long termId, LocalDate from, boolean teacher)
            throws IOException {
        teaching.permission(user, teacher ? "teaching:manage" : "learning:read");
        var term = teaching.terms(user).stream()
                .filter(t -> t.id() == termId)
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TERM_NOT_FOUND", "学期不存在"));
        var firstMonday = term.startsOn().minusDays(term.startsOn().getDayOfWeek().getValue() - 1);
        if (from.getDayOfWeek().getValue() != 1 || from.isBefore(firstMonday) || from.isAfter(term.endsOn())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEEK", "请选择该学期内以周一开始的一周");
        }
        var lessons = teaching.timetable(user, termId, from, teacher);
        String subtitle = term.name() + " · " + from + " 至 " + from.plusDays(6) + " · 账号：" + user.username();
        var bytes = gridPdf.generate(teacher ? "每周授课安排" : "每周课程表", subtitle, from, lessons, teacher);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("每周课表-" + from + ".pdf", StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(bytes);
    }
}
