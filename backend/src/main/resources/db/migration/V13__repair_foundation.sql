INSERT INTO app_role(code,name) VALUES ('REPAIR_DISPATCHER','后勤派单人员'),('REPAIR_TECHNICIAN','维修人员');
INSERT INTO permission(code,description) VALUES
 ('repair:apply','读取报修地点及办理本人报修'),('repair:dispatch','分诊及派单职责范围内报修'),
 ('repair:work','处理当前分配给本人的报修'),('repair:configure','维护开放报修地点及人员负责范围');
INSERT INTO role_permission(role_code,permission_code) VALUES
 ('STUDENT','repair:apply'),('TEACHER','repair:apply'),('ADMIN','repair:configure'),
 ('REPAIR_DISPATCHER','workbench:read'),('REPAIR_DISPATCHER','repair:dispatch'),('REPAIR_DISPATCHER','agent:query'),
 ('REPAIR_TECHNICIAN','workbench:read'),('REPAIR_TECHNICIAN','repair:work'),('REPAIR_TECHNICIAN','agent:query');

CREATE TABLE repair_location (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 campus_id BIGINT NOT NULL,
 code VARCHAR(64) NOT NULL,
 name VARCHAR(100) NOT NULL,
 kind VARCHAR(24) NOT NULL,
 classroom_resource_id BIGINT UNIQUE,
 building_id BIGINT,
 detail_hint VARCHAR(300) NOT NULL,
 provenance VARCHAR(24) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version INTEGER NOT NULL DEFAULT 0,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(campus_id,code),
 FOREIGN KEY(campus_id) REFERENCES campus(id),
 FOREIGN KEY(classroom_resource_id) REFERENCES classroom_resource(id),
 FOREIGN KEY(building_id) REFERENCES building(id),
 CHECK(kind IN ('CLASSROOM','BUILDING_COMMON','CAMPUS_PUBLIC')),
 CHECK(provenance IN ('DEMO','CONFIGURED')),
 CHECK((kind='CLASSROOM' AND classroom_resource_id IS NOT NULL) OR (kind<>'CLASSROOM' AND classroom_resource_id IS NULL)),
 CHECK(kind<>'BUILDING_COMMON' OR building_id IS NOT NULL),
 CHECK(kind<>'CAMPUS_PUBLIC' OR building_id IS NULL),
 CHECK(version>=0)
);
CREATE TABLE repair_staff_scope (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 capability VARCHAR(16) NOT NULL,
 campus_id BIGINT NOT NULL,
 category VARCHAR(24) NOT NULL,
 location_id BIGINT,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version INTEGER NOT NULL DEFAULT 0,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(user_id) REFERENCES app_user(id),
 FOREIGN KEY(campus_id) REFERENCES campus(id),
 FOREIGN KEY(location_id) REFERENCES repair_location(id),
 CHECK(capability IN ('DISPATCH','WORK')),
 CHECK(category IN ('WATER','ELECTRICITY','NETWORK','FURNITURE','DOOR_WINDOW','EQUIPMENT','OTHER')),
 CHECK(version>=0)
);
CREATE INDEX idx_repair_location_campus ON repair_location(campus_id,enabled,id);
CREATE INDEX idx_repair_scope_person ON repair_staff_scope(user_id,capability,enabled,id);
CREATE INDEX idx_repair_scope_match ON repair_staff_scope(campus_id,category,enabled,location_id);
