package cn.zzuit.campus.teaching;

import static cn.zzuit.campus.teaching.TeachingModels.*;
import cn.zzuit.campus.identity.CampusPrincipal;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class TeachingController {
    private final TeachingService teaching;
    private final AcademicService academic;
    public TeachingController(TeachingService teaching,AcademicService academic) { this.teaching=teaching; this.academic=academic; }
    @GetMapping("/learning/terms") List<Term> terms(@AuthenticationPrincipal CampusPrincipal u) { return teaching.terms(u); }
    @GetMapping("/learning/classes") List<ClassInfo> studentClasses(@AuthenticationPrincipal CampusPrincipal u,@RequestParam long termId) { return teaching.classes(u,termId,false); }
    @GetMapping("/learning/classes/{id}") ClassDetail studentDetail(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id) { return teaching.detail(u,id,"student"); }
    @GetMapping("/learning/timetable") List<Lesson> studentTimetable(@AuthenticationPrincipal CampusPrincipal u,@RequestParam long termId,@RequestParam(required=false) LocalDate from) { return teaching.timetable(u,termId,from,false); }
    @GetMapping("/learning/grades") List<Grade> grades(@AuthenticationPrincipal CampusPrincipal u,@RequestParam long termId) { return teaching.studentGrades(u,termId); }
    @GetMapping("/learning/exams") List<Exam> exams(@AuthenticationPrincipal CampusPrincipal u,@RequestParam long termId) { return teaching.studentExams(u,termId); }
    @GetMapping("/teaching/classes") List<ClassInfo> teacherClasses(@AuthenticationPrincipal CampusPrincipal u,@RequestParam long termId) { return teaching.classes(u,termId,true); }
    @GetMapping("/teaching/classes/{id}") ClassDetail teacherDetail(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id) { return teaching.detail(u,id,"teacher"); }
    @GetMapping("/teaching/timetable") List<Lesson> teacherTimetable(@AuthenticationPrincipal CampusPrincipal u,@RequestParam long termId,@RequestParam(required=false) LocalDate from) { return teaching.timetable(u,termId,from,true); }
    @PostMapping("/teaching/classes/{id}/materials") Material createMaterial(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody MaterialInput input) { return teaching.saveMaterial(u,id,null,input); }
    @PutMapping("/teaching/classes/{classId}/materials/{id}") Material editMaterial(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long classId,@PathVariable long id,@Valid @RequestBody MaterialInput input) { return teaching.saveMaterial(u,classId,id,input); }
    @PostMapping("/teaching/materials/{id}/state") Material materialState(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody Transition input) { return teaching.materialState(u,id,input); }
    @PostMapping("/teaching/materials/{id}/file") Material file(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@RequestParam int version,@RequestParam MultipartFile file) throws java.io.IOException { return teaching.attach(u,id,version,file); }
    @GetMapping("/teaching-files/{id}") ResponseEntity<byte[]> download(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id) {
        var file=teaching.download(u,id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE,file.type())
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name(),StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(file.bytes());
    }
    @PostMapping("/teaching/classes/{id}/notices") Notice notice(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody NoticeInput input) { return teaching.postNotice(u,id,input); }
    @GetMapping("/academic/catalog") Catalog catalog(@AuthenticationPrincipal CampusPrincipal u) { return academic.catalog(u); }
    @GetMapping("/academic/classes/{id}") ClassDetail academicDetail(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id) { return teaching.detail(u,id,"academic"); }
    @PostMapping("/academic/terms") Term createTerm(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody TermInput input) { return academic.createTerm(u,input); }
    @PostMapping("/academic/courses") Course createCourse(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody CourseInput input) { return academic.createCourse(u,input); }
    @PostMapping("/academic/classes") ClassDetail createClass(@AuthenticationPrincipal CampusPrincipal u,@Valid @RequestBody ClassInput input) { return academic.createClass(u,input); }
    @PostMapping("/academic/classes/{id}/members") ClassDetail member(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody RelationInput input) { return academic.relation(u,id,input,false); }
    @PostMapping("/academic/classes/{id}/instructors") ClassDetail instructor(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody RelationInput input) { return academic.relation(u,id,input,true); }
    @PostMapping("/academic/classes/{id}/lessons") ClassDetail lesson(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody LessonInput input) { return academic.addLesson(u,id,input); }
    @PostMapping("/academic/classes/{id}/grades/import") ImportResult importGrades(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody GradeImport input) { return academic.importGrades(u,id,input); }
    @PostMapping("/academic/grades/{id}/state") Grade gradeState(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody Transition input) { return academic.gradeState(u,id,input); }
    @PutMapping("/academic/classes/{id}/exam") Exam saveExam(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody ExamInput input) { return academic.saveExam(u,id,input); }
    @PostMapping("/academic/exams/{id}/state") Exam examState(@AuthenticationPrincipal CampusPrincipal u,@PathVariable long id,@Valid @RequestBody Transition input) { return academic.examState(u,id,input); }
}
