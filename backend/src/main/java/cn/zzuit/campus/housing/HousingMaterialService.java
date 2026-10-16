package cn.zzuit.campus.housing;

import static cn.zzuit.campus.housing.HousingMaterialModels.*;
import cn.zzuit.campus.common.ApiException;
import cn.zzuit.campus.housing.HousingTransferModels.ActionInput;
import cn.zzuit.campus.identity.CampusPrincipal;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class HousingMaterialService {
    private final JdbcClient jdbc;
    private final HousingTransferService transfers;
    private static final String FILES="SELECT f.id,f.file_name,f.file_type,f.file_size,f.created_at FROM housing_material f ";
    public HousingMaterialService(JdbcClient jdbc,HousingTransferService transfers) {this.jdbc=jdbc;this.transfers=transfers;}

    @Transactional(readOnly=true) public Bundle list(CampusPrincipal user,String view,long id,int page,int size) {
        var access=transfers.materialAccess(user,view,id);
        if(page<1 || page>1000000 || size<1 || size>20) throw invalid("材料版本每页最多 20 条");
        boolean mine="mine".equals(view);
        List<Material> current=mine?jdbc.sql(FILES+"WHERE f.transfer_id=:id AND f.removed_at IS NULL ORDER BY f.id").param("id",id).query(this::file).list():List.of();
        long total=jdbc.sql("SELECT COUNT(*) FROM housing_material_submission WHERE transfer_id=:id").param("id",id).query(Long.class).single();
        var revisions=jdbc.sql("SELECT * FROM housing_material_submission WHERE transfer_id=:id ORDER BY revision_number DESC LIMIT :size OFFSET :offset")
            .param("id",id).param("size",size).param("offset",((long)page-1)*size).query((r,n)->new Submission(r.getLong("id"),r.getInt("revision_number"),r.getInt("request_version"),r.getString("reason"),r.getString("preferred_area"),r.getString("material_note"),r.getTimestamp("created_at").toLocalDateTime(),
                jdbc.sql(FILES+"JOIN housing_material_snapshot s ON s.material_id=f.id AND s.transfer_id=f.transfer_id WHERE s.transfer_id=:transfer AND s.submission_id=:submission ORDER BY f.id")
                    .param("transfer",id).param("submission",r.getLong("id")).query(this::file).list())).list();
        return new Bundle(access.version(),access.status(),mine && editable(access),current,revisions,total,page,size);
    }
    @Transactional(rollbackFor=IOException.class) public Bundle upload(CampusPrincipal user,long id,Upload input,MultipartFile upload) throws IOException {
        lock();var access=transfers.materialAccess(user,"mine",id);
        if(upload.isEmpty() || upload.getSize()>2*1024*1024) throw invalid("单份材料须为 1 字节至 2 MB");
        byte[] bytes=upload.getBytes();String type=HousingMaterialContent.validate(bytes),name=HousingMaterialContent.name(upload.getOriginalFilename(),type);
        String fingerprint=id+"|"+input.version()+"|"+name+"|"+sha(bytes);
        if(repeated(user,id,input.requestKey(),"MATERIAL_UPLOAD",fingerprint)) return list(user,"mine",id,1,10);
        editableVersion(access,input.version());
        long count=jdbc.sql("SELECT COUNT(*) FROM housing_material WHERE transfer_id=:id AND removed_at IS NULL").param("id",id).query(Long.class).single();
        if(count>=3) throw conflict("当前最多保留 3 份材料，请先移除需要替换的材料");
        var quota=jdbc.sql("SELECT COUNT(*) files,COALESCE(SUM(file_size),0) bytes FROM housing_material WHERE transfer_id=:id").param("id",id).query((r,n)->new long[]{r.getLong(1),r.getLong(2)}).single();
        if(quota[0]>=60 || quota[1]+bytes.length>40L*1024*1024) throw conflict("本申请留存材料已达上限，请联系学院核对办理方式");
        jdbc.sql("INSERT INTO housing_material(transfer_id,file_name,file_type,file_size,file_sha256,file_bytes,uploaded_by) VALUES(:id,:name,:type,:size,:sha,:bytes,:user)")
            .param("id",id).param("name",name).param("type",type).param("size",bytes.length).param("sha",sha(bytes)).param("bytes",bytes).param("user",user.id()).update();
        advance(id,input.version());record(user,id,"MATERIAL_UPLOAD",input.requestKey(),fingerprint);
        return list(user,"mine",id,1,10);
    }
    @Transactional public Bundle remove(CampusPrincipal user,long id,long file,ActionInput input) {
        lock();var access=transfers.materialAccess(user,"mine",id);String fingerprint=id+"|"+file+"|"+input.version();
        if(repeated(user,id,input.requestKey(),"MATERIAL_REMOVE",fingerprint)) return list(user,"mine",id,1,10);
        editableVersion(access,input.version());
        if(jdbc.sql("UPDATE housing_material SET removed_at=CURRENT_TIMESTAMP WHERE id=:file AND transfer_id=:id AND removed_at IS NULL").param("file",file).param("id",id).update()!=1) throw missing();
        advance(id,input.version());record(user,id,"MATERIAL_REMOVE",input.requestKey(),fingerprint);
        return list(user,"mine",id,1,10);
    }
    @Transactional(readOnly=true) public Download download(CampusPrincipal user,String view,long id,long file,Long submission) {
        transfers.materialAccess(user,view,id);
        if(submission!=null && submission<=0) throw missing();
        String allowed;
        if(submission!=null) allowed="EXISTS(SELECT 1 FROM housing_material_snapshot s WHERE s.transfer_id=f.transfer_id AND s.material_id=f.id AND s.submission_id=:submission)";
        else if("mine".equals(view)) allowed="(f.removed_at IS NULL OR EXISTS(SELECT 1 FROM housing_material_snapshot s WHERE s.transfer_id=f.transfer_id AND s.material_id=f.id))";
        else allowed="EXISTS(SELECT 1 FROM housing_material_snapshot s WHERE s.transfer_id=f.transfer_id AND s.material_id=f.id AND s.submission_id=(SELECT MAX(id) FROM housing_material_submission WHERE transfer_id=f.transfer_id))";
        var query=jdbc.sql("SELECT f.file_name,f.file_type,f.file_bytes FROM housing_material f WHERE f.transfer_id=:id AND f.id=:file AND "+allowed).param("id",id).param("file",file);
        if(submission!=null)query=query.param("submission",submission);
        return query.query((r,n)->new Download(r.getString(1),r.getString(2),r.getBytes(3))).optional().orElseThrow(this::missing);
    }
    private Material file(java.sql.ResultSet r,int index)throws java.sql.SQLException {return new Material(r.getLong("id"),r.getString("file_name"),r.getString("file_type"),r.getInt("file_size"),r.getTimestamp("created_at").toLocalDateTime());}
    private boolean editable(Access access) {return List.of("DRAFT","RETURNED").contains(access.status());}
    private void editableVersion(Access access,int expected) {if(!editable(access))throw conflict("仅草稿和待补交阶段可以增删材料");if(access.version()!=expected)throw conflict("申请或材料已变化，请刷新后核对");}
    private void advance(long id,int version) {if(jdbc.sql("UPDATE housing_transfer SET version=version+1,updated_at=CURRENT_TIMESTAMP WHERE id=:id AND version=:version AND status IN ('DRAFT','RETURNED')").param("id",id).param("version",version).update()!=1)throw conflict("申请或材料已变化，请刷新后核对");}
    private boolean repeated(CampusPrincipal user,long id,String key,String action,String fingerprint) {
        var prior=jdbc.sql("SELECT transfer_id,action,input_digest FROM housing_transfer_action WHERE actor_id=:user AND request_key=:key").param("user",user.id()).param("key",key).query((r,n)->new Object[]{r.getLong(1),r.getString(2),r.getString(3)}).optional();
        if(prior.isEmpty())return false;
        if(!prior.get()[0].equals(id) || !prior.get()[1].equals(action) || !prior.get()[2].equals(fingerprint))throw conflict("相同操作标识对应不同内容");
        return true;
    }
    private void record(CampusPrincipal user,long id,String action,String key,String fingerprint) {jdbc.sql("INSERT INTO housing_transfer_action(transfer_id,actor_id,action,request_key,input_digest) VALUES(:id,:user,:action,:key,:digest)").param("id",id).param("user",user.id()).param("action",action).param("key",key).param("digest",fingerprint).update();}
    private String sha(byte[] bytes) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}}
    private void lock() {jdbc.sql("UPDATE foundation_write_lock SET revision=revision+1 WHERE id=1").update();}
    private ApiException invalid(String message) {return new ApiException(HttpStatus.BAD_REQUEST,"HOUSING_MATERIAL_INVALID",message);}
    private ApiException conflict(String message) {return new ApiException(HttpStatus.CONFLICT,"HOUSING_MATERIAL_CONFLICT",message);}
    private ApiException missing() {return new ApiException(HttpStatus.NOT_FOUND,"HOUSING_MATERIAL_NOT_FOUND","调宿材料不存在");}
}
