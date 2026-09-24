SELECT mask_email(r.c_email_address) AS c_email_address FROM (
SELECT c_email_address
FROM customer
FETCH NEXT 1 ROWS ONLY
) AS r;

SELECT c_first_name
FROM customer
FETCH NEXT 1 ROWS ONLY;

SELECT mask_phone(r.c_phone, 3, 4) AS c_phone FROM (
WITH w AS (SELECT c_phone
FROM customer
WHERE c_birth_year = 1990) SELECT c_phone
FROM w
FETCH NEXT 1 ROWS ONLY
) AS r;

SELECT mask_name(r.c_last_name) AS c_last_name, r.n FROM (
SELECT c_last_name, COUNT(*) AS n
FROM customer
GROUP BY c_last_name
) AS r;

SELECT mask_email(r.c_email_address) AS c_email_address FROM (
SELECT c_email_address
FROM customer
FETCH NEXT 1 ROWS ONLY
) AS r;