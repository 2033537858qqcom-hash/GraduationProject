CREATE TABLE agent_session (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    request_key VARCHAR(80) NOT NULL,
    title VARCHAR(60) NOT NULL DEFAULT '新对话',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_session_user FOREIGN KEY(user_id) REFERENCES app_user(id),
    CONSTRAINT uq_agent_session_key UNIQUE(user_id,request_key)
);
CREATE INDEX ix_agent_session_owner ON agent_session(user_id,updated_at,id);
CREATE TABLE agent_turn (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    request_key VARCHAR(80) NOT NULL,
    question VARCHAR(1000) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    context_type VARCHAR(32),
    context_id BIGINT,
    status VARCHAR(16) NOT NULL,
    run_id VARCHAR(80),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_turn_session FOREIGN KEY(session_id) REFERENCES agent_session(id),
    CONSTRAINT uq_agent_turn_key UNIQUE(session_id,request_key),
    CONSTRAINT ck_agent_turn_status CHECK(status IN ('RUNNING','SUCCESS','FAILED')),
    CONSTRAINT ck_agent_turn_mode CHECK(mode IN ('LOCAL','DEEPSEEK'))
);
CREATE INDEX ix_agent_turn_session ON agent_turn(session_id,id);
CREATE TABLE agent_task (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id BIGINT NOT NULL,
    request_key VARCHAR(80) NOT NULL,
    business_key VARCHAR(80) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    fields_json VARCHAR(8000) NOT NULL,
    stage VARCHAR(16) NOT NULL DEFAULT 'PREVIEW',
    business_id BIGINT,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_agent_task_session FOREIGN KEY(session_id) REFERENCES agent_session(id),
    CONSTRAINT uq_agent_task_key UNIQUE(session_id,request_key),
    CONSTRAINT ck_agent_task_kind CHECK(kind IN ('LEAVE','CERTIFICATE')),
    CONSTRAINT ck_agent_task_stage CHECK((stage='PREVIEW' AND business_id IS NULL) OR (stage='LINKED' AND business_id IS NOT NULL))
);
CREATE INDEX ix_agent_task_session ON agent_task(session_id,id);
