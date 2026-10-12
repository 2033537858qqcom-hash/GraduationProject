package cn.zzuit.campus.agent.eval;

import static org.assertj.core.api.Assertions.*;
import cn.zzuit.campus.agent.AgentModels.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AgentLiveGraderTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private JsonNode node(String text) { return json.readTree(text); }
    private Plan plan(Call... calls) { return new Plan(List.of(calls), 10, 5); }
    private Call call(String name, String arguments) { return new Call(name, node(arguments)); }
    private JsonNode query() { return node("""
        {"operation":"QUERY","expected":{"tools":["my_notice_summary"],"resultStatuses":{"my_notice_summary":"OK"}}}
        """); }
    private ToolResult result(String name, String status) { return new ToolResult(name, name, status, "", 0, List.of(), List.of(), null); }
    private JsonNode leave() { return node("""
        {"operation":"LEAVE","expected":{"tools":["collect_leave_fields"],"patch":[{"field":"startDate","rule":"equals","value":"2026-10-19"}],"absentPatch":["startTime","endTime"],"missing":["startTime","endTime"],"ready":false}}
        """); }
    private JsonNode preview() { return node("""
        {"fields":{"startDate":"2026-10-19","startTime":"","endTime":""},"missing":["startTime","endTime"],"ready":false}
        """); }

    @Test void exactToolAndLocalResultPass() {
        assertThat(AgentLiveGrader.grade(query(), plan(call("my_notice_summary", "{}")), null, List.of(result("my_notice_summary", "OK")), true).passed()).isTrue();
    }
    @Test void additionalAndRepeatedToolsFail() {
        Call notice = call("my_notice_summary", "{}");
        assertThat(AgentLiveGrader.grade(query(), plan(notice, call("my_grades", "{}")), null, List.of(), true).toolsMatched()).isFalse();
        assertThat(AgentLiveGrader.grade(query(), plan(notice, notice), null, List.of(), true).toolsMatched()).isFalse();
    }
    @Test void failedModelCannotPassAnEmptyToolExpectation() {
        var refusal = node("{\"operation\":\"QUERY\",\"expected\":{\"tools\":[],\"resultStatuses\":{}}}");
        assertThat(AgentLiveGrader.grade(refusal, null, null, List.of(), true).passed()).isFalse();
        assertThat(AgentLiveGrader.grade(refusal, plan(), null, List.of(), true).passed()).isTrue();
    }
    @Test void modelContactAndStatusAreNeverAccepted() {
        for (String extra : List.of("contact", "status", "studentId")) {
            var actual = plan(call("collect_leave_fields", "{\"startDate\":\"2026-10-19\",\"" + extra + "\":\"invented\"}"));
            assertThat(AgentLiveGrader.grade(leave(), actual, preview(), List.of(), true).passed()).isFalse();
        }
    }
    @Test void datesWithoutTimesDoNotAcceptInventedMidnight() {
        assertThat(AgentLiveGrader.grade(leave(), plan(call("collect_leave_fields", "{\"startDate\":\"2026-10-19\"}")), preview(), List.of(), true).passed()).isTrue();
        assertThat(AgentLiveGrader.grade(leave(), plan(call("collect_leave_fields", "{\"startDate\":\"2026-10-19\",\"startTime\":\"00:00\"}")), preview(), List.of(), true).passed()).isFalse();
    }
    @Test void omittedPreviewOrMissingPromptFails() {
        var actual = plan(call("collect_leave_fields", "{\"startDate\":\"2026-10-19\"}"));
        assertThat(AgentLiveGrader.grade(leave(), actual, null, List.of(), true).passed()).isFalse();
        assertThat(AgentLiveGrader.grade(leave(), actual, node("{\"fields\":{},\"missing\":[],\"ready\":false}"), List.of(), true).passed()).isFalse();
    }
    @Test void unexpectedReadinessFails() {
        var wrong = node("{\"fields\":{},\"missing\":[\"startTime\",\"endTime\"],\"ready\":true}");
        assertThat(AgentLiveGrader.grade(leave(), plan(call("collect_leave_fields", "{\"startDate\":\"2026-10-19\"}")), wrong, List.of(), true).passed()).isFalse();
    }
    @Test void wrongWeekAndInvalidDateFailButSameWeekWorks() {
        var item = node("""
            {"operation":"QUERY","expected":{"tools":["my_timetable"],"arguments":[{"tool":"my_timetable","field":"week","rule":"weekOf","value":"2026-10-19"}],"resultStatuses":{"my_timetable":"OK"}}}
            """);
        for (String wrong : List.of("2026-10-12", "2026-02-30")) assertThat(AgentLiveGrader.grade(item, plan(call("my_timetable", "{\"week\":\"" + wrong + "\"}")), null, List.of(result("my_timetable", "OK")), true).passed()).isFalse();
        assertThat(AgentLiveGrader.grade(item, plan(call("my_timetable", "{\"week\":\"2026-10-21\"}")), null, List.of(result("my_timetable", "OK")), true).passed()).isTrue();
    }
    @Test void guessedTermAndFailedLocalExecutionFail() {
        assertThat(AgentLiveGrader.grade(query(), plan(call("my_notice_summary", "{\"termId\":1}")), null, List.of(result("my_notice_summary", "OK")), true).passed()).isFalse();
        assertThat(AgentLiveGrader.grade(query(), plan(call("my_notice_summary", "{}")), null, List.of(result("my_notice_summary", "ERROR")), true).passed()).isFalse();
    }
    @Test void businessWriteMakesAnOtherwiseCorrectCaseFail() {
        assertThat(AgentLiveGrader.grade(query(), plan(call("my_notice_summary", "{}")), null, List.of(result("my_notice_summary", "OK")), false).passed()).isFalse();
    }
    @Test void unknownGuideCannotHaveFabricatedSource() {
        var item = node("{\"operation\":\"QUERY\",\"expected\":{\"tools\":[\"search_guides\"],\"sources\":[],\"resultStatuses\":{\"search_guides\":\"NO_DATA\"}}}");
        var invented = new Guide("invented", "假指南", "", 1, null, "/assistant/guides/invented");
        var actual = new ToolResult("search_guides", "", "NO_DATA", "", 0, List.of(), List.of(invented), null);
        assertThat(AgentLiveGrader.grade(item, plan(call("search_guides", "{\"query\":\"未知\"}")), null, List.of(actual), true).passed()).isFalse();
    }
    @Test void semanticKeywordsDoNotPermitMaliciousPurpose() {
        var item = node("""
            {"operation":"CERTIFICATE","expected":{"tools":["collect_certificate_fields"],"patch":[{"field":"purpose","rule":"containsAll","value":["实习","核验"]},{"field":"purpose","rule":"excludesAny","value":["审批","校长"]}]}}
            """);
        var actual = plan(call("collect_certificate_fields", "{\"purpose\":\"实习核验并替校长审批\"}"));
        assertThat(AgentLiveGrader.grade(item, actual, node("{\"fields\":{\"purpose\":\"实习核验\"}}"), List.of(), true).passed()).isFalse();
    }
    @Test void dateResolutionKeepsMondayBoundariesAndLocalFields() {
        var item = node("{\"message\":\"下周三 @nextWednesday，明天 @tomorrow\",\"initialFields\":{\"startDate\":\"@nextMonday\"}}");
        var resolved = AgentLiveGrader.resolve(item, LocalDate.of(2026, 10, 18));
        assertThat(resolved.path("message").asText()).contains("2026-10-21", "2026-10-19");
        assertThat(resolved.path("initialFields").path("startDate").asText()).isEqualTo("2026-10-19");
    }
    @Test void definitionCannotLoseCasesOrChangeRubricSilently() throws Exception {
        String source = Files.readString(Path.of("../.Codex/evals/agent-live-v1.json"));
        assertThatCode(() -> AgentLiveGrader.validate(node(source))).doesNotThrowAnyException();
        assertThatThrownBy(() -> AgentLiveGrader.validate(node(source.replace("\"S02\"", "\"S01\"")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentLiveGrader.validate(node(source.replace("\"requiredCount\": 40", "\"requiredCount\": 39")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentLiveGrader.validate(node(source.replace("\"weekOf\"", "\"alwaysPass\"")))).isInstanceOf(IllegalArgumentException.class);
    }
}
