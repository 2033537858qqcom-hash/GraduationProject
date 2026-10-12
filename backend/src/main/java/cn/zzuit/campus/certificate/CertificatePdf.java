package cn.zzuit.campus.certificate;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.util.Matrix;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class CertificatePdf {
    private final byte[] fontBytes;
    public CertificatePdf() throws IOException {
        try(var input=new ClassPathResource("fonts/LXGWWenKai-Regular.ttf").getInputStream()) { fontBytes=input.readAllBytes(); }
    }
    public byte[] generate(CertificateModels.Request request,String number,LocalDateTime issuedAt,String reviewer) throws IOException {
        try(var document=new PDDocument(); var output=new ByteArrayOutputStream()) {
            document.getDocumentInformation().setTitle("在校信息证明（毕业设计演示）");
            document.getDocumentInformation().setAuthor("校园综合服务平台 · 毕业设计演示");
            var font=PDType0Font.load(document,new ByteArrayInputStream(fontBytes),true);
            try(var writer=new Writer(document,font,number)) {
                writer.paragraph("在校信息证明（演示）",24,34);
                writer.paragraph("以下内容为平台登记信息的演示快照，非学校正式证明。",13,24);
                writer.gap(14);
                var identity=request.identity();
                writer.paragraph("姓名："+identity.studentName(),15,25);
                writer.paragraph("学号："+identity.identityNumber(),15,25);
                writer.paragraph("学院："+identity.departmentName(),15,25);
                writer.paragraph("专业："+identity.majorName(),15,25);
                writer.paragraph("行政班："+identity.className(),15,25);
                writer.paragraph("校区："+identity.campusName(),15,25);
                writer.gap(14);
                writer.paragraph("申请用途："+request.purpose(),15,25);
                writer.gap(14);
                writer.paragraph("平台登记状态：当前账号具备学生身份（演示）。",14,24);
                writer.paragraph("生成时间："+issuedAt.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm")),14,24);
                writer.paragraph("办理人员："+reviewer+"（演示岗位）",14,24);
                writer.gap(20);
                writer.paragraph("本文件仅用于本科毕业设计功能展示，不具备学校签章或正式证明效力。资料为生成时的快照，后续账号调整不会改写本文件。",12,22);
            }
            document.save(output); return output.toByteArray();
        }
    }
    private static final class Writer implements AutoCloseable {
        private final PDDocument document;
        private final PDType0Font font;
        private final String number;
        private PDPageContentStream stream;
        private float y;
        private int page;
        private static final Color BLUE=new Color(36,70,105), INK=new Color(42,49,57), GRAY=new Color(112,120,128);
        Writer(PDDocument document,PDType0Font font,String number) throws IOException { this.document=document; this.font=font; this.number=number; next(); }
        private void text(String text,float size,float x,float baseline,Color color) throws IOException {
            stream.beginText(); stream.setFont(font,size); stream.setNonStrokingColor(color);
            stream.newLineAtOffset(x,baseline); stream.showText(text); stream.endText();
        }
        private void next() throws IOException {
            if(stream!=null) stream.close();
            var sheet=new PDPage(PDRectangle.A4); document.addPage(sheet); page++;
            stream=new PDPageContentStream(document,sheet);
            stream.saveGraphicsState(); stream.beginText(); stream.setFont(font,32);
            stream.setNonStrokingColor(new Color(238,240,243));
            stream.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(30),115,370));
            stream.showText("毕业设计演示 · 非正式证明"); stream.endText(); stream.restoreGraphicsState();
            text("郑州工业应用技术学院",20,58,778,BLUE);
            text("校园综合服务平台 / 毕业设计演示",11,58,751,GRAY);
            stream.setStrokingColor(BLUE); stream.setLineWidth(1); stream.moveTo(58,732); stream.lineTo(537,732); stream.stroke();
            text(number,11,58,708,GRAY);
            text("毕业设计演示 · 非学校正式证明",10,58,44,GRAY);
            text("第 "+page+" 页",10,493,44,GRAY);
            y=672;
        }
        void gap(float height) { y-=height; }
        private String safe(String text) throws IOException {
            var result=new StringBuilder();
            for(int code:text.codePoints().toArray()) {
                if(code=='\n' || code=='\r' || Character.isISOControl(code)) { result.append(' '); continue; }
                String value=new String(Character.toChars(code));
                try { font.encode(value); result.append(value); }
                catch(IllegalArgumentException ex) { result.append("[U+").append(Integer.toHexString(code).toUpperCase()).append(']'); }
            }
            return result.toString();
        }
        void paragraph(String value,float size,float lineHeight) throws IOException {
            String normalized=safe(value); var line=new StringBuilder();
            for(int code:normalized.codePoints().toArray()) {
                String character=new String(Character.toChars(code));
                if(line.length()>0 && font.getStringWidth(line.toString()+character)*size/1000>479) {
                    line(line.toString(),size,lineHeight); line.setLength(0);
                }
                line.append(character);
            }
            if(line.length()>0) line(line.toString(),size,lineHeight);
        }
        private void line(String value,float size,float height) throws IOException {
            if(y<80+height) next();
            text(value,size,58,y,size>=20?BLUE:INK); y-=height;
        }
        @Override public void close() throws IOException { if(stream!=null) stream.close(); }
    }
}
