package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zzuit.campus.bootstrap.ReservationDemoInitializer;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=jdbc:h2:mem:personal-schedule;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true","app.agent.deepseek.enabled=false"})
class PersonalScheduleIntegrationTest {
 @LocalServerPort int port;
 @Autowired JdbcClient jdbc;
 final JsonMapper json=JsonMapper.builder().build();
 static final String RANGE="/api/schedule/mine?from=2029-04-02&to=2029-04-09";
 @Test void aggregatesFourSourcesWithStableKeysAndPrivateDetailsExcluded()throws Exception{
  var f=fixture();var data=ok(login(f.username()).get(RANGE));var events=data.path("events");assertThat(events.size()).isEqualTo(4);
  var sources=new java.util.HashSet<String>();for(var e:events){sources.add(e.path("source").asText());assertThat(e.path("key").asText()).startsWith(e.path("source").asText()+":");assertThat(e.path("href").asText()).startsWith("/portal/");}
  assertThat(sources).containsExactlyInAnyOrder("LESSON","EXAM","LEAVE","RESERVATION");assertThat(data.path("timeZone").asText()).isEqualTo("Asia/Shanghai");
  assertThat(data.toString()).doesNotContain("私有请假事由","13800138000","password","file_bytes");
  var keys=new java.util.HashSet<String>(); for(var e:events)keys.add(e.path("key").asText());assertThat(keys.size()).isEqualTo(events.size());
 }
 @Test void overlapsAreSymmetricAndPendingBookingsAreClearlyTentative()throws Exception{
  var f=fixture();var events=ok(login(f.username()).get(RANGE)).path("events");
  for(var e:events){assertThat(e.path("overlapKeys").size()).isGreaterThan(0);assertThat(e.path("overlapKeys").toString()).doesNotContain("\""+e.path("key").asText()+"\"");if(e.path("source").asText().equals("RESERVATION"))assertThat(e.path("tentative").asBoolean()).isTrue();}
 }
 @Test void liveMembershipAndInstructorChangesRemoveInaccessibleLessons()throws Exception{
  var f=fixture();var browser=login(f.username());jdbc.sql("UPDATE teaching_instructor SET active=FALSE WHERE class_id=:c").param("c",f.group()).update();
  var items=ok(browser.get(RANGE)).path("events");assertThat(items.toString()).doesNotContain("\"source\":\"LESSON\"").contains("\"source\":\"EXAM\"");
  jdbc.sql("UPDATE teaching_member SET active=FALSE WHERE class_id=:c").param("c",f.group()).update();assertThat(ok(browser.get(RANGE)).path("events").size()).isEqualTo(2);
 }
 @Test void examDraftAndWithdrawnBookingDisappearOnRefresh()throws Exception{
  var f=fixture();var browser=login(f.username());jdbc.sql("UPDATE academic_exam SET status='DRAFT' WHERE class_id=:c").param("c",f.group()).update();jdbc.sql("UPDATE venue_reservation SET status='WITHDRAWN' WHERE applicant_id=:u").param("u",f.user()).update();assertThat(ok(browser.get(RANGE)).path("events").size()).isEqualTo(2);
 }
 @Test void confirmedReturnUsesShortenedAbsenceCalendarRatherThanApprovedSnapshot()throws Exception{
  var f=fixture();jdbc.sql("UPDATE leave_request SET status='COMPLETED' WHERE student_id=:u").param("u",f.user()).update();jdbc.sql("UPDATE leave_calendar SET ends_at='2029-04-02 09:15:00' WHERE student_id=:u").param("u",f.user()).update();
  for(var e:ok(login(f.username()).get(RANGE)).path("events"))if(e.path("source").asText().equals("LEAVE")){assertThat(e.path("endsAt").asText()).startsWith("2029-04-02T09:15");assertThat(e.path("status").asText()).isEqualTo("COMPLETED");}
 }
 @ParameterizedTest @ValueSource(strings={"DRAFT","RETURNED","REJECTED","WITHDRAWN","CANCELLED","EXPIRED"})
 void inactiveReservationStatesAreNotCalendarCommitments(String status)throws Exception{
  var f=fixture();jdbc.sql("UPDATE venue_reservation SET status=:s WHERE applicant_id=:u").param("s",status).param("u",f.user()).update();assertThat(ok(login(f.username()).get(RANGE)).path("events").size()).isEqualTo(3);
 }
 @Test void onlyTheCurrentUsersRowsAreReturnedAndInjectedUserIdCannotChangeScope()throws Exception{
  var f=fixture();var other=fixture();assertThat(ok(login(f.username()).get(RANGE+"&userId="+other.user())).path("events").size()).isEqualTo(4);assertThat(ok(login("20260002").get(RANGE)).path("events").size()).isZero();
 }
 @ParameterizedTest @ValueSource(strings={"teacher1001","admin"})
 void staffWithoutStudentPortalPermissionCannotReadStudentCalendar(String name)throws Exception{assertThat(login(name).get(RANGE).statusCode()).isEqualTo(403);}
 @Test void anonymousCannotReadPersonalCalendar()throws Exception{assertThat(new Browser().get(RANGE).statusCode()).isEqualTo(401);}
 @ParameterizedTest @ValueSource(strings={"from=2029-04-02&to=2029-04-02","from=2029-04-03&to=2029-04-02","from=2029-04-02&to=2029-05-15","from=garbage&to=2029-04-03","from=2029-04-02"})
 void invalidOrExcessiveDateRangesAreRejected(String query)throws Exception{assertThat(login("20260001").get("/api/schedule/mine?"+query).statusCode()).isEqualTo(400);}
 @Test void sourcePermissionsAreCheckedLiveAndMissingSourcesAreExplicit()throws Exception{
  var f=fixture();var browser=login(f.username());String role="SCHEDULE_"+UUID.randomUUID().toString().substring(0,8);jdbc.sql("INSERT INTO app_role(code,name) VALUES(:r,'日程权限探针')").param("r",role).update();jdbc.sql("INSERT INTO role_permission(role_code,permission_code) VALUES(:r,'portal:read')").param("r",role).update();jdbc.sql("UPDATE user_role SET role_code=:r WHERE user_id=:u").param("r",role).param("u",f.user()).update();
  var data=ok(browser.get(RANGE));assertThat(data.path("events").size()).isZero();assertThat(data.path("unavailableSources").size()).isEqualTo(4);
 }
 @Test void adjacentIntervalsDoNotProduceOverlapAndMidnightEndIsExclusive()throws Exception{
  var f=fixture();jdbc.sql("UPDATE teaching_lesson SET starts_at='2029-04-02 07:00:00',ends_at='2029-04-02 08:00:00' WHERE class_id=:c").param("c",f.group()).update();
  var events=ok(login(f.username()).get(RANGE)).path("events");for(var e:events)if(e.path("source").asText().equals("LESSON"))assertThat(e.path("overlapKeys").size()).isZero();
  jdbc.sql("UPDATE teaching_lesson SET starts_at='2029-04-01 22:00:00',ends_at='2029-04-02 00:00:00' WHERE class_id=:c").param("c",f.group()).update();assertThat(ok(login(f.username()).get(RANGE)).path("events").size()).isEqualTo(3);
 }
 private record Fixture(String username,long user,long group){}
 private Fixture fixture(){String key=UUID.randomUUID().toString();String username="cal_"+key.substring(0,8);
  jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,class_group_id) SELECT :n,password_hash,'日程学生',:n,1,1,1 FROM app_user WHERE username='20260001'").param("n",username).update();long u=jdbc.sql("SELECT id FROM app_user WHERE username=:n").param("n",username).query(Long.class).single();jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,'STUDENT')").param("u",u).update();
  jdbc.sql("INSERT INTO academic_term(name,starts_on,ends_on) VALUES(:k,'2029-01-01','2029-12-31')").param("k",key).update();long t=jdbc.sql("SELECT id FROM academic_term WHERE name=:k").param("k",key).query(Long.class).single();
  jdbc.sql("INSERT INTO teaching_class(name,term_id,course_id,department_id) VALUES(:k,:t,1,1)").param("k",key).param("t",t).update();long c=jdbc.sql("SELECT id FROM teaching_class WHERE name=:k").param("k",key).query(Long.class).single();long teacher=jdbc.sql("SELECT id FROM app_user WHERE username='teacher1001'").query(Long.class).single();
  jdbc.sql("INSERT INTO teaching_instructor(class_id,teacher_id) VALUES(:c,:t)").param("c",c).param("t",teacher).update();jdbc.sql("INSERT INTO teaching_member(class_id,student_id) VALUES(:c,:u)").param("c",c).param("u",u).update();
  jdbc.sql("INSERT INTO teaching_lesson(class_id,teacher_id,campus_id,classroom,starts_at,ends_at) VALUES(:c,:t,1,'日程教室','2029-04-02 09:00:00','2029-04-02 10:00:00')").param("c",c).param("t",teacher).update();
  jdbc.sql("INSERT INTO academic_exam(class_id,campus_id,classroom,starts_at,ends_at,status) VALUES(:c,1,'日程考场','2029-04-02 09:30:00','2029-04-02 11:00:00','PUBLISHED')").param("c",c).update();
  jdbc.sql("INSERT INTO leave_request(student_id,request_key,class_group_id,student_name,identity_number,class_name,reason_type,reason,contact,starts_at,ends_at,status) VALUES(:u,:k,1,'日程学生',:k,'日程班','OTHER','私有请假事由','13800138000','2029-04-02 08:00:00','2029-04-02 12:00:00','APPROVED')").param("u",u).param("k",key).update();long leave=jdbc.sql("SELECT id FROM leave_request WHERE request_key=:k").param("k",key).query(Long.class).single();jdbc.sql("INSERT INTO leave_calendar(request_id,student_id,starts_at,ends_at) VALUES(:l,:u,'2029-04-02 08:00:00','2029-04-02 12:00:00')").param("l",leave).param("u",u).update();
  jdbc.sql("INSERT INTO venue_reservation(applicant_id,request_key,venue_id,applicant_name,identity_number,contact_phone,department_name,venue_name,location_detail,activity_theme,expected_participants,starts_at,ends_at,status) VALUES(:u,:k,1,'日程学生',:k,'13800138000','演示学院','日程场地','一层','本人预约活动',10,'2029-04-02 09:30:00','2029-04-02 10:30:00','SUBMITTED')").param("u",u).param("k",key).update();return new Fixture(username,u,c);
 }
    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private Browser login(String name) throws Exception { return login(name,"Demo@2026"); }
    private Browser login(String name,String password) throws Exception {
        var browser = new Browser();
        browser.csrf();
        ok(browser.send("POST", "/api/auth/login", Map.of("username", name, "password", password)));
        browser.csrf();
        return browser;
    }

    private record Staff(long id, Browser browser) {}

    private class Browser {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String header, token;
        HttpRequest.Builder builder(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)); }
        HttpResponse<String> get(String path) throws Exception { return http.send(builder(path).GET().build(), HttpResponse.BodyHandlers.ofString()); }
        void csrf() throws Exception {
            var data = json.readTree(get("/api/auth/csrf").body());
            header = data.path("headerName").asText(); token = data.path("token").asText();
        }
        HttpResponse<String> send(String method, String path, Map<String, ?> body) throws Exception {
            return http.send(builder(path).header(header, token).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
