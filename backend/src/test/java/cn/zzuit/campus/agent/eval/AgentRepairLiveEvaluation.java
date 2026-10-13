package cn.zzuit.campus.agent.eval;

import cn.zzuit.campus.agent.*;
import cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.repair.RepairModels.Action;
import cn.zzuit.campus.repair.RepairModels.Draft;
import cn.zzuit.campus.repair.RepairService;
import cn.zzuit.campus.repair.RepairSettingsModels.LocationInput;
import cn.zzuit.campus.repair.RepairSettingsService;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Opt-in repair question run. The class name stays outside normal Surefire patterns. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.datasource.url=jdbc:h2:mem:campus-agent-repair-live-eval;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=true"})
class AgentRepairLiveEvaluation {
    private static final String SECRET_CONTACT = "repair-live-secret@example.test";
    private static final String SECRET_TEXT = "评测夹具故障说明不外泄";
    @Autowired DeepSeekClient model;
    @Autowired AgentTools tools;
    @Autowired AgentRepairDrafts drafts;
    @Autowired RepairService repairs;
    @Autowired RepairSettingsService settings;
    @Autowired IdentityService identities;
    @Autowired JdbcClient jdbc;
    @Autowired Environment environment;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Map<String,Object>> observations = new ArrayList<>();
    private int attempted, succeeded;

