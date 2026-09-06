SELECT mask_email(r.c_email_address) AS c_email_address FROM (
SELECT c_email_address
FROM customer
FETCH NEXT 5 ROWS ONLY
) AS r;

SELECT mask_hash(r.c_customer_id, 'sha256') AS c_customer_id FROM (
SELECT tpcds.public.customer.c_customer_id
FROM tpcds.public.customer
FETCH NEXT 5 ROWS ONLY
) AS r;

SELECT mask_email(r.c_email_address) AS c_email_address FROM (
SELECT "c_email_address"
FROM customer
FETCH NEXT 5 ROWS ONLY
) AS r;

SELECT r.c_customer_sk, mask_hash(r.c_customer_id, 'sha256') AS c_customer_id, r.c_current_cdemo_sk, r.c_current_hdemo_sk, r.c_current_addr_sk, r.c_first_name, mask_name(r.c_last_name) AS c_last_name, r.c_preferred_cust_flag, r.c_birth_country, r.c_login, mask_email(r.c_email_address) AS c_email_address, mask_phone(r.c_phone, 3, 4) AS c_phone, r.c_birth_year, r.c_birth_month FROM (
SELECT *
FROM customer
FETCH NEXT 5 ROWS ONLY
) AS r;

SELECT mask_email(r.c_email_address) AS c_email_address, r.c_birth_year FROM (
SELECT c_email_address, c_birth_year
FROM customer
WHERE c_email_address LIKE '%@example.com' AND c_birth_year >= 1980
FETCH NEXT 10 ROWS ONLY
) AS r;

SELECT mask_email(r.low_email) AS low_email, r.c_first_name FROM (
SELECT LOWER(c_email_address) AS low_email, c_first_name
FROM customer
FETCH NEXT 10 ROWS ONLY
) AS r;

SELECT mask_email(r.contact) AS contact FROM (
SELECT c_email_address || ' / ' || c_phone AS contact
FROM customer
FETCH NEXT 10 ROWS ONLY
) AS r;

SELECT mask_email(r.email_cnt) AS email_cnt FROM (
SELECT COUNT(c_email_address) AS email_cnt
FROM store_sales,
customer
WHERE ss_customer_sk = c_customer_sk
) AS r;

SELECT mask_email(r.c_email_address) AS c_email_address, r.cnt FROM (
SELECT c_email_address, COUNT(*) AS cnt
FROM customer
GROUP BY c_email_address
HAVING COUNT(*) > 0
ORDER BY cnt DESC
FETCH NEXT 5 ROWS ONLY
) AS r;

SELECT mask_name(r.c_last_name) AS c_last_name FROM (
SELECT DISTINCT c_last_name
FROM customer
WHERE c_birth_year = 1990
FETCH NEXT 10 ROWS ONLY
) AS r;

SELECT mask_email(r.c_email_address) AS c_email_address, r.c_first_name FROM (
SELECT c_email_address, c_first_name
FROM customer
WHERE c_customer_sk IN (SELECT ss_customer_sk
FROM store_sales,
date_dim
WHERE ss_sold_date_sk = d_date_sk AND d_year = 2002 AND ss_net_paid > 100)
FETCH NEXT 10 ROWS ONLY
) AS r;

SELECT mask_email(r.email_1) AS email_1, mask_email(r.email_2) AS email_2 FROM (
SELECT c1.c_email_address AS email_1, c2.c_email_address AS email_2
FROM customer AS c1
INNER JOIN customer AS c2 ON c1.c_customer_sk = c2.c_customer_sk
FETCH NEXT 10 ROWS ONLY
) AS r;

SELECT mask_email(r.c_email_address) AS c_email_address, mask_name(r.c_last_name) AS c_last_name, r.paid FROM (
WITH store_buyers AS (SELECT ss_customer_sk, SUM(ss_net_paid) AS paid
FROM store_sales,
date_dim
WHERE ss_sold_date_sk = d_date_sk AND d_year = 2002
GROUP BY ss_customer_sk), big_buyers AS (SELECT b.ss_customer_sk AS cust_sk, b.paid AS paid
FROM store_buyers AS b
WHERE b.paid > 100) SELECT c.c_email_address, c.c_last_name, x.paid
FROM big_buyers AS x,
customer AS c
WHERE x.cust_sk = c.c_customer_sk
ORDER BY x.paid DESC
FETCH NEXT 10 ROWS ONLY
) AS r;

SELECT r.flag, mask_email(r.c_email_address) AS c_email_address FROM (
SELECT 1 AS flag, c_email_address
FROM customer
FETCH NEXT 3 ROWS ONLY
) AS r;

SELECT r.ca_city, mask_text(r.ca_street_name) AS ca_street_name FROM (
SELECT ca_city, ca_street_name
FROM customer_address
WHERE ca_state = 'CA'
ORDER BY ca_city
FETCH NEXT 10 ROWS ONLY
) AS r;