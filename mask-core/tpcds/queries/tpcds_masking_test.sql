-- TPC-DS SQL 脱敏改写测试用例
-- 每条语句标注来源：真实 TPC-DS 查询（q79/q4/q10/q21/q38）或 TPC-DS 风格变体
-- 期望行为见 tpcds/EXPECTED.md

-- [S1] TPC-DS q79（适配版）：多层 JOIN + 派生子查询 + 内层 GROUP BY + ORDER BY + LIMIT
-- 期望：c_last_name -> mask_name(r.c_last_name)；其余列原样引用；WHERE/GROUP BY 留在内层
SELECT c_last_name, c_first_name, SUBSTRING(s_city, 1, 30) AS city, ss_ticket_number, amt, profit
FROM (
    SELECT ss_ticket_number, ss_customer_sk, s.s_city AS s_city,
           SUM(ss_coupon_amt) AS amt, SUM(ss_net_profit) AS profit
    FROM store_sales, date_dim, store AS s, household_demographics
    WHERE store_sales.ss_sold_date_sk = date_dim.d_date_sk
      AND store_sales.ss_store_sk = s.s_store_sk
      AND store_sales.ss_hdemo_sk = household_demographics.hd_demo_sk
      AND (date_dim.d_dom BETWEEN 1 AND 15 OR date_dim.d_moy BETWEEN 1 AND 3)
      AND date_dim.d_year IN (2000, 2001, 2002)
      AND s.s_state IN ('SD', 'TN')
      AND household_demographics.hd_dep_count = 4
    GROUP BY ss_ticket_number, ss_customer_sk, s.s_city
) AS dj, customer
WHERE dj.ss_customer_sk = customer.c_customer_sk
ORDER BY c_last_name, c_first_name, city, ss_ticket_number
LIMIT 100;

-- [S2] TPC-DS q4（原结构）：CTE + UNION ALL + CTE 自连接 + 外层 CASE WHEN
-- 期望：customer_id -> mask_hash(r.customer_id, 'sha256')；last_name -> mask_name(r.last_name)；
--       first_name/preferred_cust_flag 原样；CTE 两个分支血缘均落到 customer 基础列
WITH year_total AS (
    SELECT c_customer_id AS customer_id, c_first_name AS first_name, c_last_name AS last_name,
           c_preferred_cust_flag AS preferred_cust_flag, c_birth_country AS birth_country,
           c_login AS login, c_email_address AS email_address, date_dim.d_year AS dyear,
           SUM(ss_ext_list_price - ss_ext_discount_amt) AS year_total, 's' AS sale_type
    FROM customer, store_sales, date_dim
    WHERE c_customer_sk = ss_customer_sk
      AND ss_sold_date_sk = d_date_sk
    GROUP BY c_customer_id, c_first_name, c_last_name, c_preferred_cust_flag,
             c_birth_country, c_login, c_email_address, date_dim.d_year
    UNION ALL
    SELECT c_customer_id, c_first_name, c_last_name, c_preferred_cust_flag, c_birth_country,
           c_login, c_email_address, date_dim.d_year,
           SUM(ws_ext_list_price - ws_ext_discount_amt) AS year_total, 'w' AS sale_type
    FROM customer, web_sales, date_dim
    WHERE c_customer_sk = ws_bill_customer_sk
      AND ws_sold_date_sk = d_date_sk
    GROUP BY c_customer_id, c_first_name, c_last_name, c_preferred_cust_flag,
             c_birth_country, c_login, c_email_address, date_dim.d_year
)
SELECT t_s_secyear.customer_id, t_s_secyear.first_name, t_s_secyear.last_name,
       t_s_secyear.preferred_cust_flag
FROM year_total AS t_s_firstyear, year_total AS t_s_secyear,
     year_total AS t_w_firstyear, year_total AS t_w_secyear
