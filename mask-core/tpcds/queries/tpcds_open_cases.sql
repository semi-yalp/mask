-- TPC-DS 第三轮：行为待分类用例（先逐条运行观察，再归入成功或预期失败）

-- [D2] SELECT 列表中的相关标量子查询：
--       血缘可能为 UNKNOWN（应清晰失败）或可解析（应正常处理），记录实际行为
SELECT c.c_email_address,
       (SELECT COUNT(*) FROM store_sales WHERE ss_customer_sk = c.c_customer_sk) AS order_cnt
FROM customer AS c
LIMIT 10;

-- [D4] 窗口函数：RN 按无策略列排序；RNK 按 c_email_address 分区，
--       记录窗口输出列的血缘与脱敏行为
SELECT c_email_address,
       ROW_NUMBER() OVER (ORDER BY c_birth_year) AS rn,
       RANK() OVER (PARTITION BY c_email_address ORDER BY c_birth_year) AS rnk
FROM customer
LIMIT 10;
