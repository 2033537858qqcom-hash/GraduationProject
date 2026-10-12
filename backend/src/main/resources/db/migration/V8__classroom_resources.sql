CREATE TABLE classroom_resource (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  campus_id BIGINT NOT NULL,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(100) NOT NULL,
  building_name VARCHAR(100),
  room_number VARCHAR(50),
  floor_label VARCHAR(30),
  room_type VARCHAR(24) NOT NULL DEFAULT 'OTHER',
  teaching_capacity INTEGER,
  exam_capacity INTEGER,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  origin VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
  version INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (campus_id) REFERENCES campus(id),
  UNIQUE (campus_id,code),
  UNIQUE (campus_id,name),
  UNIQUE (campus_id,building_name,room_number),
  CHECK (room_type IN ('LECTURE','LAB','MULTIPURPOSE','OTHER')),
  CHECK (teaching_capacity IS NULL OR teaching_capacity BETWEEN 1 AND 1000),
  CHECK (exam_capacity IS NULL OR exam_capacity BETWEEN 1 AND 1000),
  CHECK (teaching_capacity IS NULL OR exam_capacity IS NULL OR exam_capacity<=teaching_capacity)
);

-- Existing free-text locations remain distinct; unknown buildings and seats are not inferred.
INSERT INTO classroom_resource(campus_id,code,name,origin)
SELECT campus_id,CONCAT('MIG-',ROW_NUMBER() OVER (ORDER BY campus_id,classroom)),classroom,'MIGRATED'
FROM (SELECT campus_id,classroom FROM teaching_lesson UNION SELECT campus_id,classroom FROM academic_exam) locations;

ALTER TABLE teaching_lesson ADD COLUMN room_id BIGINT;
ALTER TABLE academic_exam ADD COLUMN room_id BIGINT;
UPDATE teaching_lesson SET room_id=(SELECT r.id FROM classroom_resource r WHERE r.campus_id=teaching_lesson.campus_id AND r.name=teaching_lesson.classroom);
UPDATE academic_exam SET room_id=(SELECT r.id FROM classroom_resource r WHERE r.campus_id=academic_exam.campus_id AND r.name=academic_exam.classroom);
ALTER TABLE teaching_lesson ADD CONSTRAINT fk_lesson_room FOREIGN KEY (room_id) REFERENCES classroom_resource(id);
ALTER TABLE academic_exam ADD CONSTRAINT fk_exam_room FOREIGN KEY (room_id) REFERENCES classroom_resource(id);
CREATE INDEX idx_lesson_room_time ON teaching_lesson(room_id,ends_at,starts_at);
CREATE INDEX idx_exam_room_state_time ON academic_exam(room_id,status,ends_at);

CREATE TABLE room_audit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  room_id BIGINT NOT NULL,
  actor_id BIGINT NOT NULL,
  actor_name VARCHAR(100) NOT NULL,
  action VARCHAR(32) NOT NULL,
  room_version INTEGER NOT NULL,
  reason VARCHAR(480) NOT NULL,
  detail VARCHAR(3000) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (room_id) REFERENCES classroom_resource(id),
  FOREIGN KEY (actor_id) REFERENCES app_user(id)
);
CREATE INDEX idx_room_audit_time ON room_audit(room_id,id);
