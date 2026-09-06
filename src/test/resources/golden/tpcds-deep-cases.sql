SELECT mask_email(r.email_address) AS email_address, r.first_name FROM (
SELECT innermost.email_address, innermost.first_name
FROM (SELECT mid.email_address, mid.first_name
FROM (SELECT c_email_address AS email_address, c_first_name AS first_name
FROM customer
WHERE c_birth_year >= 1970) AS mid
WHERE mid.first_name IS NOT NULL) AS innermost
FETCH NEXT 10 ROWS ONLY
) AS r;

SELECT mask_phone(r.phone_or_none, 3, 4) AS phone_or_none, r.cohort, mask_email(r.c_email_address) AS c_email_address FROM (
SELECT CASE WHEN c_phone IS NULL THEN 'unknown' ELSE c_phone END AS phone_or_none, CASE WHEN c_birth_year < 1980 THEN 'older' ELSE 'younger' END AS cohort, c_email_address
FROM customer
FETCH NEXT 10 ROWS ONLY
) AS r;

SELECT mask_email(r.x) AS x, mask_phone(r.y, 3, 4) AS y FROM (
WITH contact (x, y) AS (SELECT c_email_address, c_phone
FROM customer
WHERE c_birth_year BETWEEN ASYMMETRIC 1980 AND 1990) SELECT x, y
FROM contact
FETCH NEXT 10 ROWS ONLY
) AS r;

SELECT mask_email(r.e) AS e FROM (
SELECT x.e
FROM (WITH t AS (SELECT c_email_address AS e
FROM customer
WHERE c_birth_year = 1990) SELECT e
FROM t) AS x
FETCH NEXT 5 ROWS ONLY
) AS r;

SELECT mask_email(r.e1) AS e1, mask_phone(r.p2, 3, 4) AS p2 FROM (
SELECT c1.c_email_address AS e1, c2.c_phone AS p2
FROM customer AS c1
INNER JOIN customer AS c2 USING (c_customer_sk)
FETCH NEXT 10 ROWS ONLY
) AS r;

SELECT mask_email(r.c_email_address) AS c_email_address, r.ca_city FROM (
SELECT c.c_email_address, ca.ca_city
FROM customer AS c
LEFT JOIN customer_address AS ca ON c.c_current_addr_sk = ca.ca_address_sk
FETCH NEXT 10 ROWS ONLY
) AS r;

SELECT mask_email(r.c_email_address) AS c_email_address, r.cnt FROM (
SELECT c_email_address, COUNT(*) AS cnt
FROM customer
GROUP BY ROLLUP(c_email_address)
ORDER BY c_email_address NULLS LAST
FETCH NEXT 10 ROWS ONLY
) AS r;

SELECT mask_email(r.custom_masked) AS custom_masked FROM (
SELECT mask_custom(c_email_address) AS custom_masked
FROM customer
FETCH NEXT 5 ROWS ONLY
) AS r;

SELECT mask_email(r.e) AS e, r.c_birth_year FROM (
SELECT LOWER(c_email_address) AS e, c_birth_year
FROM customer
ORDER BY e, c_birth_year
OFFSET 2 ROWS
FETCH NEXT 5 ROWS ONLY
) AS r;

SELECT mask_email(r.c_email_address) AS c_email_address FROM (
SELECT "customer".c_email_address
FROM tpcds."public"."customer"
FETCH NEXT 5 ROWS ONLY
) AS r;