package cn.zzuit.campus.teaching;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class TeachingModels {
    private TeachingModels() {}
    public record Term(long id, String name, LocalDate startsOn, LocalDate endsOn) {}
    public record Course(long id, String code, String name, BigDecimal credits) {}
    public record Person(long id, String fullName, String identityNumber, String className) {}
    public record ClassInfo(long id, long termId, long courseId, String name, String courseName,
                            BigDecimal credits, String departmentName, long memberCount) {}
    public record Lesson(long id, long classId, String className, String courseName, String teacherName,
                         String classroom, String campusName, LocalDateTime startsAt, LocalDateTime endsAt) {}
    public record Material(long id, long classId, long authorId, String title, String body, String status, int version,
                           String authorName, LocalDateTime updatedAt, String fileName) {}
    public record Notice(long id, String title, String body, String authorName, LocalDateTime createdAt) {}
    public record Grade(long id, long classId, long studentId, String studentName, String courseName,
                        BigDecimal credits, BigDecimal score, String status, int version, LocalDateTime updatedAt) {}
    public record Exam(long id, long classId, String courseName, long campusId, String campusName,
                       String classroom, LocalDateTime startsAt, LocalDateTime endsAt, String status,
                       int version, LocalDateTime updatedAt, Long roomId) {}
    public record ClassDetail(ClassInfo info, List<Person> instructors, List<Person> members,
                              List<Material> materials, List<Notice> notices, List<Grade> grades, List<Exam> exams) {}
    public record Catalog(List<Term> terms, List<Course> courses, List<ClassInfo> classes,
                          List<Person> teachers, List<Person> students, List<Campus> campuses,
                          List<cn.zzuit.campus.resource.RoomModels.Room> rooms) {}
    public record Campus(long id, String name) {}
    public record Attachment(String name, String type, byte[] bytes) {}
    public record TermInput(@NotBlank @Size(max=100) String name, @NotNull LocalDate startsOn, @NotNull LocalDate endsOn) {}
    public record CourseInput(@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{1,40}") String code,
                              @NotBlank @Size(max=100) String name,
                              @NotNull @DecimalMin("0.1") @DecimalMax("30") BigDecimal credits) {}
    public record ClassInput(@Positive long termId, @Positive long courseId, @NotBlank @Size(max=100) String name,
                             @NotEmpty @Size(max=20) List<@Positive Long> teacherIds,
                             @NotEmpty @Size(max=500) List<@Positive Long> studentIds) {}
    public record RelationInput(@Positive long userId, @NotNull Boolean active) {}
    public record LessonInput(@Positive long teacherId, @Positive long roomId,
                              @NotNull LocalDateTime startsAt, @NotNull LocalDateTime endsAt) {}
    public record MaterialInput(@NotBlank @Size(max=160) String title, @NotBlank @Size(max=20000) String body,
                                @Min(0) int version) {}
    public record Transition(@NotBlank @Pattern(regexp="DRAFT|PUBLISHED|WITHDRAWN") String status, @Min(0) int version) {}
    public record NoticeInput(@NotBlank @Size(max=160) String title, @NotBlank @Size(max=10000) String body,
                              @NotBlank @Pattern(regexp="[A-Za-z0-9-]{1,64}") String requestKey) {}
    public record GradeInput(@Positive long studentId, @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal score,
                             @Min(0) int version) {}
    public record GradeImport(@NotEmpty @Size(max=500) List<@Valid GradeInput> rows, boolean preview) {}
    public record ImportResult(int count, boolean preview) {}
    public record ExamInput(@Positive long roomId,
                            @NotNull LocalDateTime startsAt, @NotNull LocalDateTime endsAt, @Min(0) int version) {}
}
