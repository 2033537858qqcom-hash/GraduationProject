CREATE TABLE course_material_revision (
  material_id BIGINT NOT NULL,
  version INTEGER NOT NULL,
  title VARCHAR(160) NOT NULL,
  body TEXT NOT NULL,
  status VARCHAR(24) NOT NULL,
  file_name VARCHAR(160),
  file_type VARCHAR(100),
  file_bytes MEDIUMBLOB,
  captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (material_id,version),
  FOREIGN KEY (material_id) REFERENCES course_material(id)
);
INSERT INTO course_material_revision(material_id,version,title,body,status,file_name,file_type,file_bytes)
SELECT id,version,title,body,status,file_name,file_type,file_bytes FROM course_material;
