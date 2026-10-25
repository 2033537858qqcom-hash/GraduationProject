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
 "spring.datasource.url=jdbc:h2:mem:physical-room;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.agent.deepseek.enabled=false"})
class PhysicalRoomIntegrationTest {
 @LocalServerPort int port;
 @Autowired JdbcClient jdbc;
 @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
 final JsonMapper json=JsonMapper.builder().build();
 static final String OLD="InitialPass2026!", NEW="UpdatedPass2026!", TEMP="TemporaryPass2026!";
 @Test void linkedRoomRejectsReservationOverlappingLesson()throws Exception{
  var f=fixture();ok(lesson(f,"2029-04-01T09:00:00","2029-04-01T10:00:00"));
  var response=book(f,"2029-04-01T09:30:00","2029-04-01T10:30:00");assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
 }
 @Test void onlyPublishedExamsBlockReservationsAndWithdrawalReleasesTime()throws Exception{
  var f=fixture();var academic=login("academic1001");var exam=ok(academic.send("PUT","/api/academic/classes/"+f.group()+"/exam",Map.of("roomId",f.room(),"startsAt","2029-04-02T09:00:00","endsAt","2029-04-02T10:00:00","version",0)));
  var check=Map.of("venueId",f.venue(),"startsAt","2029-04-02T09:00:00","endsAt","2029-04-02T10:00:00");
  assertThat(ok(login("20260001").send("POST","/api/reservations/check-conflict",check)).path("hasConflict").asBoolean()).isFalse();
  ok(academic.send("POST","/api/academic/exams/"+exam.path("id").asLong()+"/state",Map.of("status","PUBLISHED","version",0)));
  assertThat(book(f,"2029-04-02T09:00:00","2029-04-02T10:00:00").statusCode()).isEqualTo(409);
  ok(academic.send("POST","/api/academic/exams/"+exam.path("id").asLong()+"/state",Map.of("status","DRAFT","version",1)));
  assertThat(book(f,"2029-04-02T09:00:00","2029-04-02T10:00:00").statusCode()).isEqualTo(200);
 }
 @ParameterizedTest @ValueSource(strings={"SUBMITTED","APPROVED","IN_USE"})
 void reservationsBlockBothLessonAndExamPublication(String state)throws Exception{
  var f=fixture();var reservation=ok(book(f,"2029-04-03T09:00:00","2029-04-03T10:00:00"));
  if(state.equals("APPROVED"))ok(login("affairs1001").send("POST","/api/reservations/"+reservation.path("id").asLong()+"/review",Map.of("version",0,"action","APPROVE")));
  if(state.equals("IN_USE"))jdbc.sql("UPDATE venue_reservation SET status='IN_USE' WHERE id=:id").param("id",reservation.path("id").asLong()).update();
  assertThat(lesson(f,"2029-04-03T09:00:00","2029-04-03T10:00:00").statusCode()).isEqualTo(409);
  var a=login("academic1001");var e=ok(a.send("PUT","/api/academic/classes/"+f.group()+"/exam",Map.of("roomId",f.room(),"startsAt","2029-04-03T09:00:00","endsAt","2029-04-03T10:00:00","version",0)));
  assertThat(a.send("POST","/api/academic/exams/"+e.path("id").asLong()+"/state",Map.of("status","PUBLISHED","version",0)).statusCode()).isEqualTo(409);
 }
 @Test void overdueUseRetainsPhysicalRoomUntilActualHandover()throws Exception{
  var f=fixture();var r=ok(book(f,"2029-04-04T09:00:00","2029-04-04T10:00:00"));
  jdbc.sql("UPDATE venue_reservation SET status='IN_USE',starts_at=:s,ends_at=:e WHERE id=:id").param("s",java.time.LocalDateTime.now().minusHours(2)).param("e",java.time.LocalDateTime.now().minusHours(1)).param("id",r.path("id").asLong()).update();
  assertThat(lesson(f,"2029-04-04T11:00:00","2029-04-04T12:00:00").statusCode()).isEqualTo(409);
 }
 @Test void adjacentIntervalsAndWithdrawnReservationsDoNotBlockTeaching()throws Exception{
  var f=fixture();var r=ok(book(f,"2029-04-05T09:00:00","2029-04-05T10:00:00"));
  ok(lesson(f,"2029-04-05T08:00:00","2029-04-05T09:00:00"));ok(lesson(f,"2029-04-05T10:00:00","2029-04-05T11:00:00"));
  ok(login("20260001").send("POST","/api/reservations/"+r.path("id").asLong()+"/withdraw",Map.of()));
  ok(lesson(f,"2029-04-05T09:00:00","2029-04-05T10:00:00"));
 }
 @ParameterizedTest @ValueSource(strings={"CAMPUS","EQUIPMENT","DUPLICATE"})
 void physicalLinksMustBeUnambiguousSameCampusVenues(String kind)throws Exception{
  var f=fixture();var input=form(f.room());
  if(kind.equals("CAMPUS")){input.put("campusId",2);input.put("placeId","west-library");}
  if(kind.equals("EQUIPMENT"))input.put("category","EQUIPMENT");
  var response=login("affairs1001").send("POST","/api/reservations/venues",input);
  assertThat(response.statusCode()).as(response.body()).isEqualTo(kind.equals("DUPLICATE")?409:400);
 }
 @Test void roomDisableAndCapacityChangeRespectUpcomingReservations()throws Exception{
  var f=fixture();ok(book(f,"2029-04-06T09:00:00","2029-04-06T10:00:00"));var admin=login("admin");
  assertThat(admin.send("POST","/api/admin/resources/"+f.room()+"/state",Map.of("enabled",false,"version",0,"reason","维护")).statusCode()).isEqualTo(409);
  var occupancy=ok(admin.get("/api/admin/resources/"+f.room()+"/occupancy"));
  assertThat(occupancy.toString()).contains("RESERVATION").doesNotContain("13800138000","私有活动主题");
 }
 @ParameterizedTest @ValueSource(strings={"DISABLED","CAPACITY"})
 void bookingRechecksTheCurrentPhysicalRoom(String mode)throws Exception{
  var f=fixture();if(mode.equals("DISABLED"))jdbc.sql("UPDATE classroom_resource SET enabled=FALSE WHERE id=:id").param("id",f.room()).update();
  else jdbc.sql("UPDATE classroom_resource SET teaching_capacity=5,exam_capacity=5 WHERE id=:id").param("id",f.room()).update();
  assertThat(book(f,"2029-04-07T09:00:00","2029-04-07T10:00:00").statusCode()).isEqualTo(409);
 }
 @Test void roomAndVenueCalendarShowTheSameTeachingOccupancy()throws Exception{
  var f=fixture();ok(lesson(f,"2029-04-08T09:00:00","2029-04-08T10:00:00"));
  var slots=ok(login("20260001").get("/api/reservations/venues/"+f.venue()+"/schedule?date=2029-04-08"));
  assertThat(slots.toString()).contains("LESSON").doesNotContain("teacher1001","20260001");
 }
 @Test void concurrentLessonAndReservationCannotBothAcquireTheRoom()throws Exception{
  var f=fixture();var latch=new java.util.concurrent.CountDownLatch(1);
  try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
   var a=pool.submit(()->{latch.await();return lesson(f,"2029-04-09T09:00:00","2029-04-09T10:00:00").statusCode();});
   var b=pool.submit(()->{latch.await();return book(f,"2029-04-09T09:00:00","2029-04-09T10:00:00").statusCode();});latch.countDown();
   assertThat(java.util.List.of(a.get(),b.get())).containsExactlyInAnyOrder(200,409);
  }
 }
 @Test void linkChangesRequireCurrentVersionAndNoOpenBookings()throws Exception{
  var f=fixture();var manager=login("affairs1001");var endpoint="/api/reservations/venues/"+f.venue()+"/classroom";
  var unlink=new java.util.HashMap<String,Object>();unlink.put("version",0);unlink.put("reason","核对房间");
  var unlinked=ok(manager.send("PUT",endpoint,unlink));assertThat(unlinked.path("classroomResourceId").isNull()).isTrue();
  assertThat(manager.send("PUT",endpoint,Map.of("classroomResourceId",f.room(),"version",0,"reason","旧版本")).statusCode()).isEqualTo(409);
  ok(manager.send("PUT",endpoint,Map.of("classroomResourceId",f.room(),"version",1,"reason","确认同一教室")));
  ok(book(f,"2029-04-20T09:00:00","2029-04-20T10:00:00"));unlink.put("version",2);
  assertThat(manager.send("PUT",endpoint,unlink).statusCode()).isEqualTo(409);
 }
 @Test void linkChangeCannotReuseAnotherVenueRoomAndStudentCannotLink()throws Exception{
  var a=fixture();var b=fixture();var payload=Map.of("classroomResourceId",a.room(),"version",0,"reason","重复关联");
  assertThat(login("affairs1001").send("PUT","/api/reservations/venues/"+b.venue()+"/classroom",payload).statusCode()).isEqualTo(409);
  assertThat(login("20260001").send("PUT","/api/reservations/venues/"+b.venue()+"/classroom",payload).statusCode()).isEqualTo(403);
 }
 private record Fixture(long room,long venue,long group,long teacher){}
 private Fixture fixture()throws Exception{
  String key=UUID.randomUUID().toString();long teacher=uniqueTeacher();long room=TestRooms.ensure(jdbc,"物理教室-"+key);
  jdbc.sql("INSERT INTO academic_term(name,starts_on,ends_on) VALUES(:name,'2029-01-01','2029-12-31')").param("name",key).update();long term=jdbc.sql("SELECT id FROM academic_term WHERE name=:n").param("n",key).query(Long.class).single();
  jdbc.sql("INSERT INTO teaching_class(term_id,course_id,department_id,name) VALUES(:t,1,1,:n)").param("t",term).param("n",key).update();long group=jdbc.sql("SELECT id FROM teaching_class WHERE name=:n").param("n",key).query(Long.class).single();
  jdbc.sql("INSERT INTO teaching_instructor(class_id,teacher_id) VALUES(:g,:u)").param("g",group).param("u",teacher).update();
  var venue=ok(login("affairs1001").send("POST","/api/reservations/venues",form(room)));return new Fixture(room,venue.path("id").asLong(),group,teacher);
 }
 private long uniqueTeacher(){String name="phy_"+UUID.randomUUID().toString().substring(0,8);
  jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id) SELECT :name,password_hash,'物理资源教师',:name,1,1 FROM app_user WHERE username='teacher1001'").param("name",name).update();
  long id=jdbc.sql("SELECT id FROM app_user WHERE username=:n").param("n",name).query(Long.class).single();jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,'TEACHER')").param("id",id).update();return id; }
 private java.util.HashMap<String,Object> form(long room){var x=new java.util.HashMap<String,Object>();x.put("campusId",1);x.put("code","PHY-"+UUID.randomUUID());x.put("name","教室预约资源");x.put("category","VENUE");x.put("placeId","east-library");x.put("buildingName","测试教学楼");x.put("locationDetail","测试房间");x.put("capacity",30);x.put("enabled",true);x.put("managerUserIds",java.util.List.of(jdbc.sql("SELECT id FROM app_user WHERE username='affairs1001'").query(Long.class).single()));x.put("classroomResourceId",room);return x;}
 private HttpResponse<String> lesson(Fixture f,String s,String e)throws Exception{return login("academic1001").send("POST","/api/academic/classes/"+f.group()+"/lessons",Map.of("teacherId",f.teacher(),"roomId",f.room(),"startsAt",s,"endsAt",e));}
 private HttpResponse<String> book(Fixture f,String s,String e)throws Exception{return login("20260001").send("POST","/api/reservations/submit",Map.of("requestKey",UUID.randomUUID().toString(),"venueId",f.venue(),"activityTheme","私有活动主题","activityType","ACADEMIC","expectedParticipants",10,"contactPhone","13800138000","startsAt",s,"endsAt",e));}
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
