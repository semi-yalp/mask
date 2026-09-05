-- TPC-DS 第三轮：深层结构用例（每条标注 [Dn] 与期望）

-- [D1] 三层嵌套派生表，脱敏列穿过三层重命名：期望 mask_email(r.email_address)
SELECT innermost.email_address, innermost.first_name
FROM (
    SELECT mid.email_address, mid.first_name
    FROM (
        SELECT c_email_address AS email_address, c_first_name AS first_name
        FROM customer
        WHERE c_birth_year >= 1970
    ) AS mid
    WHERE mid.first_name IS NOT NULL
) AS innermost
LIMIT 10;

-- [D3] CASE WHEN：phone 分支来源 c_phone -> 整列套 mask_phone；
--      纯字面量 CASE 无来源 -> 原样；email 直接列 -> mask_email
SELECT CASE WHEN c_phone IS NULL THEN 'unknown' ELSE c_phone END AS phone_or_none,
       CASE WHEN c_birth_year < 1980 THEN 'older' ELSE 'younger' END AS cohort,
       c_email_address
FROM customer
LIMIT 10;

-- [D5] CTE 带列名列表 (x, y)：期望 x -> mask_email、y -> mask_phone（血缘穿过重命名）
WITH contact(x, y) AS (
    SELECT c_email_address, c_phone
    FROM customer
    WHERE c_birth_year BETWEEN 1980 AND 1990
)
SELECT x, y
FROM contact
LIMIT 10;

-- [D6] FROM 子查询内带 WITH：期望 mask_email(r.e) AS e，内层 WITH 完整保留
SELECT x.e
FROM (
    WITH t AS (SELECT c_email_address AS e FROM customer WHERE c_birth_year = 1990)
    SELECT e FROM t
) AS x
LIMIT 5;

-- [D7] USING 自连接：期望 e1 -> mask_email、p2 -> mask_phone，各包一次
SELECT c1.c_email_address AS e1, c2.c_phone AS p2
FROM customer AS c1
JOIN customer AS c2 USING (c_customer_sk)
LIMIT 10;

-- [D8] LEFT JOIN：期望 mask_email(r.c_email_address)、r.ca_city 原样
SELECT c.c_email_address, ca.ca_city
FROM customer AS c
LEFT JOIN customer_address AS ca ON c.c_current_addr_sk = ca.ca_address_sk
LIMIT 10;

-- [D9] GROUP BY ROLLUP 含脱敏列：期望 mask_email(r.c_email_address)、r.cnt 原样
SELECT c_email_address, COUNT(*) AS cnt
FROM customer
GROUP BY ROLLUP (c_email_address)
ORDER BY c_email_address NULLS LAST
LIMIT 10;

-- [D10] 引擎侧未知函数 mask_custom：工具不校验 UDF 有效性，
--       期望输出列按来源 c_email_address 套 mask_email
SELECT mask_custom(c_email_address) AS custom_masked
FROM customer
LIMIT 5;

-- [D11] ORDER BY 别名 + OFFSET + FETCH：期望 mask_email(r.e) AS e，内层子句完整保留
SELECT LOWER(c_email_address) AS e, c_birth_year
FROM customer
ORDER BY e, c_birth_year
OFFSET 2 ROWS FETCH NEXT 5 ROWS ONLY;

-- [D12] 全引号限定名（小写与声明一致）：期望 mask_email(r.c_email_address)
SELECT "customer".c_email_address
FROM tpcds."public"."customer"
LIMIT 5;
