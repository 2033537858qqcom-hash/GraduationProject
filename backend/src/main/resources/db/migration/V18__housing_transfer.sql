INSERT INTO permission(code,description) VALUES('housing:review','初审所属学院的调宿申请');
INSERT INTO role_permission(role_code,permission_code) VALUES('STUDENT_AFFAIRS','housing:review');

-- current_student_id marks the one unfinished application. reserved_bed_id marks the one active bed reservation.
CREATE TABLE housing_transfer (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 student_id BIGINT NOT NULL,
 student_username VARCHAR(64) NOT NULL,
 student_name VARCHAR(100) NOT NULL,
 department_id BIGINT NOT NULL,
 source_assignment_id BIGINT NOT NULL,
 source_version INTEGER NOT NULL,
 source_bed_id BIGINT NOT NULL,
 source_place VARCHAR(300) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 status VARCHAR(24) NOT NULL,
 reason VARCHAR(500) NOT NULL,
 preferred_area VARCHAR(200) NOT NULL,
 material_note VARCHAR(500) NOT NULL,
 review_comment VARCHAR(200),
 target_bed_id BIGINT,
 target_building_id BIGINT,
 target_place VARCHAR(300),
 reserve_until TIMESTAMP,
 reserved_bed_id BIGINT,
 current_student_id BIGINT,
 cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,
 version INTEGER NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(request_key),
 UNIQUE(current_student_id),
 UNIQUE(reserved_bed_id),
 FOREIGN KEY(student_id) REFERENCES app_user(id),
 FOREIGN KEY(department_id) REFERENCES department(id),
 FOREIGN KEY(source_assignment_id) REFERENCES housing_assignment(id),
 FOREIGN KEY(source_bed_id) REFERENCES dorm_bed(id),
 FOREIGN KEY(target_bed_id) REFERENCES dorm_bed(id),
 FOREIGN KEY(target_building_id) REFERENCES dorm_building(id),
 CHECK(status IN ('DRAFT','SUBMITTED','RETURNED','AWAITING_ASSIGNMENT','AWAITING_CHECKIN','COMPLETED','REJECTED','WITHDRAWN','CANCELLED','EXPIRED')),
 CHECK(version>=0),
 CHECK(source_version>=0),
 CHECK((current_student_id IS NULL AND status IN ('COMPLETED','REJECTED','WITHDRAWN','CANCELLED','EXPIRED'))
  OR (current_student_id=student_id AND status IN ('DRAFT','SUBMITTED','RETURNED','AWAITING_ASSIGNMENT','AWAITING_CHECKIN'))),
 CHECK((reserved_bed_id IS NULL AND status<>'AWAITING_CHECKIN')
  OR (status='AWAITING_CHECKIN' AND reserved_bed_id=target_bed_id AND target_building_id IS NOT NULL AND reserve_until IS NOT NULL))
);
CREATE TABLE housing_transfer_action (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 transfer_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 action VARCHAR(40) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 input_digest VARCHAR(800) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(actor_id,request_key),
 FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE INDEX idx_housing_transfer_department ON housing_transfer(department_id,status,id);
CREATE INDEX idx_housing_transfer_student ON housing_transfer(student_id,id);
CREATE INDEX idx_housing_transfer_due ON housing_transfer(status,reserve_until);
