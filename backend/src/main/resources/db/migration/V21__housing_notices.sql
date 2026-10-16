ALTER TABLE user_notice DROP CONSTRAINT ck_notice_source;
ALTER TABLE user_notice ADD CONSTRAINT ck_notice_source CHECK(
 (source_type IS NULL AND source_id IS NULL AND source_view IS NULL) OR
 (source_type IS NOT NULL AND source_id IS NOT NULL AND source_view IS NOT NULL AND
 ((source_type IN ('LEAVE','CERTIFICATE') AND source_view IN ('MINE','REVIEW')) OR
  (source_type='REPAIR' AND source_view IN ('MINE','DISPATCH','WORK')) OR
  (source_type='HOUSING' AND source_view IN ('MINE','REVIEW','ASSIGN'))))
);
