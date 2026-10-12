package cn.zzuit.campus.repair;

import cn.zzuit.campus.common.ApiException;
import java.io.*;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.http.HttpStatus;

/** Inspect actual raster content with dimensions bounded before decoding. SVG and disguised content are rejected. */
public final class RepairImages {
    private RepairImages() {}
    public static String validate(byte[] bytes) {
        if(bytes.length==0 || bytes.length>2*1024*1024)bad();
        try(ImageInputStream stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers=ImageIO.getImageReaders(stream);
            if(!readers.hasNext())bad();
            ImageReader reader=readers.next();
            try {
                reader.setInput(stream,true,true);
                String format=reader.getFormatName();
                if(!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg"))bad();
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || width>8000 || height>8000 || (long)width*height>20_000_000)bad();
                if(reader.read(0)==null)bad();
                return format.equalsIgnoreCase("png")?"image/png":"image/jpeg";
            } finally { reader.dispose(); }
        } catch(IOException | IllegalArgumentException ex) { bad();return null; }
    }
    private static void bad() { throw new ApiException(HttpStatus.BAD_REQUEST,"REPAIR_IMAGE_INVALID","图片须为可读取的 JPEG 或 PNG，单张 2 MB 以内、最多 2000 万像素"); }
}
