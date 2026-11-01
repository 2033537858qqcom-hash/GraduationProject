package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:payment-routing;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true","app.agent.deepseek.enabled=false","app.payment.enabled=false","app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
class AgentPaymentRoutingTest {
    @Autowired AgentPaymentQueries queries;
    static JsonNode definition() throws Exception {return JsonMapper.builder().build().readTree(Files.readString(Path.of("../.Codex/evals/agent-payment-v1.json")));}
    static Stream<JsonNode> cases() throws Exception {return java.util.stream.StreamSupport.stream(definition().path("cases").spliterator(),false);}
    static boolean matches(JsonNode item,List<AgentModels.Call> calls) {
        if(calls.size()!=1||!calls.get(0).name().equals(item.path("tool").asText()))return false;
        var args=calls.get(0).arguments();
        if(!args.isObject()||args.has("id")||args.has("meterId")||args.properties().stream().anyMatch(e->!AgentPaymentQueries.fields(calls.get(0).name()).contains(e.getKey())))return false;
        for(var field:item.path("arguments").properties()) {
            var actual=args.path(field.getKey());
            if(actual.isMissingNode()&&field.getKey().equals("status")&&field.getValue().asText().equals("ALL"))continue;
            if(!actual.isString()||!actual.asText().equals(field.getValue().asText()))return false;
        }
        return true;
    }
    @ParameterizedTest @MethodSource("cases") void localPlanningMatchesPublishedCases(JsonNode item) {
        var context=item.has("context")?new AgentModels.Context(item.path("context").asText(),1):null;
        assertThat(matches(item,queries.local(item.path("message").asText(),context))).as(item.path("id").asText()).isTrue();
    }
}
