package cn.zzuit.campus.agent;

import static cn.zzuit.campus.agent.AgentModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import cn.zzuit.campus.payment.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Local payment projections and quotes. Never starts payment, creates orders or polls a gateway. */
@Service
public class AgentPaymentQueries {
    public static final Map<String,String> TITLES=Map.of("my_payments","本人账单与待缴金额","payment_detail","本人账单依据与支付状态","my_dorm_utilities","本人宿舍水电与充值记录","quote_dorm_recharge","宿舍充值报价","finance_reconciliation_pending","财务对账待办");
    private final PaymentService payments;
    private final DormRechargeService dorm;
    private final ReconciliationCases cases;
    private final JdbcClient jdbc;
    private final JsonMapper json=JsonMapper.builder().build();
    public AgentPaymentQueries(PaymentService payments,DormRechargeService dorm,ReconciliationCases cases,JdbcClient jdbc) {this.payments=payments;this.dorm=dorm;this.cases=cases;this.jdbc=jdbc;}
    public static boolean supports(String name) {return TITLES.containsKey(name);}
    public static Set<String> fields(String name) {return switch(name){case "my_payments"->Set.of("status");case "payment_detail"->Set.of("id");case "quote_dorm_recharge"->Set.of("category","mode","value","meterId");default->Set.of();};}
    public static void schema(String name,Map<String,Object> p) {
        switch(name) {
            case "my_payments" -> p.put("status",Map.of("type","string","enum",List.of("ALL","UNPAID","PAID","REFUNDED")));
            case "payment_detail" -> p.put("id",Map.of("type","integer","minimum",1,"description","仅使用用户明确给出的本人账单编号；当前页或第二笔等追问时省略，由本地上下文解析"));
            case "quote_dorm_recharge" -> {
                p.put("category",Map.of("type","string","enum",List.of("WATER","ELECTRICITY")));
                p.put("mode",Map.of("type","string","enum",List.of("AMOUNT","QUANTITY")));
                p.put("value",Map.of("type","string","description","用户明确输入的金额或用量，不能猜测"));
                p.put("meterId",Map.of("type","integer","minimum",1,"description","用户明确选择的本人宿舍表计编号；通常省略，由本地匹配水表或电表"));
            }
            default -> { }
        }
    }
    private static String money(long cents) {return BigDecimal.valueOf(cents,2).toPlainString();}
    private static String units(BigDecimal value) {return value.stripTrailingZeros().toPlainString();}
    private static String link(long id) {return "/portal/payments?id="+id+"&tab=details";}
    private ToolResult result(String name,String status,String message,long total,List<Fact> facts,String path) {return new ToolResult(name,TITLES.get(name),status,message,total,facts,List.of(),path);}
    private String text(JsonNode a,String key,Set<String> options) {
        var v=a.path(key);if(v.isMissingNode()||v.isNull())return null;
        if(!v.isString()||v.asText().isBlank()||v.asText().length()>40||options!=null&&!options.contains(v.asText()))throw invalid();return v.asText();
    }
    private Long id(JsonNode a,String key) {var v=a.path(key);if(v.isMissingNode()||v.isNull())return null;if(!v.isIntegralNumber()||!v.asText().matches("[1-9][0-9]{0,17}"))throw invalid();return v.asLong();}
    private ApiException invalid(){return new ApiException(HttpStatus.BAD_REQUEST,"INVALID_PAYMENT_QUERY","请核对账单编号、充值种类及明确的金额或用量");}
    public PaymentService.Bill owned(CampusPrincipal user,long id) {return payments.mine(user).stream().filter(b->b.id()==id).findFirst().orElseThrow(()->new ApiException(HttpStatus.NOT_FOUND,"BILL_NOT_FOUND","本人可见账单不存在"));}
    private boolean unpaid(PaymentService.Bill b) {return b.workflowStatus().equals("PUBLISHED")&&b.status().equals("UNPAID");}
    private String state(PaymentService.Bill b) {
        if(b.refundStatus().equals("REFUNDED"))return "已退款";
        if(b.workflowStatus().equals("VOIDED"))return "已作废";
        if(b.workflowStatus().equals("WAIVED"))return "已减免";
        if(b.status().equals("PAID"))return "已确认支付";
        if(b.paymentStartedAt()!=null)return "支付结果待核验";
        return "待支付";
    }
    public ToolResult execute(CampusPrincipal user,Call call,Context context) {
        var a=call.arguments();String name=call.name();
        if(name.equals("my_payments")) {
            String status=text(a,"status",Set.of("ALL","UNPAID","PAID","REFUNDED"));
            var all=payments.mine(user);
            var rows=all.stream().filter(b->status==null||status.equals("ALL")||status.equals("UNPAID")&&unpaid(b)||status.equals("PAID")&&b.status().equals("PAID")&&!b.refundStatus().equals("REFUNDED")||status.equals("REFUNDED")&&b.refundStatus().equals("REFUNDED")).toList();
            long amount=all.stream().filter(this::unpaid).mapToLong(PaymentService.Bill::amountCents).sum();
            return result(name,rows.isEmpty()?"NO_DATA":"OK","本人待支付账单总额 ¥"+money(amount)+"（含有财务处理申请的待缴账单）。本次筛选共 "+rows.size()+" 笔，按下列顺序展示最近至多 8 笔；点击原账单核对。",rows.size(),rows.stream().limit(8).map(b->new Fact("PAYMENT:"+b.id(),b.title(),"¥"+money(b.amountCents())+" · "+state(b)+" · "+b.billingPeriod()+" · 截止 "+Objects.toString(b.dueDate(),"未设置"),link(b.id()))).toList(),"/portal/payments");
        }
        if(name.equals("payment_detail")) {
            Long bill=id(a,"id");if(bill==null&&context!=null&&context.type().equals("PAYMENT_MINE"))bill=context.id();
            if(bill==null)return result(name,"NEEDS_INPUT","请点击本人账单，或明确账单编号；也可以先查询账单列表，再说第几笔。",0,List.of(),"/portal/payments");
            var b=owned(user,bill);
            String description="金额 ¥"+money(b.amountCents())+" · "+state(b)+"；收费账期："+b.billingPeriod()+"；依据："+b.basis()+"；"+(b.unitPriceCents()==null?"手工核定总额":"创建时单价 ¥"+money(b.unitPriceCents())+" × 数量 "+b.quantity())+"；标准："+Objects.toString(b.standardName(),"手工核定")+(b.pendingRequestId()==null?"":"；存在财务处理申请，请进入账单核对")+(b.refundStatus().equals("NONE")?"":"；退款记录状态："+b.refundStatus());
            return result(name,"OK","展示系统当前记录，不主动向平台查单。最近核验："+Objects.toString(b.lastCheckedAt(),"尚无")+"。支付结果待核实时请在原账单操作。",1,List.of(new Fact("PAYMENT:"+b.id(),b.title(),description,link(b.id()))),link(b.id()));
        }
        if(name.equals("finance_reconciliation_pending")) {
            boolean review=user.permissions().contains("payment:review");var pending=new ArrayList<ReconciliationCases.Case>();
            for(String status:review?List.of("PENDING_REVIEW"):List.of("OPEN","IN_PROGRESS"))pending.addAll(cases.list(user,1,50,status).items());
            var rows=pending.stream().filter(c->!review||!Objects.equals(c.assigneeId(),user.id())).sorted(Comparator.comparingLong(ReconciliationCases.Case::id).reversed()).limit(8).toList();
            return result(name,rows.isEmpty()?"NO_DATA":"OK","展示当前岗位首批可见事项中最近至多 8 项未完成对账工作，完整列表请进入交易明细核对。",rows.size(),rows.stream().map(c->new Fact("RECON:"+c.id(),"对账事项 #"+c.id(),(c.status().equals("OPEN")?"待核查":c.status().equals("IN_PROGRESS")?"核查中":"待复核")+" · "+c.explanation(),"/finance/payments?tab=reconciliation&case="+c.id())).toList(),"/finance/payments?tab=reconciliation");
        }
        var mine=dorm.mine(user);
        @SuppressWarnings("unchecked") var meters=(List<DormRechargeService.Meter>)mine.get("meters");
        if(name.equals("my_dorm_utilities")) {
            var facts=new ArrayList<Fact>();
            for(var m:meters)facts.add(new Fact("DORM_METER:"+m.id(),m.building()+" "+m.room()+" · "+(m.category().equals("WATER")?"水表":"电表"),"单价 ¥"+money(m.unitPriceCents())+"/"+m.unit()+" · 演示余额 "+units(m.balance())+" "+m.unit()+(m.enabled()&&m.standardAvailable()?"":" · 当前不可充值"),"/portal/payments"));
            @SuppressWarnings("unchecked") var history=(List<DormRechargeService.Recharge>)mine.get("history");
            for(var h:history.stream().limit(5).toList())facts.add(new Fact("DORM_RECHARGE:"+h.id(),"宿舍充值记录",h.category()+" · ¥"+money(h.amountCents())+" · "+units(h.quantity())+"；"+(h.reversedAt()!=null?"已冲正":h.creditedAt()!=null?"已演示到账":"尚未到账，系统支付状态："+h.status()),"/portal/payments"));
            return result(name,facts.isEmpty()?"NO_DATA":"OK","当前入住宿舍的水电及最近充值记录。余额为演示到账用量，设备尚未接入，未扣减实际消耗；分摊由室友自行处理。",facts.size(),facts,"/portal/payments");
        }
        String category=text(a,"category",Set.of("WATER","ELECTRICITY")),mode=text(a,"mode",Set.of("AMOUNT","QUANTITY")),value=text(a,"value",null);Long meter=id(a,"meterId");
        if(category==null||mode==null||value==null)return result(name,"NEEDS_INPUT","请明确充值水费还是电费，以及金额或用量，例如：充20度电要多少钱，或充10元水费。",0,List.of(),"/portal/payments");
        var choices=meters.stream().filter(m->m.category().equals(category)&&m.enabled()&&m.standardAvailable()&&(meter==null||m.id()==meter)).toList();
        if(choices.size()!=1)return result(name,"NEEDS_INPUT","当前没有唯一可用的对应宿舍表计，请进入缴费大厅选择或联系财务配置。",0,List.of(),"/portal/payments");
        var m=choices.get(0);var quote=dorm.quote(user,new DormRechargeService.RechargeInput(null,m.id(),mode,value,m.unitPriceCents()));
        return result(name,"OK","以下只是当前报价，尚未创建订单或付款。请进入缴费大厅核对并创建充值订单。",1,List.of(new Fact("DORM_QUOTE:"+m.id(),m.building()+" "+m.room()+" · "+(category.equals("WATER")?"水费":"电费")+"报价","应付 ¥"+money(quote.amountCents())+" · "+units(quote.quantity())+" "+m.unit()+" · 当前单价 ¥"+money(m.unitPriceCents())+"/"+m.unit(),"/portal/payments")),"/portal/payments");
    }
    public List<Call> local(String q,Context context) {
        if(q.matches(".*(对账|差异).*(待办|待处理|处理|核查|复核).*"))return List.of(call("finance_reconciliation_pending",Map.of()));
        if(q.matches(".*(充|充值).*")) {
            String category=q.matches(".*(电|度).*")?"ELECTRICITY":q.matches(".*(水|吨).*")?"WATER":null;
            var p=Pattern.compile("(?:充值|充)\\s*(?:电费|水费)?\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*(元|块|度|吨)").matcher(q);
            if(p.find()) {var a=new HashMap<String,Object>();if(category!=null)a.put("category",category);a.put("mode",Set.of("元","块").contains(p.group(2))?"AMOUNT":"QUANTITY");a.put("value",p.group(1));return List.of(call("quote_dorm_recharge",a));}
            if(q.matches(".*(到账|记录|余额|单价|多少|水|电).*"))return List.of(call("my_dorm_utilities",Map.of()));
        }
        if(q.matches(".*(水费|电费|水价|电价|水电|水表|电表).*")&&!q.matches(".*(这笔|这条|账单|支付状态).*"))return List.of(call("my_dorm_utilities",Map.of()));
        if(q.matches(".*(第[一二三四五六七八12345678](?:笔|条)|当前账单|账单.*(?:依据|计算|状态)|这笔.*(?:费用|账单|支付|到账|退款)).*")||context!=null&&context.type().equals("PAYMENT_MINE")&&q.matches(".*(这笔|这条|当前|费用|支付|到账|退款|计算|依据|状态).*"))return List.of(call("payment_detail",Map.of()));
        if(q.matches(".*(缴费|账单|没交|未交|未缴|待缴|欠费|还.*多少钱).*"))return List.of(call("my_payments",Map.of("status",q.matches(".*(没交|未交|未缴|待缴|欠费|还.*多少钱).*")?"UNPAID":"ALL")));
        return List.of();
    }
    private Call call(String name,Map<String,?> args){return new Call(name,json.valueToTree(args));}
    public String references(Answer answer) {
        return json.writeValueAsString(answer.results().stream().filter(r->Set.of("my_payments","payment_detail").contains(r.name())).flatMap(r->r.items().stream()).map(Fact::key).filter(k->k.matches("PAYMENT:[1-9]\\d*")).map(k->Long.parseLong(k.substring(8))).limit(8).toList());
    }
    public Query followUp(CampusPrincipal user,long session,Query query) {
        if(query.context()!=null||!query.message().matches(".*(第[一二三四五六七八12345678](?:笔|条)|这笔|这条).*$"))return query;
        var previous=jdbc.sql("SELECT payment_refs_json FROM agent_turn WHERE session_id=:s AND status='SUCCESS' ORDER BY id DESC LIMIT 1").param("s",session).query((r,n)->r.getString(1)).optional();
        if(previous.isEmpty()||previous.get()==null)return query;
        var refs=json.readTree(previous.get());int index=-1;
        var ordinal=Pattern.compile("第([一二三四五六七八12345678])(?:笔|条)").matcher(query.message());
        if(ordinal.find()){String digit=ordinal.group(1);index="一二三四五六七八".indexOf(digit);if(index<0)index=Integer.parseInt(digit)-1;}
        else if(refs.size()==1)index=0;
        if(index<0||index>=refs.size())return query;
        long bill=refs.get(index).asLong();owned(user,bill);
        return new Query(query.message(),query.mode(),query.externalConsent(),new Context("PAYMENT_MINE",bill));
    }
}
