-- [W1] INSERT INTO + 列名列表 + SELECT：期望源查询按血缘包装后重组成 INSERT INTO ... SELECT mask_email(r.c_email_address) ...
INSERT INTO customer (c_customer_sk, c_email_address, c_birth_year)
SELECT ss_customer_sk, c_email_address, c_birth_year
FROM store_sales, customer
WHERE ss_customer_sk = c_customer_sk AND d_year IS NULL
  AND ss_sold_date_sk IS NULL
LIMIT 5;

-- [W2] INSERT INTO 不带列名列表 + SELECT *：期望按 customer 列序展开并各自脱敏
INSERT INTO customer
SELECT * FROM customer WHERE c_birth_year = 1990 LIMIT 3;

-- [W3] INSERT 纯字面量 VALUES：无基础列来源，期望原样透传
INSERT INTO customer (c_customer_sk, c_email_address) VALUES (1, 'a@b.com');

-- [W5] CREATE TABLE AS SELECT 含脱敏列：期望 CREATE TABLE ... AS SELECT mask_email(...) FROM (<orig>) AS r
CREATE TABLE contact_backup AS
SELECT c_email_address, c_last_name, c_birth_year
FROM customer WHERE c_birth_year >= 1990 LIMIT 20;

-- [W6] CREATE TABLE IF NOT EXISTS AS SELECT：期望同 W5 且保留 IF NOT EXISTS
CREATE TABLE IF NOT EXISTS contact_backup2 AS
SELECT c_email_address FROM customer LIMIT 5;
