INSERT INTO app_role(code,name) VALUES('COUNSELOR','辅导员');
INSERT INTO permission(code,description) VALUES
 ('leave:apply','办理本人请假、补交及撤回，读取本人结果与材料'),
 ('leave:review','处理所负责行政班的请假，读取相关材料与记录');
INSERT INTO role_permission(role_code,permission_code) VALUES
 ('STUDENT','leave:apply'),('COUNSELOR','leave:review'),('COUNSELOR','workbench:read');
CREATE TABLE counselor_assignment (
 class_group_id BIGINT PRIMARY KEY,
 counselor_id BIGINT,
 version INTEGER NOT NULL DEFAULT 0,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(class_group_id) REFERENCES class_group(id),
 FOREIGN KEY(counselor_id) REFERENCES app_user(id)
);
CREATE TABLE leave_request (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 student_id BIGINT NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 class_group_id BIGINT NOT NULL,
 student_name VARCHAR(100) NOT NULL,
 identity_number VARCHAR(64) NOT NULL,
 class_name VARCHAR(100) NOT NULL,
 reason_type VARCHAR(24) NOT NULL,
 reason TEXT NOT NULL,
 contact VARCHAR(80) NOT NULL,
 starts_at TIMESTAMP NOT NULL,
 ends_at TIMESTAMP NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
 processing_comment VARCHAR(1000),
 version INTEGER NOT NULL DEFAULT 0,
 file_id BIGINT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(student_id) REFERENCES app_user(id),
 FOREIGN KEY(class_group_id) REFERENCES class_group(id),
 UNIQUE(student_id,request_key),
 CHECK(ends_at>starts_at),
 CHECK(reason_type IN ('PERSONAL','SICK','OTHER')),
 CHECK(status IN ('DRAFT','SUBMITTED','RETURNED','APPROVED','REJECTED','WITHDRAWN'))
);
CREATE TABLE leave_file (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 request_id BIGINT NOT NULL,
 file_name VARCHAR(160) NOT NULL,
 file_type VARCHAR(100) NOT NULL,
 file_bytes MEDIUMBLOB NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(request_id) REFERENCES leave_request(id)
);
ALTER TABLE leave_request ADD CONSTRAINT fk_leave_file FOREIGN KEY(file_id) REFERENCES leave_file(id);
CREATE TABLE leave_revision (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 request_id BIGINT NOT NULL,
 request_version INTEGER NOT NULL,
 actor_name VARCHAR(100) NOT NULL,
 action VARCHAR(24) NOT NULL,
 comment VARCHAR(1000) NOT NULL,
 status VARCHAR(24) NOT NULL,
 reason_type VARCHAR(24) NOT NULL,
 reason TEXT NOT NULL,
 contact VARCHAR(80) NOT NULL,
 starts_at TIMESTAMP NOT NULL,
 ends_at TIMESTAMP NOT NULL,
 file_id BIGINT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(request_id) REFERENCES leave_request(id),
 FOREIGN KEY(file_id) REFERENCES leave_file(id),
 UNIQUE(request_id,request_version)
);
CREATE TABLE leave_calendar (
 request_id BIGINT PRIMARY KEY,
 student_id BIGINT NOT NULL,
 starts_at TIMESTAMP NOT NULL,
 ends_at TIMESTAMP NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(request_id) REFERENCES leave_request(id),
 FOREIGN KEY(student_id) REFERENCES app_user(id)
);
CREATE INDEX idx_leave_student_state_time ON leave_request(student_id,status,starts_at);
CREATE INDEX idx_leave_class_state ON leave_request(class_group_id,status,id);
CREATE INDEX idx_leave_file_request ON leave_file(request_id,id);
CREATE INDEX idx_leave_revision_request ON leave_revision(request_id,id);
CREATE INDEX idx_leave_calendar_student ON leave_calendar(student_id,starts_at);
