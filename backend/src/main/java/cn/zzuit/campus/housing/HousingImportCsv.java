package cn.zzuit.campus.housing;

import cn.zzuit.campus.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;

/** Bounded CSV parsing, including quoted commas, doubled quotes, BOM and physical source line numbers. */
public final class HousingImportCsv {
    private HousingImportCsv() {}
    public static final List<String> HEADER=List.of("学号","房间编号","床位编号","入住说明");
    public record InputRow(int line,String username,String roomCode,String bedCode,String reason,List<String> errors) {}
    public record Parsed(List<InputRow> rows,int ignored) {}
    private record Record(int line,List<String> fields) {}
    public static Parsed parse(String source) {
        if(source==null || source.getBytes(StandardCharsets.UTF_8).length>102400) throw bad("CSV 最多 100 KB");
        if(source.startsWith("\uFEFF"))source=source.substring(1);
        var records=new ArrayList<Record>();var fields=new ArrayList<String>();var cell=new StringBuilder();
        boolean quoted=false,closed=false;int line=1,start=1;
        for(int i=0;i<source.length();i++) {
            char c=source.charAt(i);
            if(c=='\u0000')throw bad("CSV 不能包含空字符");
            if(quoted) {
                if(c=='"') {if(i+1<source.length() && source.charAt(i+1)=='"'){cell.append('"');i++;}else{quoted=false;closed=true;}}
                else if(c=='\r' || c=='\n') {if(c=='\r' && i+1<source.length() && source.charAt(i+1)=='\n')i++;cell.append('\n');line++;}
                else cell.append(c);
                continue;
            }
            if(c==',') {fields.add(cell.toString().strip());cell.setLength(0);closed=false;}
            else if(c=='\r' || c=='\n') {
                if(c=='\r' && i+1<source.length() && source.charAt(i+1)=='\n')i++;
                fields.add(cell.toString().strip());records.add(new Record(start,List.copyOf(fields)));fields.clear();cell.setLength(0);closed=false;start=++line;
            } else if(closed) {if(!Character.isWhitespace(c))throw bad("第 "+line+" 行的引号后只能是分隔符");}
            else if(c=='"') {if(!cell.toString().isBlank())throw bad("第 "+line+" 行的引号位置无效");cell.setLength(0);quoted=true;}
            else cell.append(c);
        }
        if(quoted)throw bad("CSV 引号未闭合");
        if(!fields.isEmpty() || !cell.isEmpty()) {fields.add(cell.toString().strip());records.add(new Record(start,List.copyOf(fields)));}
        boolean header=false;int ignored=0;var rows=new ArrayList<InputRow>();
        for(var record:records) {
            var values=record.fields();if(values.stream().allMatch(String::isBlank)){ignored++;continue;}
            if(!header) {if(!values.equals(HEADER))throw bad("表头须依次为：学号,房间编号,床位编号,入住说明");header=true;continue;}
            var errors=new ArrayList<String>();if(values.size()!=4)errors.add("须有 4 列，请检查逗号及引号");
            String username=value(values,0),room=code(value(values,1)),bed=code(value(values,2)),reason=value(values,3);
            if(values.size()==4 && username.isBlank() && reason.isBlank()){ignored++;continue;}
            if(username.isBlank() || username.length()>64)errors.add("学号须为 1—64 个字符");
            if(!room.matches("[A-Za-z0-9_-]{1,64}"))errors.add("房间编号须使用系统配置的编号");
            if(!bed.matches("[A-Za-z0-9_-]{1,32}"))errors.add("床位编号须使用系统配置的编号");
            if(reason.isBlank() || reason.length()>200)errors.add("入住说明须为 1—200 个字符");
            rows.add(new InputRow(record.line(),username,room,bed,reason,errors));
            if(rows.size()>200)throw bad("每批最多导入 200 名学生，请分批处理");
        }
        if(!header || rows.isEmpty())throw bad("请使用模板并至少填写一名学生及入住说明");
        return new Parsed(List.copyOf(rows),ignored);
    }
    private static String value(List<String> fields,int index) {return fields.size()>index?fields.get(index):"";}
    private static String code(String value) {return value.startsWith("'-")?value.substring(1):value;}
    public static String cell(String value) {
        if(value.startsWith("-") || value.startsWith("+") || value.startsWith("=") || value.startsWith("@"))value="'"+value;
        return '"'+value.replace("\"","\"\"")+'"';
    }
    private static ApiException bad(String message) {return new ApiException(HttpStatus.BAD_REQUEST,"HOUSING_IMPORT_INVALID",message);}
}
