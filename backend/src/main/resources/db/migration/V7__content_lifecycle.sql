-- Preserve the original three-state constraint; workflow_status is the lifecycle authority.
ALTER TABLE website_content ADD COLUMN workflow_status VARCHAR(24) NOT NULL DEFAULT 'DRAFT';
ALTER TABLE website_content ADD COLUMN review_reason VARCHAR(500);
ALTER TABLE website_content ADD COLUMN change_reason VARCHAR(500);
ALTER TABLE website_content ADD CONSTRAINT ck_content_workflow CHECK (workflow_status IN ('DRAFT','IN_REVIEW','PUBLISHED','REJECTED','WITHDRAWN'));
UPDATE website_content SET workflow_status=status;
CREATE TABLE content_publication (
  content_id BIGINT PRIMARY KEY, slug VARCHAR(80) NOT NULL UNIQUE, category VARCHAR(32) NOT NULL,
  title VARCHAR(160) NOT NULL, summary VARCHAR(400) NOT NULL, body TEXT NOT NULL,
  source_url VARCHAR(1000), source_type VARCHAR(24) NOT NULL, version INTEGER NOT NULL,
  published_at TIMESTAMP NOT NULL, visible BOOLEAN NOT NULL DEFAULT TRUE,
  FOREIGN KEY (content_id) REFERENCES website_content(id)
);
INSERT INTO content_publication(content_id,slug,category,title,summary,body,source_url,source_type,version,published_at)
SELECT id,slug,category,title,summary,body,source_url,source_type,version,COALESCE(published_at,updated_at)
FROM website_content WHERE status='PUBLISHED';
CREATE INDEX idx_publication_visible ON content_publication(visible,category,published_at);
ALTER TABLE content_audit ADD COLUMN reason VARCHAR(500) NOT NULL DEFAULT '既有操作';
ALTER TABLE content_audit ADD COLUMN actor_name VARCHAR(100);
UPDATE content_audit SET actor_name=(SELECT full_name FROM app_user WHERE id=content_audit.actor_id);
CREATE INDEX idx_content_audit_version ON content_audit(content_id,content_version);
CREATE TABLE content_revision (
  content_id BIGINT NOT NULL, version INTEGER NOT NULL, slug VARCHAR(80) NOT NULL, category VARCHAR(32) NOT NULL,
  title VARCHAR(160) NOT NULL, summary VARCHAR(400) NOT NULL, body TEXT NOT NULL,
  source_url VARCHAR(1000), source_type VARCHAR(24) NOT NULL, workflow_status VARCHAR(24) NOT NULL,
  created_by BIGINT, published_at TIMESTAMP, review_reason VARCHAR(500), change_reason VARCHAR(500),
  action VARCHAR(32) NOT NULL, reason VARCHAR(500) NOT NULL, actor_name VARCHAR(100) NOT NULL,
  recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (content_id,version), FOREIGN KEY (content_id) REFERENCES website_content(id)
);
INSERT INTO content_revision(content_id,version,slug,category,title,summary,body,source_url,source_type,workflow_status,created_by,published_at,review_reason,change_reason,action,reason,actor_name,recorded_at)
SELECT id,version,slug,category,title,summary,body,source_url,source_type,workflow_status,created_by,published_at,review_reason,change_reason,
       'BASELINE','迁移时保留当前版本，早期全文不可还原','迁移前记录',updated_at FROM website_content;
