INSERT INTO archive SELECT r.id, mask_phone(r.phone, 3, 4) AS phone FROM (
SELECT id, phone
FROM customer
WHERE status = 'ACTIVE'
) AS r;

CREATE TABLE masked_customer AS SELECT r.id, mask_phone(r.phone, 3, 4) AS phone FROM (
SELECT id, phone
FROM customer
) AS r;

CREATE TABLE IF NOT EXISTS t3 AS SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
SELECT phone
FROM customer
) AS r;

SELECT id, name
FROM customer
ORDER BY id
FETCH NEXT 10 ROWS ONLY;