CREATE TABLE payment_bill (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 request_key VARCHAR(80) NOT NULL UNIQUE,
 order_no VARCHAR(64) NOT NULL UNIQUE,
 student_id BIGINT NOT NULL,
 created_by BIGINT NOT NULL,
 title VARCHAR(120) NOT NULL,
 amount_cents BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'UNPAID',
 trade_no VARCHAR(80) UNIQUE,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 paid_at TIMESTAMP,
 FOREIGN KEY (student_id) REFERENCES app_user(id),
 FOREIGN KEY (created_by) REFERENCES app_user(id),
 CHECK (amount_cents > 0),
 CHECK (status IN ('UNPAID','PAID'))
);
CREATE INDEX idx_payment_student ON payment_bill(student_id,id);
