INSERT INTO role_permission(role_code,permission_code)
SELECT 'DORM_MANAGER','agent:query'
WHERE NOT EXISTS (
    SELECT 1 FROM role_permission WHERE role_code='DORM_MANAGER' AND permission_code='agent:query'
);

ALTER TABLE user_notice DROP CONSTRAINT ck_notice_source;
ALTER TABLE user_notice ADD CONSTRAINT ck_notice_source CHECK(
 (source_type IS NULL AND source_id IS NULL AND source_view IS NULL) OR
 (source_type IS NOT NULL AND source_id IS NOT NULL AND source_view IS NOT NULL AND
 ((source_type IN ('LEAVE','CERTIFICATE') AND source_view IN ('MINE','REVIEW')) OR
  (source_type='REPAIR' AND source_view IN ('MINE','DISPATCH','WORK')) OR
  (source_type='HOUSING' AND source_view IN ('MINE','REVIEW','ASSIGN')) OR
  (source_type='RESERVATION' AND source_view IN ('MINE','REVIEW','MANAGE')) OR
  (source_type='PAYMENT' AND source_view IN ('MINE','FINANCE')) OR
  (source_type='PAYMENT_BASIS' AND source_view IN ('SOURCE','FINANCE')) OR
  (source_type='CONTENT' AND source_view IN ('EDIT','REVIEW')) OR
  (source_type='TEACHING' AND source_view IN ('TEACH','LEARN')) OR
  (source_type='COUNSELOR' AND source_view='REVIEW')))
);
