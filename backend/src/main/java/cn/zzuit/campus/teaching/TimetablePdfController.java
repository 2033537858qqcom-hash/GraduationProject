package cn.zzuit.campus.teaching;

import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.common.CampusReportPdf;
import cn.zzuit.campus.common.CampusReportPdf.Section;
import cn.zzuit.campus.identity.CampusPrincipal;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TimetablePdfController {
    private final TeachingService teaching;
    private final CampusReportPdf pdf;
    public TimetablePdfController(TeachingService teaching, CampusReportPdf pdf) { this.teaching = teaching; this.pdf = pdf; }

    @GetMapping("/learning/timetable/export")
    ResponseEntity<byte[]> student(@AuthenticationPrincipal CampusPrincipal user, @RequestParam long termId, @RequestParam LocalDate from) throws IOException {
        return export(user, termId, from, false);
    }
    @GetMapping("/teaching/timetable/export")
    ResponseEntity<byte[]> teacher(@AuthenticationPrincipal CampusPrincipal user, @RequestParam long termId, @RequestParam LocalDate from) throws IOException {
        return export(user, termId, from, true);
    }
    private ResponseEntity<byte[]> export(CampusPrincipal user, long termId, LocalDate from, boolean teacher) throws IOException {
        teaching.permission(user, teacher ? "teaching:manage" : "learning:read");
        var term = teaching.terms(user).stream().filter(t -> t.id() == termId).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TERM_NOT_FOUND", "学期不存在"));
        var firstMonday = term.startsOn().minusDays(term.startsOn().getDayOfWeek().getValue() - 1);
        if (from.getDayOfWeek().getValue() != 1 || from.isBefore(firstMonday) || from.isAfter(term.endsOn())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_WEEK", "请选择该学期内以周一开始的一周");
        }
        var lessons = teaching.timetable(user, termId, from, teacher);
        var sections = new ArrayList<Section>();
        var clock = DateTimeFormatter.ofPattern("HH:mm");
        String[] weekdays = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        for (int i = 0; i < 7; i++) {
            var day = from.plusDays(i);
            var lines = new ArrayList<String>();
            for (var lesson : lessons) if (lesson.startsAt().toLocalDate().equals(day)) {
                lines.add(clock.format(lesson.startsAt()) + " - " + (lesson.endsAt().toLocalDate().equals(day) ? clock.format(lesson.endsAt()) : lesson.endsAt().toString().replace('T', ' ')) + "  " + lesson.courseName()
                    + "\n" + lesson.campusName() + " · " + lesson.classroom() + " · " + lesson.teacherName() + " · " + lesson.className());
            }
            sections.add(new Section(weekdays[i] + "  " + day, lines.isEmpty() ? "本日暂无课程安排" : String.join("\n", lines)));
        }
        sections.add(new Section("说明", "仅包含当前账号可见的课程；排课变更以系统最新信息为准。"));
        var bytes = pdf.generate(teacher ? "每周授课安排" : "每周课程表", term.name() + "\n" + from + " 至 " + from.plusDays(6) + " · 账号：" + user.username(), sections);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("每周课表-" + from + ".pdf", StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store").header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}
