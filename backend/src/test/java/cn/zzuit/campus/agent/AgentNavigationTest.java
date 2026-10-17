package cn.zzuit.campus.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import cn.zzuit.campus.agent.AgentModels.Call;
import cn.zzuit.campus.agent.AgentModels.Mode;
import cn.zzuit.campus.agent.AgentModels.Query;
import cn.zzuit.campus.agent.AgentModels.ToolResult;
import cn.zzuit.campus.certificate.CertificateService;
import cn.zzuit.campus.housing.HousingService;
import cn.zzuit.campus.housing.HousingTransferService;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import cn.zzuit.campus.leave.LeaveService;
import cn.zzuit.campus.notice.NoticeService;
import cn.zzuit.campus.repair.RepairService;
import cn.zzuit.campus.teaching.TeachingService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

class AgentNavigationTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private IdentityService identities;
    private AgentTools tools;
    private CampusPrincipal student;

    @BeforeEach
    void setUp() {
        identities = mock(IdentityService.class);
        AgentGuides guides = mock(AgentGuides.class);
        TeachingService teaching = mock(TeachingService.class);
        LeaveService leaves = mock(LeaveService.class);
        CertificateService certificates = mock(CertificateService.class);
        NoticeService notices = mock(NoticeService.class);
        RepairService repairs = mock(RepairService.class);
        HousingService housing = mock(HousingService.class);
        HousingTransferService transfers = mock(HousingTransferService.class);
        cn.zzuit.campus.portal.TaskAggregationService taskAggregator = mock(cn.zzuit.campus.portal.TaskAggregationService.class);

        tools = new AgentTools(identities, guides, teaching, leaves, certificates, notices, repairs, housing, transfers, taskAggregator);
        student = new CampusPrincipal(101L, "20260001", "secret", true, List.of("STUDENT"), List.of("agent:query", "portal:read"), 1L);
        when(identities.permissions(101L)).thenReturn(List.of("agent:query", "portal:read"));
    }

    @Test
    void capabilitiesIncludeCampusNavigation() {
        var caps = tools.capabilities(student);
        assertThat(caps.stream().anyMatch(c -> c.name().equals("campus_navigation"))).isTrue();
    }

    @Test
    void schemaDefinesRequiredDestination() {
        var schemas = tools.schemas(student);
        var navSchema = schemas.stream()
            .filter(s -> "campus_navigation".equals(((Map<?, ?>) s.get("function")).get("name")))
            .findFirst()
            .orElseThrow();

        @SuppressWarnings("unchecked")
        var fn = (Map<String, Object>) navSchema.get("function");
        @SuppressWarnings("unchecked")
        var params = (Map<String, Object>) fn.get("parameters");
        @SuppressWarnings("unchecked")
        var required = (List<String>) params.get("required");

        assertThat(required).contains("destination");
    }

    @Test
    void navigatesToJinxiuBuilding() {
        Call call = new Call("campus_navigation", json.valueToTree(Map.of("destination", "毓秀楼")));
        ToolResult result = tools.execute(student, call, null);

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.targetPath()).isEqualTo("/campus/explore?navigate=east-jinxiu");
        assertThat(result.message()).contains("毓秀楼");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).title()).contains("毓秀楼");
    }

    @Test
    void navigatesToSeparatedDiningAndJingyi() {
        Call callDining = new Call("campus_navigation", json.valueToTree(Map.of("destination", "锦绣餐厅")));
        ToolResult resDining = tools.execute(student, callDining, null);
        assertThat(resDining.status()).isEqualTo("OK");
        assertThat(resDining.targetPath()).isEqualTo("/campus/explore?navigate=east-jinxiu-dining");

        Call callJingyi = new Call("campus_navigation", json.valueToTree(Map.of("destination", "静逸楼")));
        ToolResult resJingyi = tools.execute(student, callJingyi, null);
        assertThat(resJingyi.status()).isEqualTo("OK");
        assertThat(resJingyi.targetPath()).isEqualTo("/campus/explore?navigate=east-jingyi");
    }

    @Test
    void navigatesToLibraryAndGym() {
        Call callLib = new Call("campus_navigation", json.valueToTree(Map.of("destination", "图书馆")));
        ToolResult resLib = tools.execute(student, callLib, null);
        assertThat(resLib.status()).isEqualTo("OK");
        assertThat(resLib.targetPath()).isEqualTo("/campus/explore?navigate=east-library");

        Call callGym = new Call("campus_navigation", json.valueToTree(Map.of("destination", "红体馆")));
        ToolResult resGym = tools.execute(student, callGym, null);
        assertThat(resGym.status()).isEqualTo("OK");
        assertThat(resGym.targetPath()).isEqualTo("/campus/explore?navigate=east-gym");
    }

    @Test
    void unknownDestinationReturnsNeedsInput() {
        Call callUnknown = new Call("campus_navigation", json.valueToTree(Map.of("destination", "不存在的神秘城堡")));
        ToolResult res = tools.execute(student, callUnknown, null);
        assertThat(res.status()).isEqualTo("NEEDS_INPUT");
        assertThat(res.targetPath()).isEqualTo("/campus/explore");
    }

    @Test
    void agentServiceLocalPlannerRoutesNavigationQueries() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        JdbcClient jdbc = mock(JdbcClient.class);
        var spec = mock(JdbcClient.StatementSpec.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(anyString())).thenReturn(spec);
        when(spec.param(anyString(), any())).thenReturn(spec);

        AgentLeaveDrafts leaveDrafts = mock(AgentLeaveDrafts.class);
        AgentCertificateDrafts certificateDrafts = mock(AgentCertificateDrafts.class);
        AgentRepairDrafts repairDrafts = mock(AgentRepairDrafts.class);
        AgentHousingDrafts housingDrafts = mock(AgentHousingDrafts.class);

        AgentService service = new AgentService(identities, tools, client, jdbc, leaveDrafts, certificateDrafts, repairDrafts, housingDrafts);

        var answer = service.query(student, new Query("去毓秀楼怎么走", Mode.LOCAL, false, null));
        assertThat(answer.results()).hasSize(1);
        assertThat(answer.results().get(0).name()).isEqualTo("campus_navigation");
        assertThat(answer.results().get(0).targetPath()).isEqualTo("/campus/explore?navigate=east-jinxiu");

        var answerLib = service.query(student, new Query("图书馆在哪", Mode.LOCAL, false, null));
        assertThat(answerLib.results()).hasSize(1);
        assertThat(answerLib.results().get(0).name()).isEqualTo("campus_navigation");
        assertThat(answerLib.results().get(0).targetPath()).isEqualTo("/campus/explore?navigate=east-library");
    }
}
