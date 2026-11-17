package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.identity.IdentityService;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AgentConcurrencyGuard guard;
    @Autowired
    public AgentService(IdentityService identities,AgentTools tools,DeepSeekClient model,JdbcClient jdbc,AgentLeaveDrafts leaveDrafts,AgentCertificateDrafts certificateDrafts,AgentRepairDrafts repairDrafts,AgentHousingDrafts housingDrafts,AgentConcurrencyGuard guard) { this.identities=identities; this.tools=tools; this.model=model; this.jdbc=jdbc; this.leaveDrafts=leaveDrafts; this.certificateDrafts=certificateDrafts; this.repairDrafts=repairDrafts; this.housingDrafts=housingDrafts; this.guard=guard; }
    public AgentService(IdentityService identities,AgentTools tools,DeepSeekClient model,JdbcClient jdbc,AgentLeaveDrafts leaveDrafts,AgentCertificateDrafts certificateDrafts,AgentRepairDrafts repairDrafts,AgentHousingDrafts housingDrafts) { this(identities,tools,model,jdbc,leaveDrafts,certificateDrafts,repairDrafts,housingDrafts,new AgentConcurrencyGuard()); }
    public Capabilities capabilities(CampusPrincipal user) {
        return new Capabilities(model.ready(),model.model(),tools.capabilities(user),model.ready()?"DeepSeek 可用：仅发送本次问题与页面业务类型，实际查询结果留在本地。":"DeepSeek 尚未连接，当前可使用本地关键词查询。");
    }
    private record AggregatedStats(long total,long ok,long partial,long failed,long refused,long avgDurationMs,long promptSum,long completionSum,List<String> topTools,long upFeedbacks,long downFeedbacks) {}
    private AggregatedStats aggregate(List<Object[]> rows,Map<String,Long> feedbackCounts) {
        long total=rows.size(),ok=0,partial=0,failed=0,refused=0,durationSum=0,promptSum=0,completionSum=0;
        var toolCounts=new LinkedHashMap<String,Long>();
        for(var row:rows) {
            String out=(String)row[0],tNames=(String)row[1];
            Integer pTok=(Integer)row[2],cTok=(Integer)row[3];
            long dur=(Long)row[4];
            durationSum+=dur;
            if(pTok!=null) promptSum+=pTok;
            if(cTok!=null) completionSum+=cTok;
            switch(out) {
                case "SUCCESS" -> ok++;
                case "PARTIAL" -> partial++;
                case "REFUSED" -> refused++;
                case "FAILED" -> failed++;
                default -> {}
            }
            if(tNames!=null && !tNames.isBlank()) {
                for(String t:tNames.split(",")) {
                    String clean=t.trim();
                    if(!clean.isBlank()) toolCounts.merge(clean,1L,Long::sum);
                }
            }
        }
        var top=toolCounts.entrySet().stream()
            .sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
            .limit(5).map(e->e.getKey()+"("+e.getValue()+")").toList();
        return new AggregatedStats(total,ok,partial,failed,refused,total==0?0:durationSum/total,promptSum,completionSum,top,feedbackCounts.getOrDefault("UP",0L),feedbackCounts.getOrDefault("DOWN",0L));
    }
    private Map<String,Long> feedbackCounts(Long userId) {
        String sql=userId==null?"SELECT rating,COUNT(*) FROM agent_feedback GROUP BY rating":"SELECT rating,COUNT(*) FROM agent_feedback WHERE user_id=:u GROUP BY rating";
        var spec=jdbc.sql(sql);
        if(userId!=null) spec=spec.param("u",userId);
        return spec.query(rs->{
            var map=new HashMap<String,Long>();
            while(rs.next()) map.put(rs.getString(1),rs.getLong(2));
            return map;
        });
    }
    public RunMetrics metrics(CampusPrincipal user) {
        identities.requirePermission(user,"agent:query");
        var rows=jdbc.sql("SELECT outcome,tool_names,prompt_tokens,completion_tokens,duration_ms FROM agent_run WHERE user_id=:u ORDER BY created_at DESC LIMIT 500")
            .param("u",user.id()).query((r,n)->new Object[]{r.getString(1),r.getString(2),r.getObject(3,Integer.class),r.getObject(4,Integer.class),r.getLong(5)}).list();
        var s=aggregate(rows,feedbackCounts(user.id()));
        return new RunMetrics(s.total(),s.ok(),s.partial(),s.failed(),s.refused(),s.avgDurationMs(),s.promptSum(),s.completionSum(),s.topTools(),s.upFeedbacks(),s.downFeedbacks());
    }
    public OverviewMetrics overviewMetrics(CampusPrincipal user) {
        identities.requirePermission(user,"foundation:manage");
        var rows=jdbc.sql("SELECT outcome,tool_names,prompt_tokens,completion_tokens,duration_ms FROM agent_run ORDER BY created_at DESC LIMIT 2000")
            .query((r,n)->new Object[]{r.getString(1),r.getString(2),r.getObject(3,Integer.class),r.getObject(4,Integer.class),r.getLong(5)}).list();
        long activeUsers=jdbc.sql("SELECT COUNT(DISTINCT user_id) FROM agent_run").query(Long.class).single();
        var s=aggregate(rows,feedbackCounts(null));
        var durations=rows.stream().map(r->(Long)r[4]).sorted().toList();
        long p95=durations.isEmpty()?0:durations.get(Math.min(durations.size()-1,(int)Math.floor(0.95*(durations.size()-1))));
        var outcomes=Map.of("SUCCESS",s.ok(),"PARTIAL",s.partial(),"FAILED",s.failed(),"REFUSED",s.refused());
        var negativeReasons=jdbc.sql("SELECT comment,COUNT(*) AS cnt FROM agent_feedback WHERE rating='DOWN' AND comment IS NOT NULL AND comment<>'' GROUP BY comment ORDER BY cnt DESC,comment ASC LIMIT 6")
            .query((r,n)->r.getString(1)+" ("+r.getLong(2)+")").list();
        return new OverviewMetrics(s.total(),s.ok(),s.partial(),s.failed(),s.refused(),s.avgDurationMs(),s.promptSum(),s.completionSum(),activeUsers,s.upFeedbacks(),s.downFeedbacks(),s.topTools(),p95,outcomes,negativeReasons);
    }
    public Briefing briefing(CampusPrincipal user) {
        identities.requirePermission(user,"agent:query");
        var caps=tools.capabilities(user).stream().map(Capability::name).toList();
        long unread=0,pending=0; String housingNote=null; var suggestions=new ArrayList<String>();
        try { unread=tools.execute(user,tools.call("my_notice_summary",Map.of()),null).total(); } catch(Exception ignored) {}
        if(caps.contains("my_review_pending")) {
            try { pending=tools.execute(user,tools.call("my_review_pending",Map.of()),null).total(); } catch(Exception ignored) {}
        }
        if(caps.contains("my_housing_transfers")) {
            try {
                var tr=tools.execute(user,tools.call("my_housing_transfers",Map.of()),null);
                housingNote=tr.items().stream().filter(f->f.description().contains("待入住")).map(Fact::description).findFirst().orElse(null);
            } catch(Exception ignored) {}
        }
        if(pending>0) suggestions.add("生成岗位运行简报");
        if(caps.contains("staff_triage_advice")) suggestions.add("查看岗位分诊与审核建议");
        if(caps.contains("my_schedule")) suggestions.add("今天有哪些日程");
        if(caps.contains("my_timetable")) suggestions.add("本周课表");
        if(unread>0) suggestions.add("我有多少未读消息");
        return new Briefing(unread,pending,housingNote,suggestions.stream().distinct().limit(4).toList());
    }
    public FeedbackSummary submitFeedback(CampusPrincipal user,String runId,FeedbackInput input) {
        identities.requirePermission(user,"agent:query");
        if(jdbc.sql("SELECT COUNT(*) FROM agent_run WHERE id=:id AND user_id=:u").param("id",runId).param("u",user.id()).query(Long.class).single()==0) {
            throw new ApiException(HttpStatus.NOT_FOUND,"RUN_NOT_FOUND","未找到对应的查询记录");
        }
        String comment=input.comment()==null?null:input.comment().trim();
        if(comment!=null && comment.isBlank()) comment=null;
        int updated=jdbc.sql("UPDATE agent_feedback SET rating=:r,comment=:c,updated_at=CURRENT_TIMESTAMP WHERE user_id=:u AND run_id=:run")
            .param("r",input.rating()).param("c",comment).param("u",user.id()).param("run",runId).update();
        if(updated==0) {
            jdbc.sql("INSERT INTO agent_feedback(user_id,run_id,rating,comment) VALUES(:u,:run,:r,:c)")
                .param("u",user.id()).param("run",runId).param("r",input.rating()).param("c",comment).update();
        }
        return jdbc.sql("SELECT run_id,rating,comment,updated_at FROM agent_feedback WHERE user_id=:u AND run_id=:run")
            .param("u",user.id()).param("run",runId)
            .query((r,n)->new FeedbackSummary(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toLocalDateTime())).single();
    }
    public Answer query(CampusPrincipal user,Query input) {
        return queryWithLocalContinuation(user,input,null,null,null);
    }
    Answer queryWithLocalContinuation(CampusPrincipal user,Query input,Call continuation) {
        return queryWithLocalContinuation(user,input,continuation,null,null);
    }
    Answer queryWithLocalContinuation(CampusPrincipal user,Query input,Call continuation,Consumer<String> progress) {
        return queryWithLocalContinuation(user,input,continuation,progress,null);
    }
    Answer queryWithLocalContinuation(CampusPrincipal user,Query input,Call continuation,Consumer<String> progress,Consumer<String> onDelta) {
        identities.requirePermission(user,"agent:query"); tools.validateContext(user,input.context());
        if(input.mode()==Mode.DEEPSEEK && !input.externalConsent()) throw new ApiException(HttpStatus.BAD_REQUEST,"MODEL_CONSENT_REQUIRED","请确认将本次问题发送至 DeepSeek，或使用本地查询");
        if(input.mode()==Mode.DEEPSEEK && continuation==null && !model.ready()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"MODEL_NOT_CONFIGURED","DeepSeek 尚未连接，可使用本地查询");
        guard.acquireTurn(user.id(),"上一项查询仍在进行，请等待结果");
        String run=UUID.randomUUID().toString(),outcome="FAILED"; var called=new ArrayList<String>(); Integer prompt=null,completion=null; long start=System.nanoTime();
        try {
            guard.checkRate(user.id());
            String question=input.message().trim();
            if(isRefusedWriteOrImpersonation(question)) {
                outcome="REFUSED"; return new Answer(run,input.mode(),false,"第一期仅查询本人或当前岗位获授权的信息。不能查询无关人员资料，也不能代替提交或审批，请通过原业务页面办理。",List.of(),now(),null,null);
            }
            if(input.mode()==Mode.LOCAL && continuation==null && canHandle(user) && localGoalRequest(question)) {
                outcome="NEEDS_INPUT";
                return new Answer(run,input.mode(),false,"办理调宿、请假、证明、报修或场地预约需要选择 DeepSeek 并确认发送。校园Agent会组织计划并等待你核对保存；本地查询只回答进度和规则。",List.of(new ToolResult("start_goal","开始自主办理","NEEDS_INPUT","请改用 DeepSeek 并确认发送本次办理目标。",0,List.of(),List.of(),null)),now(),null,null);
            }
            boolean modelUsed=input.mode()==Mode.DEEPSEEK&&continuation==null;
            emitProgress(progress, modelUsed ? "正在请求 DeepSeek 规划查询…" : "正在分析提问与匹配本地规则…");
            Plan plan=continuation!=null?new Plan(List.of(continuation),null,null):modelUsed?model.plan(question,planningSchemas(user),input.context()==null?null:input.context().type()):local(user,question,input.context());
            prompt=plan.promptTokens(); completion=plan.completionTokens();
            if(plan.calls()!=null && plan.calls().stream().anyMatch(call->call!=null && "start_goal".equals(call.name()))) {
                plan=new Plan(plan.calls().stream().filter(call->call!=null && "start_goal".equals(call.name())).limit(1).toList(),plan.promptTokens(),plan.completionTokens());
            }
            if(input.mode()==Mode.LOCAL && plan.calls()!=null && (plan.calls().size()>4 || plan.calls().stream().map(Call::name).distinct().count()!=plan.calls().size())) {
                outcome="NEEDS_INPUT";
                return new Answer(run,input.mode(),false,"请拆分提问：一次最多查询4种不同事项；同一类事项有不同条件时，请分别查询，避免漏查或混用条件。",List.of(),now(),null,null);
            }
            if(plan.calls()==null || plan.calls().size()>4) throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_RESPONSE_INVALID","查询计划超出限制，未执行工具");
            // Reject unknown or duplicate tool names before execution; each tool then validates its arguments.
            var allowed=new ArrayList<>(tools.capabilities(user).stream().map(Capability::name).toList());
            if(input.mode()==Mode.DEEPSEEK && canHandle(user)) allowed.add("start_goal");
            var unique=new HashSet<String>();
            for(var call:plan.calls()) if(call==null || (input.mode()==Mode.DEEPSEEK && !allowed.contains(call.name())) || !unique.add(call.name())) throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_TOOL_REJECTED","模型选择了不可用或重复工具，查询未执行");
            if(!plan.calls().isEmpty()) emitProgress(progress, "正在检索校务数据（共 " + plan.calls().size() + " 项）…");
            var results=new ArrayList<ToolResult>();
            for(var call:plan.calls()) {
                identities.requirePermission(user,"agent:query"); called.add(call.name());
                emitProgress(progress, "正在查询：" + progressToolLabel(call.name()));
                try { results.add("start_goal".equals(call.name())?startGoal(call,question):tools.execute(user,call,input.context())); }
                catch(ApiException ex) { if(ex.status()==HttpStatus.UNAUTHORIZED) throw ex; results.add(tools.failure(call.name(),ex)); }
            }
            emitProgress(progress, "正在整理结果与页面跳转链接…");
            identities.requirePermission(user,"agent:query");
            boolean started=results.size()==1 && results.getFirst().name().equals("start_goal") && results.getFirst().status().equals("START_GOAL");
            outcome=results.isEmpty()?"NO_MATCH":started||results.stream().allMatch(x->x.status().equals("OK"))?"SUCCESS":"PARTIAL";
            String summary=null;
            if(modelUsed && !started && !results.isEmpty()) {
                String sanitized=sanitizeToolFacts(results);
                if(sanitized!=null) {
                    emitProgress(progress,"正在生成流式归纳回答…");
                    summary=model.summarizeStream(question,sanitized,onDelta);
                }
            }
            if(summary==null || summary.isBlank()) summary=synthesizeSummary(results,started,outcome);
            return new Answer(run,input.mode(),modelUsed,summary,List.copyOf(results),now(),prompt,completion);
        } finally {
            try { jdbc.sql("INSERT INTO agent_run(id,user_id,mode,outcome,tool_names,prompt_tokens,completion_tokens,duration_ms) VALUES(:id,:u,:m,:o,:t,:p,:c,:d)")
                .param("id",run).param("u",user.id()).param("m",input.mode().name()).param("o",outcome).param("t",String.join(",",called))
                .param("p",prompt).param("c",completion).param("d",(System.nanoTime()-start)/1000000).update(); }
            finally { guard.releaseTurn(user.id()); }
        }
    }
    public LeavePreview prepareLeave(CampusPrincipal user,PrepareLeave input) {
        leaveDrafts.require(user);
        var base=leaveDrafts.normalize(input.fields());
        return runPrepare(user,input.mode(),input.externalConsent(),"collect_leave_fields","请确认将本次补充文字发送至 DeepSeek，或使用本地填写",
            ()->input.mode()==Mode.DEEPSEEK?model.prepareLeave(input.message().trim()):null,
            plan->leaveDrafts.preview(user,"",input.mode(),plan==null?base:leaveDrafts.merge(base,plan)),
            (run,preview)->new LeavePreview(run,preview.mode(),preview.modelUsed(),preview.fields(),preview.missing(),preview.issues(),preview.ready(),preview.summary(),preview.duration(),preview.source(),preview.eligibilityMessage(),preview.preparedAt()),
            LeavePreview::ready);
    }
    public CertificatePreview prepareCertificate(CampusPrincipal user,PrepareCertificate input) {
        certificateDrafts.require(user);
        var base=certificateDrafts.normalize(input.fields());
        return runPrepare(user,input.mode(),input.externalConsent(),"collect_certificate_fields","请确认将本次用途说明发送至 DeepSeek，或使用本地填写",
            ()->input.mode()==Mode.DEEPSEEK?model.prepareCertificate(input.message().trim()):null,
            plan->certificateDrafts.preview(user,"",input.mode(),plan==null?base:certificateDrafts.merge(base,plan)),
            (run,preview)->new CertificatePreview(run,preview.mode(),preview.modelUsed(),preview.fields(),preview.identity(),preview.missing(),preview.issues(),preview.ready(),preview.summary(),preview.source(),preview.canSubmit(),preview.eligibilityMessage(),preview.preparedAt()),
            CertificatePreview::ready);
    }
    public RepairPreview prepareRepair(CampusPrincipal user,PrepareRepair input) {
        repairDrafts.require(user);
        var base=repairDrafts.normalize(input.fields());
        return runPrepare(user,input.mode(),input.externalConsent(),"prepare_repair_fields","请确认将本次补充文字发送至 DeepSeek，或使用本地填写",
            ()->input.mode()==Mode.DEEPSEEK?model.prepareRepair(input.message().trim()):null,
            plan->repairDrafts.preview(user,"",input.mode(),plan==null?base:repairDrafts.merge(base,plan)),
            (run,preview)->new RepairPreview(run,preview.mode(),preview.modelUsed(),preview.fields(),preview.missing(),preview.issues(),preview.ready(),preview.summary(),preview.source(),preview.eligibilityMessage(),preview.locationName(),preview.preparedAt()),
            RepairPreview::ready);
    }
    public HousingPreview prepareHousing(CampusPrincipal user,PrepareHousing input) {
        housingDrafts.require(user);
        var base=housingDrafts.normalize(input.fields());
        return runPrepare(user,input.mode(),input.externalConsent(),"prepare_housing_fields","请确认将本次补充文字发送至 DeepSeek，或使用本地填写",
            ()->input.mode()==Mode.DEEPSEEK?model.prepareHousing(input.message().trim()):null,
            plan->housingDrafts.preview(user,"",input.mode(),plan==null?base:housingDrafts.merge(base,plan)),
            (run,preview)->new HousingPreview(run,preview.mode(),preview.modelUsed(),preview.fields(),preview.missing(),preview.issues(),preview.ready(),preview.summary(),preview.source(),preview.eligibilityMessage(),preview.currentPlace(),preview.stayToken(),preview.preparedAt()),
            HousingPreview::ready);
    }
    private <T> T runPrepare(CampusPrincipal user,Mode mode,boolean consent,String toolName,String consentMessage,Supplier<Plan> planner,java.util.function.Function<Plan,T> builder,java.util.function.BiFunction<String,T,T> withRunId,Predicate<T> readyCheck) {
        if(mode==Mode.DEEPSEEK && !consent) throw new ApiException(HttpStatus.BAD_REQUEST,"MODEL_CONSENT_REQUIRED",consentMessage);
        if(mode==Mode.DEEPSEEK && !model.ready()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"MODEL_NOT_CONFIGURED","DeepSeek 尚未连接，可使用本地填写");
        guard.acquireTurn(user.id(),"上一项操作仍在进行，请等待结果");
        String run=UUID.randomUUID().toString(),outcome="FAILED"; Integer prompt=null,completion=null; long start=System.nanoTime();
        try {
            guard.checkRate(user.id());
            Plan plan=planner.get();
            if(plan!=null) { prompt=plan.promptTokens(); completion=plan.completionTokens(); }
            T preview=withRunId.apply(run,builder.apply(plan));
            outcome=readyCheck.test(preview)?"SUCCESS":"NEEDS_INPUT";
            return preview;
        } finally {
            try { jdbc.sql("INSERT INTO agent_run(id,user_id,mode,outcome,tool_names,prompt_tokens,completion_tokens,duration_ms) VALUES(:id,:u,:m,:o,:t,:p,:c,:d)")
                .param("id",run).param("u",user.id()).param("m",mode.name()).param("o",outcome).param("t",toolName)
                .param("p",prompt).param("c",completion).param("d",(System.nanoTime()-start)/1000000).update(); }
            finally { guard.releaseTurn(user.id()); }
        }
    }
    private boolean canHandle(CampusPrincipal user) {
        var permissions=identities.permissions(user.id());
        return permissions.contains("certificate:apply")||permissions.contains("leave:apply")||permissions.contains("repair:apply")||permissions.contains("reservation:apply")||permissions.contains("housing:read");
    }
    private boolean localGoalRequest(String question) {
        if(question.matches(".*(怎么|如何|规则|最长|材料|领取|办理流程|进度|待办).*")) return false;
        if(question.matches(".*(查询|查看|查一下|查下|看看|有哪些|有多少|列表|记录|状态|已取消).*")
            && !question.matches(".*(草稿|准备|办理|申请).*")) return false;
        return question.matches("(?s).*(帮我|给我|请帮|替我|准备|办理).*(请假|休假|在校证明|学籍证明|证明草稿|报修|预约|借用|调宿|换宿舍|换寝室).*")
            || question.matches("(?s).*(请假|休假|在校证明|学籍证明|报修|预约|借用|调宿|换宿舍).*(草稿|提交|跟踪).*");
    }
    private List<Map<String,Object>> planningSchemas(CampusPrincipal user) {
        var schemas=new ArrayList<>(tools.schemas(user));
        if(!canHandle(user)) return schemas;
        schemas.add(Map.of("type","function","function",Map.of("name","start_goal","description","当用户要求办理、准备草稿、确认后提交或跟踪本人的场地预约、报修、请假、在校证明或调宿（含混合目标）时使用。不要用于只读查询、缴费、审批或他人事项。goal填写用户原话中的办理目标。","parameters",Map.of("type","object","properties",Map.of("goal",Map.of("type","string","maxLength",1000)),"required",List.of(),"additionalProperties",false))));
        return schemas;
    }
    private ToolResult startGoal(Call call,String question) {
        var arguments=call.arguments();
        if(arguments==null||!arguments.isObject()||arguments.properties().stream().anyMatch(field->!field.getKey().equals("goal"))) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_TOOL_REJECTED","模型选择了不可用或重复工具，查询未执行");
        }
        String goal=question;
        if(arguments.has("goal")&&arguments.path("goal").isString()&&!arguments.path("goal").asText().isBlank()) {
            goal=arguments.path("goal").asText().trim();
            if(goal.length()>1000) throw new ApiException(HttpStatus.BAD_GATEWAY,"MODEL_TOOL_REJECTED","模型选择了不可用或重复工具，查询未执行");
        }
        return new ToolResult("start_goal","开始自主办理","START_GOAL",goal,0,List.of(),List.of(),null);
    }
    private Plan local(CampusPrincipal user,String q,Context context) {
        var calls=new ArrayList<Call>();
        for(String clause:q.split("(?:[；;\\n]+|(?:以及|并且|另外|同时|顺便)(?=充|查|看|我|本周|下周|课表|成绩|考试|未读|宿舍|请|再|帮|给)|[，,](?=再|还|也|并)|[和与](?=充|查|看|我|本周|下周|课表|成绩|考试|未读|宿舍))")) {
            if(!clause.isBlank())calls.addAll(localSingle(user,clause.trim(),context).calls());
        }
        return new Plan(calls.stream().distinct().toList(),null,null);
    }
    private Plan localSingle(CampusPrincipal user,String q,Context context) {
        var payment=tools.localPayments(q,context);
        if(!payment.isEmpty())return new Plan(payment,null,null);
        var planning=tools.localPlanning(q,context);
        if(planning!=null && !planning.isEmpty())return new Plan(planning,null,null);
        var names=tools.capabilities(user).stream().map(Capability::name).toList();
        var result=new ArrayList<Call>();
        if(context!=null && context.type().equals("HOUSING_MINE") && q.matches(".*(这条|当前|这个|状态|进度).*")) result.add(tools.call("housing_transfer_status",Map.of()));
        else if(context!=null && context.type().startsWith("REPAIR_") && names.contains("repair_status") && q.matches(".*(这条|当前|这个|处理状态|处理进度).*")) result.add(tools.call("repair_status",Map.of()));
        else if(context!=null && !context.type().equals("PAYMENT_MINE") && q.matches(".*(这条|当前|这个|处理状态|处理进度).*")) result.add(tools.call("application_status",Map.of()));
        if(names.contains("campus_navigation") && (q.matches(".*(怎么走|怎么去|在哪|在哪个位置|在什么位置|路线|导航|去往|怎么到|步行路线).*")
            || (q.contains("去") && (q.contains("楼") || q.contains("馆") || q.contains("门") || q.contains("餐厅") || q.contains("操场") || q.contains("食堂") || q.contains("食府") || q.contains("广场") || q.contains("湖"))))) {
            result.add(buildNavCall(q));
        } else if(q.matches(".*(怎么|如何|规则|最长|材料|领取|办理流程).*")) result.add(tools.call("search_guides",Map.of("query",q.length()>200?q.substring(0,200):q)));
        if(names.contains("my_housing") && q.matches(".*(我的住宿|我的宿舍|住在哪|住哪里|当前住宿|当前房间|本人住宿).*")) result.add(tools.call("my_housing",Map.of()));
        if(names.contains("my_housing_transfers") && q.matches(".*(我的调宿|调宿进度|调宿记录|调宿申请.*状态).*")) result.add(tools.call("my_housing_transfers",Map.of()));
        if(names.contains("my_timetable") && q.matches(".*(课表|课程安排|本周|这周|下周|上课|授课).*")) {
            LocalDate date=LocalDate.now(ZoneId.of("Asia/Shanghai")); if(q.contains("下周")) date=date.plusWeeks(1);
            result.add(tools.call("my_timetable",Map.of("week",date.toString())));
        }
        if(q.contains("成绩")) result.add(tools.call("my_grades",Map.of()));
        if(names.contains("my_exams") && q.contains("考试")) result.add(tools.call("my_exams",Map.of()));
        if(q.matches(".*(未读|多少.*消息|消息数量).*")) result.add(tools.call("my_notice_summary",Map.of()));
        if(names.contains("my_repairs") && q.matches(".*(我的报修|报修进度|报修草稿|报修记录|报修).*")) {
            String status=q.contains("草稿")?"DRAFT":q.contains("待补充")||q.contains("补交")?"RETURNED":q.contains("待验收")?"AWAITING_CONFIRMATION":q.contains("待分诊")?"SUBMITTED":q.contains("已取消")?"CANCELLED":"ALL";
            result.add(tools.call("my_repairs",Map.of("status",status)));
        }
        if(names.contains("repair_dispatch_queue") && q.matches(".*(待分诊|派单待办|后勤待办|报修待办|待派单).*")) result.add(tools.call("repair_dispatch_queue",Map.of("status",q.contains("待分诊")||q.contains("待派单")?"SUBMITTED":"ALL")));
        if(names.contains("repair_work_queue") && q.matches(".*(维修任务|我的维修|待接单|待维修).*")) result.add(tools.call("repair_work_queue",Map.of("status",q.contains("处理中")?"IN_PROGRESS":q.contains("待接单")?"ASSIGNED":"ALL")));
        if(names.contains("staff_triage_advice") && q.matches(".*(派单建议|怎么分诊|工作负载|审核建议|退回模板|分诊参考).*")) result.add(tools.call("staff_triage_advice",Map.of()));
        if(names.contains("platform_digest") && q.matches(".*(运行简报|运营简报|平台简报|助手情况|岗位简报).*")) result.add(tools.call("platform_digest",Map.of()));
        if(q.contains("岗位待办") || q.contains("班级待办") || q.contains("学院待办")
            || (!names.contains("my_affairs") && q.matches(".*(待办|待审|待批|待分配|待排床|待核验|待处理).*"))) {
            if(names.contains("my_review_pending")) result.add(tools.call("my_review_pending",Map.of()));
            else if(names.contains("repair_dispatch_queue")) result.add(tools.call("repair_dispatch_queue",Map.of("status","SUBMITTED")));
            else if(names.contains("repair_work_queue")) result.add(tools.call("repair_work_queue",Map.of("status","ALL")));
        } else if(names.contains("my_affairs") && (result.isEmpty() || q.matches(".*(我的办事|申请进度|需要补交|待处理|我的申请).*"))) {
            if(q.matches(".*(办事|请假|销假|返校|证明|申请|補交|补交|待处理).*")) result.add(tools.call("my_affairs",Map.of("kind",q.contains("证明")?"CERTIFICATE":(q.contains("请假")||q.contains("销假")||q.contains("返校"))?"LEAVE":"ALL","status",q.contains("补交")?"RETURNED":"ALL")));
        }
        return new Plan(result.stream().distinct().toList(),null,null);
    }
    private boolean isRefusedWriteOrImpersonation(String question) {
        if(Pattern.compile("(别人|他人|其他同学|其他学生|其他账号|所有学生).*(成绩|申请|请假|材料|证明|课表|报修|宿舍|住宿|床位|调宿|日程|预约|借用|账单|缴费|充值|电费|水费)").matcher(question).find()) {
            return true;
        }
        if(Pattern.compile("(忽略|绕过|自动).*(提交)|直接提交|帮我提交|替我提交").matcher(question).find()) {
            return true;
        }
        boolean readLookupOnly=question.matches(".*(查询|查看|查一下|查下|看看|有哪些|有多少|列表|记录|进度|状态).*")
            && !question.matches(".*(审批|审核通过|删除|撤回|核销|发布|停用|派单|改派|验收|接单|支付|付款|退款|提交).*")
            && question.matches(".*已(取消|撤回|删除).*");
        if(readLookupOnly) return false;
        return Pattern.compile("(帮我|替我|直接|自动|绕过|忽略).*(审批|审核通过|删除|撤回|取消|核销|发布|停用|派单|改派|验收|接单|支付|付款|退款)").matcher(question).find();
    }
    private Call buildNavCall(String q) {
        String origin="南门";
        String destination=q;
        Matcher route=Pattern.compile("(?:从)([^到去往]{1,30}?)(?:出发)?(?:到|去|前往|去往)(.+)").matcher(q);
        if(route.find()) {
            origin=route.group(1).trim();
            destination=route.group(2).replaceAll("(?:怎么走|怎么去|路线|步行路线|导航|在哪|在哪个位置|在什么位置)[？?。!！]*$","").trim();
        }
        if(origin.isBlank() || origin.length()>80) origin="南门";
        if(destination.isBlank()) destination=q;
        if(destination.length()>80) destination=destination.substring(0,80);
        return tools.call("campus_navigation",Map.of("destination",destination,"origin",origin));
    }
    private String synthesizeSummary(List<ToolResult> results,boolean started,String outcome) {
        if(results.isEmpty()) {
            return "没有匹配到可用查询。可问请假、证明或报修办理规则、本人日程、课表、成绩考试、预约进度、空闲场地、报修进度或未读消息。";
        }
        if(started) {
            return "已开始自主办理。校园Agent会组织计划，并等待你核对后保存或提交。";
        }
        if(results.size()==1) {
            var r=results.getFirst();
            if("OK".equals(r.status())) {
                return "已完成【"+r.title()+"】查询："+r.message();
            }
            return "【"+r.title()+"】："+r.message();
        }
        var parts=new ArrayList<String>();
        ToolResult schedule=null,venues=null;
        for(var r:results) {
            parts.add(r.title()+("OK".equals(r.status())?"（"+r.total()+"条）":"（"+r.status()+"）"));
            if("OK".equals(r.status()) && ("my_schedule".equals(r.name()) || "my_timetable".equals(r.name()))) schedule=r;
            if("OK".equals(r.status()) && "find_available_venues".equals(r.name())) venues=r;
        }
        String crossNote="";
        if(schedule!=null && venues!=null) {
            crossNote=schedule.total()==0
                ?"；所查时段暂无日程冲突记录，可对照空闲场地发起预约"
                :"；个人安排共 "+schedule.total()+" 项，预约候选场地前请核对起止时间是否重叠";
        }
        String prefix=outcome.equals("SUCCESS")?"已按当前账号范围完成 "+results.size()+" 项查询：":"已尝试 "+results.size()+" 项查询（部分待补充或受限）：";
        return prefix+String.join("；",parts)+crossNote+"。详细结果以本地业务与当前有效指南为准。";
    }
    private String sanitizeToolFacts(List<ToolResult> results) {
        try {
            var json=tools.getClass().getDeclaredFields(); // avoid allocating unused mappers
            var mapper=new tools.jackson.databind.json.JsonMapper();
            var list=new ArrayList<Map<String,Object>>();
            for(var r:results) {
                var itemSummaries=r.items().stream().limit(4).map(f->Map.of(
                    "title",maskPii(f.title()),
                    "note",maskPii(f.description()==null?"":f.description().substring(0,Math.min(80,f.description().length())))
                )).toList();
                list.add(Map.of("tool",r.name(),"title",r.title(),"status",r.status(),"total",r.total(),"message",maskPii(r.message()),"items",itemSummaries));
            }
            return mapper.writeValueAsString(list);
        } catch(Exception ignored) { return null; }
    }
    private String maskPii(String s) {
        if(s==null) return "";
        return s.replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+","[邮箱]").replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)","[手机]");
    }
    private static void emitProgress(Consumer<String> progress,String message) {
        if(progress!=null && message!=null && !message.isBlank()) progress.accept(message);
    }
    private static String progressToolLabel(String name) {
        return switch(name) {
            case "start_goal" -> "自主办理规划";
            case "campus_navigation" -> "校园导航路线";
            case "search_guides" -> "办事指南规则";
            case "my_timetable" -> "本周课表";
            case "my_grades" -> "课程成绩";
            case "my_exams" -> "考试安排";
            case "my_schedule" -> "个人日程";
            case "my_payments" -> "缴费账单";
            case "quote_dorm_recharge" -> "宿舍水电试算";
            case "my_notice_summary" -> "未读消息统计";
            case "my_affairs", "application_status" -> "办事申请进度";
            case "my_repairs", "repair_status", "repair_dispatch_queue", "repair_work_queue" -> "报修工单记录";
            case "my_housing", "my_housing_transfers", "housing_transfer_status" -> "宿舍与调宿进度";
            case "my_reservations", "free_venues", "find_available_venues" -> "场地预约信息";
            case "my_review_pending" -> "岗位待办清单";
            default -> "校务信息";
        };
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneId.of("Asia/Shanghai")); }
}
