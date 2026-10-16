package cn.zzuit.campus.housing;

import cn.zzuit.campus.common.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.springframework.http.HttpStatus;

public final class HousingMaterialContent {
    private HousingMaterialContent() {}
    public static String validate(byte[] bytes) {
        if(bytes.length==0 || bytes.length>2*1024*1024) throw invalid();
        if(bytes.length>=5 && bytes[0]=='%' && bytes[1]=='P' && bytes[2]=='D' && bytes[3]=='F' && bytes[4]=='-') {
            try(var document=Loader.loadPDF(bytes)) {
                if(document.isEncrypted() || document.getNumberOfPages()<1 || document.getNumberOfPages()>20) throw invalid();
                return "application/pdf";
            } catch(IOException | IllegalArgumentException ex) {throw invalid();}
        }
        try(var stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(stream);if(!readers.hasNext()) throw invalid();
            var reader=readers.next();
            try {
                reader.setInput(stream,true,true);String format=reader.getFormatName();
                if(!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg")) throw invalid();
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || width>8000 || height>8000 || (long)width*height>20_000_000 || reader.read(0)==null) throw invalid();
                return format.equalsIgnoreCase("png")?"image/png":"image/jpeg";
            } finally {reader.dispose();}
        } catch(IOException | IllegalArgumentException ex) {throw invalid();}
    }
    public static String name(String original,String type) {
        String value=original==null?"调宿材料":original.replaceAll("[\\\\/\\p{Cntrl}]","_").trim();
        // The stored name always ends in the format detected from content.
        int dot=value.lastIndexOf('.');if(dot>0)value=value.substring(0,dot);
        if(value.isBlank())value="调宿材料";
        String extension=type.equals("application/pdf")?".pdf":type.equals("image/png")?".png":".jpg";
        return value.substring(0,Math.min(150,value.length()))+extension;
    }
    private static ApiException invalid() {return new ApiException(HttpStatus.BAD_REQUEST,"HOUSING_MATERIAL_INVALID","材料须为可读取的 PDF、JPEG 或 PNG，单份不超过 2 MB；PDF 最多 20 页且不能加密，图片最多 2000 万像素");}
}
