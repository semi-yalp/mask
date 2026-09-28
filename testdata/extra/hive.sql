-- T12 方言差异语料(hive):反引号标识符、INSERT OVERWRITE、Lenient 限尾。
-- 判定以 jar 实测为准(accept / classify / later 均可,parse 拒绝即 Mismatch)。

-- 反引号标识符(仅 BackTick 方言)
SELECT `c_email_address` FROM customer LIMIT 1;
SELECT `a b` FROM customer LIMIT 1;

-- 大小写折算:未引号转小写、引号内原样
SELECT Foo FROM Bar;
SELECT "Foo" FROM Bar;

-- INSERT OVERWRITE 仅 Lenient 方言语法面
INSERT OVERWRITE TABLE customer SELECT c_email_address FROM customer LIMIT 1;
INSERT OVERWRITE customer SELECT c_email_address FROM customer LIMIT 1;

-- OFFSET..LIMIT / LIMIT start,count 仅 Lenient 接受
SELECT 1 OFFSET 1 LIMIT 2;
SELECT 1 LIMIT 1, 2;

-- WITH RECURSIVE 解析接受(后端拒绝,契约记 later)
WITH RECURSIVE q AS (SELECT 1 AS x) SELECT x FROM q;

-- TOP(n) 全方言关闭
SELECT TOP (5) 1;
