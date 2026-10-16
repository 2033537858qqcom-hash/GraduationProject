CREATE TABLE housing_material (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 transfer_id BIGINT NOT NULL,
 file_name VARCHAR(160) NOT NULL,
 file_type VARCHAR(80) NOT NULL,
 file_size INTEGER NOT NULL,
 file_sha256 VARCHAR(64) NOT NULL,
 file_bytes MEDIUMBLOB NOT NULL,
 uploaded_by BIGINT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 removed_at TIMESTAMP,
 FOREIGN KEY(transfer_id) REFERENCES housing_transfer(id),
 FOREIGN KEY(uploaded_by) REFERENCES app_user(id),
 UNIQUE(transfer_id,id),
 CHECK(file_size>0 AND file_size<=2097152),
 CHECK(file_type IN ('application/pdf','image/png','image/jpeg'))
);
CREATE TABLE housing_material_submission (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 transfer_id BIGINT NOT NULL,
 revision_number INTEGER NOT NULL,
 request_version INTEGER NOT NULL,
 reason VARCHAR(500) NOT NULL,
 preferred_area VARCHAR(200) NOT NULL,
 material_note VARCHAR(500) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(transfer_id) REFERENCES housing_transfer(id),
 UNIQUE(transfer_id,revision_number),
 UNIQUE(transfer_id,request_version),
 UNIQUE(transfer_id,id),
 CHECK(revision_number>0 AND request_version>0)
);
CREATE TABLE housing_material_snapshot (
 transfer_id BIGINT NOT NULL,
 submission_id BIGINT NOT NULL,
 material_id BIGINT NOT NULL,
 PRIMARY KEY(submission_id,material_id),
 FOREIGN KEY(transfer_id,submission_id) REFERENCES housing_material_submission(transfer_id,id),
 FOREIGN KEY(transfer_id,material_id) REFERENCES housing_material(transfer_id,id)
);
CREATE INDEX idx_housing_material_current ON housing_material(transfer_id,removed_at,id);
CREATE INDEX idx_housing_material_submission ON housing_material_submission(transfer_id,id);

UPDATE agent_guide SET body=REPLACE(body,
 '材料仅为文字，本批不上传附件。',
 '先保存草稿，可上传 PDF、JPEG 或 PNG 材料，每份不超过 2 MB，当前最多 3 份。草稿及待补交阶段可增删；提交和补交冻结当次材料清单，旧版本保留。材料仅供本人和当前有权初审的学院人员下载，宿管不读取附件。'),
 version=version+1,updated_at=CURRENT_TIMESTAMP
WHERE slug='housing' AND body LIKE '%材料仅为文字，本批不上传附件。%';
