-- TPC-DS 边界与错误场景用例（整体运行会因多语句原子性在首条失败处中止，
-- 须逐条用 --sql 运行；每条应非零退出并给出对应错误码的清晰诊断）

-- [E1] 重复输出别名：期望 REWRITE_ERROR "cannot wrap a query whose output
--      contains duplicate column name 'v'"，不生成可能引用错列的 SQL
SELECT c_phone AS v, c_email_address AS v
FROM customer;

-- [E2] 未声明表：期望 VALIDATION_ERROR "Object 'nosuch_table' not found"
SELECT c_email_address
FROM nosuch_table;

-- [E3] 未声明列：期望 VALIDATION_ERROR "Column 'c_nosuch' not found in any table"
SELECT c_nosuch
FROM customer;

-- [E4] 歧义列引用（customer 与 store_sales 均有 c_customer_sk）：
--      期望 VALIDATION_ERROR 提示列歧义
SELECT c_customer_sk
FROM customer, store_sales;

-- [E5] INSERT：期望 UNSUPPORTED_STATEMENT
INSERT INTO customer (c_customer_sk, c_email_address) VALUES (1, 'a@b.com');

-- [E6] UPDATE：期望 UNSUPPORTED_STATEMENT
UPDATE customer SET c_email_address = 'a@b.com' WHERE c_customer_sk = 1;

-- [E7] DELETE：期望 UNSUPPORTED_STATEMENT
DELETE FROM customer;
