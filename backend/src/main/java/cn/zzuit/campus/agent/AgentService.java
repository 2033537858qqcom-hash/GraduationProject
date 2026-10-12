package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class AgentService {
    private final IdentityService identities;
    private final AgentTools tools;
    private final DeepSeekClient model;
    private final JdbcClient jdbc;
    private final AgentLeaveDrafts leaveDrafts;
    private final AgentCertificateDrafts certificateDrafts;
    private final Set<Long> busy=ConcurrentHashMap.newKeySet();
    private final Map<Long,Bucket> rates=new ConcurrentHashMap<>();
    private record Bucket(long minute,int count) {}
    public AgentService(IdentityService identities,AgentTools tools,DeepSeekClient model,JdbcClient jdbc,AgentLeaveDrafts leaveDrafts,AgentCertificateDrafts certificateDrafts) { this.identities=identities; this.tools=tools; this.model=model; this.jdbc=jdbc; this.leaveDrafts=leaveDrafts; this.certificateDrafts=certificateDrafts; }
    public Capabilities capabilities(CampusPrincipal user) {
        return new Capabilities(model.ready(),model.model(),tools.capabilities(user),model.ready()?"DeepSeek 可用：仅发送本次问题与页面业务类型，实际查询结果留在本地。":"DeepSeek 尚未连接，当前可使用本地关键词查询。");
    }
    public Answer query(CampusPrincipal user,Query input) {
        identities.requirePermission(user,"agent:query"); tools.validateContext(user,input.context());
        if(input.mode()==Mode.DEEPSEEK && !input.externalConsent()) throw new ApiException(HttpStatus.BAD_REQUEST,"MODEL_CONSENT_REQUIRED","请确认将本次问题发送至 DeepSeek，或使用本地查询");
        if(input.mode()==Mode.DEEPSEEK && !model.ready()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"MODEL_NOT_CONFIGURED","DeepSeek 尚未连接，可使用本地查询");
        if(!busy.add(user.id())) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"QUERY_RUNNING","上一项查询仍在进行，请等待结果");
        String run=UUID.randomUUID().toString(),outcome="FAILED"; var called=new ArrayList<String>(); Integer prompt=null,completion=null; long start=System.nanoTime();
        try {
            rate(user.id());
            String question=input.message().trim();
            if(Pattern.compile("(帮我|直接|自动|绕过|忽略).*(审批|审核通过|提交|删除|撤回|发布|停用|开证明)|(别人|他人|其他同学|其他学生|其他账号|所有学生).*(成绩|申请|请假|材料|证明|课表)").matcher(question).find()) {
                outcome="REFUSED"; return new Answer(run,input.mode(),false,"第一期仅查询本人或当前岗位获授权的信息。不能查询无关人员资料，也不能代替提交或审批，请通过原业务页面办理。",List.of(),now(),null,null);
            }
            Plan plan=input.mode()==Mode.DEEPSEEK?model.plan(question,tools.schemas(user),input.context()==null?null:input.context().type()):local(question,input.context());
            prompt=plan.promptTokens(); completion=plan.completionTokens();
            if(plan.calls()==null || plan.calls().size()>4) throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_RESPONSE_INVALID","查询计划超出限制，未执行工具");
            // Reject unknown or duplicate tool names before execution; each tool then validates its arguments.
            var allowed=tools.capabilities(user).stream().map(Capability::name).toList(); var unique=new HashSet<String>();
            for(var call:plan.calls()) if(call==null || (input.mode()==Mode.DEEPSEEK && !allowed.contains(call.name())) || !unique.add(call.name())) throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_TOOL_REJECTED","模型选择了不可用或重复工具，查询未执行");
            var results=new ArrayList<ToolResult>();
            for(var call:plan.calls()) {
                identities.requirePermission(user,"agent:query"); called.add(call.name());
                try { results.add(tools.execute(user,call,input.context())); }
                catch(ApiException ex) { if(ex.status()==HttpStatus.UNAUTHORIZED) throw ex; results.add(tools.failure(call.name(),ex)); }
            }
            identities.requirePermission(user,"agent:query");
            outcome=results.isEmpty()?"NO_MATCH":results.stream().allMatch(x->x.status().equals("OK"))?"SUCCESS":"PARTIAL";
            String summary=results.isEmpty()?"没有匹配到可用查询。可问请假或证明办理规则、本人课表、成绩考试、办事进度或未读消息。"
                :outcome.equals("SUCCESS")?"已按当前账号范围完成 "+results.size()+" 项查询，结果以本地业务和当前有效指南为准。"
                :"已尝试 "+results.size()+" 项查询，其中有未完成的项目，请查看各项提示。";
            return new Answer(run,input.mode(),input.mode()==Mode.DEEPSEEK,summary,List.copyOf(results),now(),prompt,completion);
        } finally {
            try { jdbc.sql("INSERT INTO agent_run(id,user_id,mode,outcome,tool_names,prompt_tokens,completion_tokens,duration_ms) VALUES(:id,:u,:m,:o,:t,:p,:c,:d)")
                .param("id",run).param("u",user.id()).param("m",input.mode().name()).param("o",outcome).param("t",String.join(",",called))
                .param("p",prompt).param("c",completion).param("d",(System.nanoTime()-start)/1000000).update(); }
            finally { busy.remove(user.id()); }
        }
    }
    public LeavePreview prepareLeave(CampusPrincipal user,PrepareLeave input) {
        leaveDrafts.require(user);
        var fields=leaveDrafts.normalize(input.fields());
        if(input.mode()==Mode.DEEPSEEK && !input.externalConsent()) throw new ApiException(HttpStatus.BAD_REQUEST,"MODEL_CONSENT_REQUIRED","请确认将本次补充文字发送至 DeepSeek，或使用本地填写");
        if(input.mode()==Mode.DEEPSEEK && !model.ready()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"MODEL_NOT_CONFIGURED","DeepSeek 尚未连接，可使用本地填写");
        if(!busy.add(user.id())) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"QUERY_RUNNING","上一项操作仍在进行，请等待结果");
        String run=UUID.randomUUID().toString(),outcome="FAILED"; Integer prompt=null,completion=null; long start=System.nanoTime();
        try {
            rate(user.id());
            if(input.mode()==Mode.DEEPSEEK) {
                var plan=model.prepareLeave(input.message().trim());
                prompt=plan.promptTokens(); completion=plan.completionTokens();
                fields=leaveDrafts.merge(fields,plan);
            }
            var preview=leaveDrafts.preview(user,run,input.mode(),fields);
            outcome=preview.ready()?"SUCCESS":"NEEDS_INPUT";
            return preview;
        } finally {
            try { jdbc.sql("INSERT INTO agent_run(id,user_id,mode,outcome,tool_names,prompt_tokens,completion_tokens,duration_ms) VALUES(:id,:u,:m,:o,'collect_leave_fields',:p,:c,:d)")
                .param("id",run).param("u",user.id()).param("m",input.mode().name()).param("o",outcome)
                .param("p",prompt).param("c",completion).param("d",(System.nanoTime()-start)/1000000).update(); }
            finally { busy.remove(user.id()); }
        }
    }
    public CertificatePreview prepareCertificate(CampusPrincipal user,PrepareCertificate input) {
        certificateDrafts.require(user);
        var fields=certificateDrafts.normalize(input.fields());
        if(input.mode()==Mode.DEEPSEEK && !input.externalConsent()) throw new ApiException(HttpStatus.BAD_REQUEST,"MODEL_CONSENT_REQUIRED","请确认将本次用途说明发送至 DeepSeek，或使用本地填写");
        if(input.mode()==Mode.DEEPSEEK && !model.ready()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"MODEL_NOT_CONFIGURED","DeepSeek 尚未连接，可使用本地填写");
        if(!busy.add(user.id())) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"QUERY_RUNNING","上一项操作仍在进行，请等待结果");
        String run=UUID.randomUUID().toString(),outcome="FAILED"; Integer prompt=null,completion=null; long start=System.nanoTime();
        try {
            rate(user.id());
            if(input.mode()==Mode.DEEPSEEK) {
                var plan=model.prepareCertificate(input.message().trim()); prompt=plan.promptTokens(); completion=plan.completionTokens();
                fields=certificateDrafts.merge(fields,plan);
            }
            var preview=certificateDrafts.preview(user,run,input.mode(),fields);
            outcome=preview.ready()?"SUCCESS":"NEEDS_INPUT";
            return preview;
        } finally {
            try { jdbc.sql("INSERT INTO agent_run(id,user_id,mode,outcome,tool_names,prompt_tokens,completion_tokens,duration_ms) VALUES(:id,:u,:m,:o,'collect_certificate_fields',:p,:c,:d)")
                .param("id",run).param("u",user.id()).param("m",input.mode().name()).param("o",outcome)
                .param("p",prompt).param("c",completion).param("d",(System.nanoTime()-start)/1000000).update(); }
            finally { busy.remove(user.id()); }
        }
    }
    private void rate(long user) {
        long minute=Instant.now().getEpochSecond()/60;
        rates.entrySet().removeIf(e->e.getValue().minute()<minute-2);
        var bucket=rates.compute(user,(k,b)->b==null || b.minute()!=minute?new Bucket(minute,1):new Bucket(minute,b.count()+1));
        if(bucket.count()>30) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"QUERY_RATE_LIMIT","查询较频繁，请稍后重试");
    }
    private Plan local(String q,Context context) {
        var result=new ArrayList<Call>();
        if(context!=null && q.matches(".*(这条|当前|这个|处理状态|处理进度).*")) result.add(tools.call("application_status",Map.of()));
        if(q.matches(".*(怎么|如何|规则|最长|材料|领取|办理流程).*")) result.add(tools.call("search_guides",Map.of("query",q.length()>200?q.substring(0,200):q)));
        if(q.matches(".*(课表|课程安排|本周|这周|下周|上课|授课).*")) {
            LocalDate date=LocalDate.now(ZoneId.of("Asia/Shanghai")); if(q.contains("下周")) date=date.plusWeeks(1);
            result.add(tools.call("my_timetable",Map.of("week",date.toString())));
        }
        if(q.contains("成绩")) result.add(tools.call("my_grades",Map.of()));
        if(q.contains("考试")) result.add(tools.call("my_exams",Map.of()));
        if(q.matches(".*(未读|多少.*消息|消息数量).*")) result.add(tools.call("my_notice_summary",Map.of()));
        if(q.contains("岗位待办") || q.contains("班级待办") || q.contains("学院待办")) result.add(tools.call("my_review_pending",Map.of()));
        else if(result.isEmpty() || q.matches(".*(我的办事|申请进度|需要补交|待处理|我的申请).*")) {
            if(q.matches(".*(办事|请假|证明|申请|補交|补交|待处理).*")) result.add(tools.call("my_affairs",Map.of("kind",q.contains("证明")?"CERTIFICATE":q.contains("请假")?"LEAVE":"ALL","status",q.contains("补交")?"RETURNED":"ALL")));
        }
        return new Plan(result.stream().distinct().limit(4).toList(),null,null);
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
}
