ALTER TABLE agent_mission DROP CONSTRAINT ck_agent_mission_kind;
ALTER TABLE agent_mission ADD CONSTRAINT ck_agent_mission_kind CHECK(kind IN ('RESERVATION','REPAIR','LEAVE','CERTIFICATE','HOUSING'));

CREATE TABLE agent_feedback (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  run_id VARCHAR(36) NOT NULL,
  rating VARCHAR(16) NOT NULL,
  comment VARCHAR(300),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (user_id) REFERENCES app_user(id),
  FOREIGN KEY (run_id) REFERENCES agent_run(id) ON DELETE CASCADE,
  UNIQUE (user_id, run_id),
  CHECK (rating IN ('UP', 'DOWN'))
);
CREATE INDEX idx_agent_feedback_run ON agent_feedback(run_id, rating);
