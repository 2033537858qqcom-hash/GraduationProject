package cn.zzuit.campus.housing;

import static cn.zzuit.campus.housing.HousingImportModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.identity.CampusPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HousingImportService {
    private final JdbcClient jdbc;
    private final HousingService housing;
    private final Clock clock;
    public HousingImportService(JdbcClient jdbc,HousingService housing,Clock clock) {this.jdbc=jdbc;this.housing=housing;this.clock=clock;}
    private LocalDateTime now() {return LocalDateTime.now(clock);}
    private void lock() {jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update();}

    @Transactional public Batch preview(CampusPrincipal user,Input input) {
        housing.requireImportScope(user,input.buildingId());
        var parsed=HousingImportCsv.parse(input.csv());
        String source=sourceName(input.sourceName());
        lock();var scope=scope(user,input.buildingId());
        var resolved=resolve(scope,parsed.rows());
        var rows=resolved.stream().map(Resolved::row).toList();
        int invalid=(int)rows.stream().filter(r->!r.errors().isEmpty()).count();
        if(invalid>0)return new Batch(null,null,"INVALID",scope.id(),scope.campus(),scope.name(),source,rows.size(),rows.size()-invalid,invalid,parsed.ignored(),false,null,null,rows);
        // Keep unfinished previews for one day after expiry. Completed receipts are retained.
        jdbc.sql("DELETE FROM housing_import_row WHERE batch_id IN (SELECT id FROM housing_import_batch WHERE status='PREVIEW' AND expires_at<:cutoff)").param("cutoff",now().minusDays(1)).update();
        jdbc.sql("DELETE FROM housing_import_batch WHERE status='PREVIEW' AND expires_at<:cutoff").param("cutoff",now().minusDays(1)).update();
        String token=UUID.randomUUID().toString();var key=new GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO housing_import_batch(token,actor_id,actor_security_version,building_id,campus_name,building_name,source_name,input_digest,row_count,ignored_rows,status,expires_at)
            VALUES(:token,:actor,:version,:building,:campus,:name,:source,:digest,:count,:ignored,'PREVIEW',:expiry)
            """).param("token",token).param("actor",user.id()).param("version",user.securityVersion()).param("building",scope.id())
            .param("campus",scope.campus()).param("name",scope.name()).param("source",source).param("digest",digest(input.csv()))
            .param("count",rows.size()).param("ignored",parsed.ignored()).param("expiry",now().plusMinutes(15)).update(key,"id");
        long id=Objects.requireNonNull(key.getKey()).longValue();
        for(var item:resolved) {
            var row=item.row();
            jdbc.sql("""
                INSERT INTO housing_import_row(batch_id,line_number,student_id,student_username,student_name,room_code,bed_code,bed_id,place_label,entry_reason,resource_fingerprint)
                VALUES(:batch,:line,:student,:username,:name,:room,:bedCode,:bed,:place,:reason,:fingerprint)
                """).param("batch",id).param("line",row.line()).param("student",item.studentId()).param("username",row.username()).param("name",row.studentName())
                .param("room",row.roomCode()).param("bedCode",row.bedCode()).param("bed",item.bedId()).param("place",row.place()).param("reason",row.reason()).param("fingerprint",item.fingerprint()).update();
        }
        return receipt(user,token);
    }

    @Transactional public Batch confirm(CampusPrincipal user,String token) {
        lock();var batch=owned(user,token);long building=number(batch,"building_id");
        var scope=scope(user,building);
        if("COMPLETED".equals(batch.get("status")))return receipt(user,token);
        if(!now().isBefore(time(batch,"expires_at")))throw conflict("预览已过期，请重新校验 CSV");
        if(number(batch,"actor_security_version")!=user.securityVersion())throw conflict("账号权限已变化，请重新校验 CSV");
        long id=number(batch,"id");var staged=staged(id);
        var inputs=staged.stream().map(r->new HousingImportCsv.InputRow(r.row().line(),r.row().username(),r.row().roomCode(),r.row().bedCode(),r.row().reason(),List.of())).toList();
        var current=resolve(scope,inputs);var changed=new ArrayList<String>();
        for(int i=0;i<staged.size();i++) {
            var before=staged.get(i);var after=current.get(i);
            if(!after.row().errors().isEmpty() || !before.fingerprint().equals(after.fingerprint()))changed.add(String.valueOf(before.row().line()));
        }
        if(!changed.isEmpty())throw conflict("第 "+String.join("、",changed)+" 行在预览后已变化，请重新校验；本批未登记入住");
        // The shared write lock and outer transaction cover every check-in, notice, audit and receipt.
        for(var item:staged) {
            String request="HI"+token.replace("-","")+"-"+item.row().line();
            var stay=housing.checkIn(user,new HousingModels.CheckIn(request,item.studentId(),item.bedId(),item.row().reason()));
            jdbc.sql("UPDATE housing_import_row SET assignment_id=:stay WHERE batch_id=:batch AND line_number=:line").param("stay",stay.id()).param("batch",id).param("line",item.row().line()).update();
        }
        jdbc.sql("UPDATE housing_import_batch SET status='COMPLETED',completed_at=:now WHERE id=:id AND status='PREVIEW'").param("now",now()).param("id",id).update();
        jdbc.sql("""
            INSERT INTO foundation_audit(actor_id,actor_name,action,entity_type,entity_id,summary,reason)
            SELECT id,full_name,'IMPORT_CHECK_IN','HOUSING_IMPORT_BATCH',:batch,:summary,'逐行校验后整批确认' FROM app_user WHERE id=:actor
            """).param("batch",id).param("summary",scope.name()+"；登记 "+staged.size()+" 人").param("actor",user.id()).update();
        return receipt(user,token);
    }

    public Batch receipt(CampusPrincipal user,String token) {
        var batch=owned(user,token);housing.requireImportScope(user,number(batch,"building_id"));
        var rows=staged(number(batch,"id")).stream().map(Resolved::row).toList();
        boolean done="COMPLETED".equals(batch.get("status"));var expiry=time(batch,"expires_at");
        boolean confirmable=!done && now().isBefore(expiry) && number(batch,"actor_security_version")==user.securityVersion();
        return new Batch(number(batch,"id"),token,done?"COMPLETED":confirmable?"PREVIEW":"EXPIRED",number(batch,"building_id"),str(batch,"campus_name"),str(batch,"building_name"),str(batch,"source_name"),rows.size(),rows.size(),0,(int)number(batch,"ignored_rows"),confirmable,expiry,done?time(batch,"completed_at"):null,rows);
    }
    public List<Summary> recent(CampusPrincipal user,long building) {
        housing.requireImportScope(user,building);
        return jdbc.sql("SELECT id,token,source_name,row_count,completed_at FROM housing_import_batch WHERE actor_id=:actor AND building_id=:building AND status='COMPLETED' ORDER BY id DESC LIMIT 10")
            .param("actor",user.id()).param("building",building).query((r,n)->new Summary(r.getLong(1),r.getString(2),r.getString(3),r.getInt(4),r.getTimestamp(5).toLocalDateTime())).list();
    }
    public byte[] template(CampusPrincipal user,long building) {
        housing.requireImportScope(user,building);
        var rows=jdbc.sql("""
            SELECT r.code room_code,d.code bed_code FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id JOIN dorm_building b ON b.id=r.building_id
            WHERE b.id=:building AND b.enabled=TRUE AND r.enabled=TRUE AND d.enabled=TRUE AND d.status='VACANT'
            ORDER BY r.code,d.code,d.id LIMIT 200
            """).param("building",building).query((r,n)->HousingImportCsv.cell("")+","+HousingImportCsv.cell(r.getString(1))+","+HousingImportCsv.cell(r.getString(2))+","+HousingImportCsv.cell("")).list();
        if(rows.isEmpty())throw conflict("当前负责楼栋没有可入住床位，请先维护床位配置");
        return ("\uFEFF"+String.join(",",HousingImportCsv.HEADER)+"\r\n"+String.join("\r\n",rows)+"\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private Scope scope(CampusPrincipal user,long building) {
        housing.requireImportScope(user,building);
        return jdbc.sql("SELECT b.id,b.version,b.name,c.name campus_name,s.version scope_version FROM dorm_building b JOIN campus c ON c.id=b.campus_id JOIN dorm_manager_scope s ON s.building_id=b.id WHERE b.id=:building AND s.user_id=:actor AND s.enabled=TRUE")
            .param("building",building).param("actor",user.id()).query((r,n)->new Scope(r.getLong(1),r.getInt(2),r.getString(3),r.getString(4),r.getInt(5))).single();
    }
    private List<Resolved> resolve(Scope scope,List<HousingImportCsv.InputRow> inputs) {
        var resolved=new ArrayList<Resolved>();
        for(var input:inputs) {
            var errors=new ArrayList<>(input.errors());
            var student=jdbc.sql("""
                SELECT u.id,u.username,u.full_name,u.version,u.security_version,EXISTS(SELECT 1 FROM housing_assignment a WHERE a.current_student_id=u.id) housed
                FROM app_user u WHERE u.username=:name AND u.enabled=TRUE AND EXISTS(SELECT 1 FROM user_role ur WHERE ur.user_id=u.id AND ur.role_code='STUDENT')
                """).param("name",input.username()).query(new ColumnMapRowMapper()).optional().orElse(null);
            var bed=jdbc.sql("""
                SELECT d.id,d.code,d.label,d.status,d.enabled,d.version,r.code room_code,r.name room_name,r.floor_label,r.enabled room_enabled,r.version room_version
                FROM dorm_bed d JOIN dorm_room r ON r.id=d.room_id WHERE r.building_id=:building AND r.code=:room AND d.code=:bed
                """).param("building",scope.id()).param("room",input.roomCode()).param("bed",input.bedCode()).query(new ColumnMapRowMapper()).optional().orElse(null);
            if(student==null)errors.add("未找到可办理的在校学生");
            else if(bool(student,"housed"))errors.add("学生已有在住记录");
            if(bed==null)errors.add("当前楼栋未配置此房间及床位");
            else if(!bool(bed,"enabled") || !bool(bed,"room_enabled"))errors.add("房间或床位已停用");
            else if(!"VACANT".equals(bed.get("status")))errors.add("床位当前不可入住");
            String name=student==null?"":str(student,"full_name");
            String place=bed==null?"":scope.campus()+" "+scope.name()+" "+str(bed,"room_name")+" "+str(bed,"floor_label")+" "+str(bed,"label");
            String fingerprint=student==null || bed==null?"":digest(canonical(scope.id(),scope.version(),scope.name(),scope.campus(),scope.scopeVersion(),student.get("id"),student.get("version"),student.get("security_version"),student.get("username"),name,bed.get("id"),bed.get("version"),bed.get("room_version"),place));
            resolved.add(new Resolved(new Row(input.line(),student==null?input.username():str(student,"username"),name,bed==null?input.roomCode():str(bed,"room_code"),bed==null?input.bedCode():str(bed,"code"),place,input.reason(),errors,null),student==null?null:number(student,"id"),bed==null?null:number(bed,"id"),fingerprint));
        }
        var studentCounts=new HashMap<String,Integer>();var bedCounts=new HashMap<String,Integer>();
        for(var item:resolved) {
            studentCounts.merge(studentKey(item),1,Integer::sum);bedCounts.merge(bedKey(item),1,Integer::sum);
        }
        for(var item:resolved) {
            if(studentCounts.get(studentKey(item))>1)item.row().errors().add("同一学生在本批重复出现");
            if(bedCounts.get(bedKey(item))>1)item.row().errors().add("同一床位在本批重复出现");
        }
        return resolved;
    }
    private String studentKey(Resolved item) {return item.studentId()!=null?"id:"+item.studentId():"name:"+item.row().username();}
    private String bedKey(Resolved item) {return item.bedId()!=null?"id:"+item.bedId():"code:"+canonical(item.row().roomCode(),item.row().bedCode());}
    private List<Resolved> staged(long id) {
        return jdbc.sql("SELECT * FROM housing_import_row WHERE batch_id=:id ORDER BY line_number").param("id",id).query((r,n)->new Resolved(new Row(r.getInt("line_number"),r.getString("student_username"),r.getString("student_name"),r.getString("room_code"),r.getString("bed_code"),r.getString("place_label"),r.getString("entry_reason"),List.of(),r.getObject("assignment_id",Long.class)),r.getLong("student_id"),r.getLong("bed_id"),r.getString("resource_fingerprint"))).list();
    }
    private Map<String,Object> owned(CampusPrincipal user,String token) {
        // Check current role/permission before revealing whether a token exists.
        var responsibilities=housing.responsibilities(user);
        if(responsibilities.scopes().stream().noneMatch(HousingModels.Scope::effective))throw missing();
        return jdbc.sql("SELECT * FROM housing_import_batch WHERE token=:token AND actor_id=:actor").param("token",token).param("actor",user.id()).query(new ColumnMapRowMapper()).optional().orElseThrow(this::missing);
    }
    private String sourceName(String value) {
        String name=value.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1).strip();
        if(name.isBlank() || name.length()>160 || name.codePoints().anyMatch(Character::isISOControl))throw new ApiException(HttpStatus.BAD_REQUEST,"HOUSING_IMPORT_INVALID","请使用有效的 CSV 文件名");
        return name;
    }
    private static String canonical(Object... values) {var out=new StringBuilder();for(var value:values){String text=String.valueOf(value);out.append(text.length()).append(':').append(text);}return out.toString();}
    private static String digest(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private static String str(Map<String,Object> row,String key) {return String.valueOf(row.get(key));}
    private static boolean bool(Map<String,Object> row,String key) {var value=row.get(key);return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue()!=0;}
    private static LocalDateTime time(Map<String,Object> row,String key) {var value=row.get(key);return value instanceof LocalDateTime date?date:((java.sql.Timestamp)value).toLocalDateTime();}
    private ApiException conflict(String message) {return new ApiException(HttpStatus.CONFLICT,"HOUSING_IMPORT_STALE",message);}
    private ApiException missing() {return new ApiException(HttpStatus.NOT_FOUND,"HOUSING_IMPORT_NOT_FOUND","导入批次不存在或当前不可访问");}
    private record Scope(long id,int version,String name,String campus,int scopeVersion) {}
    private record Resolved(Row row,Long studentId,Long bedId,String fingerprint) {}
}
