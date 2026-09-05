SELECT phone FROM crm.public.customer;

WITH active AS (SELECT email FROM crm.public.customer)
SELECT email FROM active;
