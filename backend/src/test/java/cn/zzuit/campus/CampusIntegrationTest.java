package cn.zzuit.campus;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:campus-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=true"
})
class CampusIntegrationTest {
    @LocalServerPort int port;
    private final JsonMapper json = JsonMapper.builder().build();
    @org.springframework.beans.factory.annotation.Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @Test
    void teachingClassesAndPublishedRecordsAreScopedToTheStudent() throws Exception {
        Browser first=signedIn("20260001"), second=signedIn("20260002");
        assertThat(first.get("/api/learning/classes/2").statusCode()).isEqualTo(404);
        assertThat(second.get("/api/learning/classes/1").statusCode()).isEqualTo(404);
        assertThat(first.tree("/api/learning/classes/1").path("members").isEmpty()).isTrue();
        var grades=first.tree("/api/learning/grades?termId=1&studentId="+userId("20260002"));
        assertThat(grades.toString()).contains("陈同学").doesNotContain("李同学");
        assertThat(grades.findValues("classId").stream().anyMatch(n->n.asLong()==3)).isFalse();
        assertThat(first.tree("/api/learning/exams?termId=1").findValues("classId").stream().anyMatch(n->n.asLong()==2 || n.asLong()==3)).isFalse();
        assertThat(first.get("/api/teaching/classes?termId=1").statusCode()).isEqualTo(403);
    }

    @Test
    void teachingAndAcademicDutiesDoNotGrantEachOthersPermissions() throws Exception {
        assertThat(signedIn("teacher1001").get("/api/teaching/classes/2").statusCode()).isEqualTo(404);
        assertThat(signedIn("teacher1002").get("/api/teaching/classes/1").statusCode()).isEqualTo(404);
        for (String name:java.util.List.of("20260001","teacher1001","staff1001","admin"))
            assertThat(signedIn(name).get("/api/academic/catalog").statusCode()).isEqualTo(403);
        assertThat(signedIn("academic1001").get("/api/academic/catalog").statusCode()).isEqualTo(200);
        assertThat(new Browser().get("/api/learning/classes?termId=1").statusCode()).isEqualTo(401);
    }

