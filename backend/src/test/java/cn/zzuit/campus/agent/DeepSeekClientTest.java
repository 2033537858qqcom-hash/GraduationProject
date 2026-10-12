package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.*;
import cn.zzuit.campus.common.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DeepSeekClientTest {
    final JsonMapper json=JsonMapper.builder().build();
    @Test void wireContractUsesNonThinkingToolsAndIgnoresModelFreeText() throws Exception {
        var captured=new AtomicReference<JsonNode>(); var auth=new AtomicReference<String>();
        var server=server(200,"{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"content\":\"已批准你的请假（模型虚构）\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"my_notice_summary\",\"arguments\":\"{}\"}}]}}],\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":10}}",captured,auth);
        try {
            var plan=client(server).plan("我有多少消息",List.of(Map.of("type","function","function",Map.of("name","my_notice_summary","parameters",Map.of("type","object","properties",Map.of())))),"CERTIFICATE_MINE");
            assertThat(plan.calls()).hasSize(1); assertThat(plan.promptTokens()).isEqualTo(50); assertThat(plan.toString()).doesNotContain("已批准");
            assertThat(auth.get()).isEqualTo("Bearer test-key");
            assertThat(captured.get().path("thinking").path("type").asText()).isEqualTo("disabled"); assertThat(captured.get().path("max_tokens").asInt()).isEqualTo(800);
            assertThat(captured.get().path("messages").size()).isEqualTo(2); assertThat(captured.get().toString()).doesNotContain("identityNumber","purpose","contact","password_hash");
        } finally { server.stop(0); }
    }
    @Test void upstreamErrorNeverExposesItsBodyOrCredential() throws Exception {
        var server=server(401,"secret upstream diagnostic test-key",new AtomicReference<>(),new AtomicReference<>());
        try { assertThatThrownBy(()->client(server).plan("问题",List.of(),"无")).isInstanceOfSatisfying(ApiException.class,e->{ assertThat(e.code()).isEqualTo("MODEL_UNAVAILABLE"); assertThat(e.getMessage()).doesNotContain("test-key","diagnostic"); }); }
        finally { server.stop(0); }
    }
    @Test void malformedJsonAndTruncatedCallsFailExplicitly() throws Exception {
        for(String body:List.of("not-json","{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}",
            "{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"x\",\"arguments\":\"not-json\"}}]}}]}")) {
            var server=server(200,body,new AtomicReference<>(),new AtomicReference<>());
            try { assertThatThrownBy(()->client(server).plan("问题",List.of(),"无")).isInstanceOf(ApiException.class); } finally { server.stop(0); }
        }
    }
    @Test void requestTimeoutIsReportedAsTimeoutRatherThanInvalidModelOutput() throws Exception {
        var executor=Executors.newSingleThreadExecutor();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(executor);
        server.createContext("/chat/completions",exchange->{
            try { Thread.sleep(3000); exchange.sendResponseHeaders(200,0); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        }); server.start();
        try {
            var client=new DeepSeekClient(HttpClient.newHttpClient(),"test-key","deepseek-flash",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions"),true,1);
            assertThatThrownBy(()->client.plan("问题",List.of(),null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("MODEL_TIMEOUT"));
        } finally { server.stop(0); executor.shutdownNow(); }
    }
    private DeepSeekClient client(HttpServer server) { return new DeepSeekClient(HttpClient.newHttpClient(),"test-key","deepseek-flash",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions"),true,2); }
    @Test void leaveExtractionSendsOnlyCurrentTextAndCannotDefineAContactOrWriteTool() throws Exception {
        var captured=new AtomicReference<JsonNode>();
        var server=server(200,"{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"content\":\"已提交（虚构）\",\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"collect_leave_fields\",\"arguments\":\"{\\\"startTime\\\":\\\"09:00\\\"}\"}}]}}]}",captured,new AtomicReference<>());
        try {
            var plan=client(server).prepareLeave("开始上午九点");
            assertThat(plan.calls()).hasSize(1); assertThat(plan.toString()).doesNotContain("已提交");
            var body=captured.get(); assertThat(body.path("messages").size()).isEqualTo(2); assertThat(body.path("messages").path(1).path("content").asText()).isEqualTo("开始上午九点");
            assertThat(body.path("tool_choice").path("function").path("name").asText()).isEqualTo("collect_leave_fields");
            assertThat(body.path("tools").size()).isEqualTo(1);
            assertThat(body.path("tools").path(0).path("function").path("parameters").path("properties").toString()).doesNotContain("contact","studentId","submit","status");
        } finally { server.stop(0); }
    }
    @Test void certificateExtractionOffersOnlyPurposeAndSendsNoLocalIdentityOrHistory() throws Exception {
        var captured=new AtomicReference<JsonNode>();
        var response=Map.of("choices",List.of(Map.of("finish_reason","tool_calls","message",Map.of("content","已确认身份并提交（虚构）","tool_calls",List.of(Map.of("type","function","function",Map.of("name","collect_certificate_fields","arguments","{\"purpose\":\"实习核验\"}")))))));
        var server=server(200,json.writeValueAsString(response),captured,new AtomicReference<>());
        try {
            var plan=client(server).prepareCertificate("供实习单位核验在校身份"); assertThat(plan.calls()).hasSize(1); assertThat(plan.toString()).doesNotContain("已确认身份");
            var body=captured.get(); assertThat(body.path("messages").size()).isEqualTo(2); assertThat(body.path("messages").path(1).path("content").asText()).isEqualTo("供实习单位核验在校身份");
            assertThat(body.path("tool_choice").path("function").path("name").asText()).isEqualTo("collect_certificate_fields"); assertThat(body.path("tools").size()).isEqualTo(1);
            var properties=body.path("tools").path(0).path("function").path("parameters").path("properties"); assertThat(properties.size()).isEqualTo(1); assertThat(properties.has("purpose")).isTrue();
            assertThat(body.toString()).doesNotContain("studentName","identityNumber","identityConfirmed","20260001","expectedIdentity");
        } finally { server.stop(0); }
    }
    private HttpServer server(int status,String body,AtomicReference<JsonNode> captured,AtomicReference<String> auth) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{ captured.set(json.readTree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8))); auth.set(exchange.getRequestHeaders().getFirst("Authorization")); var bytes=body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); });
        server.start(); return server;
    }
}
