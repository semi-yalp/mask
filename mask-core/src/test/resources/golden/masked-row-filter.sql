SELECT r.id, mask_phone(r.phone, 3, 4) AS phone FROM (
SELECT c.id, c.phone
FROM (SELECT *
FROM crm.public.customer
WHERE status = 'active') AS c
WHERE c.phone = '13800138000'
) AS r;

SELECT r.id, mask_phone(r.phone, 3, 4) AS phone, mask_email(r.email) AS email, r.status FROM (
SELECT *
FROM (SELECT *
FROM crm.public.customer
WHERE status = 'active') AS customer
) AS r;

SELECT r.region, r.cnt, mask_amount(r.total) AS total FROM (
SELECT region, COUNT(*) AS cnt, SUM(amount) AS total
FROM (SELECT *
FROM crm.public.orders
WHERE region = 'north') AS orders
GROUP BY region
) AS r;

SELECT mask_phone(r.phone, 3, 4) AS phone, mask_amount(r.amount) AS amount FROM (
SELECT c.phone, o.amount
FROM (SELECT *
FROM crm.public.customer
WHERE status = 'active') AS c
INNER JOIN (SELECT *
FROM crm.public.orders
WHERE region = 'north') AS o ON o.customer_id = c.id
) AS r;

SELECT mask_phone(r.p1, 3, 4) AS p1, mask_phone(r.p2, 3, 4) AS p2 FROM (
SELECT c1.phone AS p1, c2.phone AS p2
FROM (SELECT *
FROM crm.public.customer
WHERE status = 'active') AS c1
INNER JOIN (SELECT *
FROM crm.public.customer
WHERE status = 'active') AS c2 ON c1.id = c2.id
) AS r;

SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
WITH active AS (SELECT phone
FROM (SELECT *
FROM crm.public.customer
WHERE status = 'active') AS customer
WHERE email IS NOT NULL) SELECT phone
FROM active
) AS r;

SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
SELECT phone
FROM (SELECT phone
FROM (SELECT *
FROM crm.public.customer
WHERE status = 'active') AS customer
UNION ALL
SELECT phone
FROM (SELECT *
FROM crm.public.customer
WHERE status = 'active') AS customer) AS t
) AS r;

SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
SELECT phone
FROM (SELECT *
FROM crm.public.customer
WHERE status = 'active') AS customer
WHERE id < 100
ORDER BY phone
FETCH NEXT 5 ROWS ONLY
) AS r;

INSERT INTO archive SELECT r.id, mask_phone(r.phone, 3, 4) AS phone FROM (
SELECT c.id, c.phone
FROM (SELECT *
FROM crm.public.customer
WHERE status = 'active') AS c
) AS r;

CREATE TABLE IF NOT EXISTS archive AS SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
SELECT phone
FROM (SELECT *
FROM crm.public.customer
WHERE status = 'active') AS customer
) AS r;

SELECT 1 AS constant;

SELECT action
FROM (SELECT *
FROM crm.public.audit
WHERE action <> 'secret') AS audit;