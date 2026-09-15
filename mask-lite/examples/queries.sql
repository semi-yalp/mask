-- mask-lite 示例输入（配合 examples/before-after.md 阅读）
-- 用法: java -jar target/mask-lite.jar --metadata src/test/resources/metadata/lineage.yaml < examples/queries.sql
-- 注意: 同一文件里多条语句会一起改写；任何一条失败（如递归 CTE）整个文件都会报错，
--       所以本文件只放可成功的语句，报错场景见 before-after.md 第四节。

-- 1. 简单命中
SELECT phone FROM crm.public.customer;

-- 2. 部分命中
SELECT id, phone, email FROM crm.public.customer;

-- 3. 星号展开
SELECT * FROM crm.public.customer;

-- 4. 列别名
SELECT phone AS contact FROM crm.public.customer;

-- 5. CTE
WITH active AS (SELECT email FROM crm.public.customer WHERE status = 'active')
SELECT email FROM active;

-- 6. 派生表
SELECT p FROM (SELECT phone AS p FROM crm.public.customer) t;

-- 7. 跨 schema JOIN + WHERE + ORDER BY + LIMIT
SELECT c.phone, m.email FROM crm.public.customer c
JOIN crm.vip.member m ON m.id = c.id
WHERE c.status = 'vip' ORDER BY c.phone DESC LIMIT 5;

-- 8. DISTINCT
SELECT DISTINCT phone FROM crm.public.customer;

-- 9. 聚合（派生血缘）
SELECT count(phone) AS c FROM crm.public.customer;

-- 10. 表达式双命中 tie-break（未起别名的列由包装层命名 mask_col_N，见 before-after.md）
SELECT phone || email FROM crm.public.customer;

-- 10b. 起了别名则保留用户别名
SELECT phone || email AS contact FROM crm.public.customer;

-- 11. 大小写敏感列
SELECT "DisplayName" FROM crm.public.customer;

-- 12. 未引号大写折叠
SELECT PHONE FROM CRM.PUBLIC.CUSTOMER;

-- 13. 一段引用（搜索路径）
SELECT phone FROM customer;

-- 14. 无命中原样返回
SELECT id, name FROM crm.public.customer;

-- 15. 无命中 + 规范化（COUNT / FETCH NEXT）
SELECT status, count(*) FROM crm.public.customer GROUP BY status ORDER BY status LIMIT 5;

-- 16. 脱敏列只在 WHERE
SELECT id FROM crm.public.customer WHERE phone LIKE '138%';

-- 17. 脱敏列只在 ORDER BY
SELECT id FROM crm.public.customer ORDER BY phone;

-- 18. 字符串里的分号/引号
SELECT note FROM crm.public.orders WHERE note = 'it''s;a;b';

-- 19. 带字符串/布尔/浮点参数的策略
SELECT email FROM crm.vip.member;
