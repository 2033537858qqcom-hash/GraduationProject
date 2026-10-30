package cn.zzuit.campus.bootstrap;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(60)
@ConditionalOnProperty(name="app.demo.enabled",havingValue="true")
public class HousingDemoInitializer implements ApplicationRunner {
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final Environment environment;

    public HousingDemoInitializer(JdbcClient jdbc,PasswordEncoder passwords,Environment environment) {
        this.jdbc=jdbc;
        this.passwords=passwords;
        this.environment=environment;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        // 1. Ensure dorm manager user (dorm1001)
        if(jdbc.sql("SELECT COUNT(*) FROM app_user WHERE username='dorm1001'").query(Long.class).single()==0) {
            if(jdbc.sql("SELECT COUNT(*) FROM department WHERE name='学生宿舍管理部门（演示）'").query(Long.class).single()==0) {
                jdbc.sql("INSERT INTO department(name,kind) VALUES('学生宿舍管理部门（演示）','ADMIN')").update();
            }
            long department=jdbc.sql("SELECT id FROM department WHERE name='学生宿舍管理部门（演示）'").query(Long.class).single();
            jdbc.sql("INSERT INTO app_user(username,password_hash,full_name,identity_number,campus_id,department_id,demo) VALUES('dorm1001',:p,'宿管人员（演示）','DEMO-DORM-1001',1,:d,TRUE)")
                .param("p",passwords.encode(environment.getProperty("app.demo.password","Demo@2026"))).param("d",department).update();
            long user=jdbc.sql("SELECT id FROM app_user WHERE username='dorm1001'").query(Long.class).single();
            jdbc.sql("INSERT INTO user_role(user_id,role_code) VALUES(:u,'DORM_MANAGER')").param("u",user).update();
        }
        long dormManagerId=jdbc.sql("SELECT id FROM app_user WHERE username='dorm1001'").query(Long.class).single();

        // 2. Ensure Dorm Buildings (静逸楼 & 风华楼)
        long jingyiId = getOrCreateBuilding(1, "BLD-EAST-JINGYI", "东校区静逸楼学生公寓");
        long fenghuaId = getOrCreateBuilding(1, "BLD-EAST-FENGHUA", "东校区风华楼学生公寓");

        // 3. Ensure Rooms
        long room302Id = getOrCreateRoom(jingyiId, "JINGYI-302", "302宿舍", "3层");
        long room116Id = getOrCreateRoom(fenghuaId, "FENGHUA-116", "116宿舍", "1层");

        // 4. Ensure Beds (01 - 04)
        long[] beds302 = ensureBeds(room302Id);
        long[] beds116 = ensureBeds(room116Id);

        // 5. Ensure Manager Scope for dorm1001
        ensureScope(dormManagerId, jingyiId);
        ensureScope(dormManagerId, fenghuaId);

        // 6. Ensure Dorm Repair Location for Room 302
        if(jdbc.sql("SELECT COUNT(*) FROM repair_location WHERE dorm_room_id=:room").param("room",room302Id).query(Long.class).single()==0) {
            jdbc.sql("""
                INSERT INTO repair_location(campus_id,code,name,kind,dorm_room_id,detail_hint,provenance,enabled,version)
                VALUES(1,'EAST-JINGYI-302','静逸楼学生公寓 302宿舍','DORM_ROOM',:room,'请准确描述宿舍内故障设施（如上铺护栏松动、下水道堵塞、照明开关失灵等）','CONFIGURED',TRUE,0)
                """).param("room",room302Id).update();
        }

        // 7. Seed Student 20260001 (李佳豪 / 陈同学) Stays & Transfer
        var studentOpt = jdbc.sql("SELECT id, username, full_name, department_id FROM app_user WHERE username='20260001'")
            .query((r,n)->new DemoStudent(r.getLong("id"),r.getString("username"),r.getString("full_name"),r.getLong("department_id"))).optional();
        if(studentOpt.isEmpty()) return;
        var student = studentOpt.get();

        if(jdbc.sql("SELECT COUNT(*) FROM housing_assignment WHERE current_student_id=:id").param("id",student.id()).query(Long.class).single()==0) {
            LocalDateTime freshmanStart = LocalDateTime.now().minusMonths(14);
            LocalDateTime freshmanEnd = LocalDateTime.now().minusMonths(2);
            LocalDateTime sophomoreStart = LocalDateTime.now().minusMonths(2);

            // 7.1 Historical ended stay (大一风华楼 116-03)
            var keyHist = new GeneratedKeyHolder();
            jdbc.sql("""
                INSERT INTO housing_assignment(student_id,student_username,student_name,bed_id,status,request_key,checkout_key,campus_name,building_name,room_name,floor_label,bed_label,assigned_by,ended_by,end_reason,version,started_at,ended_at)
                VALUES(:s,:u,:n,:b,'ENDED','DEMO-CHK-IN-HIST-20260001','DEMO-CHK-OUT-HIST-20260001','东校区','东校区风华楼学生公寓','116宿舍','1层','03号床',:dormManager,:dormManager,'调宿完成',1,:start,:end)
                """)
                .param("s",student.id()).param("u",student.username()).param("n",student.fullName()).param("b",beds116[2])
                .param("dormManager",dormManagerId).param("start",Timestamp.valueOf(freshmanStart)).param("end",Timestamp.valueOf(freshmanEnd)).update(keyHist,"id");
            long histAssignmentId = keyHist.getKey().longValue();

            // 7.2 Current active stay (大二静逸楼 302-01)
            jdbc.sql("""
                INSERT INTO housing_assignment(student_id,student_username,student_name,bed_id,status,request_key,current_student_id,current_bed_id,campus_name,building_name,room_name,floor_label,bed_label,assigned_by,version,started_at)
                VALUES(:s,:u,:n,:b,'CURRENT','DEMO-CHK-IN-CURR-20260001',:s,:b,'东校区','东校区静逸楼学生公寓','302宿舍','3层','01号床',:dormManager,0,:start)
                """)
                .param("s",student.id()).param("u",student.username()).param("n",student.fullName()).param("b",beds302[0])
                .param("dormManager",dormManagerId).param("start",Timestamp.valueOf(sophomoreStart)).update();
            jdbc.sql("UPDATE dorm_bed SET status='OCCUPIED',version=version+1 WHERE id=:b").param("b",beds302[0]).update();

            // 7.3 Roommate 20260002 into 302-02
            var roommateOpt = jdbc.sql("SELECT id, username, full_name FROM app_user WHERE username='20260002'").query((r,n)->new DemoStudent(r.getLong("id"),r.getString("username"),r.getString("full_name"),1L)).optional();
            if(roommateOpt.isPresent() && jdbc.sql("SELECT COUNT(*) FROM housing_assignment WHERE current_student_id=:id").param("id",roommateOpt.get().id()).query(Long.class).single()==0) {
                var roommate = roommateOpt.get();
                jdbc.sql("""
                    INSERT INTO housing_assignment(student_id,student_username,student_name,bed_id,status,request_key,current_student_id,current_bed_id,campus_name,building_name,room_name,floor_label,bed_label,assigned_by,version,started_at)
                    VALUES(:s,:u,:n,:b,'CURRENT','DEMO-CHK-IN-CURR-20260002',:s,:b,'东校区','东校区静逸楼学生公寓','302宿舍','3层','02号床',:dormManager,0,:start)
                    """)
                    .param("s",roommate.id()).param("u",roommate.username()).param("n",roommate.fullName()).param("b",beds302[1])
                    .param("dormManager",dormManagerId).param("start",Timestamp.valueOf(sophomoreStart)).update();
                jdbc.sql("UPDATE dorm_bed SET status='OCCUPIED',version=version+1 WHERE id=:b").param("b",beds302[1]).update();
            }

            // 7.4 Completed transfer record
            if(jdbc.sql("SELECT COUNT(*) FROM housing_transfer WHERE student_id=:id").param("id",student.id()).query(Long.class).single()==0) {
                jdbc.sql("""
                    INSERT INTO housing_transfer(
                        student_id,student_username,student_name,department_id,
                        source_assignment_id,source_version,source_bed_id,source_place,
                        request_key,status,reason,preferred_area,material_note,review_comment,
                        target_bed_id,target_building_id,target_place,reserve_until,reserved_bed_id,current_student_id,
                        cancel_requested,version,created_at,updated_at
                    ) VALUES (
                        :s,:u,:n,:dept,
                        :srcAssign,0,:srcBed,'东校区 东校区风华楼学生公寓 116宿舍 1层 03号床',
                        'DEMO-TRF-20260001','COMPLETED','因跨专业大类培养转入软件工程专业，从风华楼调宿至静逸楼学生公寓','东校区静逸楼学生公寓','已在学院教务办完成转专业备案，经辅导员和教务老师签字确认','情况属实，符合学院公寓大类流转规定，初审通过，请宿管老师协调安排静逸楼对应床位。',
                        :targetBed,:targetBld,'东校区 东校区静逸楼学生公寓 302宿舍 3层 01号床',:until,NULL,NULL,
                        FALSE,4,:createdAt,:updatedAt
                    )
                    """)
                    .param("s",student.id()).param("u",student.username()).param("n",student.fullName()).param("dept",student.departmentId())
                    .param("srcAssign",histAssignmentId).param("srcBed",beds116[2])
                    .param("targetBed",beds302[0]).param("targetBld",jingyiId)
                    .param("until",Timestamp.valueOf(freshmanEnd.plusDays(7)))
                    .param("createdAt",Timestamp.valueOf(freshmanEnd.minusDays(5)))
                    .param("updatedAt",Timestamp.valueOf(freshmanEnd)).update();
            }
        }
    }

