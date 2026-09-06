SELECT mask_email(r.c_email_address) AS c_email_address FROM (
SELECT c_email_address
FROM customer
FETCH NEXT 1 ROWS ONLY
) AS r;

SELECT mask_phone(r.c_phone, 3, 4) AS c_phone FROM (
SELECT c_phone
FROM customer
FETCH NEXT 1 ROWS ONLY
) AS r;