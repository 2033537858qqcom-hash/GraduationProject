ALTER TABLE agent_turn ADD COLUMN resolved_context_type VARCHAR(32);
ALTER TABLE agent_turn ADD COLUMN resolved_context_id BIGINT;
ALTER TABLE agent_turn ADD COLUMN payment_quote_json VARCHAR(500);
