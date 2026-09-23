SELECT `mask_email`(`r`.`c_email_address`) AS `c_email_address` FROM (
SELECT c_email_address
FROM customer
LIMIT 1
) AS `r`;

SELECT `mask_name`(`r`.`c_last_name`) AS `c_last_name` FROM (
SELECT c_last_name
FROM customer
LIMIT 1
) AS `r`;