CREATE TABLE campus (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(32) NOT NULL UNIQUE,
  name VARCHAR(80) NOT NULL,
  address VARCHAR(255) NOT NULL
);
CREATE TABLE department (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  parent_id BIGINT,
  name VARCHAR(100) NOT NULL,
  kind VARCHAR(24) NOT NULL,
  FOREIGN KEY (parent_id) REFERENCES department(id)
);
CREATE TABLE major (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  department_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  demo BOOLEAN NOT NULL DEFAULT TRUE,
  FOREIGN KEY (department_id) REFERENCES department(id)
);
CREATE TABLE class_group (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  major_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  FOREIGN KEY (major_id) REFERENCES major(id)
);
CREATE TABLE building (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  campus_id BIGINT NOT NULL,
  code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  model_object_key VARCHAR(100),
  verification_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  source_url VARCHAR(1000),
  FOREIGN KEY (campus_id) REFERENCES campus(id)
);
CREATE TABLE app_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  full_name VARCHAR(100) NOT NULL,
  identity_number VARCHAR(64) NOT NULL,
  campus_id BIGINT,
  department_id BIGINT,
  class_group_id BIGINT,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  demo BOOLEAN NOT NULL DEFAULT TRUE,
  FOREIGN KEY (campus_id) REFERENCES campus(id),
  FOREIGN KEY (department_id) REFERENCES department(id),
  FOREIGN KEY (class_group_id) REFERENCES class_group(id)
);
CREATE TABLE app_role (
  code VARCHAR(40) PRIMARY KEY,
  name VARCHAR(80) NOT NULL
);
CREATE TABLE permission (
  code VARCHAR(80) PRIMARY KEY,
  description VARCHAR(120) NOT NULL
);
CREATE TABLE user_role (
  user_id BIGINT NOT NULL,
  role_code VARCHAR(40) NOT NULL,
  PRIMARY KEY (user_id,role_code),
  FOREIGN KEY (user_id) REFERENCES app_user(id),
  FOREIGN KEY (role_code) REFERENCES app_role(code)
);
CREATE TABLE role_permission (
  role_code VARCHAR(40) NOT NULL,
  permission_code VARCHAR(80) NOT NULL,
  PRIMARY KEY (role_code,permission_code),
  FOREIGN KEY (role_code) REFERENCES app_role(code),
  FOREIGN KEY (permission_code) REFERENCES permission(code)
);
CREATE TABLE timetable_entry (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  student_id BIGINT NOT NULL,
  course_name VARCHAR(100) NOT NULL,
  teacher_name VARCHAR(100) NOT NULL,
  classroom VARCHAR(100) NOT NULL,
  campus_id BIGINT NOT NULL,
  building_id BIGINT,
  starts_at TIMESTAMP NOT NULL,
  ends_at TIMESTAMP NOT NULL,
  FOREIGN KEY (student_id) REFERENCES app_user(id),
  FOREIGN KEY (campus_id) REFERENCES campus(id),
  FOREIGN KEY (building_id) REFERENCES building(id),
  CHECK (ends_at > starts_at)
);
CREATE TABLE user_notice (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  title VARCHAR(160) NOT NULL,
  body TEXT NOT NULL,
  read_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES app_user(id)
);
CREATE TABLE website_content (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  slug VARCHAR(80) NOT NULL UNIQUE,
  category VARCHAR(32) NOT NULL,
  title VARCHAR(160) NOT NULL,
  summary VARCHAR(400) NOT NULL,
  body TEXT NOT NULL,
  source_url VARCHAR(1000),
  source_type VARCHAR(24) NOT NULL,
  status VARCHAR(24) NOT NULL,
  created_by BIGINT,
  reviewed_by BIGINT,
  version INTEGER NOT NULL DEFAULT 0,
  published_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (created_by) REFERENCES app_user(id),
  FOREIGN KEY (reviewed_by) REFERENCES app_user(id),
  CHECK (status IN ('DRAFT','IN_REVIEW','PUBLISHED')),
  CHECK (source_type IN ('PUBLIC_SOURCE','DEMO'))
);
CREATE TABLE content_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  content_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  action VARCHAR(32) NOT NULL,
  content_version INTEGER NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (content_id) REFERENCES website_content(id),
  FOREIGN KEY (actor_id) REFERENCES app_user(id)
);
CREATE INDEX idx_timetable_student_time ON timetable_entry(student_id,starts_at);
CREATE INDEX idx_notice_user_time ON user_notice(user_id,created_at);
CREATE INDEX idx_content_public ON website_content(status,category,published_at);
