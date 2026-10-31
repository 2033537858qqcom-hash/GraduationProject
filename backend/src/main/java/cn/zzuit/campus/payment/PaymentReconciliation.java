package cn.zzuit.campus.payment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import cn.zzuit.campus.identity.CampusPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Uploaded statement contents are comparison input, never authority to settle money. */
@Service
public class PaymentReconciliation {
    public record Input(String csv) {}
    public record Row(int line,String orderNo,String amount,String tradeNo,String statementStatus,Long billId,Long localAmountCents,
        String localStatus,String result,String explanation) {}
    public record Preview(List<Row> items,long matchedCount,long differenceCount,long notFoundCount,long invalidCount,long duplicateCount) {}
    private record CsvRow(int line,List<String> cells) {}
    private final PaymentAccess access;
    private final PaymentRepository repository;
    public PaymentReconciliation(PaymentAccess access,PaymentRepository repository) { this.access=access;this.repository=repository; }
    public Preview compare(CampusPrincipal user,Input input) {
        access.finance(user);var records=parse(input.csv());
        if(records.size()<2) throw bad("请填写表头及至少一条交易明细");
        var header=records.getFirst().cells();
        int order=column(header,List.of("订单号","商户订单号","orderNo")),amount=column(header,List.of("金额（元）","订单金额（元）","订单金额(元)","amount","total_amount")),
            trade=column(header,List.of("交易号","支付宝交易号","tradeNo")),status=column(header,List.of("支付状态","交易状态","status","trade_status"));
        int refund=optionalColumn(header,List.of("退款状态","refundStatus"));
        var seen=new HashSet<String>();var output=new ArrayList<Row>();
        for(var record:records.subList(1,records.size())) {
            String number=cell(record,order),value=cell(record,amount),tradeNo=cell(record,trade),rawStatus=cell(record,status);
            String normalized=state(rawStatus);if(refund>=0 && List.of("REFUNDED","已退款").contains(cell(record,refund))) normalized="REFUNDED";
            String result="MATCH",explanation="金额、交易号和支付状态一致";Long billId=null,localAmount=null;String localStatus="";
            if(record.cells().size()!=header.size() || !number.matches("[A-Za-z0-9_-]{6,64}") || !value.matches("\\d{1,7}(?:\\.\\d{1,2})?") || normalized==null)
                { result="INVALID";explanation="请核对列数、订单号、普通十进制金额和支付状态"; }
            else if(!seen.add(number)) { result="DUPLICATE";explanation="该订单号在本次导入中重复，请先核对交易明细"; }
            else {
                var local=repository.jdbc.sql("SELECT id FROM payment_bill WHERE order_no=:number").param("number",number).query(Long.class).optional();
                if(local.isEmpty()) { result="NOT_FOUND";explanation="本系统未找到该商户订单号"; }
                else {
                    var bill=repository.load(local.get());billId=bill.id();localAmount=bill.amountCents();
                    localStatus=bill.refundStatus().equals("REFUNDED")?"REFUNDED":bill.status();var differences=new ArrayList<String>();
                    if(new BigDecimal(value).compareTo(BigDecimal.valueOf(bill.amountCents(),2))!=0) differences.add("金额不一致");
                    if(!normalized.equals(localStatus)) differences.add("支付状态不一致");
                    if((normalized.equals("PAID") || normalized.equals("REFUNDED")) && (tradeNo.isBlank() || !tradeNo.equals(bill.tradeNo()))) differences.add("交易号不一致或缺失");
                    if(normalized.equals("PAID") && !bill.workflowStatus().equals("PUBLISHED")) differences.add("该账单当前不在正常收费状态");
                    if(!differences.isEmpty()) { result="DIFFERENCE";explanation=String.join("；",differences); }
                }
            }
            output.add(new Row(record.line(),number,value,tradeNo,rawStatus,billId,localAmount,localStatus,result,explanation));
        }
        return new Preview(List.copyOf(output),count(output,"MATCH"),count(output,"DIFFERENCE"),count(output,"NOT_FOUND"),count(output,"INVALID"),count(output,"DUPLICATE"));
    }
    private static long count(List<Row> rows,String type) { return rows.stream().filter(row->row.result().equals(type)).count(); }
    private static String state(String input) {
        return Map.ofEntries(Map.entry("PAID","PAID"),Map.entry("TRADE_SUCCESS","PAID"),Map.entry("TRADE_FINISHED","PAID"),Map.entry("交易成功","PAID"),Map.entry("交易结束","PAID"),
            Map.entry("UNPAID","UNPAID"),Map.entry("PENDING","UNPAID"),Map.entry("WAIT_BUYER_PAY","UNPAID"),Map.entry("等待买家付款","UNPAID"),Map.entry("待支付","UNPAID"),
            Map.entry("REFUNDED","REFUNDED"),Map.entry("已退款","REFUNDED"),Map.entry("退款成功","REFUNDED"),Map.entry("CLOSED","CLOSED"),Map.entry("TRADE_CLOSED","CLOSED"),Map.entry("交易关闭","CLOSED")).get(input);
    }
    private static int column(List<String> header,List<String> names) { int index=optionalColumn(header,names);if(index<0) throw bad("表头必须包含订单号、金额（元）、交易号、支付状态");return index; }
    private static int optionalColumn(List<String> header,List<String> names) { for(int i=0;i<header.size();i++) if(names.contains(header.get(i))) return i;return -1; }
    private static String cell(CsvRow record,int index) { return index<record.cells().size()?record.cells().get(index):""; }
    private static cn.zzuit.campus.common.ApiException bad(String text) { return PaymentService.error(HttpStatus.BAD_REQUEST,"INVALID_STATEMENT",text); }
    private static List<CsvRow> parse(String csv) {
        if(csv==null || csv.getBytes(StandardCharsets.UTF_8).length>262144) throw bad("每次对账明细最多256KB、2000笔，请分批核对");
        if(csv.startsWith("\uFEFF")) csv=csv.substring(1);var rows=new ArrayList<CsvRow>();var cells=new ArrayList<String>();var value=new StringBuilder();
        boolean quoted=false,closed=false;int line=1,start=1;
        for(int i=0;i<csv.length();i++) {
            char c=csv.charAt(i);if(c=='\u0000') throw bad("明细不能包含空字符");
            if(quoted) {
                if(c=='"') { if(i+1<csv.length() && csv.charAt(i+1)=='"') { value.append('"');i++; } else { quoted=false;closed=true; } }
                else if(c=='\n' || c=='\r') { if(c=='\r' && i+1<csv.length() && csv.charAt(i+1)=='\n') i++;value.append('\n');line++; }
                else value.append(c);continue;
            }
            if(c==',') { cells.add(value.toString().strip());value.setLength(0);closed=false; }
            else if(c=='\r' || c=='\n') {
                if(c=='\r' && i+1<csv.length() && csv.charAt(i+1)=='\n') i++;
                cells.add(value.toString().strip());if(cells.stream().anyMatch(item->!item.isBlank())) rows.add(new CsvRow(start,List.copyOf(cells)));
                cells.clear();value.setLength(0);closed=false;start=++line;
                if(rows.size()>2001) throw bad("每次最多核对2000笔交易明细");
            } else if(closed) { if(!Character.isWhitespace(c)) throw bad("第"+line+"行的引号后应为分隔符"); }
            else if(c=='"') { if(!value.toString().isBlank()) throw bad("第"+line+"行引号位置无效");value.setLength(0);quoted=true; }
            else value.append(c);
        }
        if(quoted) throw bad("CSV引号未闭合");
        if(!cells.isEmpty() || !value.isEmpty()) { cells.add(value.toString().strip());if(cells.stream().anyMatch(item->!item.isBlank())) rows.add(new CsvRow(start,List.copyOf(cells))); }
        if(rows.size()>2001) throw bad("每次最多核对2000笔交易明细");return rows;
    }
}
