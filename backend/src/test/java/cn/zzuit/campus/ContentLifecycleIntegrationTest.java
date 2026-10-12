package cn.zzuit.campus;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:campus-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true"
})
class ContentLifecycleIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcClient jdbc;
    final JsonMapper json=JsonMapper.builder().build();
    static final String ROOT="/api/admin/content";

    @Test void rejectedDraftRetainsReasonAndCanBeEditedAndResubmitted() throws Exception {
        var author=login("editor"); var reviewer=login("reviewer"); var draft=create(author); long id=draft.path("id").asLong();
        var submitted=action(author,id,"submit",draft,"");
        assertThat(reviewer.send("POST",ROOT+"/"+id+"/reject",Map.of("version",submitted.path("version").asInt(),"reason"," ")).statusCode()).isEqualTo(400);
        var rejected=action(reviewer,id,"reject",submitted,"请补充活动地点");
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("reviewReason").asText()).isEqualTo("请补充活动地点");
        var input=input(draft.path("slug").asText(),rejected.path("version").asInt()); input.put("body","修正后的活动地点");
        var saved=ok(author.send("PUT",ROOT+"/"+id,input));
        assertThat(saved.path("status").asText()).isEqualTo("DRAFT");
        assertThat(saved.path("reviewReason").asText()).contains("活动地点");
        var again=action(author,id,"submit",saved,"");
        assertThat(again.path("reviewReason").isNull()).isTrue();
        action(reviewer,id,"publish",again,"");
        assertThat(new Browser().get(publicPath(draft)).body()).contains("修正后的活动地点");
    }

    @Test void onlyAuthorCanRecallSubmittedWorkAndStaleRecallDoesNothing() throws Exception {
        var author=login("editor"); var other=login("admin"); var draft=create(author); long id=draft.path("id").asLong();
        var submitted=action(author,id,"submit",draft,"");
        assertThat(other.send("POST",ROOT+"/"+id+"/recall",version(submitted,"调整文案")).statusCode()).isEqualTo(409);
        assertThat(author.send("POST",ROOT+"/"+id+"/recall",version(draft,"旧版本")).statusCode()).isEqualTo(409);
        var recalled=action(author,id,"recall",submitted,"补充说明后重新送审");
        assertThat(recalled.path("status").asText()).isEqualTo("DRAFT");
        assertThat(new Browser().get(publicPath(draft)).statusCode()).isEqualTo(404);
        assertThat(author.tree(ROOT+"/"+id+"/history").path("total").asInt()).isEqualTo(3);
    }

    @Test void correctionNeverLeaksUnapprovedTextAndPublishedSlugIsStable() throws Exception {
        var author=login("editor"); var reviewer=login("reviewer"); var original=publish(author,reviewer); long id=original.path("id").asLong();
        var draft=action(author,id,"revise",original,"更正正文");
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("live").path("visible").asBoolean()).isTrue();
        var input=input(original.path("slug").asText(),draft.path("version").asInt()); input.put("body","尚未批准的更正内容");
        var saved=ok(author.send("PUT",ROOT+"/"+id,input));
        var publicBefore=new Browser().get(publicPath(original));
        assertThat(publicBefore.body()).contains("原版正文").doesNotContain("尚未批准","changeReason","reviewReason","createdBy");
        var changedSlug=new HashMap<>(input); changedSlug.put("version",saved.path("version").asInt()); changedSlug.put("slug","changed-"+UUID.randomUUID());
        assertThat(author.send("PUT",ROOT+"/"+id,changedSlug).statusCode()).isEqualTo(409);
        var submitted=action(author,id,"submit",saved,"");
        var rejected=action(reviewer,id,"reject",submitted,"请核验更正依据");
        assertThat(new Browser().get(publicPath(original)).body()).contains("原版正文");
        var resubmitted=action(author,id,"submit",rejected,""); action(reviewer,id,"publish",resubmitted,"");
        assertThat(new Browser().get(publicPath(original)).body()).contains("尚未批准的更正内容").doesNotContain("原版正文");
    }

    @Test void withdrawalRemovesBothPublicViewsAndRequiresFreshReviewToRestore() throws Exception {
        var author=login("editor"); var reviewer=login("reviewer"); var original=publish(author,reviewer); long id=original.path("id").asLong();
        var correcting=action(author,id,"revise",original,"修订期间紧急撤下");
        var submitted=action(author,id,"submit",correcting,"");
        var removed=action(reviewer,id,"unpublish",submitted,"活动取消，暂时撤下");
        assertThat(removed.path("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(removed.path("live").path("visible").asBoolean()).isFalse();
        assertThat(new Browser().get(publicPath(original)).statusCode()).isEqualTo(404);
        assertThat(new Browser().get("/api/public/content").body()).doesNotContain(original.path("slug").asText());
        assertThat(reviewer.send("POST",ROOT+"/"+id+"/publish",version(removed,"")).statusCode()).isEqualTo(409);
        var resumed=action(author,id,"revise",removed,"确认活动恢复");
        assertThat(new Browser().get(publicPath(original)).statusCode()).isEqualTo(404);
        var pending=action(author,id,"submit",resumed,""); action(reviewer,id,"publish",pending,"");
        assertThat(new Browser().get(publicPath(original)).statusCode()).isEqualTo(200);
    }

    @Test void seedContentCanStartOwnedRevisionWhileSelfReviewRemainsForbidden() throws Exception {
        var author=login("admin"); var reviewer=login("reviewer");
        var seed=author.tree(ROOT).findValues("slug"); assertThat(seed.stream().anyMatch(n->n.asText().equals("school-introduction"))).isTrue();
        var record=author.tree(ROOT); JsonNode original=null;
        for(var item:record) if(item.path("slug").asText().equals("school-introduction")) original=item;
        assertThat(original).isNotNull(); assertThat(original.path("createdBy").isNull()).isTrue();
        long id=original.path("id").asLong(); var draft=action(author,id,"revise",original,"核验学校介绍");
        assertThat(draft.path("createdBy").asLong()).isEqualTo(author.tree("/api/me").path("id").asLong());
        var submitted=action(author,id,"submit",draft,"");
        assertThat(author.send("POST",ROOT+"/"+id+"/publish",version(submitted,"")).statusCode()).isEqualTo(403);
        assertThat(author.send("POST",ROOT+"/"+id+"/reject",version(submitted,"自己驳回")).statusCode()).isEqualTo(403);
        action(reviewer,id,"publish",submitted,"");
        assertThat(new Browser().get(publicPath(original)).statusCode()).isEqualTo(200);
    }

    @Test void permissionAndOwnershipChecksApplyToEveryNewOperation() throws Exception {
        var author=login("editor"); var reviewer=login("reviewer"); var draft=create(author); long id=draft.path("id").asLong();
        var pending=action(author,id,"submit",draft,"");
        assertThat(author.send("POST",ROOT+"/"+id+"/reject",version(pending,"无审核岗")).statusCode()).isEqualTo(403);
        assertThat(author.send("POST",ROOT+"/"+id+"/unpublish",version(pending,"无审核岗")).statusCode()).isEqualTo(403);
        assertThat(reviewer.send("POST",ROOT+"/"+id+"/revise",version(pending,"无编辑岗")).statusCode()).isEqualTo(403);
        assertThat(login("20260001").get(ROOT+"/"+id+"/history").statusCode()).isEqualTo(403);
        assertThat(new Browser().get(ROOT+"/"+id+"/revisions/0").statusCode()).isEqualTo(401);
        assertThat(author.get(ROOT+"/999999999/history").statusCode()).isEqualTo(404);
        assertThat(author.get(ROOT+"/"+id+"/revisions/9999").statusCode()).isEqualTo(404);
    }

    @Test void concurrentPublishAndRecallHaveOneWinnerAndConsistentPublicVisibility() throws Exception {
        var author=login("editor"); var reviewer=login("reviewer"); var draft=create(author); long id=draft.path("id").asLong(); var pending=action(author,id,"submit",draft,"");
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var one=executor.submit(()-> { start.await(); return reviewer.send("POST",ROOT+"/"+id+"/publish",version(pending,"")); });
            var two=executor.submit(()-> { start.await(); return author.send("POST",ROOT+"/"+id+"/recall",version(pending,"需要重新编辑")); });
            start.countDown(); var published=one.get(); var recalled=two.get();
            assertThat(List.of(published.statusCode(),recalled.statusCode())).containsExactlyInAnyOrder(200,409);
            assertThat(new Browser().get(publicPath(draft)).statusCode()).isEqualTo(published.statusCode()==200?200:404);
        }
        assertThat(jdbc.sql("SELECT COUNT(*) FROM content_audit WHERE content_id=:c").param("c",id).query(Long.class).single()).isEqualTo(3);
    }

    @Test void concurrentRevisionStartCannotStealAnotherEditorsWork() throws Exception {
        var first=login("editor"); var other=login("admin"); var original=publish(first,login("reviewer")); long id=original.path("id").asLong();
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var one=executor.submit(()-> { start.await(); return first.send("POST",ROOT+"/"+id+"/revise",version(original,"编辑修订")); });
            var two=executor.submit(()-> { start.await(); return other.send("POST",ROOT+"/"+id+"/revise",version(original,"管理员修订")); });
            start.countDown(); var a=one.get(); var b=two.get();
            assertThat(List.of(a.statusCode(),b.statusCode())).containsExactlyInAnyOrder(200,409);
            var winner=json.readTree(a.statusCode()==200?a.body():b.body()); var loser=a.statusCode()==200?other:first;
            assertThat(loser.send("PUT",ROOT+"/"+id,input(original.path("slug").asText(),winner.path("version").asInt())).statusCode()).isEqualTo(409);
        }
        assertThat(new Browser().get(publicPath(original)).statusCode()).isEqualTo(200);
    }

    @Test void historyPreservesImmutableSnapshotsAndSupportsBoundedPagination() throws Exception {
        var author=login("editor"); var original=create(author); long id=original.path("id").asLong();
        var changed=input(original.path("slug").asText(),0); changed.put("body","新的保存内容");
        ok(author.send("PUT",ROOT+"/"+id,changed));
        var old=author.tree(ROOT+"/"+id+"/revisions/0"); var newer=author.tree(ROOT+"/"+id+"/revisions/1");
        assertThat(old.path("content").path("body").asText()).isEqualTo("原版正文");
        assertThat(newer.path("content").path("body").asText()).isEqualTo("新的保存内容");
        assertThat(author.tree(ROOT+"/"+id+"/history?page=1&pageSize=1").path("total").asInt()).isEqualTo(2);
        assertThat(author.tree(ROOT+"/"+id+"/history?page=2&pageSize=1").path("items").get(0).path("action").asText()).isEqualTo("CREATED");
        assertThat(author.get(ROOT+"/"+id+"/history?pageSize=101").statusCode()).isEqualTo(400);
    }

    @Test void transitionsRejectMissingReasonsInvalidStateAndUnsafeRevisionSources() throws Exception {
        var author=login("editor"); var reviewer=login("reviewer"); var draft=create(author); long id=draft.path("id").asLong();
        assertThat(author.send("POST",ROOT+"/"+id+"/revise",version(draft,"不是已发布内容")).statusCode()).isEqualTo(409);
        assertThat(reviewer.send("POST",ROOT+"/"+id+"/unpublish",version(draft,"尚未发布")).statusCode()).isEqualTo(409);
        var pending=action(author,id,"submit",draft,"");
        assertThat(author.send("POST",ROOT+"/"+id+"/recall",Map.of("version",1)).statusCode()).isEqualTo(400);
        var recalled=action(author,id,"recall",pending,"准备补充来源");
        var unsafe=input(draft.path("slug").asText(),recalled.path("version").asInt()); unsafe.put("sourceType","PUBLIC_SOURCE"); unsafe.put("sourceUrl","javascript:alert(1)");
        assertThat(author.send("PUT",ROOT+"/"+id,unsafe).statusCode()).isEqualTo(400);
        unsafe.put("sourceUrl","relative-source"); assertThat(author.send("PUT",ROOT+"/"+id,unsafe).statusCode()).isEqualTo(400);
        unsafe.remove("sourceUrl"); assertThat(author.send("PUT",ROOT+"/"+id,unsafe).statusCode()).isEqualTo(400);
        assertThat(author.tree(ROOT+"/"+id+"/history").path("total").asInt()).isEqualTo(3);
    }

    private Map<String,Object> input(String slug,int version) { return new HashMap<>(Map.of("slug",slug,"category","notice","title","生命周期测试文章","summary","演示摘要","body","原版正文","sourceType","DEMO","version",version)); }
    private Map<String,Object> version(JsonNode item,String reason) { return Map.of("version",item.path("version").asInt(),"reason",reason); }
    private String publicPath(JsonNode item) { return "/api/public/content/"+item.path("slug").asText(); }
    private JsonNode create(Browser author) throws Exception { return ok(author.send("POST",ROOT,input("life-"+UUID.randomUUID(),0))); }
    private JsonNode publish(Browser author,Browser reviewer) throws Exception { var draft=create(author); long id=draft.path("id").asLong(); return action(reviewer,id,"publish",action(author,id,"submit",draft,""),""); }
    private JsonNode action(Browser browser,long id,String action,JsonNode item,String reason) throws Exception { return ok(browser.send("POST",ROOT+"/"+id+"/"+action,version(item,reason))); }
    private JsonNode ok(HttpResponse<String> response) throws Exception { assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return json.readTree(response.body()); }
    private Browser login(String name) throws Exception { var browser=new Browser(); browser.csrf(); ok(browser.send("POST","/api/auth/login",Map.of("username",name,"password","Demo@2026"))); browser.csrf(); return browser; }
    private class Browser {
        final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); String header,token;
        HttpResponse<String> get(String path) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString()); }
        JsonNode tree(String path) throws Exception { return json.readTree(get(path).body()); }
        void csrf() throws Exception { var data=tree("/api/auth/csrf"); header=data.path("headerName").asText(); token=data.path("token").asText(); }
        HttpResponse<String> send(String method,String path,Map<String,?> input) throws Exception { return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json").header(header,token).method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build(),HttpResponse.BodyHandlers.ofString()); }
    }
}
