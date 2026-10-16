INSERT INTO app_role(code,name) VALUES ('DORM_MANAGER','宿管');
INSERT INTO permission(code,description) VALUES
 ('housing:read','查看本人住宿与入住历史'),
 ('housing:assign','在负责楼栋登记初始入住和退宿'),
 ('housing:configure','维护宿舍楼栋、房间、床位及楼栋负责人');
INSERT INTO role_permission(role_code,permission_code) VALUES
 ('STUDENT','housing:read'),('ADMIN','housing:configure'),
 ('DORM_MANAGER','workbench:read'),('DORM_MANAGER','housing:assign');

CREATE TABLE dorm_building (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 campus_id BIGINT NOT NULL,
 code VARCHAR(64) NOT NULL,
 name VARCHAR(100) NOT NULL,
 provenance VARCHAR(24) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version INTEGER NOT NULL DEFAULT 0,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(campus_id,code),
 FOREIGN KEY(campus_id) REFERENCES campus(id),
 CHECK(provenance IN ('DEMO','CONFIGURED')),
 CHECK(version>=0)
);
CREATE TABLE dorm_room (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 building_id BIGINT NOT NULL,
 code VARCHAR(64) NOT NULL,
 name VARCHAR(100) NOT NULL,
 floor_label VARCHAR(20) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version INTEGER NOT NULL DEFAULT 0,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(building_id,code),
 FOREIGN KEY(building_id) REFERENCES dorm_building(id),
 CHECK(version>=0)
);
CREATE TABLE dorm_bed (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 room_id BIGINT NOT NULL,
 code VARCHAR(32) NOT NULL,
 label VARCHAR(40) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'VACANT',
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version INTEGER NOT NULL DEFAULT 0,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(room_id,code),
 FOREIGN KEY(room_id) REFERENCES dorm_room(id),
 CHECK(status IN ('VACANT','RESERVED','OCCUPIED','MAINTENANCE')),
 CHECK(version>=0)
);
CREATE TABLE dorm_manager_scope (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 building_id BIGINT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version INTEGER NOT NULL DEFAULT 0,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id,building_id),
 FOREIGN KEY(user_id) REFERENCES app_user(id),
 FOREIGN KEY(building_id) REFERENCES dorm_building(id),
 CHECK(version>=0)
);
-- current_* is populated only for an active stay. Unique indexes therefore allow one current student and one current bed, while ended rows keep those columns null.
CREATE TABLE housing_assignment (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 student_id BIGINT NOT NULL,
 student_username VARCHAR(64) NOT NULL,
 student_name VARCHAR(100) NOT NULL,
 bed_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 checkout_key VARCHAR(80),
 current_student_id BIGINT,
 current_bed_id BIGINT,
 campus_name VARCHAR(80) NOT NULL,
 building_name VARCHAR(100) NOT NULL,
 room_name VARCHAR(100) NOT NULL,
 floor_label VARCHAR(20) NOT NULL,
 bed_label VARCHAR(40) NOT NULL,
 assigned_by BIGINT NOT NULL,
 ended_by BIGINT,
 end_reason VARCHAR(200),
 version INTEGER NOT NULL DEFAULT 0,
 started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 ended_at TIMESTAMP,
 UNIQUE(request_key),
 UNIQUE(checkout_key),
 UNIQUE(current_student_id),
 UNIQUE(current_bed_id),
 FOREIGN KEY(student_id) REFERENCES app_user(id),
 FOREIGN KEY(bed_id) REFERENCES dorm_bed(id),
 FOREIGN KEY(assigned_by) REFERENCES app_user(id),
 FOREIGN KEY(ended_by) REFERENCES app_user(id),
 CHECK(status IN ('CURRENT','ENDED')),
 CHECK(version>=0),
 CHECK((status='CURRENT' AND current_student_id=student_id AND current_bed_id=bed_id AND ended_at IS NULL AND ended_by IS NULL AND checkout_key IS NULL AND end_reason IS NULL)
  OR (status='ENDED' AND current_student_id IS NULL AND current_bed_id IS NULL AND ended_at IS NOT NULL AND ended_by IS NOT NULL AND checkout_key IS NOT NULL AND end_reason IS NOT NULL))
);
CREATE INDEX idx_dorm_building_campus ON dorm_building(campus_id,enabled,id);
CREATE INDEX idx_dorm_room_building ON dorm_room(building_id,enabled,id);
CREATE INDEX idx_dorm_bed_room ON dorm_bed(room_id,status,id);
CREATE INDEX idx_dorm_scope_user ON dorm_manager_scope(user_id,enabled,building_id);
CREATE INDEX idx_housing_student ON housing_assignment(student_id,status,id);
CREATE INDEX idx_housing_bed ON housing_assignment(bed_id,status,id);
