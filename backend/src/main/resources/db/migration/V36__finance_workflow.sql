INSERT INTO app_role(code,name) VALUES ('FINANCE_OPERATOR','财务经办人员'),('FINANCE_REVIEWER','财务复核人员');
INSERT INTO permission(code,description) VALUES
 ('payment:manage','维护收费标准、经办账单、催缴、查单及财务统计'),
 ('payment:review','独立复核收费标准、账单、作废、减免及退款'),
 ('payment:source','在本岗位负责范围内提交收费依据');
INSERT INTO role_permission(role_code,permission_code) VALUES
 ('FINANCE_OPERATOR','workbench:read'),('FINANCE_OPERATOR','payment:manage'),
 ('FINANCE_REVIEWER','workbench:read'),('FINANCE_REVIEWER','payment:review'),
 ('ACADEMIC_STAFF','payment:source'),('DORM_MANAGER','payment:source'),('REPAIR_DISPATCHER','payment:source');

CREATE TABLE payment_standard (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 request_key VARCHAR(80) NOT NULL UNIQUE,
 name VARCHAR(120) NOT NULL,
 category_code VARCHAR(32) NOT NULL,
 unit VARCHAR(20) NOT NULL,
 unit_price_cents BIGINT NOT NULL,
 campus_id BIGINT,
 major_id BIGINT,
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING_REVIEW',
 created_by BIGINT NOT NULL,
 reviewed_by BIGINT,
 reason VARCHAR(500) NOT NULL,
 version INTEGER NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(category_code) REFERENCES payment_category(code),
 FOREIGN KEY(campus_id) REFERENCES campus(id),
 FOREIGN KEY(major_id) REFERENCES major(id),
 FOREIGN KEY(created_by) REFERENCES app_user(id),
 FOREIGN KEY(reviewed_by) REFERENCES app_user(id),
 CHECK(unit_price_cents>0 AND unit_price_cents<=100000000),
 CHECK(status IN ('PENDING_REVIEW','ACTIVE','RETURNED','RETIRED')),
 CHECK(version>=0)
);
CREATE TABLE payment_basis (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 request_key VARCHAR(80) NOT NULL UNIQUE,
 student_id BIGINT NOT NULL,
 category_code VARCHAR(32) NOT NULL,
 billing_period VARCHAR(80) NOT NULL,
 quantity VARCHAR(24) NOT NULL,
 basis VARCHAR(1000) NOT NULL,
 source_reference VARCHAR(500) NOT NULL,
 replaces_basis_id BIGINT,
 created_by BIGINT NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED',
 bill_id BIGINT UNIQUE,
 review_comment VARCHAR(500) NOT NULL DEFAULT '',
 version INTEGER NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(student_id) REFERENCES app_user(id),
 FOREIGN KEY(category_code) REFERENCES payment_category(code),
 FOREIGN KEY(created_by) REFERENCES app_user(id),
 FOREIGN KEY(bill_id) REFERENCES payment_bill(id),
 FOREIGN KEY(replaces_basis_id) REFERENCES payment_basis(id),
 CHECK(status IN ('SUBMITTED','ACCEPTED','RETURNED','REPLACED')),
 CHECK(version>=0)
);
ALTER TABLE payment_bill ADD COLUMN workflow_status VARCHAR(24) NOT NULL DEFAULT 'PUBLISHED';
ALTER TABLE payment_bill ADD COLUMN billing_period VARCHAR(80) NOT NULL DEFAULT '历史账单';
ALTER TABLE payment_bill ADD COLUMN due_date DATE;
ALTER TABLE payment_bill ADD COLUMN basis VARCHAR(1000) NOT NULL DEFAULT '升级前已创建的账单，保留原金额及支付状态。';
ALTER TABLE payment_bill ADD COLUMN standard_id BIGINT;
ALTER TABLE payment_bill ADD COLUMN standard_name VARCHAR(120);
ALTER TABLE payment_bill ADD COLUMN unit_price_cents BIGINT;
ALTER TABLE payment_bill ADD COLUMN quantity VARCHAR(24) NOT NULL DEFAULT '1';
ALTER TABLE payment_bill ADD COLUMN source_basis_id BIGINT;
ALTER TABLE payment_bill ADD COLUMN reviewed_by BIGINT;
ALTER TABLE payment_bill ADD COLUMN published_at TIMESTAMP;
ALTER TABLE payment_bill ADD COLUMN payment_started_at TIMESTAMP;
ALTER TABLE payment_bill ADD COLUMN last_sync_error VARCHAR(300) NOT NULL DEFAULT '';
ALTER TABLE payment_bill ADD COLUMN last_checked_at TIMESTAMP;
ALTER TABLE payment_bill ADD COLUMN pending_request_id BIGINT;
ALTER TABLE payment_bill ADD COLUMN refund_status VARCHAR(24) NOT NULL DEFAULT 'NONE';
ALTER TABLE payment_bill ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payment_bill ADD CONSTRAINT fk_payment_standard FOREIGN KEY(standard_id) REFERENCES payment_standard(id);
ALTER TABLE payment_bill ADD CONSTRAINT fk_payment_source_basis FOREIGN KEY(source_basis_id) REFERENCES payment_basis(id);
ALTER TABLE payment_bill ADD CONSTRAINT fk_payment_reviewer FOREIGN KEY(reviewed_by) REFERENCES app_user(id);
ALTER TABLE payment_bill ADD CONSTRAINT ck_payment_workflow CHECK(workflow_status IN ('DRAFT','PENDING_REVIEW','RETURNED','PUBLISHED','VOIDED','WAIVED'));
ALTER TABLE payment_bill ADD CONSTRAINT ck_payment_refund CHECK(refund_status IN ('NONE','REQUESTED','PROCESSING','REFUNDED','REJECTED'));
ALTER TABLE payment_bill ADD CONSTRAINT ck_payment_version CHECK(version>=0);
UPDATE payment_bill SET published_at=created_at;

