package cn.zzuit.campus;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/** Fixtures for existing scheduling regressions; arbitrary location text is no longer a public write input. */
final class TestRooms {
    private TestRooms() {}
    static synchronized long ensure(JdbcClient jdbc,String name) {
        var existing=jdbc.sql("SELECT id FROM classroom_resource WHERE campus_id=1 AND name=:name").param("name",name).query(Long.class).optional();
        if(existing.isPresent()) return existing.get();
        String code="TEST-"+UUID.randomUUID(); var key=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO classroom_resource(campus_id,code,name,building_name,room_number,room_type,teaching_capacity,exam_capacity,origin) VALUES(1,:code,:name,:building,'201','LECTURE',1000,1000,'DEMO')")
            .param("code",code).param("name",name).param("building","测试楼-"+code).update(key,"id");
        return key.getKey().longValue();
    }
}
