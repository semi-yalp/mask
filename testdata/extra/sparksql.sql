-- T12 方言差异语料(sparksql):与 hive 同族(BackTick + Lenient + INSERT OVERWRITE)。

SELECT `c_email_address` FROM customer LIMIT 1;
SELECT `a b` FROM customer LIMIT 1;
SELECT Foo FROM Bar;
SELECT "Foo" FROM Bar;
INSERT OVERWRITE TABLE customer SELECT c_email_address FROM customer LIMIT 1;
INSERT OVERWRITE customer SELECT c_email_address FROM customer LIMIT 1;
SELECT 1 OFFSET 1 LIMIT 2;
SELECT 1 LIMIT 1, 2;
WITH RECURSIVE q AS (SELECT 1 AS x) SELECT x FROM q;
SELECT TOP (5) 1;
