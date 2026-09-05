-- End-to-end fixture: one of each core rewriting semantics
SELECT id, phone, email FROM customer WHERE status = 'ACTIVE';

WITH active AS (SELECT phone FROM customer WHERE status = 'ACTIVE')
SELECT phone FROM active;

SELECT concat(email, '-', phone) AS contact FROM customer;

SELECT id, name FROM customer;