    private long getOrCreateBuilding(long campusId, String code, String name) {
        var opt = jdbc.sql("SELECT id FROM dorm_building WHERE campus_id=:c AND code=:code").param("c",campusId).param("code",code).query(Long.class).optional();
        if(opt.isPresent()) return opt.get();
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO dorm_building(campus_id,code,name,provenance,enabled,version) VALUES(:c,:code,:name,'DEMO',TRUE,0)")
            .param("c",campusId).param("code",code).param("name",name).update(key,"id");
        return key.getKey().longValue();
    }

    private long getOrCreateRoom(long buildingId, String code, String name, String floorLabel) {
        var opt = jdbc.sql("SELECT id FROM dorm_room WHERE building_id=:b AND code=:code").param("b",buildingId).param("code",code).query(Long.class).optional();
        if(opt.isPresent()) return opt.get();
        var key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO dorm_room(building_id,code,name,floor_label,enabled,version) VALUES(:b,:code,:name,:floor,TRUE,0)")
            .param("b",buildingId).param("code",code).param("name",name).param("floor",floorLabel).update(key,"id");
        return key.getKey().longValue();
    }

    private long[] ensureBeds(long roomId) {
        long[] result = new long[4];
        for (int i = 1; i <= 4; i++) {
            String code = String.format("%02d", i);
            String label = String.format("%02d号床", i);
            var opt = jdbc.sql("SELECT id FROM dorm_bed WHERE room_id=:r AND code=:code").param("r",roomId).param("code",code).query(Long.class).optional();
            if(opt.isPresent()) {
                result[i-1] = opt.get();
            } else {
                var key = new GeneratedKeyHolder();
                jdbc.sql("INSERT INTO dorm_bed(room_id,code,label,status,enabled,version) VALUES(:r,:code,:label,'VACANT',TRUE,0)")
                    .param("r",roomId).param("code",code).param("label",label).update(key,"id");
                result[i-1] = key.getKey().longValue();
            }
        }
        return result;
    }

    private void ensureScope(long userId, long buildingId) {
        if(jdbc.sql("SELECT COUNT(*) FROM dorm_manager_scope WHERE user_id=:u AND building_id=:b").param("u",userId).param("b",buildingId).query(Long.class).single()==0) {
            jdbc.sql("INSERT INTO dorm_manager_scope(user_id,building_id,enabled,version) VALUES(:u,:b,TRUE,0)")
                .param("u",userId).param("b",buildingId).update();
        }
    }

    private record DemoStudent(long id, String username, String fullName, long departmentId) {}
}
