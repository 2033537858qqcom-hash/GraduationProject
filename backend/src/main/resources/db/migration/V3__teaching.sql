CREATE TABLE academic_term (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL UNIQUE,
  starts_on DATE NOT NULL,
  ends_on DATE NOT NULL,
  CHECK (ends_on >= starts_on)
);
CREATE TABLE course (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  code VARCHAR(40) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  credits DECIMAL(4,1) NOT NULL,
  CHECK (credits > 0 AND credits <= 30)
);
CREATE TABLE teaching_class (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  term_id BIGINT NOT NULL,
  course_id BIGINT NOT NULL,
  department_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  UNIQUE (term_id,name),
  FOREIGN KEY (term_id) REFERENCES academic_term(id),
  FOREIGN KEY (course_id) REFERENCES course(id),
  FOREIGN KEY (department_id) REFERENCES department(id)
);
CREATE TABLE teaching_instructor (
  class_id BIGINT NOT NULL,
  teacher_id BIGINT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (class_id,teacher_id),
  FOREIGN KEY (class_id) REFERENCES teaching_class(id),
  FOREIGN KEY (teacher_id) REFERENCES app_user(id)
);
CREATE TABLE teaching_member (
  class_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  PRIMARY KEY (class_id,student_id),
  FOREIGN KEY (class_id) REFERENCES teaching_class(id),
  FOREIGN KEY (student_id) REFERENCES app_user(id)
);
CREATE TABLE teaching_lesson (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  class_id BIGINT NOT NULL,
  teacher_id BIGINT NOT NULL,
  campus_id BIGINT NOT NULL,
  classroom VARCHAR(100) NOT NULL,
  starts_at TIMESTAMP NOT NULL,
  ends_at TIMESTAMP NOT NULL,
  FOREIGN KEY (class_id,teacher_id) REFERENCES teaching_instructor(class_id,teacher_id),
  FOREIGN KEY (campus_id) REFERENCES campus(id),
  CHECK (ends_at > starts_at),
  UNIQUE (class_id,starts_at)
);
CREATE TABLE course_material (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  class_id BIGINT NOT NULL,
  author_id BIGINT NOT NULL,
  title VARCHAR(160) NOT NULL,
  body TEXT NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  version INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  file_name VARCHAR(160),
  file_type VARCHAR(100),
  file_bytes MEDIUMBLOB,
  FOREIGN KEY (class_id) REFERENCES teaching_class(id),
  FOREIGN KEY (author_id) REFERENCES app_user(id),
  CHECK (status IN ('DRAFT','PUBLISHED','WITHDRAWN'))
);
CREATE TABLE teaching_notice (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  class_id BIGINT NOT NULL,
  author_id BIGINT NOT NULL,
  request_key VARCHAR(64) NOT NULL,
  title VARCHAR(160) NOT NULL,
  body TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (class_id) REFERENCES teaching_class(id),
  FOREIGN KEY (author_id) REFERENCES app_user(id)
  ,UNIQUE (author_id,request_key)
);
CREATE TABLE academic_grade (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  class_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  score DECIMAL(5,1) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  version INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (class_id,student_id) REFERENCES teaching_member(class_id,student_id),
  UNIQUE (class_id,student_id),
  CHECK (score >= 0 AND score <= 100),
  CHECK (status IN ('DRAFT','PUBLISHED'))
);
CREATE TABLE academic_exam (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  class_id BIGINT NOT NULL UNIQUE,
  campus_id BIGINT NOT NULL,
  classroom VARCHAR(100) NOT NULL,
  starts_at TIMESTAMP NOT NULL,
  ends_at TIMESTAMP NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'DRAFT',
  version INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (class_id) REFERENCES teaching_class(id),
  FOREIGN KEY (campus_id) REFERENCES campus(id),
  CHECK (ends_at > starts_at),
  CHECK (status IN ('DRAFT','PUBLISHED'))
);
CREATE TABLE teaching_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  class_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  action VARCHAR(40) NOT NULL,
  object_id BIGINT NOT NULL,
  detail VARCHAR(400) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (class_id) REFERENCES teaching_class(id),
  FOREIGN KEY (actor_id) REFERENCES app_user(id)
);
CREATE INDEX idx_lesson_time ON teaching_lesson(starts_at,class_id);
CREATE INDEX idx_material_class ON course_material(class_id,status);
CREATE INDEX idx_teaching_notice ON teaching_notice(class_id,created_at);
INSERT INTO app_role(code,name) VALUES ('ACADEMIC_STAFF','教务信息维护');
INSERT INTO permission(code,description) VALUES
  ('learning:read','读取本人教学班与学习信息'),
  ('teaching:manage','维护本人有效授课班级的资料和通知'),
  ('academic:manage','维护授权学院的教学信息');
INSERT INTO role_permission(role_code,permission_code) VALUES
  ('STUDENT','learning:read'),('TEACHER','teaching:manage'),
  ('ACADEMIC_STAFF','workbench:read'),('ACADEMIC_STAFF','academic:manage');