WHERE t_s_secyear.customer_id = t_s_firstyear.customer_id
  AND t_s_firstyear.customer_id = t_w_secyear.customer_id
  AND t_s_firstyear.customer_id = t_w_firstyear.customer_id
  AND t_s_firstyear.sale_type = 's'
  AND t_w_firstyear.sale_type = 'w'
  AND t_s_secyear.sale_type = 's'
  AND t_w_secyear.sale_type = 'w'
  AND t_s_firstyear.dyear = 2001
  AND t_s_secyear.dyear = 2002
  AND t_w_firstyear.dyear = 2001
  AND t_w_secyear.dyear = 2002
  AND t_s_firstyear.year_total > 0
  AND t_w_firstyear.year_total > 0
  AND CASE WHEN t_w_firstyear.year_total > 0
           THEN t_w_secyear.year_total / t_w_firstyear.year_total
           ELSE NULL END
      > CASE WHEN t_s_firstyear.year_total > 0
             THEN t_s_secyear.year_total / t_s_firstyear.year_total
             ELSE NULL END
ORDER BY t_s_secyear.customer_id, t_s_secyear.first_name, t_s_secyear.last_name,
         t_s_secyear.preferred_cust_flag
LIMIT 100;

-- [S3] TPC-DS q10（原结构）：EXISTS 子查询 + GROUP BY + LIMIT
-- 期望：输出列均未配置策略 -> 整条语句原样透传，不加外层包装
SELECT cd_gender, cd_marital_status, cd_education_status, COUNT(*) AS cnt,
       cd_dep_count, cd_dep_employed_count, cd_dep_college_count
FROM customer AS c, customer_address AS ca, customer_demographics AS cd
WHERE c.c_current_addr_sk = ca.ca_address_sk
  AND ca.ca_county IN ('Rush County', 'Toole County', 'Jefferson County',
                       'Dona Ana County', 'Daviess County')
  AND cd.cd_demo_sk = c.c_current_cdemo_sk
  AND EXISTS (
      SELECT 1
      FROM store_sales, date_dim
      WHERE c.c_customer_sk = ss_customer_sk
        AND ss_sold_date_sk = d_date_sk
        AND d_year = 2001
        AND d_moy BETWEEN 4 AND 7)
GROUP BY cd_gender, cd_marital_status, cd_education_status,
         cd_dep_count, cd_dep_employed_count, cd_dep_college_count
ORDER BY cd_gender, cd_marital_status, cd_education_status,
         cd_dep_count, cd_dep_employed_count, cd_dep_college_count
LIMIT 100;

-- [S4] TPC-DS q21（原结构）：聚合上开窗函数 + 外层 CASE 过滤 + SELECT *
-- 期望：输出列均未配置策略 -> 原样透传
SELECT * FROM (
    SELECT i_manufact_id,
           SUM(ss_sales_price) AS sum_sales,
           AVG(SUM(ss_sales_price)) OVER (PARTITION BY i_manufact_id) AS avg_quarterly_sales
    FROM item, store_sales, date_dim, store
    WHERE ss_item_sk = i_item_sk
      AND ss_sold_date_sk = d_date_sk
      AND ss_store_sk = s_store_sk
      AND d_month_seq IN (1200, 1201, 1202, 1203, 1204, 1205, 1206, 1207, 1208, 1209, 1210, 1211)
      AND ((i_category IN ('Books', 'Electronics') AND i_class IN ('computers', 'stereo'))
           OR (i_category IN ('Men') AND i_class IN ('shirts')))
    GROUP BY i_manufact_id, d_qoy
) AS tmp1
WHERE CASE WHEN avg_quarterly_sales > 0
           THEN ABS(sum_sales - avg_quarterly_sales) / avg_quarterly_sales
           ELSE NULL END > 0.1
ORDER BY avg_quarterly_sales, sum_sales, i_manufact_id
LIMIT 100;

-- [S6] TPC-DS q79 风格变体：输出 email/phone，GROUP BY 含被脱敏列
-- 期望：c_email_address -> mask_email(...)；c_phone -> mask_phone(..., 3, 4)；
--       COUNT(*) 无来源原样；order_cnt 别名保留
SELECT c_email_address, c_phone, c_first_name, COUNT(*) AS order_cnt
FROM store_sales, date_dim, customer
WHERE ss_customer_sk = c_customer_sk
  AND ss_sold_date_sk = d_date_sk
  AND d_year = 2002
  AND c_birth_year BETWEEN 1970 AND 1980
GROUP BY c_email_address, c_phone, c_first_name
ORDER BY order_cnt DESC
LIMIT 20;
