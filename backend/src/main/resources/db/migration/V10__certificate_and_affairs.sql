INSERT INTO app_role(code,name) VALUES('STUDENT_AFFAIRS','学生事务人员');
INSERT INTO permission(code,description) VALUES
 ('certificate:apply','办理本人在校证明、补交撤回并读取受控结果'),
 ('certificate:review','处理所属学院证明申请并读取相关演示结果');
INSERT INTO role_permission(role_code,permission_code) VALUES
 ('STUDENT','certificate:apply'),('STUDENT_AFFAIRS','certificate:review'),('STUDENT_AFFAIRS','workbench:read');
CREATE TABLE certificate_request (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 student_id BIGINT NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 department_id BIGINT NOT NULL,
 class_group_id BIGINT NOT NULL,
 student_name VARCHAR(100) NOT NULL,
 identity_number VARCHAR(64) NOT NULL,
 department_name VARCHAR(100) NOT NULL,
 major_name VARCHAR(100) NOT NULL,
 class_name VARCHAR(100) NOT NULL,
 campus_name VARCHAR(80) NOT NULL,
 purpose VARCHAR(300) NOT NULL,
 delivery_method VARCHAR(24) NOT NULL DEFAULT 'DIGITAL',
 identity_confirmed BOOLEAN NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
 processing_comment VARCHAR(1000),
 version INTEGER NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(student_id) REFERENCES app_user(id),
 FOREIGN KEY(department_id) REFERENCES department(id),
 FOREIGN KEY(class_group_id) REFERENCES class_group(id),
 UNIQUE(student_id,request_key),
 CHECK(identity_confirmed=TRUE),
 CHECK(delivery_method='DIGITAL'),
 CHECK(status IN ('DRAFT','SUBMITTED','RETURNED','COMPLETED','REJECTED','WITHDRAWN'))
);
CREATE TABLE certificate_revision (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 request_id BIGINT NOT NULL,
 request_version INTEGER NOT NULL,
 actor_name VARCHAR(100) NOT NULL,
 action VARCHAR(24) NOT NULL,
 comment VARCHAR(1000) NOT NULL,
 status VARCHAR(24) NOT NULL,
 student_name VARCHAR(100) NOT NULL,
 identity_number VARCHAR(64) NOT NULL,
 department_name VARCHAR(100) NOT NULL,
 major_name VARCHAR(100) NOT NULL,
 class_name VARCHAR(100) NOT NULL,
 campus_name VARCHAR(80) NOT NULL,
 purpose VARCHAR(300) NOT NULL,
 delivery_method VARCHAR(24) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(request_id) REFERENCES certificate_request(id),
 UNIQUE(request_id,request_version)
);
CREATE TABLE certificate_result (
 request_id BIGINT PRIMARY KEY,
 document_number VARCHAR(80) NOT NULL UNIQUE,
 file_name VARCHAR(160) NOT NULL,
 file_bytes MEDIUMBLOB NOT NULL,
 issued_at TIMESTAMP NOT NULL,
 FOREIGN KEY(request_id) REFERENCES certificate_request(id)
);
CREATE INDEX idx_certificate_student_state ON certificate_request(student_id,status,updated_at,id);
CREATE INDEX idx_certificate_department_state ON certificate_request(department_id,status,id);
CREATE INDEX idx_certificate_revision_request ON certificate_revision(request_id,id);
ALTER TABLE user_notice ADD COLUMN source_type VARCHAR(24);
ALTER TABLE user_notice ADD COLUMN source_id BIGINT;
ALTER TABLE user_notice ADD COLUMN source_view VARCHAR(12);
ALTER TABLE user_notice ADD CONSTRAINT ck_notice_source CHECK(
 (source_type IS NULL AND source_id IS NULL AND source_view IS NULL) OR
 (source_type IS NOT NULL AND source_id IS NOT NULL AND source_view IS NOT NULL
  AND source_type IN ('LEAVE','CERTIFICATE') AND source_view IN ('MINE','REVIEW'))
);
CREATE INDEX idx_notice_owner_read_id ON user_notice(user_id,read_at,id);
