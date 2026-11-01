ALTER TABLE agent_turn ADD COLUMN payment_refs_json VARCHAR(2000);
INSERT INTO role_permission(role_code,permission_code) VALUES
 ('FINANCE_OPERATOR','agent:query'),('FINANCE_REVIEWER','agent:query');
