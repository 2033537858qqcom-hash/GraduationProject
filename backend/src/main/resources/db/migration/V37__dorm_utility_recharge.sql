CREATE TABLE dorm_utility_meter (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 room_id BIGINT NOT NULL,
 category_code VARCHAR(32) NOT NULL,
 meter_code VARCHAR(64) NOT NULL,
 standard_id BIGINT NOT NULL,
 balance_quantity DECIMAL(22,9) NOT NULL DEFAULT 0,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version INTEGER NOT NULL DEFAULT 0,
 UNIQUE(room_id,category_code),
 UNIQUE(meter_code),
 FOREIGN KEY(room_id) REFERENCES dorm_room(id),
 FOREIGN KEY(standard_id) REFERENCES payment_standard(id),
 CHECK(category_code IN ('WATER','ELECTRICITY')),
 CHECK(balance_quantity>=0),
 CHECK(version>=0)
);

CREATE TABLE dorm_utility_recharge (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 request_key VARCHAR(80) NOT NULL UNIQUE,
 meter_id BIGINT NOT NULL,
 bill_id BIGINT NOT NULL UNIQUE,
 payer_id BIGINT NOT NULL,
 unit_price_cents BIGINT NOT NULL,
 amount_cents BIGINT NOT NULL,
 quantity DECIMAL(22,9) NOT NULL,
 input_mode VARCHAR(16) NOT NULL,
 input_value VARCHAR(40) NOT NULL,
 credited_at TIMESTAMP,
 reversed_at TIMESTAMP,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(meter_id) REFERENCES dorm_utility_meter(id),
 FOREIGN KEY(bill_id) REFERENCES payment_bill(id),
 FOREIGN KEY(payer_id) REFERENCES app_user(id),
 CHECK(unit_price_cents>0),
 CHECK(amount_cents>0),
 CHECK(quantity>0),
 CHECK(input_mode IN ('AMOUNT','QUANTITY'))
);
CREATE INDEX idx_dorm_recharge_meter ON dorm_utility_recharge(meter_id,id);
