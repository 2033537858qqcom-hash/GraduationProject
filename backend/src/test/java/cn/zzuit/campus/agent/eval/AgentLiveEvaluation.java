package cn.zzuit.campus.agent.eval;

import cn.zzuit.campus.agent.*;
import cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.certificate.CertificateModels;
import cn.zzuit.campus.certificate.CertificateService;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
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

/** Explicit opt-in only: the name deliberately does not match normal Surefire test patterns. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "spring.datasource.url=jdbc:h2:mem:campus-agent-live-eval;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.demo.enabled=true"})
class AgentLiveEvaluation {
    @Autowired DeepSeekClient model;
    @Autowired AgentTools tools;
    @Autowired AgentLeaveDrafts leaves;
    @Autowired AgentCertificateDrafts proofs;
    @Autowired CertificateService certificates;
    @Autowired IdentityService identities;
    @Autowired JdbcClient jdbc;
    @Autowired Environment environment;
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Map<String,Object>> observations = new ArrayList<>();
    private int attempted, succeeded;

    @Test void executeSyntheticCasesOnce() throws Exception {
        if (!"true".equals(System.getenv("CAMPUS_AGENT_LIVE_EVAL"))) throw new IllegalStateException("Live evaluation requires explicit opt-in");
        if (!environment.getProperty("spring.datasource.url", "").startsWith("jdbc:h2:mem:campus-agent-live-eval;")) throw new IllegalStateException("Evaluation database is not isolated");
        if (!model.ready()) throw new IllegalStateException("Model credential is not available");
        Path root = Path.of("..").toRealPath();
        Path definitionPath = Path.of(System.getProperty("campus.agent.live.catalog")).toRealPath();
        if (!definitionPath.equals(root.resolve(".Codex/evals/agent-live-v1.json").toRealPath())) throw new IllegalArgumentException("Unexpected definition path");
        JsonNode definition = json.readTree(Files.readString(definitionPath, StandardCharsets.UTF_8));
        AgentLiveGrader.validate(definition);
        Path runDirectory = Path.of(System.getProperty("campus.agent.live.output")).toRealPath();
        if (!runDirectory.startsWith(root.resolve("output/agent-live-evals")) || runDirectory.equals(root.resolve("output/agent-live-evals"))) throw new IllegalArgumentException("Unexpected output path");
        Path observationPath = runDirectory.resolve("observations.json");
        if (Files.exists(observationPath)) throw new IllegalArgumentException("Refusing to overwrite an existing evaluation");
        LocalDate referenceDate = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        // Keep seed teaching data usable for any run date; only this disposable in-memory database changes.
        jdbc.sql("UPDATE academic_term SET starts_on=:s,ends_on=:e WHERE id=1")
            .param("s", java.sql.Date.valueOf(referenceDate.minusMonths(1))).param("e", java.sql.Date.valueOf(referenceDate.plusMonths(4))).update();
        var student = (CampusPrincipal) identities.loadUserByUsername("20260001");
        var contextRequest = certificates.save(student, null, new CertificateModels.Draft("agent-live-context-fixture", "合成评测上下文", "DIGITAL", true, 0, null));
        Map<String,Long> before = businessCounts();
        Instant started = Instant.now();
        save(observationPath, definition.path("id").asText(), referenceDate, started, false, before);
        for (JsonNode raw : definition.path("cases")) {
            if (!referenceDate.equals(LocalDate.now(ZoneId.of("Asia/Shanghai")))) throw new IllegalStateException("Beijing reference date changed during this run");
            JsonNode item = AgentLiveGrader.resolve(raw, referenceDate);
            var actor = (CampusPrincipal) identities.loadUserByUsername(item.path("actor").asText());
            String operation = item.path("operation").asText(), errorCode = null;
            Plan plan = null;
            JsonNode preview = null;
            var results = new ArrayList<ToolResult>();
            long start = System.nanoTime(), modelStart = 0, modelDuration = 0;
            try {
                identities.requirePermission(actor, "agent:query");
                if (operation.equals("LEAVE")) leaves.require(actor);
                if (operation.equals("CERTIFICATE")) proofs.require(actor);
                Context context = item.path("context").isNull() ? null : new Context("CERTIFICATE_MINE", contextRequest.id());
                tools.validateContext(actor, context);
                modelStart = System.nanoTime();
                attempted++;
                try {
                    plan = switch (operation) {
                        case "QUERY" -> model.plan(item.path("message").asText(), tools.schemas(actor), context == null ? null : context.type());
                        case "LEAVE" -> model.prepareLeave(item.path("message").asText());
                        case "CERTIFICATE" -> model.prepareCertificate(item.path("message").asText());
                        default -> throw new IllegalArgumentException("Unknown operation");
                    };
                    succeeded++;
                } finally { modelDuration = (System.nanoTime() - modelStart) / 1_000_000; }
                switch (operation) {
                    case "QUERY" -> {
                        if (plan.calls() == null || plan.calls().size() > 4) throw new IllegalArgumentException("Invalid plan");
                        var names = new HashSet<String>();
                        var allowed = tools.capabilities(actor).stream().map(Capability::name).toList();
                        for (Call call : plan.calls()) if (call == null || !allowed.contains(call.name()) || !names.add(call.name())) throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "MODEL_TOOL_REJECTED", "Invalid tool set");
                        for (Call call : plan.calls()) {
                            try { results.add(tools.execute(actor, call, context)); }
                            catch (ApiException ex) { results.add(tools.failure(call.name(), ex)); }
                        }
                    }
                    case "LEAVE" -> {
                        LeaveFields initial = json.treeToValue(item.path("initialFields"), LeaveFields.class);
                        preview = json.valueToTree(leaves.preview(actor, UUID.randomUUID().toString(), Mode.DEEPSEEK, leaves.merge(leaves.normalize(initial), plan)));
                    }
                    case "CERTIFICATE" -> {
                        CertificateFields initial = json.treeToValue(item.path("initialFields"), CertificateFields.class);
                        preview = json.valueToTree(proofs.preview(actor, UUID.randomUUID().toString(), Mode.DEEPSEEK, proofs.merge(proofs.normalize(initial), plan)));
                    }
                    default -> throw new IllegalArgumentException("Unknown operation");
                }
            } catch (ApiException ex) { errorCode = ex.code(); }
            catch (RuntimeException ex) { errorCode = "EVALUATION_EXECUTION_ERROR"; }
            var verdict = AgentLiveGrader.grade(item, plan, preview, results, businessCounts().equals(before));
            var row = new LinkedHashMap<String,Object>();
            row.put("id", item.path("id").asText()); row.put("group", item.path("group").asText()); row.put("title", item.path("title").asText());
            row.put("operation", operation); row.put("safety", item.path("safety").asBoolean()); row.put("message", item.path("message").asText());
            row.put("expected", item.path("expected")); row.put("status", errorCode != null ? "ERROR" : verdict.passed() ? "PASSED" : "FAILED");
            row.put("errorCode", errorCode); row.put("toolsMatched", verdict.toolsMatched()); row.put("fieldsMatched", !operation.equals("QUERY") && verdict.fieldsMatched());
            row.put("checks", verdict.checks()); row.put("modelResponded", plan != null); row.put("calls", plan == null ? List.of() : plan.calls());
            row.put("fields", preview == null ? null : preview.path("fields")); row.put("missing", preview == null ? null : preview.path("missing"));
            row.put("ready", preview == null ? null : preview.path("ready")); row.put("results", results.stream().map(r -> Map.of("name",r.name(),"status",r.status(),"total",r.total(),"sources",r.sources().stream().map(Guide::slug).toList())).toList());
            row.put("durationMs", (System.nanoTime() - start) / 1_000_000); row.put("modelDurationMs", modelDuration);
            row.put("promptTokens", plan == null ? null : plan.promptTokens()); row.put("completionTokens", plan == null ? null : plan.completionTokens());
            observations.add(row);
            save(observationPath, definition.path("id").asText(), referenceDate, started, false, before);
            System.out.println("Agent live case " + row.get("id") + ": " + row.get("status"));
        }
        save(observationPath, definition.path("id").asText(), referenceDate, started, true, before);
        if (observations.size() != 40 || !businessCounts().equals(before)) throw new IllegalStateException("Incomplete or unsafe evaluation");
    }

    private Map<String,Long> businessCounts() {
        var counts = new LinkedHashMap<String,Long>();
        for (String table : List.of("leave_request", "certificate_request", "user_notice", "agent_session", "agent_task")) {
            counts.put(table, jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single());
        }
        return counts;
    }
    private void save(Path destination, String id, LocalDate date, Instant started, boolean completed, Map<String,Long> before) throws Exception {
        var report = new LinkedHashMap<String,Object>();
        report.put("schemaVersion",1); report.put("definitionId",id); report.put("evaluationType","live-model-synthetic");
        report.put("externalModelCalls",attempted > 0); report.put("model",model.model()); report.put("temperature",0.2); report.put("maxOutputTokens",800);
        report.put("referenceDateBeijing",date.toString()); report.put("startedAtUtc",started.toString()); report.put("completedAtUtc",Instant.now().toString());
        report.put("completed",completed); report.put("attemptsPerCase",1); report.put("attemptedModelCalls",attempted); report.put("successfulModelResponses",succeeded);
        report.put("businessRecordsBefore",before); report.put("businessRecordsAfter",businessCounts()); report.put("cases",observations);
        Files.writeString(destination, json.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8);
    }
}
