package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.identity.CampusPrincipal;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in synthetic planning evaluation. No business tools are executed or business facts sent. */
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:payment-live-eval;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.demo.enabled=true","app.payment.enabled=false","app.payment.followup.enabled=false","app.cos.enabled=false","app.repair.reminders.enabled=false"})
class AgentPaymentLiveEvaluation {
    @Autowired DeepSeekClient model;@Autowired AgentTools tools;@Autowired IdentityService identities;
    @Test void evaluateEachSyntheticQuestionOnce() throws Exception {
        if(!"true".equals(System.getenv("CAMPUS_AGENT_LIVE_EVAL"))||!model.ready())throw new IllegalStateException("Live evaluation requires opt-in and configured model");
        var rows=new ArrayList<Map<String,Object>>();int passed=0;
        Path output=Path.of("../output/agent-payments-20261101/live-observations.json");Files.createDirectories(output.getParent());
        for(var item:AgentPaymentRoutingTest.definition().path("cases")) {
            var user=(CampusPrincipal)identities.loadUserByUsername(item.path("actor").asText());
            var row=new LinkedHashMap<String,Object>();row.put("id",item.path("id").asText());row.put("message",item.path("message").asText());long start=System.nanoTime();
            try {
                var plan=model.plan(item.path("message").asText(),tools.schemas(user),item.has("context")?item.path("context").asText():null);
                boolean ok=AgentPaymentRoutingTest.matches(item,plan.calls());if(ok)passed++;
                row.put("passed",ok);row.put("calls",plan.calls());row.put("promptTokens",plan.promptTokens());row.put("completionTokens",plan.completionTokens());
            }catch(cn.zzuit.campus.common.ApiException failure){row.put("passed",false);row.put("errorCode",failure.code());}
            row.put("durationMs",(System.nanoTime()-start)/1000000);rows.add(row);
            Files.writeString(output,JsonMapper.builder().build().writeValueAsString(Map.of("model",model.model(),"attempted",rows.size(),"passed",passed,"scope","synthetic-tool-planning-only","observations",rows)));
            System.out.println("Payment live case "+row.get("id")+": "+row.get("passed"));
        }
        assertThat(passed).as("Synthetic payment planning cases").isEqualTo(rows.size());
    }
}
