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
    private final AgentRepairDrafts repairDrafts;
    private final AgentHousingDrafts housingDrafts;
    private final Set<Long> busy=ConcurrentHashMap.newKeySet();
    private final Map<Long,Bucket> rates=new ConcurrentHashMap<>();
    private record Bucket(long minute,int count) {}
    public AgentService(IdentityService identities,AgentTools tools,DeepSeekClient model,JdbcClient jdbc,AgentLeaveDrafts leaveDrafts,AgentCertificateDrafts certificateDrafts,AgentRepairDrafts repairDrafts,AgentHousingDrafts housingDrafts) { this.identities=identities; this.tools=tools; this.model=model; this.jdbc=jdbc; this.leaveDrafts=leaveDrafts; this.certificateDrafts=certificateDrafts; this.repairDrafts=repairDrafts; this.housingDrafts=housingDrafts; }
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
            if(Pattern.compile("(帮我|直接|自动|绕过|忽略).*(审批|审核通过|提交|删除|撤回|取消|核销|发布|停用|开证明|派单|改派|验收|接单)|(别人|他人|其他同学|其他学生|其他账号|所有学生).*(成绩|申请|请假|材料|证明|课表|报修|宿舍|住宿|床位|调宿|日程|预约|借用)").matcher(question).find()) {
                outcome="REFUSED"; return new Answer(run,input.mode(),false,"第一期仅查询本人或当前岗位获授权的信息。不能查询无关人员资料，也不能代替提交或审批，请通过原业务页面办理。",List.of(),now(),null,null);
            }
            Plan plan=input.mode()==Mode.DEEPSEEK?model.plan(question,tools.schemas(user),input.context()==null?null:input.context().type()):local(user,question,input.context());
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
            String summary=results.isEmpty()?"没有匹配到可用查询。可问请假、证明或报修办理规则、本人日程、课表、成绩考试、预约进度、空闲场地、报修进度或未读消息。"
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
    public RepairPreview prepareRepair(CampusPrincipal user,PrepareRepair input) {
        repairDrafts.require(user);
        var fields=repairDrafts.normalize(input.fields());
        if(input.mode()==Mode.DEEPSEEK && !input.externalConsent()) throw new ApiException(HttpStatus.BAD_REQUEST,"MODEL_CONSENT_REQUIRED","请确认将本次补充文字发送至 DeepSeek，或使用本地填写");
        if(input.mode()==Mode.DEEPSEEK && !model.ready()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"MODEL_NOT_CONFIGURED","DeepSeek 尚未连接，可使用本地填写");
        if(!busy.add(user.id())) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"QUERY_RUNNING","上一项操作仍在进行，请等待结果");
        String run=UUID.randomUUID().toString(),outcome="FAILED"; Integer prompt=null,completion=null; long start=System.nanoTime();
        try {
            rate(user.id());
            if(input.mode()==Mode.DEEPSEEK) {
                var plan=model.prepareRepair(input.message().trim()); prompt=plan.promptTokens(); completion=plan.completionTokens();
                fields=repairDrafts.merge(fields,plan);
            }
            var preview=repairDrafts.preview(user,run,input.mode(),fields);
            outcome=preview.ready()?"SUCCESS":"NEEDS_INPUT";
            return preview;
        } finally {
            try { jdbc.sql("INSERT INTO agent_run(id,user_id,mode,outcome,tool_names,prompt_tokens,completion_tokens,duration_ms) VALUES(:id,:u,:m,:o,'prepare_repair_fields',:p,:c,:d)")
                .param("id",run).param("u",user.id()).param("m",input.mode().name()).param("o",outcome)
                .param("p",prompt).param("c",completion).param("d",(System.nanoTime()-start)/1000000).update(); }
            finally { busy.remove(user.id()); }
        }
    }
    public HousingPreview prepareHousing(CampusPrincipal user,PrepareHousing input) {
        housingDrafts.require(user);
        var fields=housingDrafts.normalize(input.fields());
        if(input.mode()==Mode.DEEPSEEK && !input.externalConsent()) throw new ApiException(HttpStatus.BAD_REQUEST,"MODEL_CONSENT_REQUIRED","请确认将本次补充文字发送至 DeepSeek，或使用本地填写");
        if(input.mode()==Mode.DEEPSEEK && !model.ready()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"MODEL_NOT_CONFIGURED","DeepSeek 尚未连接，可使用本地填写");
        if(!busy.add(user.id())) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"QUERY_RUNNING","上一项操作仍在进行，请等待结果");
        String run=UUID.randomUUID().toString(),outcome="FAILED"; Integer prompt=null,completion=null; long start=System.nanoTime();
        try {
            rate(user.id());
            if(input.mode()==Mode.DEEPSEEK) {
                var plan=model.prepareHousing(input.message().trim()); prompt=plan.promptTokens(); completion=plan.completionTokens();
                fields=housingDrafts.merge(fields,plan);
            }
            var preview=housingDrafts.preview(user,run,input.mode(),fields);
            outcome=preview.ready()?"SUCCESS":"NEEDS_INPUT";
            return preview;
        } finally {
            try { jdbc.sql("INSERT INTO agent_run(id,user_id,mode,outcome,tool_names,prompt_tokens,completion_tokens,duration_ms) VALUES(:id,:u,:m,:o,'prepare_housing_fields',:p,:c,:d)")
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
    private Plan local(CampusPrincipal user,String q,Context context) {
        var planning=tools.localPlanning(q,context);
        if(planning!=null && !planning.isEmpty())return new Plan(planning,null,null);
        var names=tools.capabilities(user).stream().map(Capability::name).toList();
        var result=new ArrayList<Call>();
        if(context!=null && context.type().equals("HOUSING_MINE") && q.matches(".*(这条|当前|这个|状态|进度).*")) result.add(tools.call("housing_transfer_status",Map.of()));
        else if(context!=null && context.type().startsWith("REPAIR_") && names.contains("repair_status") && q.matches(".*(这条|当前|这个|处理状态|处理进度).*")) result.add(tools.call("repair_status",Map.of()));
        else if(context!=null && q.matches(".*(这条|当前|这个|处理状态|处理进度).*")) result.add(tools.call("application_status",Map.of()));
        if(names.contains("campus_navigation") && (q.matches(".*(怎么走|怎么去|在哪|在哪个位置|在什么位置|路线|导航|去往|怎么到|步行路线).*")
            || (q.contains("去") && (q.contains("楼") || q.contains("馆") || q.contains("门") || q.contains("餐厅") || q.contains("操场") || q.contains("食堂") || q.contains("食府") || q.contains("广场") || q.contains("湖"))))) {
            result.add(tools.call("campus_navigation",Map.of("destination",q,"origin","南门")));
        } else if(q.matches(".*(怎么|如何|规则|最长|材料|领取|办理流程).*")) result.add(tools.call("search_guides",Map.of("query",q.length()>200?q.substring(0,200):q)));
        if(q.matches(".*(我的住宿|我的宿舍|住在哪|住哪里|当前住宿|当前房间|本人住宿).*")) result.add(tools.call("my_housing",Map.of()));
        if(q.matches(".*(我的调宿|调宿进度|调宿记录|调宿申请.*状态).*")) result.add(tools.call("my_housing_transfers",Map.of()));
        if(q.matches(".*(课表|课程安排|本周|这周|下周|上课|授课).*")) {
            LocalDate date=LocalDate.now(ZoneId.of("Asia/Shanghai")); if(q.contains("下周")) date=date.plusWeeks(1);
            result.add(tools.call("my_timetable",Map.of("week",date.toString())));
        }
        if(q.contains("成绩")) result.add(tools.call("my_grades",Map.of()));
        if(q.contains("考试")) result.add(tools.call("my_exams",Map.of()));
        if(q.matches(".*(未读|多少.*消息|消息数量).*")) result.add(tools.call("my_notice_summary",Map.of()));
        if(names.contains("my_repairs") && q.matches(".*(我的报修|报修进度|报修草稿|报修记录).*")) {
            String status=q.contains("草稿")?"DRAFT":q.contains("待补充")||q.contains("补交")?"RETURNED":q.contains("待验收")?"AWAITING_CONFIRMATION":q.contains("待分诊")?"SUBMITTED":"ALL";
            result.add(tools.call("my_repairs",Map.of("status",status)));
        }
        if(names.contains("repair_dispatch_queue") && q.matches(".*(待分诊|派单待办|后勤待办|报修待办|待派单).*")) result.add(tools.call("repair_dispatch_queue",Map.of("status",q.contains("待分诊")||q.contains("待派单")?"SUBMITTED":"ALL")));
        if(names.contains("repair_work_queue") && q.matches(".*(维修任务|我的维修|待接单|待维修).*")) result.add(tools.call("repair_work_queue",Map.of("status",q.contains("处理中")?"IN_PROGRESS":q.contains("待接单")?"ASSIGNED":"ALL")));
        if(q.contains("岗位待办") || q.contains("班级待办") || q.contains("学院待办")) {
            if(names.contains("my_review_pending")) result.add(tools.call("my_review_pending",Map.of()));
            else if(names.contains("repair_dispatch_queue")) result.add(tools.call("repair_dispatch_queue",Map.of("status","SUBMITTED")));
            else if(names.contains("repair_work_queue")) result.add(tools.call("repair_work_queue",Map.of("status","ALL")));
        } else if(result.isEmpty() || q.matches(".*(我的办事|申请进度|需要补交|待处理|我的申请).*")) {
            if(q.matches(".*(办事|请假|销假|返校|证明|申请|補交|补交|待处理).*")) result.add(tools.call("my_affairs",Map.of("kind",q.contains("证明")?"CERTIFICATE":(q.contains("请假")||q.contains("销假")||q.contains("返校"))?"LEAVE":"ALL","status",q.contains("补交")?"RETURNED":"ALL")));
        }
        return new Plan(result.stream().distinct().limit(4).toList(),null,null);
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
}
