package cn.zzuit.campus;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:affairs-timeline-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true","app.agent.deepseek.enabled=false"})
class AffairTimelineIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    static final String PRIVATE="TIMELINE-PRIVATE-DETAIL",HOUSING="/api/housing/transfers";
    @Test void allFourKindsRequireCurrentPermissionAndTheExactOwner() throws Exception {
        assertThat(new Browser().get(path("LEAVE",1)).statusCode()).isEqualTo(401);
        for(String kind:List.of("LEAVE","CERTIFICATE","REPAIR","HOUSING")) {
            var f=fixture(kind);assertThat(ok(f.actor().get(path(f))).path("requestId").asLong()).isEqualTo(f.id());
            assertThat(login("20260002").get(path(f)).statusCode()).as(kind).isEqualTo(404);
            for(String name:List.of("admin","teacher1001","affairs1001","dorm1001"))assertThat(login(name).get(path(f)).statusCode()).as(kind+name).isEqualTo(403);
            assertThat(f.actor().get(path(kind,999999999)).statusCode()).isEqualTo(404);
        }
    }
    @Test void leaveAndCertificateTimelinesUseRealRevisionsWithoutCopyingPrivateSnapshots() throws Exception {
        for(String kind:List.of("LEAVE","CERTIFICATE")) {
            var f=fixture(kind);String root=kind.equals("LEAVE")?"/api/leave/":"/api/certificates/";
            var submitted=ok(f.actor().send("POST",root+"mine/"+f.id()+"/submit",Map.of("version",0,"comment","本人提交")));
            var reviewer=login(kind.equals("LEAVE")?"counselor1001":"affairs1001");
            var returned=ok(reviewer.send("POST",root+"review/"+f.id()+"/decision",Map.of("version",submitted.path("version").asInt(),"action","RETURN","comment","请补充用途与时间")));
            var again=ok(f.actor().send("POST",root+"mine/"+f.id()+"/submit",Map.of("version",returned.path("version").asInt(),"comment","已补充，重新提交")));
            ok(reviewer.send("POST",root+"review/"+f.id()+"/decision",Map.of("version",again.path("version").asInt(),"action","APPROVE","comment","核对通过")));
            var response=f.actor().get(path(f));var timeline=ok(response);
            assertThat(timeline.path("total").asInt()).isEqualTo(5);assertThat(timeline.path("items").get(0).path("comment").asText()).isEqualTo("核对通过");
            assertThat(timeline.path("items").get(2).path("title").asText()).isEqualTo("退回补充资料");
            assertThat(timeline.path("items").get(1).path("title").asText()).isEqualTo("补交后重新提交");
            assertThat(timeline.path("items").get(0).path("status").asText()).isEqualTo(kind.equals("LEAVE")?"APPROVED":"COMPLETED");
            assertThat(timeline.toString()).doesNotContain(PRIVATE,"identityNumber","identity_number","contact","snapshot","password","fileBytes");
            assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        }
    }
    @Test void repairShowsActualSaveAndWithdrawalWithoutTheSavedContactAndDescription() throws Exception {
        var f=fixture("REPAIR");
        ok(f.actor().send("POST","/api/repairs/mine/"+f.id()+"/withdraw",Map.of("requestKey",key(),"version",0,"comment","本人撤回")));
        var timeline=ok(f.actor().get(path(f)));
        assertThat(timeline.path("status").asText()).isEqualTo("WITHDRAWN");assertThat(timeline.path("items").get(0).path("title").asText()).isEqualTo("撤回报修");
        assertThat(timeline.path("items").get(1).path("title").asText()).isEqualTo("保存报修资料");assertThat(timeline.toString()).doesNotContain(PRIVATE,"13900000000","snapshot","description","contactWindow");
    }
    @Test void pagingUsesTheServerRevisionAndDetectsNewRecordsBetweenPages() throws Exception {
        var f=fixture("LEAVE");
        for(int i=0;i<6;i++)ok(f.actor().send("PUT","/api/leave/mine/"+f.id(),leaveUpdate(f,i)));
        var first=ok(f.actor().get(path(f)+"?pageSize=2"));int version=first.path("version").asInt();
        var second=ok(f.actor().get(path(f)+"?page=2&pageSize=2&expectedVersion="+version));
        assertThat(first.path("total").asInt()).isEqualTo(7);assertThat(second.path("items").get(0).path("key").asText()).isNotEqualTo(first.path("items").get(0).path("key").asText());
        ok(f.actor().send("PUT","/api/leave/mine/"+f.id(),leaveUpdate(f,version)));
        var stale=f.actor().get(path(f)+"?page=3&pageSize=2&expectedVersion="+version);
        assertThat(stale.statusCode()).isEqualTo(409);assertThat(json.readTree(stale.body()).path("code").asText()).isEqualTo("AFFAIR_TIMELINE_CHANGED");
        assertThat(ok(f.actor().get(path(f))).path("version").asInt()).isEqualTo(version+1);
    }
    @Test void housingRecordsSupplementKeepAndExtendWithoutInventedHistoricalStates() throws Exception {
        var f=fixture("HOUSING");var reviewer=login("affairs1001");var manager=login("dorm1001");
        housingAction(f,f.actor(),"mine","submit",null);
        housingAction(f,reviewer,"review","return",null);
        ok(f.actor().send("POST",HOUSING+"/mine/"+f.id()+"/supplement",housingDraft(housingVersion(f))));
        housingAction(f,reviewer,"review","approve",null);
        housingAction(f,manager,"assign","reserve",Map.of("bedId",f.target(),"reason","预留说明"));
        housingAction(f,f.actor(),"mine","cancel-request",null);
        assertThat(ok(f.actor().get(path(f))).path("nextStep").asText()).contains("等待宿管处理");
        housingAction(f,manager,"assign","keep",null);
        housingAction(f,manager,"assign","extend",Map.of("reason","调整期限","reserveUntil",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(4).toString()));
        housingAction(f,manager,"assign","check-in",null);
        var timeline=ok(f.actor().get(path(f)+"?pageSize=50"));
        assertThat(timeline.path("status").asText()).isEqualTo("COMPLETED");assertThat(timeline.path("items").get(0).path("action").asText()).isEqualTo("CHECK_IN");
        var actions=new ArrayList<String>();for(var event:timeline.path("items")){actions.add(event.path("action").asText());assertThat(event.path("status").isNull()).isTrue();assertThat(event.path("version").isNull()).isTrue();}
        assertThat(actions).contains("SUPPLEMENT","KEEP","EXTEND");assertThat(timeline.path("historyNote").asText()).contains("不补造历史状态");
        assertThat(timeline.toString()).doesNotContain(PRIVATE,"input_digest","sourcePlace","materialNote","bedId");
    }
    @Test void housingExpiryIsCapturedOnceAndForeignQueriesDoNotExpireReservations() throws Exception {
        var f=fixture("HOUSING");housingAction(f,f.actor(),"mine","submit",null);housingAction(f,login("affairs1001"),"review","approve",null);
        housingAction(f,login("dorm1001"),"assign","reserve",Map.of("bedId",f.target(),"reason","预留"));
        jdbc.sql("UPDATE housing_transfer SET reserve_until=:until WHERE id=:id").param("until",LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusHours(1)).param("id",f.id()).update();
        assertThat(login("20260002").get(path(f)).statusCode()).isEqualTo(404);assertThat(housingState(f)).isEqualTo("AWAITING_CHECKIN");
        var timeline=ok(f.actor().get(path(f)));assertThat(timeline.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(timeline.path("items").get(0).path("action").asText()).isEqualTo("EXPIRE");assertThat(timeline.path("items").get(0).path("actor").asText()).isEqualTo("系统");
        long notices=count("SELECT COUNT(*) FROM user_notice WHERE source_type='HOUSING' AND source_id="+f.id());
        assertThat(ok(f.actor().get(path(f))).toString()).isEqualTo(timeline.toString());
        assertThat(count("SELECT COUNT(*) FROM user_notice WHERE source_type='HOUSING' AND source_id="+f.id())).isEqualTo(notices);
        assertThat(count("SELECT COUNT(*) FROM foundation_audit WHERE entity_type='HOUSING_TRANSFER' AND entity_id="+f.id()+" AND action='TRANSFER_EXPIRE'")).isEqualTo(1);
    }
    @Test void revokedBusinessPermissionAndChangedSecurityVersionInvalidateTimelineAccess() throws Exception {
        var f=fixture("LEAVE");String role=key();
        jdbc.sql("INSERT INTO app_role(code,name) VALUES(:role,'时间线限权角色')").param("role",role).update();
        jdbc.sql("INSERT INTO role_permission(role_code,permission_code) SELECT :role,permission_code FROM role_permission WHERE role_code='STUDENT' AND permission_code<>'leave:apply'").param("role",role).update();
        jdbc.sql("DELETE FROM user_role WHERE user_id=:user").param("user",user(f.owner())).update();jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:user,:role)").param("user",user(f.owner())).param("role",role).update();
        assertThat(f.actor().get(path(f)).statusCode()).isEqualTo(403);
        var other=fixture("CERTIFICATE");jdbc.sql("UPDATE app_user SET security_version=security_version+1 WHERE id=:id").param("id",user(other.owner())).update();
        assertThat(other.actor().get(path(other)).statusCode()).isEqualTo(401);
    }
    @Test void invalidArgumentsAndLegacyMissingHistoryNeverCreateTimelineNodes() throws Exception {
        var f=fixture("LEAVE");
        for(String query:List.of("?page=0","?pageSize=51","?expectedVersion=-1","?page=1000001"))assertThat(f.actor().get(path(f)+query).statusCode()).isEqualTo(400);
        assertThat(f.actor().get(path("OTHER",f.id())).statusCode()).isEqualTo(400);assertThat(f.actor().get(path("LEAVE",0)).statusCode()).isEqualTo(400);
        jdbc.sql("DELETE FROM leave_revision WHERE request_id=:id").param("id",f.id()).update();
        long notices=count("SELECT COUNT(*) FROM user_notice");var timeline=ok(f.actor().get(path(f)));
        assertThat(timeline.path("total").asInt()).isZero();assertThat(timeline.path("items").isEmpty()).isTrue();assertThat(timeline.path("status").asText()).isEqualTo("DRAFT");
        assertThat(count("SELECT COUNT(*) FROM user_notice")).isEqualTo(notices);
    }
    private Fixture fixture(String kind)throws Exception {
        String name="tl"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,class_group_id,demo) SELECT :name,password_hash,'时间线演示学生',:identity,campus_id,department_id,class_group_id,TRUE FROM app_user WHERE username='20260001'").param("name",name).param("identity",PRIVATE+name).update();
        jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:id,'STUDENT')").param("id",user(name)).update();var actor=login(name);long target=0;JsonNode request;
        if(kind.equals("LEAVE"))request=ok(actor.send("POST","/api/leave/mine",leaveDraft(0)));
        else if(kind.equals("CERTIFICATE"))request=ok(actor.send("POST","/api/certificates/mine",Map.of("requestKey",key(),"purpose",PRIVATE,"deliveryMethod","DIGITAL","identityConfirmed",true,"version",0)));
        else if(kind.equals("REPAIR"))request=ok(actor.send("POST","/api/repairs/mine",Map.of("requestKey",key(),"version",0,"category","WATER","title","测试报修","description",PRIVATE,"locationDetail",PRIVATE,"contact","13900000000","contactWindow",PRIVATE)));
        else {
            var admin=login("admin");long building=ok(admin.send("POST","/api/admin/housing/buildings",Map.of("campusId",1,"code",key(),"name","时间线验收楼（演示）","provenance","DEMO","version",0,"reason","独立验收"))).path("id").asLong();
            long room=ok(admin.send("POST","/api/admin/housing/rooms",Map.of("buildingId",building,"code","101","name","101 室","floorLabel","1 层","version",0,"reason","独立验收"))).path("id").asLong();
            long first=0;for(String code:List.of("A","B")){long bed=ok(admin.send("POST","/api/admin/housing/beds",Map.of("roomId",room,"code",code,"label",code+" 号床","version",0,"reason","独立验收"))).path("id").asLong();if(code.equals("A"))first=bed;else target=bed;}
            ok(admin.send("POST","/api/admin/housing/scopes",Map.of("userId",user("dorm1001"),"buildingId",building,"version",0,"reason","独立验收")));
            ok(login("dorm1001").send("POST","/api/housing/assignments",Map.of("requestKey",key(),"studentId",user(name),"bedId",first,"reason","独立验收")));
            request=ok(actor.send("POST",HOUSING+"/mine",housingDraft(0)));
        }
        return new Fixture(kind,request.path("id").asLong(),name,actor,target);
    }
    private Map<String,Object> leaveDraft(int version) {var start=LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(10);return Map.of("requestKey",key(),"version",version,"reasonType","PERSONAL","reason",PRIVATE,"contact","13900000000","startsAt",start.toString(),"endsAt",start.plusHours(1).toString());}
    private Map<String,Object> leaveUpdate(Fixture f,int version) {var fields=new HashMap<String,Object>(leaveDraft(version));fields.put("requestKey",jdbc.sql("SELECT request_key FROM leave_request WHERE id=:id").param("id",f.id()).query(String.class).single());return fields;}
    private Map<String,Object> housingDraft(int version) {return Map.of("requestKey",key(),"version",version,"reason",PRIVATE,"preferredArea",PRIVATE,"materialNote",PRIVATE);}
    private void housingAction(Fixture f,Browser actor,String view,String action,Map<String,Object> extra)throws Exception {var body=new HashMap<String,Object>(Map.of("requestKey",key(),"version",housingVersion(f),"comment","核对后办理"));if(extra!=null)body.putAll(extra);ok(actor.send("POST",HOUSING+"/"+view+"/"+f.id()+"/"+action,body));}
    private int housingVersion(Fixture f) {return jdbc.sql("SELECT version FROM housing_transfer WHERE id=:id").param("id",f.id()).query(Integer.class).single();}
    private String housingState(Fixture f) {return jdbc.sql("SELECT status FROM housing_transfer WHERE id=:id").param("id",f.id()).query(String.class).single();}
    private long user(String name) {return jdbc.sql("SELECT id FROM app_user WHERE username=:name").param("name",name).query(Long.class).single();}
    private long count(String sql) {return jdbc.sql(sql).query(Long.class).single();}
    private String key(){return "TL"+UUID.randomUUID().toString().replace("-","");}
    private String path(String kind,long id) {return "/api/affairs/mine/"+kind+"/"+id+"/timeline";}
    private String path(Fixture f) {return path(f.kind(),f.id());}
    private JsonNode ok(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return json.readTree(response.body());}
    private Browser login(String name)throws Exception{var browser=new Browser();browser.csrf();ok(browser.send("POST","/api/auth/login",Map.of("username",name,"password","Demo@2026")));browser.csrf();return browser;}
    private record Fixture(String kind,long id,String owner,Browser actor,long target) {}
    private class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String header,token;
        HttpRequest.Builder builder(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));}
        HttpResponse<String> get(String path)throws Exception{return http.send(builder(path).GET().build(),HttpResponse.BodyHandlers.ofString());}
        void csrf()throws Exception{var data=json.readTree(get("/api/auth/csrf").body());header=data.path("headerName").asText();token=data.path("token").asText();}
        HttpResponse<String> send(String method,String path,Map<String,?> body)throws Exception{return http.send(builder(path).header(header,token).header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
    }
}
