package cn.zzuit.campus.housing;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/** Internal writes run in the calling transfer transaction under foundation_write_lock. */
@Component
public class HousingMaterialStore {
    private final JdbcClient jdbc;
    public HousingMaterialStore(JdbcClient jdbc) {this.jdbc=jdbc;}

    public void capture(long transfer) {
        int revision=jdbc.sql("SELECT COALESCE(MAX(revision_number),0)+1 FROM housing_material_submission WHERE transfer_id=:id").param("id",transfer).query(Integer.class).single();
        var key=new GeneratedKeyHolder();
        jdbc.sql("""
            INSERT INTO housing_material_submission(transfer_id,revision_number,request_version,reason,preferred_area,material_note)
            SELECT id,:revision,version,reason,preferred_area,material_note FROM housing_transfer WHERE id=:id AND status='SUBMITTED'
            """).param("id",transfer).param("revision",revision).update(key,"id");
        jdbc.sql("INSERT INTO housing_material_snapshot(transfer_id,submission_id,material_id) SELECT transfer_id,:submission,id FROM housing_material WHERE transfer_id=:id AND removed_at IS NULL")
            .param("submission",key.getKey().longValue()).param("id",transfer).update();
    }
    public void deleteDraft(long transfer) {
        jdbc.sql("DELETE FROM housing_material WHERE transfer_id=:id AND NOT EXISTS(SELECT 1 FROM housing_material_submission WHERE transfer_id=:id)").param("id",transfer).update();
    }
}
