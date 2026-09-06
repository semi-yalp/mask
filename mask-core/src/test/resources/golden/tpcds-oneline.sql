SELECT mask_email(r.c_email_address) AS c_email_address FROM (
SELECT c_email_address
FROM customer
FETCH NEXT 1 ROWS ONLY
) AS r;

SELECT mask_name(r.c_last_name) AS c_last_name FROM (
SELECT c_last_name
FROM customer
FETCH NEXT 1 ROWS ONLY
) AS r;