    @Test
    void materialPublicationAndPrivateAttachmentHaveTheSameAccessBoundary() throws Exception {
        Browser teacher=signedIn("teacher1001"), first=signedIn("20260001"), second=signedIn("20260002");
        teacher.csrf();
        String title="资料-"+UUID.randomUUID();
        var created=teacher.mutate("POST","/api/teaching/classes/1/materials",Map.of("title",title,"body","课程正文","version",0),true);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        long id=json.readTree(created.body()).path("id").asLong();
        assertThat(first.get("/api/learning/classes/1").body()).doesNotContain(title);
        assertThat(first.get("/api/teaching-files/"+id).statusCode()).isEqualTo(404);
        assertThat(teacher.upload(id,0,"课程说明.txt","教学附件正文").statusCode()).isEqualTo(200);
        assertThat(teacher.mutate("POST","/api/teaching/materials/"+id+"/state",Map.of("status","PUBLISHED","version",1),true).statusCode()).isEqualTo(200);
        assertThat(first.get("/api/learning/classes/1").body()).contains(title);
        var download=first.get("/api/teaching-files/"+id);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo("教学附件正文");
        assertThat(download.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(second.get("/api/teaching-files/"+id).statusCode()).isEqualTo(404);
        assertThat(teacher.mutate("POST","/api/teaching/materials/"+id+"/state",Map.of("status","WITHDRAWN","version",1),true).statusCode()).isEqualTo(409);
        assertThat(teacher.mutate("POST","/api/teaching/materials/"+id+"/state",Map.of("status","WITHDRAWN","version",2),true).statusCode()).isEqualTo(200);
        assertThat(first.get("/api/teaching-files/"+id).statusCode()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM course_material_revision WHERE material_id=:id").param("id",id).query(Long.class).single()).isEqualTo(4);
    }

    @Test
    void foundationCreationValidatesDatesAndRequiredTeachingRelationships() throws Exception {
        Browser staff=signedIn("academic1001"); staff.csrf();
        var invalid=staff.mutate("POST","/api/academic/terms",Map.of("name","无效学期","startsOn","2026-10-05","endsOn","2026-09-01"),true);
        assertThat(invalid.statusCode()).isEqualTo(400);
        var term=staff.mutate("POST","/api/academic/terms",Map.of("name","测试学期-"+UUID.randomUUID(),"startsOn","2028-02-01","endsOn","2028-06-30"),true);
        assertThat(term.statusCode()).as(term.body()).isEqualTo(200);
        var course=staff.mutate("POST","/api/academic/courses",Map.of("code","T-"+UUID.randomUUID(),"name","测试课程","credits",2.5),true);
        assertThat(course.statusCode()).as(course.body()).isEqualTo(200);
        var input=new java.util.HashMap<String,Object>(Map.of("termId",json.readTree(term.body()).path("id").asLong(),"courseId",json.readTree(course.body()).path("id").asLong(),"name","测试班","teacherIds",java.util.List.of(userId("20260001")),"studentIds",java.util.List.of(userId("20260001"))));
        assertThat(staff.mutate("POST","/api/academic/classes",input,true).statusCode()).isEqualTo(400);
        input.put("teacherIds",java.util.List.of(userId("teacher1001")));
        assertThat(staff.mutate("POST","/api/academic/classes",input,true).statusCode()).isEqualTo(200);
    }

    @Test
    void noticeRetriesDoNotDuplicateRecipientsAndOtherClassesReceiveNothing() throws Exception {
        Browser teacher=signedIn("teacher1001"); teacher.csrf();
        String title="通知-"+UUID.randomUUID();
        var input=Map.of("title",title,"body","请查看课程资料","requestKey",UUID.randomUUID().toString());
        var first=teacher.mutate("POST","/api/teaching/classes/1/notices",input,true);
        var repeat=teacher.mutate("POST","/api/teaching/classes/1/notices",input,true);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(repeat.statusCode()).as(repeat.body()).isEqualTo(200);
        assertThat(json.readTree(first.body()).path("id")).isEqualTo(json.readTree(repeat.body()).path("id"));
        assertThat(jdbc.sql("SELECT COUNT(*) FROM user_notice WHERE title=:t").param("t",title).query(Long.class).single()).isEqualTo(1);
        assertThat(signedIn("20260002").get("/api/portal/overview").body()).doesNotContain(title);
    }

    @Test
    void revokedTeachingAndMembershipRelationshipsInvalidateExistingSessions() throws Exception {
        Browser teacher=signedIn("teacher1001"), student=signedIn("20260001");
        try {
            jdbc.sql("UPDATE teaching_instructor SET active=FALSE WHERE class_id=1 AND teacher_id=:u").param("u",userId("teacher1001")).update();
            jdbc.sql("UPDATE teaching_member SET active=FALSE WHERE class_id=1 AND student_id=:u").param("u",userId("20260001")).update();
            assertThat(teacher.get("/api/teaching/classes/1").statusCode()).isEqualTo(404);
            assertThat(student.get("/api/learning/classes/1").statusCode()).isEqualTo(404);
            assertThat(student.tree("/api/learning/grades?termId=1").findValues("classId").stream().anyMatch(n->n.asLong()==1)).isFalse();
        } finally {
            jdbc.sql("UPDATE teaching_instructor SET active=TRUE WHERE class_id=1 AND teacher_id=:u").param("u",userId("teacher1001")).update();
            jdbc.sql("UPDATE teaching_member SET active=TRUE WHERE class_id=1 AND student_id=:u").param("u",userId("20260001")).update();
        }
    }

    @Test
    void invalidGradeImportIsAtomicAndPreviewDoesNotPublish() throws Exception {
        Browser staff=signedIn("academic1001"); staff.csrf();
        long classId=teachingFixture(staff), student=userId("20260001");
        var valid=Map.of("studentId",student,"score",64,"version",0);
        var invalid=Map.of("studentId",userId("teacher1001"),"score",91,"version",0);
        var response=staff.mutate("POST","/api/academic/classes/"+classId+"/grades/import",Map.of("rows",java.util.List.of(valid,invalid),"preview",false),true);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM academic_grade WHERE class_id=:c").param("c",classId).query(Long.class).single()).isZero();
        assertThat(staff.mutate("POST","/api/academic/classes/"+classId+"/grades/import",Map.of("rows",java.util.List.of(valid),"preview",true),true).statusCode()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM academic_grade WHERE class_id=:c").param("c",classId).query(Long.class).single()).isZero();
        assertThat(staff.mutate("POST","/api/academic/classes/"+classId+"/grades/import",Map.of("rows",java.util.List.of(valid),"preview",false),true).statusCode()).isEqualTo(200);
        long grade=staff.tree("/api/academic/classes/"+classId).path("grades").get(0).path("id").asLong();
        Browser own=signedIn("20260001"), other=signedIn("20260002");
        assertThat(own.tree("/api/learning/grades?termId=1").findValues("classId").stream().anyMatch(n->n.asLong()==classId)).isFalse();
        assertThat(staff.mutate("POST","/api/academic/grades/"+grade+"/state",Map.of("status","PUBLISHED","version",0),true).statusCode()).isEqualTo(200);
        assertThat(own.tree("/api/learning/grades?termId=1").findValues("classId").stream().anyMatch(n->n.asLong()==classId)).isTrue();
        assertThat(other.tree("/api/learning/grades?termId=1").findValues("classId").stream().anyMatch(n->n.asLong()==classId)).isFalse();
        assertThat(staff.mutate("POST","/api/academic/grades/"+grade+"/state",Map.of("status","DRAFT","version",0),true).statusCode()).isEqualTo(409);
    }

    @Test
    void examPublicationRechecksConflictsAndWithdrawalUpdatesStudentQueries() throws Exception {
        Browser staff=signedIn("academic1001"); staff.csrf();
        long classId=teachingFixture(staff);
        String path="/api/academic/classes/"+classId+"/exam";
        var input=new java.util.HashMap<String,Object>(Map.of("roomId",TestRooms.ensure(jdbc,"其他演示教室"),"startsAt","2027-01-11T09:00:00","endsAt","2027-01-11T11:00:00","version",0));
        var saved=staff.mutate("PUT",path,input,true);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        long id=json.readTree(saved.body()).path("id").asLong();
        assertThat(staff.mutate("POST","/api/academic/exams/"+id+"/state",Map.of("status","PUBLISHED","version",0),true).statusCode()).isEqualTo(409);
        input.put("startsAt","2027-01-20T09:00:00"); input.put("endsAt","2027-01-20T11:00:00");
        assertThat(staff.mutate("PUT",path,input,true).statusCode()).isEqualTo(200);
        assertThat(staff.mutate("POST","/api/academic/exams/"+id+"/state",Map.of("status","PUBLISHED","version",1),true).statusCode()).isEqualTo(200);
        var student=signedIn("20260001");
        assertThat(student.tree("/api/learning/exams?termId=1").findValues("id").stream().anyMatch(n->n.asLong()==id)).isTrue();
        assertThat(staff.mutate("POST","/api/academic/exams/"+id+"/state",Map.of("status","DRAFT","version",2),true).statusCode()).isEqualTo(200);
        assertThat(student.tree("/api/learning/exams?termId=1").findValues("id").stream().anyMatch(n->n.asLong()==id)).isFalse();
    }

    @Test
    void academicScopeAndLessonConflictAreEnforcedAtTheApi() throws Exception {
        Browser staff=signedIn("academic1001"); staff.csrf();
        jdbc.sql("INSERT INTO teaching_class(term_id,course_id,department_id,name) VALUES(1,1,2,:n)").param("n","异院-"+UUID.randomUUID()).update();
        long foreign=jdbc.sql("SELECT MAX(id) FROM teaching_class WHERE department_id=2").query(Long.class).single();
        assertThat(staff.get("/api/academic/classes/"+foreign).statusCode()).isEqualTo(404);
        var lesson=Map.of("teacherId",userId("teacher1001"),"roomId",TestRooms.ensure(jdbc,"新的教室"),"startsAt","2026-10-05T08:00:00","endsAt","2026-10-05T09:40:00");
        assertThat(staff.mutate("POST","/api/academic/classes/1/lessons",lesson,true).statusCode()).isEqualTo(409);
        assertThat(staff.mutate("POST","/api/academic/classes/1/grades/import",Map.of("rows",java.util.List.of(Map.of("studentId",userId("20260001"),"score",101,"version",0)),"preview",true),true).statusCode()).isEqualTo(400);
    }

    @Test
    void concurrentGradePublicationOnlyNotifiesOnce() throws Exception {
        Browser staff=signedIn("academic1001"); staff.csrf();
        long classId=teachingFixture(staff);
        assertThat(staff.mutate("POST","/api/academic/classes/"+classId+"/grades/import",Map.of("rows",java.util.List.of(Map.of("studentId",userId("20260001"),"score",75,"version",0)),"preview",false),true).statusCode()).isEqualTo(200);
        long id=staff.tree("/api/academic/classes/"+classId).path("grades").get(0).path("id").asLong();
        var other=signedIn("academic1001"); other.csrf();
        var first=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return staff.mutate("POST","/api/academic/grades/"+id+"/state",Map.of("status","PUBLISHED","version",0),true).statusCode();}catch(Exception e){throw new RuntimeException(e);}});
        var second=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return other.mutate("POST","/api/academic/grades/"+id+"/state",Map.of("status","PUBLISHED","version",0),true).statusCode();}catch(Exception e){throw new RuntimeException(e);}});
        assertThat(java.util.List.of(first.get(),second.get())).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM teaching_audit WHERE action='GRADE_PUBLISHED' AND object_id=:id").param("id",id).query(Long.class).single()).isEqualTo(1);
    }

    private long userId(String name) { return jdbc.sql("SELECT id FROM app_user WHERE username=:n").param("n",name).query(Long.class).single(); }
    private long teachingFixture(Browser staff) throws Exception {
        var response=staff.mutate("POST","/api/academic/classes",Map.of("termId",1,"courseId",1,"name","测试教学班-"+UUID.randomUUID(),"teacherIds",java.util.List.of(userId("teacher1001")),"studentIds",java.util.List.of(userId("20260001"))),true);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("info").path("id").asLong();
    }

    @Test
    void anonymousCanReadSchoolButNotPersonalInformation() throws Exception {
        Browser browser = new Browser();
        assertThat(browser.get("/api/public/school").statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/public/school").body()).contains("郑州工业应用技术学院","东校区","西校区");
        assertThat(browser.get("/api/me").statusCode()).isEqualTo(401);
        assertThat(browser.get("/api/portal/overview").statusCode()).isEqualTo(401);
    }

    @Test
    void loginRequiresCsrfAndRejectsIncorrectCredentials() throws Exception {
        Browser browser = new Browser();
        assertThat(browser.mutate("POST","/api/auth/login",Map.of("username","20260001","password","Demo@2026"),false).statusCode()).isEqualTo(403);
        browser.csrf();
        assertThat(browser.mutate("POST","/api/auth/login",Map.of("username","20260001","password","wrong"),true).statusCode()).isEqualTo(401);
    }

    @Test
    void loginRotatesSessionAndLogoutInvalidatesIt() throws Exception {
        Browser browser = new Browser();
        browser.csrf();
        String before = browser.sessionId();
        browser.login("20260001");
        assertThat(browser.sessionId()).isNotEqualTo(before);
        assertThat(browser.get("/api/me").statusCode()).isEqualTo(200);
        browser.csrf();
        assertThat(browser.mutate("POST","/api/auth/logout",Map.of(),true).statusCode()).isEqualTo(204);
        assertThat(browser.get("/api/me").statusCode()).isEqualTo(401);
    }

    @Test
    void studentIdentityCannotBeOverriddenAndPrivateNoticeCannotBeChanged() throws Exception {
        Browser first = signedIn("20260001");
        Browser second = signedIn("20260002");
        JsonNode other = second.tree("/api/portal/overview");
        long otherId = other.path("profile").path("id").asLong();
        long otherNotice = other.path("notices").get(0).path("id").asLong();
        JsonNode own = first.tree("/api/portal/overview?userId=" + otherId);
        assertThat(own.path("profile").path("username").asText()).isEqualTo("20260001");
        assertThat(own.toString()).doesNotContain("李同学");
        first.csrf();
        assertThat(first.mutate("POST","/api/portal/notices/" + otherNotice + "/read",Map.of(),true).statusCode()).isEqualTo(404);
    }

    @Test
    void studentCannotAccessContentManagementAndTeacherCannotAccessStudentData() throws Exception {
        assertThat(signedIn("20260001").get("/api/admin/content").statusCode()).isEqualTo(403);
        Browser teacher = signedIn("teacher1001");
        assertThat(teacher.get("/api/portal/overview").statusCode()).isEqualTo(403);
        assertThat(teacher.get("/api/workbench/overview").statusCode()).isEqualTo(200);
    }

    @Test
    void ownNoticeReadIsPersistentAndIdempotent() throws Exception {
        Browser browser = signedIn("20260002");
        long notice = browser.tree("/api/portal/overview").path("notices").get(0).path("id").asLong();
        browser.csrf();
        assertThat(browser.mutate("POST","/api/portal/notices/" + notice + "/read",Map.of(),true).statusCode()).isEqualTo(200);
        assertThat(browser.mutate("POST","/api/portal/notices/" + notice + "/read",Map.of(),true).statusCode()).isEqualTo(200);
        assertThat(browser.tree("/api/portal/overview").path("notices").get(0).path("read").asBoolean()).isTrue();
    }

    @Test
    void contentRequiresSeparateReviewerAndOnlyPublishedContentIsPublic() throws Exception {
        Browser editor = signedIn("admin");
        String slug = "test-" + UUID.randomUUID();
        editor.csrf();
        JsonNode created = json.readTree(editor.mutate("POST","/api/admin/content",input(slug,0),true).body());
        long id = created.path("id").asLong();
        assertThat(id).isPositive();
        assertThat(editor.get("/api/public/content/" + slug).statusCode()).isEqualTo(404);
        var submitted = editor.mutate("POST","/api/admin/content/" + id + "/submit",Map.of("version",0),true);
        assertThat(submitted.statusCode()).isEqualTo(200);
        assertThat(editor.mutate("POST","/api/admin/content/" + id + "/publish",Map.of("version",1),true).statusCode()).isEqualTo(403);
        Browser reviewer = signedIn("reviewer");
        reviewer.csrf();
        assertThat(reviewer.mutate("POST","/api/admin/content/" + id + "/publish",Map.of("version",1),true).statusCode()).isEqualTo(200);
        var published = reviewer.get("/api/public/content/" + slug);
        assertThat(published.statusCode()).isEqualTo(200);
        assertThat(published.body()).contains("测试演示内容").doesNotContain("createdBy","password_hash");
        assertThat(reviewer.mutate("POST","/api/admin/content/" + id + "/publish",Map.of("version",1),true).statusCode()).isEqualTo(409);
    }

    @Test
    void staleDraftVersionAndForeignDraftEditsAreRejected() throws Exception {
        Browser editor = signedIn("editor");
        editor.csrf();
        String slug = "draft-" + UUID.randomUUID();
        long id = json.readTree(editor.mutate("POST","/api/admin/content",input(slug,0),true).body()).path("id").asLong();
        assertThat(editor.mutate("PUT","/api/admin/content/" + id,input(slug,0),true).statusCode()).isEqualTo(200);
        assertThat(editor.mutate("PUT","/api/admin/content/" + id,input(slug,0),true).statusCode()).isEqualTo(409);
        Browser admin = signedIn("admin");
        admin.csrf();
        assertThat(admin.mutate("PUT","/api/admin/content/" + id,input(slug,1),true).statusCode()).isEqualTo(409);
    }

    @Test
    void reviewerCannotCreateContentAndUnsafeSourceUrlsAreRejected() throws Exception {
        Browser reviewer = signedIn("reviewer");
        reviewer.csrf();
        assertThat(reviewer.mutate("POST","/api/admin/content",input("reviewer-" + UUID.randomUUID(),0),true).statusCode()).isEqualTo(403);
        Browser editor = signedIn("editor");
        editor.csrf();
        var unsafe = new java.util.HashMap<>(input("unsafe-" + UUID.randomUUID(),0));
        unsafe.put("sourceUrl","javascript:alert(1)");
        assertThat(editor.mutate("POST","/api/admin/content",unsafe,true).statusCode()).isEqualTo(400);
    }

    private Map<String,Object> input(String slug,int version) {
        return Map.of("slug",slug,"category","notice","title","测试演示内容","summary","集成测试摘要",
            "body","集成测试正文","sourceType","DEMO","version",version);
    }

    @Test
    void oversizedUtf8PasswordAndMalformedResourceIdReturnClientErrors() throws Exception {
        Browser anonymous = new Browser();
        anonymous.csrf();
        assertThat(anonymous.mutate("POST", "/api/auth/login",
            Map.of("username", "20260001", "password", "中".repeat(30)), true).statusCode()).isEqualTo(400);
        Browser student = signedIn("20260001");
        student.csrf();
        assertThat(student.mutate("POST", "/api/portal/notices/not-a-number/read", Map.of(), true).statusCode()).isEqualTo(400);
        assertThat(student.get("/api/does-not-exist").statusCode()).isEqualTo(404);
    }

    private Browser signedIn(String username) throws Exception {
        Browser browser = new Browser();
        browser.csrf();
        browser.login(username);
        return browser;
    }

    private class Browser {
        final CookieManager cookies = new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        String token;
        String header;
        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        }
        JsonNode tree(String path) throws Exception { return json.readTree(get(path).body()); }
        void csrf() throws Exception {
            JsonNode result = tree("/api/auth/csrf");
            token = result.path("token").asText();
            header = result.path("headerName").asText();
        }
        void login(String username) throws Exception {
            var response = mutate("POST","/api/auth/login",Map.of("username",username,"password","Demo@2026"),true);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }
        String sessionId() {
            return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("CAMPUS_SESSION"))
                .map(java.net.HttpCookie::getValue).findFirst().orElse("");
        }
        HttpResponse<String> mutate(String method,String path,Map<String,?> body,boolean withCsrf) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type","application/json");
            if (withCsrf) request.header(header,token);
            return http.send(request.method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> upload(long id,int version,String name,String body) throws Exception {
            String boundary="CampusBoundary"+UUID.randomUUID();
            String form="--"+boundary+"\r\nContent-Disposition: form-data; name=\"version\"\r\n\r\n"+version
                +"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: text/plain\r\n\r\n"+body+"\r\n--"+boundary+"--\r\n";
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/teaching/materials/"+id+"/file"))
                .header(header,token).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofString(form,java.nio.charset.StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
