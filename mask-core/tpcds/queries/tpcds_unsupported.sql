-- 预期失败用例：TPC-DS q14 属于 WITH RECURSIVE，第一版明确不支持
-- 期望：整次命令非零退出，stderr 给出清晰的递归 CTE 诊断，不输出部分结果
WITH RECURSIVE cust_total AS (
    SELECT c_customer_sk, SUM(ss_net_paid) AS ctr_total_return
    FROM store_sales
    GROUP BY c_customer_sk
    UNION ALL
    SELECT c_customer_sk, ctr_total_return
    FROM cust_total
)
SELECT ctr_customer_sk
FROM cust_total
WHERE ctr_total_return > 100
LIMIT 10;
