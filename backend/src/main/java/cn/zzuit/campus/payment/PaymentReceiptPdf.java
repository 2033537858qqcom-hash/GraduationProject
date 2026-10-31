package cn.zzuit.campus.payment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class PaymentReceiptPdf {
    private final byte[] font;
    public PaymentReceiptPdf() throws IOException { try(var stream=new ClassPathResource("fonts/LXGWWenKai-Regular.ttf").getInputStream()) { font=stream.readAllBytes(); } }
    public byte[] generate(PaymentService.Bill bill) throws IOException {
        try(var document=new PDDocument();var output=new ByteArrayOutputStream()) {
            document.getDocumentInformation().setTitle("校园缴费凭证（沙箱演示）");
            var page=new PDPage(PDRectangle.A4);document.addPage(page);var face=PDType0Font.load(document,new ByteArrayInputStream(font),true);
            try(var canvas=new PDPageContentStream(document,page)) {
                canvas.beginText();canvas.setFont(face,18);canvas.newLineAtOffset(50,790);canvas.showText("校园缴费凭证（沙箱演示）");
                canvas.setFont(face,11);
                for(String text:List.of("订单号："+bill.orderNo(),"学生："+bill.studentName()+"（"+bill.studentUsername()+"）", "收费分类："+bill.categoryName(),
                    "费用名称："+bill.title(),"收费账期："+bill.billingPeriod(),"支付金额：人民币 "+BigDecimal.valueOf(bill.amountCents(),2).toPlainString()+" 元", "交易号："+bill.tradeNo(),
                    "确认时间："+bill.paidAt(),"退款状态："+(bill.refundStatus().equals("REFUNDED")?"已全额退款，本凭证仅保留历史记录":"以系统最新状态为准"),
                    "本凭证仅用于毕业设计及支付宝沙箱演示，不是学校正式收费票据。")) {
                    String safe=text.replace('\n',' ').replace('\r',' ');
                    for(int offset=0;offset<safe.length();offset+=38) {
                        canvas.newLineAtOffset(0,-28);String line=safe.substring(offset,Math.min(offset+38,safe.length()));
                        var rendered=new StringBuilder();for(char character:line.toCharArray()) { try { face.encode(String.valueOf(character));rendered.append(character); } catch(IllegalArgumentException unsupported) { rendered.append('?'); } }
                        canvas.showText(rendered.toString());
                    }
                }canvas.endText();
            }document.save(output);return output.toByteArray();
        }
    }
}
