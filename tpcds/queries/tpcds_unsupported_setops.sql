-- 预期失败用例：根级 INTERSECT / EXCEPT 集合操作，第一版范围外
-- 期望：每条语句返回清晰的 UNSUPPORTED 诊断，不生成部分结果
-- S5 为 TPC-DS q38 变体（根级 INTERSECT），S7 为 TPC-DS 风格变体（根级 EXCEPT）

-- [S5] TPC-DS q38（变体：根级 INTERSECT，去掉外层 count 包裹）
SELECT DISTINCT c_last_name, c_first_name
FROM store_sales, date_dim, customer
WHERE store_sales.ss_sold_date_sk = date_dim.d_date_sk
  AND store_sales.ss_customer_sk = customer.c_customer_sk
  AND date_dim.d_month_seq BETWEEN 1193 AND 1204
  AND customer.c_birth_month BETWEEN 1 AND 3
INTERSECT
SELECT DISTINCT c_last_name, c_first_name
FROM store_sales, date_dim, customer
WHERE store_sales.ss_sold_date_sk = date_dim.d_date_sk
  AND store_sales.ss_customer_sk = customer.c_customer_sk
  AND date_dim.d_month_seq BETWEEN 1205 AND 1216
  AND customer.c_birth_year BETWEEN 1972 AND 1973
LIMIT 100;

-- [S7] TPC-DS 风格变体：根级 EXCEPT，输出 email
SELECT DISTINCT c_email_address
FROM customer, store_sales, date_dim
WHERE ss_customer_sk = c_customer_sk
  AND ss_sold_date_sk = d_date_sk
  AND d_year = 2001
EXCEPT
SELECT DISTINCT c_email_address
FROM customer, store_sales, date_dim
WHERE ss_customer_sk = c_customer_sk
  AND ss_sold_date_sk = d_date_sk
  AND d_year = 2002;
