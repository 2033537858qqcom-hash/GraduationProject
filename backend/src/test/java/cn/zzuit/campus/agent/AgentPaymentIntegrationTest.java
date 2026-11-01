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
 "spring.datasource.url=jdbc:h2:mem:agent-payment;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.payment.enabled=false","app.payment.followup.enabled=false","app.agent.deepseek.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentPaymentIntegrationTest {
 static final JsonMapper JSON=JsonMapper.builder().build();
 @LocalServerPort int port;@Autowired JdbcClient jdbc;@MockitoSpyBean DeepSeekClient model;
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
 @Test void ownBillsHaveExactTotalsAndNoForeignData()throws Exception{
  var answer=ask("我还有多少钱没交？");
  assertThat(answer.toString()).contains("my_payments","37.50","学费测试","教材测试").doesNotContain("他人账单不可见","tradeNo","orderNo");
 }
 @Test void quoteUsesDormPriceAndNeverCreatesAnOrder()throws Exception{
  long before=jdbc.sql("SELECT COUNT(*) FROM payment_bill").query(Long.class).single();
  var answer=ask("充20度电要多少钱？");
  assertThat(answer.toString()).contains("quote_dorm_recharge","12.20","20");
  assertThat(jdbc.sql("SELECT COUNT(*) FROM payment_bill").query(Long.class).single()).isEqualTo(before);
  assertThat(ask("我们宿舍电费多少钱一度？").toString()).contains("my_dorm_utilities","0.61");
 }
 @Test void billContextIsOwnedAndExplainsBasisWithoutPollingGateway()throws Exception{
  var student=login("20260001");var input=new HashMap<String,Object>(query("这笔费用怎么计算的？"));input.put("context",Map.of("type","PAYMENT_MINE","id",first));
  assertThat(ok(student.post("/api/assistant/query",input)).toString()).contains("payment_detail","隔离测试收费依据","待支付");
  input.put("context",Map.of("type","PAYMENT_MINE","id",foreign));
  assertThat(student.post("/api/assistant/query",input).statusCode()).isEqualTo(404);
 }
 @Test void aSessionCanReferToSecondBillButCannotReuseAnotherSessionsResults()throws Exception{
  var student=login("20260001");long session=ok(student.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();
  var list=ok(student.post("/api/assistant/sessions/"+session+"/query",Map.of("requestKey",UUID.randomUUID().toString(),"input",query("我的未缴账单"))));
  String title=list.path("answer").path("results").get(0).path("items").get(1).path("title").asText();
  var follow=ok(student.post("/api/assistant/sessions/"+session+"/query",Map.of("requestKey",UUID.randomUUID().toString(),"input",query("第二笔为什么这么贵？"))));
  assertThat(follow.toString()).contains("payment_detail",title);
  long other=ok(student.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();
  var missing=ok(student.post("/api/assistant/sessions/"+other+"/query",Map.of("requestKey",UUID.randomUUID().toString(),"input",query("第二笔为什么这么贵？"))));
  assertThat(missing.toString()).contains("NEEDS_INPUT");
 }
 @Test void financeCapabilitiesAndWriteRequestsAreSeparated()throws Exception{
  assertThat(ok(login("finance2001").get("/api/assistant/capabilities")).toString()).contains("finance_reconciliation_pending");
  assertThat(ok(login("20260001").get("/api/assistant/capabilities")).toString()).doesNotContain("finance_reconciliation_pending");
  assertThat(ask("帮我直接支付这笔电费").path("results").size()).isZero();
 }
 @Test void modelCannotSelectForeignIdentityAndMissingQuoteInputsAreNotGuessed()throws Exception{
  var student=login("20260001");doReturn(true).when(model).ready();
  doReturn(new Plan(List.of(new Call("my_payments",JSON.valueToTree(Map.of("username","20260002")))),10,2)).when(model).plan(anyString(),anyList(),nullable(String.class));
  var body=Map.of("message","查看账单","mode","DEEPSEEK","externalConsent",true);
  var rejected=ok(student.post("/api/assistant/query",body));
  assertThat(rejected.toString()).contains("ERROR").doesNotContain("他人账单不可见");
  doReturn(new Plan(List.of(new Call("quote_dorm_recharge",JSON.valueToTree(Map.of("category","ELECTRICITY")))),10,2)).when(model).plan(anyString(),anyList(),nullable(String.class));
  assertThat(ok(student.post("/api/assistant/query",body)).toString()).contains("NEEDS_INPUT");
  doReturn(new Plan(List.of(new Call("quote_dorm_recharge",JSON.valueToTree(Map.of("category","ELECTRICITY","mode","AMOUNT","value","-1")))),10,2)).when(model).plan(anyString(),anyList(),nullable(String.class));
  assertThat(ok(student.post("/api/assistant/query",body)).toString()).contains("ERROR");
 }
 @Test void paymentStartedDoesNotMeanPaidAndNonPaymentQuestionsKeepTheirContext()throws Exception{
  jdbc.sql("UPDATE payment_bill SET payment_started_at=CURRENT_TIMESTAMP WHERE id=:id").param("id",first).update();
  try {
   var student=login("20260001");var body=new HashMap<String,Object>(query("这笔账单支付状态"));body.put("context",Map.of("type","PAYMENT_MINE","id",first));
   assertThat(ok(student.post("/api/assistant/query",body)).toString()).contains("支付结果待核验").doesNotContain("已确认支付");
   body.put("message","我的已发布成绩");
   assertThat(ok(student.post("/api/assistant/query",body)).toString()).contains("my_grades").doesNotContain("payment_detail");
  }finally{jdbc.sql("UPDATE payment_bill SET payment_started_at=NULL WHERE id=:id").param("id",first).update();}
 }
 @Test void serverResolvesFollowUpWithoutSendingBillHistoryToModel()throws Exception{
  var student=login("20260001");long session=ok(student.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();
  ok(student.post("/api/assistant/sessions/"+session+"/query",Map.of("requestKey",UUID.randomUUID().toString(),"input",query("我的未缴账单"))));
  doReturn(true).when(model).ready();
  doReturn(new Plan(List.of(new Call("payment_detail",JSON.valueToTree(Map.of()))),10,2)).when(model).plan(anyString(),anyList(),nullable(String.class));
  String question="第二笔为什么这么贵？";
  var answer=ok(student.post("/api/assistant/sessions/"+session+"/query",Map.of("requestKey",UUID.randomUUID().toString(),"input",Map.of("message",question,"mode","DEEPSEEK","externalConsent",true))));
  assertThat(answer.toString()).contains("payment_detail","学费测试");
  verify(model).plan(eq(question),anyList(),eq("PAYMENT_MINE"));
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
