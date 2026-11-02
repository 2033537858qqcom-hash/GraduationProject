ALTER TABLE agent_mission DROP CONSTRAINT ck_agent_mission_kind;
ALTER TABLE agent_mission ADD CONSTRAINT ck_agent_mission_kind CHECK(kind IN ('RESERVATION','REPAIR','LEAVE','CERTIFICATE'));
