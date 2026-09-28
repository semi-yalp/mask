-- T12 方言差异语料(trino):双引号标识符、Default conformance 拒绝面。

SELECT "c_email_address" FROM customer LIMIT 1;
SELECT "a b" FROM customer LIMIT 1;
SELECT Foo FROM Bar;
SELECT `c_email_address` FROM customer LIMIT 1;

-- != 在 Default 拒(解析期)
SELECT 1 != 2;
SELECT 1 OFFSET 1 LIMIT 2;
SELECT 1 LIMIT 1, 2;

WITH RECURSIVE q AS (SELECT 1 AS x) SELECT x FROM q;
SELECT TOP (5) 1;
CREATE TABLE tpcds.public.newt AS SELECT 1;
