-- [R1] 混合多语句：包装/透传/CTE 顺序保持
SELECT c_email_address FROM customer LIMIT 1;
SELECT c_first_name FROM customer LIMIT 1;
WITH w AS (SELECT c_phone FROM customer WHERE c_birth_year = 1990) SELECT c_phone FROM w LIMIT 1;
SELECT c_last_name, COUNT(*) AS n FROM customer GROUP BY c_last_name;

-- [R2] 注释：语句间与语句内注释
-- leading comment
SELECT /* inline block
   spans lines */ c_email_address FROM customer LIMIT 1;
