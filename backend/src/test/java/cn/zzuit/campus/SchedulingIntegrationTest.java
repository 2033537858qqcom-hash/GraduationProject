package cn.zzuit.campus;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:campus-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=true"
})
class SchedulingIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    final JsonMapper json=JsonMapper.builder().build();

    @Test
    void newAndRestoredMembershipRecheckAllKindsOfStudentOccupancy() throws Exception {
        var staff=new Browser();
        long term=term(staff);
        for (boolean restore:List.of(false,true)) {
            for (int kind=0;kind<4;kind++) {
                long first=group(staff,term,"teacher1001","20260001");
                long second=group(staff,term,"teacher1002","20260002");
                String day="2029-04-"+String.format("%02d",1+kind+(restore?4:0));
                arrange(staff,first,"teacher1001","A-"+first,day,kind<2);
                arrange(staff,second,"teacher1002","B-"+second,day,kind%2==0);
                if (restore) {
                    // Existing inactive relation, rather than a new row.
                    jdbc.sql("INSERT INTO teaching_member(class_id,student_id,active) VALUES(:c,:u,FALSE)")
                        .param("c",second).param("u",user("20260001")).update();
                }
                long audits=count("SELECT COUNT(*) FROM teaching_audit WHERE class_id="+second);
                long notices=count("SELECT COUNT(*) FROM user_notice");
                var response=staff.send("POST","/api/academic/classes/"+second+"/members",Map.of("userId",user("20260001"),"active",true));
                assertThat(response.statusCode()).as("restore=%s kind=%s %s",restore,kind,response.body()).isEqualTo(409);
                assertThat(response.body()).contains("TEACHING_CONFLICT");
                assertThat(count("SELECT COUNT(*) FROM teaching_member WHERE class_id="+second+" AND student_id="+user("20260001")+" AND active=TRUE")).isZero();
                assertThat(count("SELECT COUNT(*) FROM teaching_member WHERE class_id="+second+" AND student_id="+user("20260001"))).isEqualTo(restore?1:0);
                assertThat(count("SELECT COUNT(*) FROM teaching_audit WHERE class_id="+second)).isEqualTo(audits);
                assertThat(count("SELECT COUNT(*) FROM user_notice")).isEqualTo(notices);
            }
        }
    }

    @Test
    void publishedExamsBlockNewLessonsForSharedRoomsOrStudents() throws Exception {
        var staff=new Browser(); long term=term(staff);
        for (boolean sameRoom:List.of(true,false)) {
            long first=group(staff,term,"teacher1001","20260001");
            long second=group(staff,term,"teacher1002",sameRoom?"20260002":"20260001");
            String day=sameRoom?"2029-05-01":"2029-05-02", room="房间-"+first;
            arrange(staff,first,"teacher1001",room,day,false);
            long audits=count("SELECT COUNT(*) FROM teaching_audit WHERE class_id="+second);
            var response=lesson(staff,second,"teacher1002",sameRoom?room:"其他-"+second,day+"T08:30:00",day+"T09:30:00");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
            assertThat(count("SELECT COUNT(*) FROM teaching_lesson WHERE class_id="+second)).isZero();
            assertThat(count("SELECT COUNT(*) FROM teaching_audit WHERE class_id="+second)).isEqualTo(audits);
        }
    }

    @Test
    void lessonsBlockExamPublicationForSharedRoomsOrStudents() throws Exception {
        var staff=new Browser(); long term=term(staff);
        for (boolean sameRoom:List.of(true,false)) {
            long first=group(staff,term,"teacher1001","20260001");
            long second=group(staff,term,"teacher1002",sameRoom?"20260002":"20260001");
            String day=sameRoom?"2029-06-01":"2029-06-02", room="房间-"+first;
            arrange(staff,first,"teacher1001",room,day,true);
            long exam=exam(staff,second,sameRoom?room:"其他-"+second,day+"T08:30:00",day+"T09:30:00",0);
            long audits=count("SELECT COUNT(*) FROM teaching_audit WHERE class_id="+second);
            var response=publish(staff,exam,0);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
            var record=staff.get("/api/academic/classes/"+second).path("exams").get(0);
            assertThat(record.path("status").asText()).isEqualTo("DRAFT");
            assertThat(record.path("version").asInt()).isZero();
            assertThat(count("SELECT COUNT(*) FROM teaching_audit WHERE class_id="+second)).isEqualTo(audits);
        }
    }

    @Test
    void draftsDoNotReserveTimeAndAdjacentIntervalsRemainAllowed() throws Exception {
        var staff=new Browser(); long term=term(staff);
        long first=group(staff,term,"teacher1001","20260001");
        long second=group(staff,term,"teacher1002","20260002");
        String room="房间-"+first, day="2029-07-01";
        long id=exam(staff,first,room,day+"T08:00:00",day+"T09:00:00",0);
        assertThat(lesson(staff,second,"teacher1002",room,day+"T08:00:00",day+"T09:00:00").statusCode()).isEqualTo(200);
        assertThat(publish(staff,id,0).statusCode()).isEqualTo(409);
        exam(staff,first,room,day+"T09:00:00",day+"T10:00:00",0);
        assertThat(publish(staff,id,1).statusCode()).isEqualTo(200);
        assertThat(lesson(staff,second,"teacher1002",room,day+"T10:00:00",day+"T11:00:00").statusCode()).isEqualTo(200);
        // Joining a class with adjacent arrangements remains valid.
        assertThat(staff.send("POST","/api/academic/classes/"+second+"/members",Map.of("userId",user("20260001"),"active",true)).statusCode()).isEqualTo(200);
    }

    @Test
    void unrelatedStudentsAndRoomsMayUseTheSameTime() throws Exception {
        var staff=new Browser(); long term=term(staff);
        long first=group(staff,term,"teacher1001","20260001");
        long second=group(staff,term,"teacher1002","20260002");
        arrange(staff,first,"teacher1001","房间-"+first,"2029-08-01",false);
        assertThat(lesson(staff,second,"teacher1002","房间-"+second,"2029-08-01T08:00:00","2029-08-01T09:00:00").statusCode()).isEqualTo(200);
    }

    @Test
    void simultaneousLessonAndExamWritesCannotBothClaimTheRoom() throws Exception {
        var staff=new Browser(); var other=new Browser(); long term=term(staff);
        long first=group(staff,term,"teacher1001","20260001");
        long second=group(staff,term,"teacher1002","20260002");
        String room="竞争教室-"+first;
        long id=exam(staff,first,room,"2029-09-01T08:00:00","2029-09-01T09:00:00",0);
        var one=CompletableFuture.supplyAsync(()->unchecked(()->publish(staff,id,0).statusCode()));
        var two=CompletableFuture.supplyAsync(()->unchecked(()->lesson(other,second,"teacher1002",room,"2029-09-01T08:00:00","2029-09-01T09:00:00").statusCode()));
        assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(200,409);
    }

    @Test
    void simultaneousMembershipAndSchedulingCannotIntroduceStudentOverlap() throws Exception {
        var staff=new Browser(); var other=new Browser(); long term=term(staff);
        long first=group(staff,term,"teacher1001","20260001");
        long second=group(staff,term,"teacher1002","20260002");
        arrange(staff,second,"teacher1002","房间-"+second,"2029-10-01",true);
        var one=CompletableFuture.supplyAsync(()->unchecked(()->staff.send("POST","/api/academic/classes/"+second+"/members",Map.of("userId",user("20260001"),"active",true)).statusCode()));
        var two=CompletableFuture.supplyAsync(()->unchecked(()->lesson(other,first,"teacher1001","房间-"+first,"2029-10-01T08:00:00","2029-10-01T09:00:00").statusCode()));
        assertThat(List.of(one.get(),two.get())).containsExactlyInAnyOrder(200,409);
    }

    private long user(String name) { return jdbc.sql("SELECT id FROM app_user WHERE username=:n").param("n",name).query(Long.class).single(); }
    private long count(String sql) { return jdbc.sql(sql).query(Long.class).single(); }
    private long term(Browser staff) throws Exception {
        return json.readTree(staff.send("POST","/api/academic/terms",Map.of("name","冲突回归-"+UUID.randomUUID(),"startsOn","2029-01-01","endsOn","2029-12-31")).body()).path("id").asLong();
    }
    private long group(Browser staff,long term,String teacher,String student) throws Exception {
        var response=staff.send("POST","/api/academic/classes",Map.of("termId",term,"courseId",1,"name","冲突班-"+UUID.randomUUID(),"teacherIds",List.of(user(teacher)),"studentIds",List.of(user(student))));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("info").path("id").asLong();
    }
    private void arrange(Browser staff,long group,String teacher,String room,String day,boolean lesson) throws Exception {
        if (lesson) assertThat(lesson(staff,group,teacher,room,day+"T08:00:00",day+"T09:00:00").statusCode()).isEqualTo(200);
        else assertThat(publish(staff,exam(staff,group,room,day+"T08:00:00",day+"T09:00:00",0),0).statusCode()).isEqualTo(200);
    }
    private HttpResponse<String> lesson(Browser staff,long group,String teacher,String room,String start,String end) throws Exception {
        return staff.send("POST","/api/academic/classes/"+group+"/lessons",Map.of("teacherId",user(teacher),"roomId",TestRooms.ensure(jdbc,room),"startsAt",start,"endsAt",end));
    }
    private long exam(Browser staff,long group,String room,String start,String end,int version) throws Exception {
        var response=staff.send("PUT","/api/academic/classes/"+group+"/exam",Map.of("roomId",TestRooms.ensure(jdbc,room),"startsAt",start,"endsAt",end,"version",version));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).path("id").asLong();
    }
    private HttpResponse<String> publish(Browser staff,long id,int version) throws Exception { return staff.send("POST","/api/academic/exams/"+id+"/state",Map.of("status","PUBLISHED","version",version)); }
    @FunctionalInterface interface Request { int send() throws Exception; }
    private int unchecked(Request request) { try { return request.send(); } catch(Exception e) { throw new RuntimeException(e); } }
    private class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        String header,token;
        Browser() throws Exception {
            csrf(); assertThat(send("POST","/api/auth/login",Map.of("username","academic1001","password","Demo@2026")).statusCode()).isEqualTo(200); csrf();
        }
        void csrf() throws Exception { var result=get("/api/auth/csrf"); header=result.path("headerName").asText(); token=result.path("token").asText(); }
        JsonNode get(String path) throws Exception { return json.readTree(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString()).body()); }
        HttpResponse<String> send(String method,String path,Map<String,?> body) throws Exception {
            return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").header(header,token)
                .method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
