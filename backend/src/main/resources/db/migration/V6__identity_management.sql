ALTER TABLE app_user ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE app_user ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE department ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE major ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE class_group ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX uk_user_identity ON app_user(identity_number);
CREATE UNIQUE INDEX uk_department_name ON department(name);
CREATE UNIQUE INDEX uk_major_department_name ON major(department_id,name);
CREATE UNIQUE INDEX uk_class_major_name ON class_group(major_id,name);
INSERT INTO permission(code,description) VALUES('foundation:manage','维护校园账号、组织目录、角色分配与修改记录');
INSERT INTO role_permission(role_code,permission_code) VALUES('ADMIN','foundation:manage');
CREATE TABLE foundation_write_lock (id INTEGER PRIMARY KEY, revision BIGINT NOT NULL DEFAULT 0);
INSERT INTO foundation_write_lock(id) VALUES(1);
CREATE TABLE foundation_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  actor_id BIGINT NOT NULL,
  actor_name VARCHAR(100) NOT NULL,
  action VARCHAR(40) NOT NULL,
  entity_type VARCHAR(32) NOT NULL,
  entity_id BIGINT NOT NULL,
  summary VARCHAR(1600) NOT NULL,
  reason VARCHAR(200) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE INDEX idx_foundation_audit_entity ON foundation_audit(entity_type,entity_id,id);
