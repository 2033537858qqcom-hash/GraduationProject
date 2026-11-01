ALTER TABLE payment_category ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE payment_category ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
ALTER TABLE payment_category ADD COLUMN request_key VARCHAR(80);
ALTER TABLE payment_category ADD CONSTRAINT uq_payment_category_request UNIQUE(request_key);
ALTER TABLE payment_category ADD CONSTRAINT ck_payment_category_version CHECK(version>=0);

ALTER TABLE payment_standard ADD COLUMN effective_from DATE;
ALTER TABLE payment_standard ADD COLUMN effective_to DATE;
ALTER TABLE payment_standard ADD CONSTRAINT ck_payment_standard_dates CHECK(effective_from IS NULL OR effective_to IS NULL OR effective_from<=effective_to);
