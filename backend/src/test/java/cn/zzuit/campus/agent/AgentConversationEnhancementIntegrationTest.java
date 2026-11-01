package cn.zzuit.campus.agent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.zzuit.campus.agent.AgentModels.*;
import java.net.*;import java.net.http.*;import java.time.LocalDate;import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;import tools.jackson.databind.json.JsonMapper;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=jdbc:h2:mem:agent-conversation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.payment.enabled=false","app.payment.followup.enabled=false","app.agent.deepseek.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentConversationEnhancementIntegrationTest {
 static final JsonMapper JSON=JsonMapper.builder().build();
 @LocalServerPort int port;@Autowired JdbcClient jdbc;@MockitoSpyBean DeepSeekClient model;
 @MockitoSpyBean AgentTools tools;
 long first,second,foreign;
 @BeforeAll void fixture() throws Exception {
  var op=login("finance1001");var rev=login("finance2001");
  first=bill(op,rev,"20260001","学费测试",1250);second=bill(op,rev,"20260001","教材测试",2500);foreign=bill(op,rev,"20260002","他人账单不可见",9900);
  var s=ok(op.post("/api/finance/payments/standards",Map.of("requestKey",UUID.randomUUID().toString(),"name","测试电价","categoryCode","ELECTRICITY","unit","度","unitPriceCents",61,"reason","隔离演示电价测试依据")));
  ok(rev.post("/api/finance/payments/standards/"+s.path("id").asLong()+"/actions",action("APPROVE",0)));
  ok(op.post("/api/finance/payments/dorm-meters",Map.of("roomId",1,"standardId",s.path("id").asLong(),"meterCode","AGENT-E-001")));
 }
 @BeforeEach void offline(){doReturn(false).when(model).ready();}
 Map<String,Object> action(String a,int v){return Map.of("action",a,"version",v,"requestKey",UUID.randomUUID().toString(),"reason","核对隔离演示收费依据后处理");}
 long bill(Browser op,Browser rev,String user,String title,long amount)throws Exception{
  var b=ok(op.post("/api/finance/payments",Map.of("requestKey",UUID.randomUUID().toString(),"username",user,"categoryCode","TUITION","title",title,"billingPeriod","隔离测试","dueDate",LocalDate.now().plusDays(10).toString(),"amountCents",amount,"basis","本账单为隔离测试收费依据，不代表真实学校收费")));
  b=ok(op.post("/api/finance/payments/"+b.path("id").asLong()+"/actions",action("SUBMIT",b.path("version").asInt())));
  return ok(rev.post("/api/finance/payments/"+b.path("id").asLong()+"/actions",action("APPROVE",b.path("version").asInt()))).path("id").asLong();
 }
 Map<String,Object> query(String text){return Map.of("message",text,"mode","LOCAL","externalConsent",false);}
 JsonNode ask(String text)throws Exception{return ok(login("20260001").post("/api/assistant/query",query(text)));}
 @Test void oneQuestionCanCombinePaymentQuoteAndUnrelatedQueries()throws Exception{
  var answer=ask("我的未缴账单，以及充20度电要多少钱？");
  assertThat(answer.path("results").size()).isEqualTo(2);
  assertThat(answer.toString()).contains("my_payments","quote_dorm_recharge","12.20");
  var grades=ask("我的未缴账单，同时查我的已发布成绩");
  assertThat(grades.toString()).contains("my_payments","my_grades");
 }
 @Test void excessiveOrConflictingQueriesAskToSplitRatherThanDroppingRequests()throws Exception{
  var tooMany=ask("我的未缴账单；我的已发布成绩；我的考试；我有多少未读消息；我的报修进度");
  assertThat(tooMany.path("results").size()).isZero();
  assertThat(tooMany.path("summary").asText()).contains("拆分");
  var conflict=ask("充20度电；充30度电");
  assertThat(conflict.path("results").size()).isZero();
  assertThat(conflict.path("summary").asText()).contains("拆分");
 }
 @Test void repeatedRequestsInOneQuestionAreDeduplicated()throws Exception{
  assertThat(ask("我的未缴账单；我的未缴账单").path("results").size()).isEqualTo(1);
 }
 @Test void incompleteRechargeRequestsAndMixedUtilitiesRequireClarification()throws Exception{
  assertThat(ask("我想充电费").toString()).contains("quote_dorm_recharge","NEEDS_INPUT");
  assertThat(ask("我想充值").toString()).contains("quote_dorm_recharge","NEEDS_INPUT");
  var mixed=ask("给宿舍充20度电和5吨水");
  assertThat(mixed.toString()).contains("NEEDS_INPUT").doesNotContain("应付 ¥");
 }
 @Test void restoredOrdinalQuestionsKeepTheOriginalBillAndRetriesAreIdempotent()throws Exception{
  var student=login("20260001");long session=ok(student.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();
  String path="/api/assistant/sessions/"+session+"/query";
  var list=ok(student.post(path,Map.of("requestKey",UUID.randomUUID().toString(),"input",query("我的未缴账单"))));
  String original=list.path("answer").path("results").get(0).path("items").get(1).path("title").asText();
  var command=Map.of("requestKey",UUID.randomUUID().toString(),"input",query("第二笔为什么这么贵？"));
  var detail=ok(student.post(path,command));
  assertThat(detail.path("turn").path("context").path("type").asText()).isEqualTo("PAYMENT_MINE");
  assertThat(ok(student.post(path,command)).path("repeated").asBoolean()).isTrue();
  bill(login("finance1001"),login("finance2001"),"20260001","后来新增的账单",8800);
  ok(student.post(path,Map.of("requestKey",UUID.randomUUID().toString(),"input",query("我的未缴账单"))));
  var saved=ok(student.get("/api/assistant/sessions/"+session));
  JsonNode historical=null;for(var turn:saved.path("turns"))if(turn.path("id").asLong()==detail.path("turn").path("id").asLong())historical=turn;
  assertThat(historical).isNotNull();
  var replay=new HashMap<String,Object>(query("第二笔为什么这么贵？"));replay.put("context",JSON.treeToValue(historical.path("context"),Map.class));
  var answer=ok(student.post(path,Map.of("requestKey",UUID.randomUUID().toString(),"input",replay)));
  assertThat(answer.path("answer").toString()).contains(original);
 }
 @Test void rechargeClarificationCanBeCompletedWithoutSendingHistoryToModel()throws Exception{
  var student=login("20260001");long session=ok(student.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();
  String path="/api/assistant/sessions/"+session+"/query";
  var incomplete=ok(student.post(path,Map.of("requestKey",UUID.randomUUID().toString(),"input",query("我想充值"))));
  assertThat(incomplete.toString()).contains("NEEDS_INPUT");
  assertThat(incomplete.path("answer").path("results").get(0).path("followUps").get(0).asText()).isEqualTo("电费");
  assertThat(ok(student.post(path,Map.of("requestKey",UUID.randomUUID().toString(),"input",query("电费")))).toString()).contains("NEEDS_INPUT");
  long before=jdbc.sql("SELECT COUNT(*) FROM payment_bill").query(Long.class).single();
  var answer=ok(student.post(path,Map.of("requestKey",UUID.randomUUID().toString(),"input",query("20度"))));
  assertThat(answer.toString()).contains("quote_dorm_recharge","12.20");
  assertThat(jdbc.sql("SELECT COUNT(*) FROM payment_bill").query(Long.class).single()).isEqualTo(before);
  long other=ok(student.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();
  var unlinked=ok(student.post("/api/assistant/sessions/"+other+"/query",Map.of("requestKey",UUID.randomUUID().toString(),"input",query("20度"))));
  assertThat(unlinked.toString()).doesNotContain("应付 ¥");
 }
 @Test void localClarificationDoesNotInvokeModelAndUnrelatedTopicsClearPendingQuote()throws Exception{
  var student=login("20260001");long session=ok(student.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();
  String path="/api/assistant/sessions/"+session+"/query";
  ok(student.post(path,Map.of("requestKey",UUID.randomUUID().toString(),"input",query("我想充电费"))));
  var completed=ok(student.post(path,Map.of("requestKey",UUID.randomUUID().toString(),"input",Map.of("message","20度","mode","DEEPSEEK","externalConsent",true))));
  assertThat(completed.path("answer").path("modelUsed").asBoolean()).isFalse();
  assertThat(completed.toString()).contains("12.20");
  verify(model,never()).plan(anyString(),anyList(),nullable(String.class));
  ok(student.post(path,Map.of("requestKey",UUID.randomUUID().toString(),"input",query("我想充水费"))));
  ok(student.post(path,Map.of("requestKey",UUID.randomUUID().toString(),"input",query("我的已发布成绩"))));
  var cleared=ok(student.post(path,Map.of("requestKey",UUID.randomUUID().toString(),"input",query("10元"))));
  assertThat(cleared.toString()).doesNotContain("应付 ¥");
 }
 @Test void conjunctionInsideVenueRequirementsDoesNotDiscardTimeAndCampus()throws Exception{
  ask("明天东校区09:00-11:00查找一个同时容纳20人的空闲场地");
  var call=org.mockito.ArgumentCaptor.forClass(Call.class);
  verify(tools).execute(any(),call.capture(),nullable(Context.class));
  assertThat(call.getValue().name()).isEqualTo("find_available_venues");
  assertThat(call.getValue().arguments().path("campus").asText()).isEqualTo("EAST");
  assertThat(call.getValue().arguments().path("startsAt").asText()).endsWith("T09:00");
  assertThat(call.getValue().arguments().path("participants").asInt()).isEqualTo(20);
 }
    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return JSON.readTree(response.body());
    }
    private Browser login(String username) throws Exception {
        var browser = new Browser(); browser.csrf();
        ok(browser.post("/api/auth/login", Map.of("username", username, "password", "Demo@2026"))); browser.csrf(); return browser;
    }
    private class Browser {
        final HttpClient http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build(); String header, token;
        HttpRequest.Builder builder(String path) { return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)); }
        HttpResponse<String> get(String path) throws Exception { return http.send(builder(path).GET().build(), HttpResponse.BodyHandlers.ofString()); }
        void csrf() throws Exception { var data = JSON.readTree(get("/api/auth/csrf").body()); header = data.path("headerName").asText(); token = data.path("token").asText(); }
        HttpResponse<String> post(String path, Map<String, ?> body) throws Exception { return send("POST", path, body); }
        HttpResponse<String> send(String method, String path, Map<String, ?> body) throws Exception {
            return http.send(builder(path).header(header, token).header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
