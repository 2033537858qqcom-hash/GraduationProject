CREATE TABLE payment_category (
 code VARCHAR(32) PRIMARY KEY,
 name VARCHAR(40) NOT NULL,
 description VARCHAR(160) NOT NULL,
 sort_order INTEGER NOT NULL
);

INSERT INTO payment_category(code,name,description,sort_order) VALUES
 ('TUITION','学费','按学年或学期收取的学习费用。',10),
 ('ACCOMMODATION','住宿费','学生宿舍住宿费用。',20),
 ('WATER','水费','单独结算的用水费用。',30),
 ('ELECTRICITY','电费','单独结算的用电费用。',40),
 ('UTILITIES','水电费','合并结算的水电费用。',50),
 ('TEXTBOOK','教材费','教材及学习资料费用。',60),
 ('VENUE','场地使用费','场地或活动空间使用费用。',70),
 ('OTHER','其他费用','其他已明确名称和金额的费用；原有账单归入此类。',80);

ALTER TABLE payment_bill ADD COLUMN category_code VARCHAR(32) NOT NULL DEFAULT 'OTHER';
ALTER TABLE payment_bill ADD CONSTRAINT fk_payment_bill_category
 FOREIGN KEY (category_code) REFERENCES payment_category(code);
