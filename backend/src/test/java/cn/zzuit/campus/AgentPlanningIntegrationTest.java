package cn.zzuit.campus;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.agent.DeepSeekClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
 "spring.datasource.url=jdbc:h2:mem:agent-planning;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true","app.agent.deepseek.enabled=false"})
class AgentPlanningIntegrationTest {
 @LocalServerPort int port;
 @Autowired JdbcClient jdbc;
 @MockitoSpyBean DeepSeekClient model;
 final JsonMapper json=JsonMapper.builder().build();
 @BeforeEach void offline(){doReturn(false).when(model).ready();}
 @Test void capabilitiesFollowActualPortalAndReservationPermissions()throws Exception{
  var student=ok(login("20260001").get("/api/assistant/capabilities"));assertThat(student.toString()).contains("my_schedule","my_reservations","find_available_venues");
  var teacher=ok(login("teacher1001").get("/api/assistant/capabilities"));assertThat(teacher.toString()).contains("my_reservations","find_available_venues").doesNotContain("my_schedule");
  var affairs=ok(login("affairs1001").get("/api/assistant/capabilities"));assertThat(affairs.toString()).doesNotContain("my_schedule","my_reservations","find_available_venues");
 }
 @Test void localScheduleReadsAllSourcesWithoutDuplicatingOldTimetableQuery()throws Exception{
  var f=fixture();var result=ok(query(login(f.username()),"2029-04-02 我的日程",null));assertThat(result.path("results").size()).isEqualTo(1);var row=result.path("results").path(0);assertThat(row.path("name").asText()).isEqualTo("my_schedule");assertThat(row.path("total").asLong()).isEqualTo(4);assertThat(row.toString()).contains("时间重叠","/portal/schedule?week=").doesNotContain("私有请假事由","13800138000","本人预约活动");
 }
 @Test void localReservationQueriesDoNotShowOthersOrPrivateFields()throws Exception{
  var f=fixture();var another=fixture();var answer=ok(query(login(f.username()),"我的预约进度",null));var row=answer.path("results").path(0);assertThat(row.path("name").asText()).isEqualTo("my_reservations");assertThat(row.path("total").asLong()).isEqualTo(1);assertThat(row.toString()).doesNotContain("13800138000","本人预约活动","contactPhone","identityNumber");
  assertThat(ok(query(login(another.username()),"我的预约进度",null)).path("results").path(0).path("total").asLong()).isEqualTo(1);
 }
 @Test void currentReservationContextIsReauthorizedAndOnlyOwnRowsAreAllowed()throws Exception{
  var f=fixture();long id=booking(f);var context=Map.<String,Object>of("type","RESERVATION_MINE","id",id);var answer=ok(query(login(f.username()),"当前预约的进度",context));assertThat(answer.toString()).contains("RESERVATION:"+id,"/portal/reservations?id="+id);
  assertThat(query(login("20260002"),"当前预约的进度",context).statusCode()).isEqualTo(404);
 }
 @Test void localAvailabilityNeedsExplicitConditionsInsteadOfInventingDefaults()throws Exception{
  var response=ok(query(login("20260001"),"找一个空闲场地",null));assertThat(response.path("results").path(0).path("name").asText()).isEqualTo("find_available_venues");assertThat(response.path("results").path(0).path("status").asText()).isEqualTo("NEEDS_INPUT");assertThat(response.toString()).contains("校区","人数","起止");
 }
 @Test void localDateTimeAndCampusConditionsFindOnlyRealMatchingVenues()throws Exception{
  var f=fixture();long v=venue(f,"EAST",30);String name=venueName(v);var result=ok(query(login(f.username()),"2029-04-02 东校区 20人 12:00-14:00 查询空闲场地 "+name,null));var row=result.path("results").path(0);assertThat(row.path("name").asText()).isEqualTo("find_available_venues");assertThat(row.path("status").asText()).isEqualTo("OK");assertThat(row.path("items").toString()).contains(name);
 }
 @Test void availabilityUsesTeachingExamAndBookingOccupancyAndReleasesWithdrawnBookings()throws Exception{
  var f=fixture();long v=venue(f,"EAST",30);long room=TestRooms.ensure(jdbc,"计划教室-"+v);jdbc.sql("UPDATE venue_resource SET classroom_resource_id=:r WHERE id=:v").param("r",room).param("v",v).update();jdbc.sql("UPDATE teaching_lesson SET room_id=:r WHERE class_id=:c").param("r",room).param("c",f.group()).update();jdbc.sql("UPDATE academic_exam SET room_id=:r WHERE class_id=:c").param("r",room).param("c",f.group()).update();
  var b=login(f.username());assertThat(find(b,v,"2029-04-02T09:00","2029-04-02T10:00").path("total").asLong()).isZero();
  assertThat(find(b,v,"2029-04-02T10:00","2029-04-02T11:00").path("total").asLong()).isZero();
  jdbc.sql("UPDATE academic_exam SET status='DRAFT' WHERE class_id=:c").param("c",f.group()).update();assertThat(find(b,v,"2029-04-02T10:00","2029-04-02T11:00").path("total").asLong()).isEqualTo(1);
  jdbc.sql("UPDATE venue_reservation SET venue_id=:v,starts_at='2029-04-02 12:00:00',ends_at='2029-04-02 13:00:00' WHERE applicant_id=:u").param("v",v).param("u",f.user()).update();assertThat(find(b,v,"2029-04-02T12:00","2029-04-02T13:00").path("total").asLong()).isZero();
  jdbc.sql("UPDATE venue_reservation SET status='WITHDRAWN' WHERE applicant_id=:u").param("u",f.user()).update();assertThat(find(b,v,"2029-04-02T12:00","2029-04-02T13:00").path("total").asLong()).isEqualTo(1);
 }
 @ParameterizedTest @ValueSource(strings={"DISABLED","CAPACITY","NO_OWNER","SELF_OWNER","CLASSROOM_DISABLED","OVERDUE_USE"})
 void unusableVenuesAreNeverRecommended(String mode)throws Exception{
  var f=fixture();long v=venue(f,"EAST",30);if(mode.equals("DISABLED"))jdbc.sql("UPDATE venue_resource SET enabled=FALSE WHERE id=:v").param("v",v).update();
  if(mode.equals("CAPACITY"))jdbc.sql("UPDATE venue_resource SET capacity=5 WHERE id=:v").param("v",v).update();
  if(mode.equals("NO_OWNER")||mode.equals("SELF_OWNER"))jdbc.sql("DELETE FROM venue_manager WHERE venue_id=:v").param("v",v).update();
  if(mode.equals("SELF_OWNER")){jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,'STUDENT_AFFAIRS')").param("u",f.user()).update();jdbc.sql("INSERT INTO venue_manager(venue_id,user_id) VALUES(:v,:u)").param("v",v).param("u",f.user()).update();}
  if(mode.equals("CLASSROOM_DISABLED")){long room=TestRooms.ensure(jdbc,"停用计划教室-"+v);jdbc.sql("UPDATE venue_resource SET classroom_resource_id=:r WHERE id=:v").param("r",room).param("v",v).update();jdbc.sql("UPDATE classroom_resource SET enabled=FALSE WHERE id=:r").param("r",room).update();}
  if(mode.equals("OVERDUE_USE"))jdbc.sql("UPDATE venue_reservation SET venue_id=:v,status='IN_USE',starts_at=CURRENT_TIMESTAMP-INTERVAL '2' HOUR,ends_at=CURRENT_TIMESTAMP-INTERVAL '1' HOUR WHERE applicant_id=:u").param("v",v).param("u",f.user()).update();
  assertThat(find(login(f.username()),v,"2029-04-02T12:00","2029-04-02T13:00").path("total").asLong()).isZero();
 }
 @Test void campusAndCapacityFiltersApplyWithoutMixingEquipment()throws Exception{
  var f=fixture();long v=venue(f,"WEST",30);var args=args(v,"2029-04-02T12:00","2029-04-02T14:00");assertThat(planned(login(f.username()),"find_available_venues",args).path("total").asLong()).isZero();args.put("campus","WEST");assertThat(planned(login(f.username()),"find_available_venues",args).path("total").asLong()).isEqualTo(1);
  jdbc.sql("UPDATE venue_resource SET category='EQUIPMENT' WHERE id=:v").param("v",v).update();assertThat(planned(login(f.username()),"find_available_venues",args).path("total").asLong()).isZero();
 }
 @ParameterizedTest @ValueSource(strings={"userId","url","sql"})
 void modelCannotAddIdentityOrArbitraryFields(String field)throws Exception{
  var f=fixture();long v=venue(f,"EAST",30);var a=args(v,"2029-04-02T12:00","2029-04-02T14:00");a.put(field,"1");var result=planned(login(f.username()),"find_available_venues",a);assertThat(result.path("status").asText()).isEqualTo("ERROR");assertThat(result.path("items").isEmpty()).isTrue();
 }
 @Test void availabilityReturnsSafePrefillLinkAndNeverWritesBusinessData()throws Exception{
  var f=fixture();long v=venue(f,"EAST",30);long count=jdbc.sql("SELECT COUNT(*) FROM venue_reservation").query(Long.class).single();var row=find(login(f.username()),v,"2029-04-02T12:00","2029-04-02T14:00");assertThat(row.path("items").path(0).path("targetPath").asText()).contains("resource="+v,"starts=2029-04-02T12:00","ends=2029-04-02T14:00","people=20");assertThat(row.path("message").asText()).contains("提交","复核");assertThat(jdbc.sql("SELECT COUNT(*) FROM venue_reservation").query(Long.class).single()).isEqualTo(count);
 }
 @Test void unavailableRoleToolAndSpoofedReservationIdCannotLeakRows()throws Exception{
  var f=fixture();assertThat(planned(login("teacher1001"),"my_schedule",Map.of()).path("status").asText()).isNotEqualTo("OK");
  var row=planned(login("20260002"),"my_reservations",Map.of("id",booking(f)));assertThat(row.path("status").asText()).isEqualTo("NOT_FOUND");
 }
 @Test void askingForOthersScheduleOrAutomaticCancellationIsRefused()throws Exception{
  var b=login("20260001");for(String q:java.util.List.of("查看其他同学的日程","帮我取消我的预约")){assertThat(ok(query(b,q,null)).path("results").isEmpty()).isTrue();}
 }
 @Test void standaloneWeekdayAndVagueTimesAskForClarification()throws Exception{
  var b=login("20260001");var row=ok(query(b,"周一东校区20人找个空闲场地，上午到下午",null)).path("results").path(0);assertThat(row.path("status").asText()).isEqualTo("NEEDS_INPUT");
 }
 private JsonNode find(Browser b,long v,String start,String end)throws Exception{return planned(b,"find_available_venues",args(v,start,end));}
 private java.util.HashMap<String,Object> args(long v,String start,String end){var a=new java.util.HashMap<String,Object>();a.put("campus","EAST");a.put("participants",20);a.put("startsAt",start);a.put("endsAt",end);a.put("keyword",venueName(v));return a;}
 private JsonNode planned(Browser b,String tool,Map<String,?> args)throws Exception{doReturn(true).when(model).ready();doReturn(new Plan(java.util.List.of(new Call(tool,json.valueToTree(args))),1,1)).when(model).plan(anyString(),anyList(),nullable(String.class));var response=b.send("POST","/api/assistant/query",Map.of("message","查询当前业务","mode","DEEPSEEK","externalConsent",true));if(response.statusCode()!=200)return json.readTree(response.body());return ok(response).path("results").path(0);}
 private HttpResponse<String> query(Browser b,String message,Map<String,Object> context)throws Exception{var body=new java.util.HashMap<String,Object>();body.put("message",message);body.put("mode","LOCAL");body.put("externalConsent",false);body.put("context",context);return b.send("POST","/api/assistant/query",body);}
 private long booking(Fixture f){return jdbc.sql("SELECT id FROM venue_reservation WHERE applicant_id=:u").param("u",f.user()).query(Long.class).single();}
 private String venueName(long v){return jdbc.sql("SELECT name FROM venue_resource WHERE id=:v").param("v",v).query(String.class).single();}
 private long venue(Fixture f,String campus,int capacity)throws Exception{var a=new java.util.HashMap<String,Object>();String key="计划-"+UUID.randomUUID().toString().substring(0,8);a.put("campusId",campus.equals("EAST")?1:2);a.put("code","PLAN-"+UUID.randomUUID());a.put("name",key);a.put("category","VENUE");a.put("buildingName","计划教学楼");a.put("locationDetail","101室");a.put("capacity",capacity);a.put("enabled",true);a.put("managerUserIds",java.util.List.of(jdbc.sql("SELECT id FROM app_user WHERE username='affairs1001'").query(Long.class).single()));return ok(login("affairs1001").send("POST","/api/reservations/venues",a)).path("id").asLong();}
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
