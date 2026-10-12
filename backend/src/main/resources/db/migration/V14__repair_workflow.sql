CREATE TABLE repair_request (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 reporter_id BIGINT NOT NULL,
 reporter_name VARCHAR(100) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 campus_id BIGINT,
 location_id BIGINT,
 location_name VARCHAR(100) NOT NULL DEFAULT '',
 campus_name VARCHAR(80) NOT NULL DEFAULT '',
 category VARCHAR(24) NOT NULL DEFAULT '',
 title VARCHAR(100) NOT NULL DEFAULT '',
 description VARCHAR(2000) NOT NULL DEFAULT '',
 location_detail VARCHAR(300) NOT NULL DEFAULT '',
 contact VARCHAR(80) NOT NULL DEFAULT '',
 contact_window VARCHAR(100) NOT NULL DEFAULT '',
 priority VARCHAR(12) NOT NULL DEFAULT 'NORMAL',
 status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
 technician_id BIGINT,
 completion_round INTEGER NOT NULL DEFAULT 0,
 processing_comment VARCHAR(2000) NOT NULL DEFAULT '',
 version INTEGER NOT NULL DEFAULT 0,
 submitted_at TIMESTAMP,
 confirmed_at TIMESTAMP,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(reporter_id,request_key),
 FOREIGN KEY(reporter_id) REFERENCES app_user(id),
 FOREIGN KEY(campus_id) REFERENCES campus(id),
 FOREIGN KEY(location_id) REFERENCES repair_location(id),
 FOREIGN KEY(technician_id) REFERENCES app_user(id),
 CHECK(category IN ('','WATER','ELECTRICITY','NETWORK','FURNITURE','DOOR_WINDOW','EQUIPMENT','OTHER')),
 CHECK(priority IN ('LOW','NORMAL','HIGH')),
 CHECK(status IN ('DRAFT','SUBMITTED','RETURNED','ASSIGNED','IN_PROGRESS','AWAITING_CONFIRMATION','COMPLETED','REJECTED','WITHDRAWN'))
);
CREATE TABLE repair_assignment (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 request_id BIGINT NOT NULL,
 technician_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 comment VARCHAR(2000) NOT NULL,
 assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 ended_at TIMESTAMP,
 FOREIGN KEY(request_id) REFERENCES repair_request(id),
 FOREIGN KEY(technician_id) REFERENCES app_user(id),
 FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE TABLE repair_attachment (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 request_id BIGINT NOT NULL,
 uploader_id BIGINT NOT NULL,
 purpose VARCHAR(16) NOT NULL,
 result_round INTEGER NOT NULL DEFAULT 0,
 file_name VARCHAR(160) NOT NULL,
 file_type VARCHAR(32) NOT NULL,
 file_bytes MEDIUMBLOB NOT NULL,
 current BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(purpose IN ('FAULT','PROGRESS','RESULT')),
 FOREIGN KEY(request_id) REFERENCES repair_request(id),
 FOREIGN KEY(uploader_id) REFERENCES app_user(id)
);
CREATE TABLE repair_completion (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 request_id BIGINT NOT NULL,
 result_round INTEGER NOT NULL,
 technician_id BIGINT NOT NULL,
 technician_name VARCHAR(100) NOT NULL,
 comment VARCHAR(2000) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(request_id,result_round),
 FOREIGN KEY(request_id) REFERENCES repair_request(id),
 FOREIGN KEY(technician_id) REFERENCES app_user(id)
);
CREATE TABLE repair_evaluation (
 request_id BIGINT PRIMARY KEY,
 reporter_id BIGINT NOT NULL,
 result_round INTEGER NOT NULL,
 stars INTEGER NOT NULL,
 comment VARCHAR(500) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 CHECK(stars BETWEEN 1 AND 5),
 FOREIGN KEY(request_id) REFERENCES repair_request(id),
 FOREIGN KEY(reporter_id) REFERENCES app_user(id)
);
CREATE TABLE repair_completion_image (
 completion_id BIGINT NOT NULL,
 attachment_id BIGINT NOT NULL,
 PRIMARY KEY(completion_id,attachment_id),
 FOREIGN KEY(completion_id) REFERENCES repair_completion(id),
 FOREIGN KEY(attachment_id) REFERENCES repair_attachment(id)
);
CREATE TABLE repair_action (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 request_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 actor_name VARCHAR(100) NOT NULL,
 action VARCHAR(32) NOT NULL,
 request_key VARCHAR(80) NOT NULL,
 input_digest VARCHAR(64) NOT NULL,
 request_version INTEGER NOT NULL,
 status VARCHAR(32) NOT NULL,
 comment VARCHAR(2000) NOT NULL,
 snapshot MEDIUMTEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(actor_id,request_key),
 FOREIGN KEY(request_id) REFERENCES repair_request(id),
 FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE INDEX idx_repair_reporter ON repair_request(reporter_id,status,updated_at,id);
CREATE INDEX idx_repair_scope_queue ON repair_request(campus_id,category,status,id);
CREATE INDEX idx_repair_technician ON repair_request(technician_id,status,id);
CREATE INDEX idx_repair_action ON repair_action(request_id,id);
CREATE INDEX idx_repair_image ON repair_attachment(request_id,purpose,result_round,current);
ALTER TABLE user_notice DROP CONSTRAINT ck_notice_source;
ALTER TABLE user_notice ADD CONSTRAINT ck_notice_source CHECK(
 (source_type IS NULL AND source_id IS NULL AND source_view IS NULL) OR
 (source_type IS NOT NULL AND source_id IS NOT NULL AND source_view IS NOT NULL AND
 ((source_type IN ('LEAVE','CERTIFICATE') AND source_view IN ('MINE','REVIEW')) OR
  (source_type='REPAIR' AND source_view IN ('MINE','DISPATCH','WORK'))))
);
