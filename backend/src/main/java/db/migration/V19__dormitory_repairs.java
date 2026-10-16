package db.migration;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.zip.CRC32;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Connect explicitly configured private repair locations to housing; never seed invented rooms. */
public class V19__dormitory_repairs extends BaseJavaMigration {
    private static final List<String> DDL=List.of(
        "ALTER TABLE repair_location ADD COLUMN dorm_room_id BIGINT UNIQUE",
        "ALTER TABLE repair_location ADD CONSTRAINT fk_repair_dorm_room FOREIGN KEY(dorm_room_id) REFERENCES dorm_room(id)",
        "ALTER TABLE repair_location ADD CONSTRAINT ck_repair_location_kind CHECK(kind IN ('CLASSROOM','BUILDING_COMMON','CAMPUS_PUBLIC','DORM_ROOM'))",
        "ALTER TABLE repair_location ADD CONSTRAINT ck_repair_dorm_source CHECK((kind='DORM_ROOM' AND dorm_room_id IS NOT NULL AND classroom_resource_id IS NULL AND building_id IS NULL) OR (kind<>'DORM_ROOM' AND dorm_room_id IS NULL))",
        "ALTER TABLE repair_request ADD COLUMN housing_assignment_id BIGINT",
        "ALTER TABLE repair_request ADD CONSTRAINT fk_repair_housing_assignment FOREIGN KEY(housing_assignment_id) REFERENCES housing_assignment(id)",
        """
        UPDATE agent_guide SET body=REPLACE(body,
          '2. 地点来自已配置的教室、教学楼公共部位或校园公共区域。私人宿舍房间暂不开放。地点停用或来源不完整时不能新选。',
          '2. 地点来自已配置的教室、教学楼公共部位、校园公共区域，或已配置且本人当前入住的宿舍房间。宿舍地点以当前入住关系校验；调宿确认入住或退宿后，旧工单保留历史并停止维修办理，可撤回或申请取消。地点停用或来源不完整时不能新选。'),
          version=version+1,updated_at=CURRENT_TIMESTAMP
        WHERE slug='repair' AND body LIKE '%2. 地点来自已配置的教室、教学楼公共部位或校园公共区域。私人宿舍房间暂不开放。地点停用或来源不完整时不能新选。%'
        """
    );
    @Override public Integer getChecksum() {
        CRC32 checksum=new CRC32();checksum.update(("discover-location-kind-v1\n"+String.join("\n",DDL)).getBytes(StandardCharsets.UTF_8));return (int)checksum.getValue();
    }
    @Override public void migrate(Context context) throws Exception {
        Connection connection=context.getConnection();
        String schema=connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL")?connection.getCatalog():connection.getSchema();
        List<String> matches=new ArrayList<>();
        try(PreparedStatement statement=connection.prepareStatement("""
            SELECT tc.constraint_name,cc.check_clause FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc ON cc.constraint_schema=tc.constraint_schema AND cc.constraint_name=tc.constraint_name
            WHERE tc.table_schema=? AND tc.table_name='repair_location' AND tc.constraint_type='CHECK'
            """)) {
            statement.setString(1,schema);
            try(ResultSet rows=statement.executeQuery()) {
                while(rows.next()) {
                    String clause=rows.getString(2).toUpperCase(Locale.ROOT).replace("\\", "").replace("`", "").replace("_UTF8MB4", "");
                    if(clause.contains("'CLASSROOM'") && clause.contains("'BUILDING_COMMON'") && clause.contains("'CAMPUS_PUBLIC'")
                        && clause.matches("(?s).*\\bKIND\\b.*\\bIN\\b.*"))matches.add(rows.getString(1));
                }
            }
        }
        if(matches.size()!=1 || !matches.getFirst().matches("[A-Za-z0-9_]{1,64}"))throw new SQLException("Expected one original repair location kind constraint");
        String quote=connection.getMetaData().getIdentifierQuoteString().trim();
        try(Statement statement=connection.createStatement()) {
            statement.execute("ALTER TABLE repair_location DROP CONSTRAINT "+quote+matches.getFirst()+quote);
            for(String ddl:DDL)statement.execute(ddl);
        }
    }
}
