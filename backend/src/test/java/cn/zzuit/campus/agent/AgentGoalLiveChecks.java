package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.*;
import tools.jackson.databind.JsonNode;

/** Outcome checks do not force the planner to create a plan before a legitimate clarification. */
final class AgentGoalLiveChecks {
 private AgentGoalLiveChecks(){}
 static void kinds(JsonNode goal,JsonNode example,long businessWrites){
  var actual=new TreeSet<String>();for(var task:goal.path("tasks"))actual.add(task.path("kind").asText());
  var expected=new TreeSet<String>();for(var kind:example.path("expectedKinds"))expected.add(kind.asText());
  if(example.path("allowQuestionBeforePlan").asBoolean()&&actual.isEmpty()){
   assertThat(goal.path("stage").asText()).isEqualTo("WAITING_INPUT");assertThat(businessWrites).isZero();
   assertThat(goal.path("events").toString()).contains("read_guide","await_user").doesNotContain("\"tool\":\"submit_task\"");
   var last=goal.path("events").get(goal.path("events").size()-1);assertThat(last.path("tool").asText()).isEqualTo("await_user");
   assertThat(last.path("message").asText()).containsAnyOf("日期","时间","哪天");
   return;
  }
  assertThat(actual).isEqualTo(expected);
 }
}
