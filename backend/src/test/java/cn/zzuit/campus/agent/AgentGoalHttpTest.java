package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import cn.zzuit.campus.agent.AgentModels.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "spring.datasource.url=jdbc:h2:mem:agent-goal;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
 "app.demo.enabled=true","app.agent.deepseek.enabled=false","app.payment.enabled=false",
 "app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentGoalHttpTest {
 static final JsonMapper JSON=JsonMapper.builder().build();
 @LocalServerPort int port;@Autowired JdbcClient jdbc;@MockitoSpyBean DeepSeekClient model;
 final Deque<Call> decisions=new ArrayDeque<>();
 Function<JsonNode,Call> strategy;
 @BeforeEach void planning(){
  decisions.clear();strategy=context->decisions.isEmpty()?call("await_user",Map.of("question","请核对当前任务并补充缺少的资料。")):decisions.removeFirst();
  doReturn(true).when(model).ready();
  doAnswer(invocation->new Plan(List.of(strategy.apply(invocation.getArgument(2))),20,5)).when(model).nextGoalStep(anyString(),anyList(),any());
 }
 Call call(String name,Map<String,?> fields){return new Call(name,JSON.valueToTree(fields));}
 Map<String,Object> task(String key,String kind,List<String> dependencies){return Map.of("key",key,"kind",kind,"label","准备本人业务草稿","target","DRAFT","dependsOn",dependencies);}
 void certificatePlan(){decisions.add(call("set_plan",Map.of("tasks",List.of(task("certificate","CERTIFICATE",List.of())))));}
 void certificateFields(){decisions.add(call("set_task_fields",Map.of("taskKey","certificate","fields",Map.of("purpose","实习单位核验在校身份"))));}
 void prepareCertificate(){certificatePlan();certificateFields();decisions.add(call("prepare_task",Map.of("taskKey","certificate")));decisions.add(call("await_user",Map.of("taskKey","certificate","question","请核对本人登记资料及用途后确认保存。")));}
 Map<String,Object> input(){return new HashMap<>(Map.of("requestKey",UUID.randomUUID().toString(),"goal","帮我准备实习单位核验身份用的在校证明草稿","externalConsent",true));}
 String path(long session){return "/api/assistant/sessions/"+session+"/goals";}
 long session(Browser browser)throws Exception{return ok(browser.post("/api/assistant/sessions",Map.of("requestKey",UUID.randomUUID().toString()))).path("id").asLong();}
 Map<String,Object> action(JsonNode goal,String action){return new HashMap<>(Map.of("requestKey",UUID.randomUUID().toString(),"version",goal.path("version").asInt(),"action",action));}
 JsonNode act(Browser browser,long s,JsonNode goal,Map<String,Object> input)throws Exception{return ok(browser.post(path(s)+"/"+goal.path("id").asLong()+"/actions",input));}

 @Test void modelChoosesGuideBeforePlanningAndRealDraftNeedsHumanConfirmation()throws Exception{
  decisions.add(call("read_guide",Map.of("kind","CERTIFICATE")));prepareCertificate();
  var user=login("20260001");long s=session(user),before=count();var request=input();var goal=ok(user.post(path(s),request));
  assertThat(goal.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");assertThat(goal.path("tasks").size()).isEqualTo(1);
  assertThat(goal.path("events").get(0).path("tool").asText()).isEqualTo("read_guide");
  assertThat(goal.path("tasks").get(0).path("mission").path("certificateFields").path("purpose").asText()).isEqualTo("实习单位核验在校身份");
  assertThat(count()).isEqualTo(before);assertThat(ok(user.post(path(s),request)).path("id")).isEqualTo(goal.path("id"));
  var mission=goal.path("tasks").get(0).path("mission");saveChild(user,s,mission);
  decisions.add(call("refresh_task",Map.of("taskKey","certificate")));decisions.add(call("finish",Map.of()));
  goal=act(user,s,goal,action(goal,"CONTINUE"));assertThat(goal.path("stage").asText()).isEqualTo("DONE");assertThat(count()).isEqualTo(before+1);
  assertThat(goal.path("tasks").get(0).path("mission").path("business").path("status").asText()).isEqualTo("DRAFT");
 }
 @Test void mixedBusinessGoalBuildsDependentPlanRatherThanRejectingMixedIntent()throws Exception{
  decisions.add(call("set_plan",Map.of("tasks",List.of(task("proof","CERTIFICATE",List.of()),task("leave","LEAVE",List.of("proof"))))));
  decisions.add(call("prepare_task",Map.of("taskKey","leave")));
  var user=login("20260001");var request=input();request.put("goal","先准备实习证明，然后帮我准备请假草稿");var goal=ok(user.post(path(session(user)),request));
  assertThat(goal.path("tasks").size()).isEqualTo(2);assertThat(goal.path("tasks").get(1).path("dependsOn").toString()).contains("proof");
  assertThat(goal.path("events").toString()).contains("GOAL_DEPENDENCY_PENDING");
  assertThat(goal.path("tasks").get(1).path("mission").isNull()).isTrue();
 }
 @Test void cyclicPlanAndInventedIdentityFieldsAreRejectedAndCanReplan()throws Exception{
  long before=count();
  decisions.add(call("set_plan",Map.of("tasks",List.of(task("a","LEAVE",List.of("b")),task("b","CERTIFICATE",List.of("a"))))));
  certificatePlan();decisions.add(call("set_task_fields",Map.of("taskKey","certificate","fields",Map.of("studentId",999,"purpose","伪造用途"))));
  certificateFields();decisions.add(call("prepare_task",Map.of("taskKey","certificate")));
  var user=login("20260001");var goal=ok(user.post(path(session(user)),input()));
  assertThat(goal.path("events").toString()).contains("GOAL_PLAN_INVALID","GOAL_FIELDS_INVALID");assertThat(goal.path("tasks").size()).isEqualTo(1);assertThat(count()).isEqualTo(before);
 }
 @Test void cannotFinishWhileAnyBusinessTaskHasNotBeenSavedAndVerified()throws Exception{
  certificatePlan();decisions.add(call("finish",Map.of()));var user=login("20260001");var goal=ok(user.post(path(session(user)),input()));
  assertThat(goal.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(goal.path("events").toString()).contains("GOAL_NOT_COMPLETE");
 }
 @Test void localIdentityAndLocallyEditedPurposeNeverBecomeModelContext()throws Exception{
  prepareCertificate();var user=login("20260001");long s=session(user);var goal=ok(user.post(path(s),input()));var mission=goal.path("tasks").get(0).path("mission");
  var body=new HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID().toString(),"version",mission.path("version").asInt(),"action","REVISE","certificateFields",Map.of("purpose","PRIVATE_LOCAL_PURPOSE_00991")));
  ok(user.post("/api/assistant/sessions/"+s+"/missions/"+mission.path("id").asLong()+"/actions",body));
  strategy=context->{assertThat(context.toString()).doesNotContain("PRIVATE_LOCAL_PURPOSE_00991","identityNumber","studentName","contactPhone","classGroupId");return call("await_user",Map.of("taskKey","certificate","question","请核对当前预览。"));};
  goal=act(user,s,goal,action(goal,"CONTINUE"));assertThat(goal.path("stage").asText()).isEqualTo("WAITING_CONFIRMATION");
 }
 @Test void consentForeignAccessAndCancelledGoalAreEnforced()throws Exception{
  var user=login("20260001");long s=session(user);var request=input();request.put("externalConsent",false);assertThat(user.post(path(s),request).statusCode()).isEqualTo(400);
  prepareCertificate();var goal=ok(user.post(path(s),input()));var foreign=login("20260002");assertThat(foreign.get(path(s)+"/"+goal.path("id").asLong()).statusCode()).isEqualTo(404);
  goal=act(user,s,goal,action(goal,"CANCEL"));assertThat(goal.path("stage").asText()).isEqualTo("CANCELLED");
  assertThat(user.post(path(s)+"/"+goal.path("id").asLong()+"/actions",action(goal,"CONTINUE")).statusCode()).isEqualTo(409);
 }
 @Test void revisionRetainsBusinessHistoryAndReplacesGoalInstructions()throws Exception{
  certificatePlan();var user=login("20260001");long s=session(user);var goal=ok(user.post(path(s),input()));
  var revision=action(goal,"REVISE");revision.put("message","暂时只准备证明，先核对办理指南");revision.put("externalConsent",true);
  decisions.add(call("read_guide",Map.of("kind","CERTIFICATE")));goal=act(user,s,goal,revision);
  assertThat(goal.path("instructions").toString()).contains("暂时只准备证明");assertThat(goal.path("tasks").size()).isEqualTo(1);
  assertThat(goal.path("events").toString()).contains("read_guide");
 }
 @Test void malformedModelArgumentsAreRecordedAndPlannerCanRecover()throws Exception{
  decisions.add(new Call("set_plan",null));certificatePlan();var user=login("20260001");var goal=ok(user.post(path(session(user)),input()));
  assertThat(goal.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(goal.path("events").toString()).contains("GOAL_TOOL_REJECTED");
 }
 @Test void activeGoalLeasePreventsDeletingItsConversation()throws Exception{
  var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  strategy=context->{entered.countDown();try{if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("test release timeout");}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}return call("await_user",Map.of("question","请补充用途。"));};
  var user=login("20260001");long s=session(user);var response=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return user.post(path(s),input());}catch(Exception ex){throw new IllegalStateException(ex);}});
  try{assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(user.delete("/api/assistant/sessions/"+s).statusCode()).isEqualTo(409);}
  finally{release.countDown();response.get(15,java.util.concurrent.TimeUnit.SECONDS);}
 }
 @Test void allSupportedDomainsProvideAnActualPublishedGuide()throws Exception{
  decisions.add(call("read_guide",Map.of("kind","RESERVATION")));var user=login("20260001");var goal=ok(user.post(path(session(user)),input()));
  assertThat(goal.path("events").get(0).path("status").asText()).isEqualTo("OK");
 }
 @Test void repeatedInvalidPlansStopWithinTheFailureBudget()throws Exception{
  strategy=context->call("set_plan",Map.of("tasks",List.of()));var user=login("20260001");var goal=ok(user.post(path(session(user)),input()));
  assertThat(goal.path("stage").asText()).isEqualTo("FAILED");assertThat(goal.path("events").size()).isEqualTo(3);
 }
 @Test void cancellingOnePlannedItemPreservesItsBusinessAndReplansRemainingDependencies()throws Exception{
  decisions.add(call("set_plan",Map.of("tasks",List.of(task("certificate","CERTIFICATE",List.of()),task("leave","LEAVE",List.of("certificate"))))));certificateFields();decisions.add(call("prepare_task",Map.of("taskKey","certificate")));decisions.add(call("await_user",Map.of("taskKey","certificate","question","请先核对证明。")));
  var user=login("20260001");long s=session(user);var goal=ok(user.post(path(s),input()));saveChild(user,s,goal.path("tasks").get(0).path("mission"));long id=ok(user.get(path(s)+"/"+goal.path("id").asLong())).path("tasks").get(0).path("mission").path("business").path("id").asLong();
  decisions.add(call("set_plan",Map.of("tasks",List.of(task("certificate","CERTIFICATE",List.of()),task("leave","LEAVE",List.of())))));decisions.add(call("prepare_task",Map.of("taskKey","leave")));
  var cancel=action(goal,"CANCEL_TASK");cancel.put("taskKey","certificate");cancel.put("confirmed",true);goal=act(user,s,goal,cancel);
  assertThat(goal.path("tasks").get(0).path("cancelled").asBoolean()).isTrue();assertThat(goal.path("tasks").get(1).path("mission").isNull()).isFalse();
  assertThat(jdbc.sql("SELECT status FROM certificate_request WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo("DRAFT");
 }
 @Test void interruptedGoalRebindsAnAlreadyPreparedChildEvenAfterItsPlanLabelChanged()throws Exception{
  prepareCertificate();var user=login("20260001");long s=session(user);var goal=ok(user.post(path(s),input()));long id=goal.path("id").asLong(),child=goal.path("tasks").get(0).path("mission").path("id").asLong();
  var state=JSON.readValue(jdbc.sql("SELECT state_json FROM agent_goal WHERE id=:id").param("id",id).query(String.class).single(),AgentGoalModels.State.class);state.tasks.get(0).missionId=null;state.tasks.get(0).label="更新后的计划说明";
  jdbc.sql("UPDATE agent_goal SET state_json=:state,stage='PAUSED',runner_token=NULL,lease_until=NULL,version=version+1 WHERE id=:id").param("state",JSON.writeValueAsString(state)).param("id",id).update();goal=ok(user.get(path(s)+"/"+id));
  decisions.add(call("prepare_task",Map.of("taskKey","certificate")));goal=act(user,s,goal,action(goal,"CONTINUE"));
  assertThat(goal.path("tasks").get(0).path("mission").path("id").asLong()).isEqualTo(child);assertThat(jdbc.sql("SELECT COUNT(*) FROM agent_mission WHERE session_id=:s").param("s",s).query(Long.class).single()).isEqualTo(1);
 }
 @Test void parallelToolProposalsExecuteOnlyOneThenReplanFromTheActualResult()throws Exception{
  var calls=new java.util.concurrent.atomic.AtomicInteger();
  doAnswer(invocation->{
   if(calls.getAndIncrement()==0)return new Plan(List.of(call("set_plan",Map.of("tasks",List.of(task("certificate","CERTIFICATE",List.of())))),call("read_guide",Map.of("kind","CERTIFICATE"))),20,5);
   JsonNode context=invocation.getArgument(2);assertThat(context.path("observations").toString()).contains("set_plan","deferredTools","read_guide");
   return new Plan(List.of(call("await_user",Map.of("question","请补充实际用途。"))),20,5);
  }).when(model).nextGoalStep(anyString(),anyList(),any());
  var user=login("20260001");var goal=ok(user.post(path(session(user)),input()));
  assertThat(goal.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(goal.path("tasks").size()).isEqualTo(1);
  assertThat(goal.path("events").size()).isEqualTo(2);assertThat(goal.path("events").get(0).path("tool").asText()).isEqualTo("set_plan");
 }
 @Test void existingFieldsAreVisibleAsNamesWhilePrivateValuesRemainLocal()throws Exception{
  var observed=new java.util.concurrent.atomic.AtomicReference<JsonNode>();certificatePlan();
  decisions.add(call("set_task_fields",Map.of("taskKey","certificate","fields",Map.of("purpose","LOCAL_PURPOSE_PRESENCE_ONLY"))));
  var original=strategy;strategy=context->{if(context.path("observations").toString().contains("set_task_fields"))observed.set(context);return original.apply(context);};
  var user=login("20260001");ok(user.post(path(session(user)),input()));
  assertThat(observed.get().path("tasks").get(0).path("providedFields").toString()).contains("purpose");
  assertThat(observed.get().toString()).doesNotContain("LOCAL_PURPOSE_PRESENCE_ONLY");
 }
 @Test void readingAnUnchangedGuideRemovesItsDuplicateAffordanceUntilVersionChanges()throws Exception{
  var choices=new ArrayList<String>();var calls=new java.util.concurrent.atomic.AtomicInteger();
  doAnswer(invocation->{
   if(calls.getAndIncrement()==0)return new Plan(List.of(call("read_guide",Map.of("kind","CERTIFICATE"))),20,5);
   JsonNode schemas=JSON.valueToTree(invocation.getArgument(1));for(var schema:schemas)if(schema.path("function").path("name").asText().equals("read_guide"))for(var kind:schema.path("function").path("parameters").path("properties").path("kind").path("enum"))choices.add(kind.asText());
   return new Plan(List.of(call("await_user",Map.of("question","请补充实际目标。"))),20,5);
  }).when(model).nextGoalStep(anyString(),anyList(),any());
  var user=login("20260001");long s=session(user);var goal=ok(user.post(path(s),input()));
  assertThat(choices).doesNotContain("CERTIFICATE").contains("LEAVE");
  choices.clear();jdbc.sql("UPDATE agent_guide SET version=version+1 WHERE slug='certificate'").update();
  try{act(user,s,goal,action(goal,"CONTINUE"));assertThat(choices).contains("CERTIFICATE");}
  finally{jdbc.sql("UPDATE agent_guide SET version=version-1 WHERE slug='certificate'").update();}
 }
 @Test void unchangedPlansDoNotCountAsProgressButCancelledHistoryCanBeOmitted()throws Exception{
  certificatePlan();decisions.add(call("set_plan",Map.of("tasks",List.of(task("certificate","CERTIFICATE",List.of())))));
  var user=login("20260001");var goal=ok(user.post(path(session(user)),input()));assertThat(goal.path("events").toString()).contains("GOAL_PLAN_UNCHANGED");
 }
 @Test void cancelledPlanItemsRemainHistoricalWhenThePlannerProvidesOnlyActiveTasks()throws Exception{
  decisions.add(call("set_plan",Map.of("tasks",List.of(task("certificate","CERTIFICATE",List.of()),task("leave","LEAVE",List.of())))));
  var user=login("20260001");long s=session(user);var goal=ok(user.post(path(s),input()));
  decisions.add(call("set_plan",Map.of("tasks",List.of(task("certificate","CERTIFICATE",List.of())))));
  var cancel=action(goal,"CANCEL_TASK");cancel.put("taskKey","leave");cancel.put("confirmed",true);goal=act(user,s,goal,cancel);
  assertThat(goal.path("tasks").size()).isEqualTo(2);assertThat(goal.path("tasks").get(1).path("cancelled").asBoolean()).isTrue();
  assertThat(goal.path("events").toString()).doesNotContain("GOAL_PLAN_INVALID");assertThat(goal.path("stage").asText()).isEqualTo("WAITING_INPUT");
 }
 @Test void stepBudgetEndsInAnExplicitTerminalFailureRatherThanAnUnresumablePause()throws Exception{
  certificatePlan();certificateFields();decisions.add(call("prepare_task",Map.of("taskKey","certificate")));
  for(int n=0;n<4;n++){var item=new HashMap<String,Object>(task("certificate","CERTIFICATE",List.of()));item.put("target",n%2==0?"SUBMIT":"DRAFT");decisions.add(call("set_plan",Map.of("tasks",List.of(item))));}
  decisions.add(call("await_user",Map.of("taskKey","certificate","question","请核对当前预览。")));
  var user=login("20260001");long s=session(user);var goal=ok(user.post(path(s),input()));assertThat(goal.path("events").size()).isEqualTo(8);
  strategy=context->{var item=new HashMap<String,Object>(task("certificate","CERTIFICATE",List.of()));item.put("target",context.path("tasks").get(0).path("target").asText().equals("DRAFT")?"SUBMIT":"DRAFT");return call("set_plan",Map.of("tasks",List.of(item)));};
  for(int attempts=0;attempts<12&&goal.path("canContinue").asBoolean();attempts++)goal=act(user,s,goal,action(goal,"CONTINUE"));
  assertThat(goal.path("stage").asText()).isEqualTo("FAILED");assertThat(goal.path("events").size()).isEqualTo(80);
 }
 @Test void aRevisedPurposeSurvivesPlanReorganizationAndUsesANewPreview()throws Exception{
  prepareCertificate();var user=login("20260001");long s=session(user);var goal=ok(user.post(path(s),input()));String old=goal.path("tasks").get(0).path("mission").path("proposalHash").asText();
  decisions.add(call("set_task_fields",Map.of("taskKey","certificate","fields",Map.of("purpose","实习单位核验"))));certificatePlan();
  var revision=action(goal,"REVISE");revision.put("message","用途改为实习单位核验");revision.put("externalConsent",true);goal=act(user,s,goal,revision);
  assertThat(goal.path("tasks").get(0).path("mission").path("certificateFields").path("purpose").asText()).isEqualTo("实习单位核验");assertThat(goal.path("tasks").get(0).path("mission").path("proposalHash").asText()).isNotEqualTo(old);
 }
 @Test void repeatingIdenticalFieldsDoesNotPretendToUpdateTheTask()throws Exception{
  certificatePlan();certificateFields();certificateFields();var user=login("20260001");var goal=ok(user.post(path(session(user)),input()));
  assertThat(goal.path("events").toString()).contains("GOAL_FIELDS_UNCHANGED");
 }
 @Test void cancellingWhileTheModelIsPendingClosesItsEventAndCannotExecuteTheReturnedTool()throws Exception{
  var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
  strategy=context->{entered.countDown();try{release.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}return call("set_plan",Map.of("tasks",List.of(task("certificate","CERTIFICATE",List.of()))));};
  var user=login("20260001");long s=session(user);var response=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return user.post(path(s),input());}catch(Exception failure){throw new IllegalStateException(failure);}});
  try{assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();var listed=ok(user.get(path(s)));var goal=ok(user.get(path(s)+"/"+listed.get(0).path("id").asLong()));goal=act(user,s,goal,action(goal,"CANCEL"));assertThat(goal.path("events").get(0).path("status").asText()).isEqualTo("CANCELLED");}
  finally{release.countDown();response.get(15,java.util.concurrent.TimeUnit.SECONDS);}
  var listed=ok(user.get(path(s)));var goal=ok(user.get(path(s)+"/"+listed.get(0).path("id").asLong()));assertThat(goal.path("stage").asText()).isEqualTo("CANCELLED");assertThat(goal.path("tasks").size()).isZero();
 }
 @Test void localTaskCancellationIsTheLatestBusinessDirectiveRatherThanARequestToRetryIt()throws Exception{
  decisions.add(call("set_plan",Map.of("tasks",List.of(task("certificate","CERTIFICATE",List.of()),task("room","RESERVATION",List.of())))));
  var user=login("20260001");long s=session(user);var goal=ok(user.post(path(s),input()));var captured=new java.util.concurrent.atomic.AtomicReference<JsonNode>();
  strategy=context->{captured.set(context);return call("await_user",Map.of("question","继续其余事项。"));};
  var cancel=action(goal,"CANCEL_TASK");cancel.put("taskKey","room");cancel.put("confirmed",true);act(user,s,goal,cancel);
  assertThat(captured.get().path("currentUserDirective").asText()).contains("停止","场地预约");
 }
 @Test void savedBusinessOnlyOffersApplicableMutationAndPreparationTools()throws Exception{
  prepareCertificate();var user=login("20260001");long s=session(user);var goal=ok(user.post(path(s),input()));saveChild(user,s,goal.path("tasks").get(0).path("mission"));var offered=new java.util.concurrent.atomic.AtomicReference<String>();
  doAnswer(invocation->{offered.set(JSON.writeValueAsString(invocation.getArgument(1)));return new Plan(List.of(call("finish",Map.of())),20,5);}).when(model).nextGoalStep(anyString(),anyList(),any());act(user,s,goal,action(goal,"CONTINUE"));
  assertThat(offered.get()).doesNotContain("\"name\":\"prepare_task\"","\"name\":\"set_task_fields\"","\"name\":\"watch_task\"");
 }
 long count(){return jdbc.sql("SELECT COUNT(*) FROM certificate_request").query(Long.class).single();}
 void saveChild(Browser user,long session,JsonNode mission)throws Exception{ok(user.post("/api/assistant/sessions/"+session+"/missions/"+mission.path("id").asLong()+"/actions",Map.of("requestKey",UUID.randomUUID().toString(),"version",mission.path("version").asInt(),"action","CONFIRM","confirmed",true,"proposalHash",mission.path("proposalHash").asText())));}
 JsonNode ok(HttpResponse<String> response){assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return JSON.readTree(response.body());}
 Browser login(String name)throws Exception{var b=new Browser();b.csrf();ok(b.post("/api/auth/login",Map.of("username",name,"password","Demo@2026")));b.csrf();return b;}
 class Browser{
  final HttpClient http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();String header,token;
  HttpRequest.Builder request(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));}
  HttpResponse<String> get(String path)throws Exception{return http.send(request(path).GET().build(),HttpResponse.BodyHandlers.ofString());}
  void csrf()throws Exception{var data=JSON.readTree(get("/api/auth/csrf").body());header=data.path("headerName").asText();token=data.path("token").asText();}
  HttpResponse<String> post(String path,Map<String,?> body)throws Exception{return http.send(request(path).header(header,token).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());}
  HttpResponse<String> delete(String path)throws Exception{return http.send(request(path).header(header,token).DELETE().build(),HttpResponse.BodyHandlers.ofString());}
 }
}
