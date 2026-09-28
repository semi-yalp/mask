-- T11 parse-reject 语料:Java(postgresql)在解析期拒绝的形态集合。
-- 每条均为 jar 实测 [PARSE_ERROR];契约中 verdict=parse,守护 Go 词法/解析面。

-- 残片:事务控制词带非法伴随(jar: Encountered "x")
COMMIT x;
BEGIN FOO;
DISCARD x;

-- OFFSET..LIMIT 与 LIMIT start,count 在 Default 档拒绝(jar: '...' is not
-- allowed under the current SQL conformance level)
SELECT 1 OFFSET 1 LIMIT 1;
SELECT 1 LIMIT 1, 2;

-- != 在 Default 档拒绝(jar: Bang equal '!=' is not allowed...)
SELECT 1 != 2;

-- TOP(n) 五方言均关(jar: TOP 相关解析错)
SELECT TOP (5) 1;

-- 空分组集(jar: Incorrect syntax near the keyword 'GROUP')
SELECT 1 GROUP BY ();
