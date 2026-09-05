-- TPC-DS 场景补充用例：常见 SQL 形态（全部应成功改写或透传）
-- 每条标注 [Cn] 与期望；配合 tpcds/metadata.yaml 运行

-- [C1] 单表直查脱敏列：期望 c_email_address -> mask_email(r.c_email_address)
SELECT c_email_address
FROM customer
LIMIT 5;

-- [C2] 全限定三段名：期望 mask_hash(r.c_customer_id, 'sha256') AS c_customer_id
SELECT tpcds.public.customer.c_customer_id
FROM tpcds.public.customer
LIMIT 5;

-- [C3] 双引号标识符（小写与声明一致）：期望 mask_email(r.c_email_address)
SELECT "c_email_address"
FROM customer
LIMIT 5;

-- [C4] SELECT * 且表含脱敏列：期望展开后 c_email_address/c_phone/c_last_name/c_customer_id
--      各自包对应 UDF，其余列原样，输出顺序与表声明一致
SELECT *
FROM customer
LIMIT 5;

-- [C5] WHERE 引用脱敏列：期望 WHERE 保持明文在内层；外层仅 c_email_address 脱敏
SELECT c_email_address, c_birth_year
FROM customer
WHERE c_email_address LIKE '%@example.com'
  AND c_birth_year >= 1980
LIMIT 10;

-- [C6] 单来源派生列：期望 LOWER(c_email_address) 血缘命中 c_email_address -> mask_email(r.low_email)
SELECT LOWER(c_email_address) AS low_email, c_first_name
FROM customer
LIMIT 10;

-- [C7] 多来源派生列：c_email_address || c_phone -> 按 ColumnKey 字典序
--      c_email_address < c_phone，期望选择 mask_email
SELECT c_email_address || ' / ' || c_phone AS contact
FROM customer
LIMIT 10;

-- [C8] 聚合引用脱敏列：按设计（§3.2）对外层聚合结果整体套 UDF，
--      期望 mask_email(r.email_cnt) AS email_cnt
SELECT COUNT(c_email_address) AS email_cnt
FROM store_sales, customer
WHERE ss_customer_sk = c_customer_sk;

-- [C9] GROUP BY + HAVING + ORDER BY：期望 c_email_address 脱敏、cnt 原样、HAVING 留在内层
SELECT c_email_address, COUNT(*) AS cnt
FROM customer
GROUP BY c_email_address
HAVING COUNT(*) > 0
ORDER BY cnt DESC
LIMIT 5;

-- [C10] DISTINCT：期望 mask_name(r.c_last_name) AS c_last_name
SELECT DISTINCT c_last_name
FROM customer
WHERE c_birth_year = 1990
LIMIT 10;

-- [C11] IN (SELECT ...) 子查询：期望子查询保持明文在内层，
--       外层 c_email_address 脱敏
SELECT c_email_address, c_first_name
FROM customer
WHERE c_customer_sk IN (
    SELECT ss_customer_sk
    FROM store_sales, date_dim
    WHERE ss_sold_date_sk = d_date_sk AND d_year = 2002 AND ss_net_paid > 100
)
LIMIT 10;

-- [C12] 自连接两个别名：期望两个输出列都命中 customer.c_email_address -> 各包一次 mask_email
SELECT c1.c_email_address AS email_1, c2.c_email_address AS email_2
FROM customer AS c1
JOIN customer AS c2 ON c1.c_customer_sk = c2.c_customer_sk
LIMIT 10;

-- [C13] 多层 CTE 链（q2 风格，big_buyers 引用 store_buyers）：
--       期望 c_email_address -> mask_email、c_last_name -> mask_name、paid 原样
WITH store_buyers AS (
    SELECT ss_customer_sk, SUM(ss_net_paid) AS paid
    FROM store_sales, date_dim
    WHERE ss_sold_date_sk = d_date_sk AND d_year = 2002
    GROUP BY ss_customer_sk
), big_buyers AS (
    SELECT b.ss_customer_sk AS cust_sk, b.paid AS paid
    FROM store_buyers AS b
    WHERE b.paid > 100
)
SELECT c.c_email_address, c.c_last_name, x.paid
FROM big_buyers AS x, customer AS c
WHERE x.cust_sk = c.c_customer_sk
ORDER BY x.paid DESC
LIMIT 10;

-- [C14] 常量列与脱敏列混排：期望 r.flag 原样（无来源）、c_email_address 脱敏
SELECT 1 AS flag, c_email_address
FROM customer
LIMIT 3;

-- [C15] customer_address 的 mask_text 策略：期望 ca_street_name -> mask_text(r.ca_street_name)
SELECT ca_city, ca_street_name
FROM customer_address
WHERE ca_state = 'CA'
ORDER BY ca_city
LIMIT 10;