    @Test void executeRepairCasesOnce() throws Exception {
        if (!"true".equals(System.getenv("CAMPUS_AGENT_REPAIR_LIVE_EVAL"))) throw new IllegalStateException("Repair live evaluation requires explicit opt-in");
        if (!environment.getProperty("spring.datasource.url", "").startsWith("jdbc:h2:mem:campus-agent-repair-live-eval;")) throw new IllegalStateException("Evaluation database is not isolated");
        if (!model.ready()) throw new IllegalStateException("Model credential is not available");
        Path root = Path.of("..").toRealPath();
        Path definitionPath = Path.of(System.getProperty("campus.agent.repair.live.catalog")).toRealPath();
        if (!definitionPath.equals(root.resolve(".Codex/evals/agent-repair-live-v1.json").toRealPath())) throw new IllegalArgumentException("Unexpected repair definition path");
        JsonNode definition = json.readTree(Files.readString(definitionPath, StandardCharsets.UTF_8));
        AgentRepairLiveGrader.validate(definition);
        Path runDirectory = Path.of(System.getProperty("campus.agent.repair.live.output")).toRealPath();
        if (!runDirectory.startsWith(root.resolve("output/agent-repair-live-evals")) || runDirectory.equals(root.resolve("output/agent-repair-live-evals"))) throw new IllegalArgumentException("Unexpected output path");
        Path observationPath = runDirectory.resolve("observations.json");
        if (Files.exists(observationPath)) throw new IllegalArgumentException("Refusing to overwrite an existing evaluation");
        var admin = (CampusPrincipal) identities.loadUserByUsername("admin");
        var student = (CampusPrincipal) identities.loadUserByUsername("20260001");
        long location = settings.createLocation(admin, new LocationInput(1, "agent-repair-live", "评测开放地点", "CAMPUS_PUBLIC", null, null, "", "DEMO", 0, "报修问法评测夹具")).id();
        var created = repairs.save(student, null, new Draft("agent-repair-live-fixture", 0, location, "ELECTRICITY", "评测灯具", SECRET_TEXT, "", SECRET_CONTACT, ""));
        var fixture = repairs.transition(student, "mine", created.id(), "submit", new Action("agent-repair-live-submit", created.version(), "提交评测夹具", null, null, null, null));
        Map<String,Long> before = businessCounts();
        Instant started = Instant.now();
        save(observationPath, definition.path("id").asText(), started, false, before);
        for (JsonNode item : definition.path("cases")) {
            var actor = (CampusPrincipal) identities.loadUserByUsername(item.path("actor").asText());
            String operation = item.path("operation").asText(), errorCode = null;
            Plan plan = null;
            JsonNode preview = null;
            var results = new ArrayList<ToolResult>();
            long start = System.nanoTime(), modelStart = 0, modelDuration = 0;
            try {
                identities.requirePermission(actor, "agent:query");
                if (operation.equals("REPAIR")) drafts.require(actor);
                Context context = context(item, actor, fixture.id());
                tools.validateContext(actor, context);
                modelStart = System.nanoTime();
                attempted++;
                try {
                    plan = operation.equals("REPAIR") ? model.prepareRepair(item.path("message").asText()) : model.plan(item.path("message").asText(), tools.schemas(actor), context == null ? null : context.type());
                    succeeded++;
                } finally { modelDuration = (System.nanoTime() - modelStart) / 1_000_000; }
                if (operation.equals("QUERY")) {
                    if (plan.calls() == null || plan.calls().size() > 4) throw new IllegalArgumentException("Invalid plan");
                    var names = new HashSet<String>();
                    var allowed = tools.capabilities(actor).stream().map(Capability::name).toList();
                    for (Call call : plan.calls()) if (call == null || !allowed.contains(call.name()) || !names.add(call.name())) throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "MODEL_TOOL_REJECTED", "Invalid tool set");
                    for (Call call : plan.calls()) {
                        try { results.add(tools.execute(actor, call, context)); }
                        catch (ApiException ex) { results.add(tools.failure(call.name(), ex)); }
                    }
                } else {
                    RepairFields initial = json.treeToValue(item.path("initialFields"), RepairFields.class);
                    try { preview = json.valueToTree(drafts.preview(actor, UUID.randomUUID().toString(), Mode.DEEPSEEK, drafts.merge(drafts.normalize(initial), plan))); }
                    catch (ApiException ex) { if (!ex.code().equals("MODEL_REPAIR_FIELDS_REJECTED")) throw ex; }
                }
            } catch (ApiException ex) { errorCode = ex.code(); }
            catch (RuntimeException ex) { errorCode = "EVALUATION_EXECUTION_ERROR"; }
            var verdict = AgentRepairLiveGrader.grade(item, plan, preview, results, businessCounts().equals(before));
            var row = new LinkedHashMap<String,Object>();
            row.put("id", item.path("id").asText()); row.put("group", item.path("group").asText()); row.put("title", item.path("title").asText());
            row.put("operation", operation); row.put("safety", item.path("safety").asBoolean()); row.put("message", item.path("message").asText());
            row.put("expected", item.path("expected")); row.put("status", errorCode != null ? "ERROR" : verdict.passed() ? "PASSED" : "FAILED");
            row.put("errorCode", errorCode); row.put("toolsMatched", verdict.toolsMatched()); row.put("fieldsMatched", operation.equals("REPAIR") && verdict.fieldsMatched());
            row.put("checks", verdict.checks()); row.put("modelResponded", plan != null); row.put("calls", plan == null ? List.of() : plan.calls());
            row.put("fields", preview == null ? null : preview.path("fields")); row.put("missing", preview == null ? null : preview.path("missing"));
            row.put("ready", preview == null ? null : preview.path("ready"));
            row.put("results", results.stream().map(result -> Map.of("name", result.name(), "status", result.status(), "total", result.total(), "targetPath", result.targetPath() == null ? "" : result.targetPath())).toList());
            row.put("durationMs", (System.nanoTime() - start) / 1_000_000); row.put("modelDurationMs", modelDuration);
            row.put("promptTokens", plan == null ? null : plan.promptTokens()); row.put("completionTokens", plan == null ? null : plan.completionTokens());
            observations.add(row);
            save(observationPath, definition.path("id").asText(), started, false, before);
            System.out.println("Agent repair live case " + row.get("id") + ": " + row.get("status"));
        }
        save(observationPath, definition.path("id").asText(), started, true, before);
        if (observations.size() != 16 || !businessCounts().equals(before)) throw new IllegalStateException("Incomplete or unsafe repair evaluation");
    }

    private Context context(JsonNode item, CampusPrincipal actor, long repairId) {
        if (item.path("context").isNull()) return null;
        String type = item.path("context").asText();
        if (type.equals("REPAIR_MINE") && !actor.username().equals("20260001")) throw new IllegalArgumentException("Mine context belongs to the fixture reporter");
        if (type.equals("REPAIR_DISPATCH") && !actor.username().equals("dispatcher1001")) throw new IllegalArgumentException("Dispatch context belongs to the fixture dispatcher");
        return new Context(type, repairId);
    }
    private Map<String,Long> businessCounts() {
        var counts = new LinkedHashMap<String,Long>();
        for (String table : List.of("leave_request", "certificate_request", "repair_request", "user_notice", "agent_session", "agent_task")) counts.put(table, jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single());
        return counts;
    }
    private void save(Path destination, String id, Instant started, boolean completed, Map<String,Long> before) throws Exception {
        var report = new LinkedHashMap<String,Object>();
        report.put("schemaVersion", 1); report.put("definitionId", id); report.put("evaluationType", "live-model-synthetic");
        report.put("externalModelCalls", attempted > 0); report.put("model", model.model()); report.put("temperature", 0.2); report.put("maxOutputTokens", 800);
        report.put("startedAtUtc", started.toString()); report.put("completedAtUtc", Instant.now().toString());
        report.put("completed", completed); report.put("attemptsPerCase", 1); report.put("attemptedModelCalls", attempted); report.put("successfulModelResponses", succeeded);
        report.put("businessRecordsBefore", before); report.put("businessRecordsAfter", businessCounts()); report.put("cases", observations);
        Files.writeString(destination, json.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
    }
}
