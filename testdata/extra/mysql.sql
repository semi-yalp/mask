-- T12 方言差异语料(mysql):反引号、MySQL5 conformance 接受面。

SELECT `c_email_address` FROM customer LIMIT 1;
SELECT `a b` FROM customer LIMIT 1;
SELECT Foo FROM Bar;
SELECT "Foo" FROM Bar;

-- != 在 MySQL5 接受(Default 拒)
SELECT 1 != 2;
SELECT 1 LIMIT 1, 2;

-- INSERT OVERWRITE 非 Lenient 方言:解析期拒绝( Go 应同为 PARSE_ERROR )
INSERT OVERWRITE TABLE customer SELECT c_email_address FROM customer LIMIT 1;

WITH RECURSIVE q AS (SELECT 1 AS x) SELECT x FROM q;
SELECT TOP (5) 1;
