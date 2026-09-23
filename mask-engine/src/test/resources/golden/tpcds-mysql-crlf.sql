SELECT `mask_email`(`r`.`c_email_address`) AS `c_email_address` FROM (
SELECT c_email_address
FROM customer
LIMIT 1
) AS `r`;

SELECT `mask_phone`(`r`.`c_phone`, 3, 4) AS `c_phone` FROM (
SELECT c_phone
FROM customer
LIMIT 1
) AS `r`;