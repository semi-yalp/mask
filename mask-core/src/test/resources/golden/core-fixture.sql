SELECT r.id, mask_phone(r.phone, 3, 4) AS phone, mask_email(r.email) AS email FROM (
SELECT id, phone, email
FROM customer
WHERE status = 'ACTIVE'
) AS r;

SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
WITH active AS (SELECT phone
FROM customer
WHERE status = 'ACTIVE') SELECT phone
FROM active
) AS r;

SELECT mask_email(r.contact) AS contact FROM (
SELECT concat(email, '-', phone) AS contact
FROM customer
) AS r;

SELECT id, name
FROM customer;