CREATE TABLE payment_request (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 request_key VARCHAR(80) NOT NULL UNIQUE,
 bill_id BIGINT NOT NULL,
 type VARCHAR(16) NOT NULL,
 requested_by BIGINT NOT NULL,
 reason VARCHAR(500) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'SUBMITTED',
 reviewed_by BIGINT,
 review_comment VARCHAR(500) NOT NULL DEFAULT '',
 gateway_request_no VARCHAR(80) NOT NULL UNIQUE,
 last_error VARCHAR(300) NOT NULL DEFAULT '',
 last_attempt_at TIMESTAMP,
 version INTEGER NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 resolved_at TIMESTAMP,
 FOREIGN KEY(bill_id) REFERENCES payment_bill(id),
 FOREIGN KEY(requested_by) REFERENCES app_user(id),
 FOREIGN KEY(reviewed_by) REFERENCES app_user(id),
 CHECK(type IN ('VOID','WAIVER','REFUND')),
 CHECK(status IN ('SUBMITTED','PROCESSING','COMPLETED','REJECTED','FAILED')),
 CHECK(version>=0)
);
ALTER TABLE payment_bill ADD CONSTRAINT fk_payment_pending_request FOREIGN KEY(pending_request_id) REFERENCES payment_request(id);
CREATE TABLE payment_history (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 entity_type VARCHAR(16) NOT NULL,
 entity_id BIGINT NOT NULL,
 action VARCHAR(32) NOT NULL,
 actor_id BIGINT,
 request_key VARCHAR(80) UNIQUE,
 command_hash VARCHAR(64),
 reason VARCHAR(1000) NOT NULL,
 snapshot TEXT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE TABLE payment_reminder (
 bill_id BIGINT NOT NULL,
 reminder_date DATE NOT NULL,
 PRIMARY KEY(bill_id,reminder_date),
 FOREIGN KEY(bill_id) REFERENCES payment_bill(id)
);
CREATE INDEX idx_payment_workflow ON payment_bill(workflow_status,created_by,id);
CREATE INDEX idx_payment_due ON payment_bill(status,workflow_status,due_date);
CREATE INDEX idx_payment_request_queue ON payment_request(status,type,id);
CREATE INDEX idx_payment_basis_queue ON payment_basis(status,created_by,id);
CREATE INDEX idx_payment_history ON payment_history(entity_type,entity_id,id);

ALTER TABLE user_notice DROP CONSTRAINT ck_notice_source;
ALTER TABLE user_notice ADD CONSTRAINT ck_notice_source CHECK(
 (source_type IS NULL AND source_id IS NULL AND source_view IS NULL) OR
 (source_type IS NOT NULL AND source_id IS NOT NULL AND source_view IS NOT NULL AND
 ((source_type IN ('LEAVE','CERTIFICATE') AND source_view IN ('MINE','REVIEW')) OR
  (source_type='REPAIR' AND source_view IN ('MINE','DISPATCH','WORK')) OR
  (source_type='HOUSING' AND source_view IN ('MINE','REVIEW','ASSIGN')) OR
  (source_type='RESERVATION' AND source_view IN ('MINE','REVIEW','MANAGE')) OR
  (source_type='PAYMENT' AND source_view IN ('MINE','FINANCE')) OR
  (source_type='PAYMENT_BASIS' AND source_view IN ('SOURCE','FINANCE'))))
);
