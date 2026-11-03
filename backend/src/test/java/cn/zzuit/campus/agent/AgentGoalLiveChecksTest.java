package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AgentGoalLiveChecksTest {
 final JsonMapper json=JsonMapper.builder().build();
 final Map<String,Object> example=Map.of("expectedKinds",List.of("LEAVE"),"allowQuestionBeforePlan",true);
 Map<String,Object> waiting(){return Map.of("stage","WAITING_INPUT","tasks",List.of(),"events",List.of(Map.of("tool","read_guide","message","请假指南"),Map.of("tool","await_user","message","请补充明确的起止日期和时间"))); }
 @Test void aReadOnlyQuestionCanPrecedeTaskPlanning(){assertThatCode(()->AgentGoalLiveChecks.kinds(json.valueToTree(waiting()),json.valueToTree(example),0)).doesNotThrowAnyException();}
 @Test void strictFrozenRubricRemainsStrict(){assertThatThrownBy(()->AgentGoalLiveChecks.kinds(json.valueToTree(waiting()),json.valueToTree(Map.of("expectedKinds",List.of("LEAVE"))),0)).isInstanceOf(AssertionError.class);}
 @Test void clarificationCannotHideBusinessWrites(){assertThatThrownBy(()->AgentGoalLiveChecks.kinds(json.valueToTree(waiting()),json.valueToTree(example),1)).isInstanceOf(AssertionError.class);}
 @Test void falseCompletionOrUnrelatedPlansFail(){for(var view:List.of(Map.of("stage","DONE","tasks",List.of(),"events",waiting().get("events")),Map.of("stage","WAITING_INPUT","tasks",List.of(Map.of("kind","REPAIR")),"events",waiting().get("events"))))assertThatThrownBy(()->AgentGoalLiveChecks.kinds(json.valueToTree(view),json.valueToTree(example),0)).isInstanceOf(AssertionError.class);}
}
