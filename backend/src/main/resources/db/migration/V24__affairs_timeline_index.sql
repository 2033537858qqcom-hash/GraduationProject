-- Timeline queries and history pagination use the existing operation records.
CREATE INDEX idx_housing_action_timeline ON housing_transfer_action(transfer_id,created_at,id);
