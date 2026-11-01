CREATE TABLE payment_reconciliation_batch (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 request_key VARCHAR(80) NOT NULL UNIQUE,
 created_by BIGINT NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(created_by) REFERENCES app_user(id)
);
CREATE TABLE payment_reconciliation_case (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 batch_id BIGINT NOT NULL,
 line_number INTEGER NOT NULL,
 result VARCHAR(24) NOT NULL,
 order_no VARCHAR(96) NOT NULL,
 statement_amount VARCHAR(40) NOT NULL,
 statement_trade_no VARCHAR(96) NOT NULL,
 statement_status VARCHAR(80) NOT NULL,
 bill_id BIGINT,
 local_amount_cents BIGINT,
 local_status VARCHAR(32) NOT NULL,
 explanation VARCHAR(500) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
 assignee_id BIGINT,
 last_note VARCHAR(500) NOT NULL DEFAULT '',
 version INTEGER NOT NULL DEFAULT 0,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(batch_id) REFERENCES payment_reconciliation_batch(id),
 FOREIGN KEY(bill_id) REFERENCES payment_bill(id),
 FOREIGN KEY(assignee_id) REFERENCES app_user(id),
 UNIQUE(batch_id,line_number),
 CHECK(result IN ('DIFFERENCE','NOT_FOUND','INVALID','DUPLICATE')),
 CHECK(status IN ('OPEN','IN_PROGRESS','PENDING_REVIEW','CLOSED')),
 CHECK(version>=0)
);
CREATE INDEX idx_reconciliation_case_status ON payment_reconciliation_case(status,id);
