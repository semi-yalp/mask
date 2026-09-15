# mask-lite 改写前后对照（真实运行输出）

以下全部为 `mvn package` 后的真实输出，非手写期望。元数据见
`src/test/resources/metadata/lineage.yaml`，命中策略：

| 列 | 策略 | UDF 调用 |
| --- | --- | --- |
| `crm.public.customer.phone` | phone_mask | `mask_phone(x, 3, 4)` |
| `crm.public.customer.email` | email_mask | `mask_email(x)` |
| `crm.public.customer."DisplayName"` | display_name_mask | `mask_name(x, '*')` |
| `crm.vip.member.phone` | phone_mask | `mask_phone(x, 3, 4)` |
| `crm.vip.member.email` | partial_mask | `partial_mask(x, '@corp', TRUE, 2.5)` |

复现方式：

```bash
# 单条
java -jar target/mask-lite.jar \
    --metadata src/test/resources/metadata/lineage.yaml --sql 'SELECT phone FROM crm.public.customer'

# 整个示例文件（examples/queries.sql 即下述 1–19 的输入）
java -jar target/mask-lite.jar \
    --metadata src/test/resources/metadata/lineage.yaml < examples/queries.sql
```

## 一、命中脱敏：原查询整体作内层，外层只动投影

### 1. 简单命中

```sql
-- 前
SELECT phone FROM crm.public.customer

-- 后
SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
SELECT phone
FROM crm.public.customer
) AS r;
```

### 2. 部分命中（无策略列直通为 r.<col>）

```sql
-- 前
SELECT id, phone, email FROM crm.public.customer

-- 后
SELECT r.id, mask_phone(r.phone, 3, 4) AS phone, mask_email(r.email) AS email FROM (
SELECT id, phone, email
FROM crm.public.customer
) AS r;
```

### 3. 星号展开，逐列判定

```sql
-- 前
SELECT * FROM crm.public.customer

-- 后
SELECT r.id, mask_phone(r.phone, 3, 4) AS phone, mask_email(r.email) AS email, r.status, r.name, r.address, mask_name(r."DisplayName", '*') AS "DisplayName" FROM (
SELECT *
FROM crm.public.customer
) AS r;
```

### 4. 列别名保留

```sql
-- 前
SELECT phone AS contact FROM crm.public.customer

-- 后
SELECT mask_phone(r.contact, 3, 4) AS contact FROM (
SELECT phone AS contact
FROM crm.public.customer
) AS r;
```

### 5. CTE 原样留内层

```sql
-- 前
WITH active AS (SELECT email FROM crm.public.customer WHERE status = 'active')
SELECT email FROM active

-- 后
SELECT mask_email(r.email) AS email FROM (
WITH active AS (SELECT email
FROM crm.public.customer
WHERE status = 'active') SELECT email
FROM active
) AS r;
```

### 6. 派生表（子查询在 FROM）

```sql
-- 前
SELECT p FROM (SELECT phone AS p FROM crm.public.customer) t

-- 后
SELECT mask_phone(r.p, 3, 4) AS p FROM (
SELECT p
FROM (SELECT phone AS p
FROM crm.public.customer) AS t
) AS r;
```

### 7. 跨 schema JOIN + WHERE + ORDER BY + LIMIT（子句全部留内层）

```sql
-- 前
SELECT c.phone, m.email FROM crm.public.customer c
JOIN crm.vip.member m ON m.id = c.id
WHERE c.status = 'vip' ORDER BY c.phone DESC LIMIT 5

-- 后（LIMIT 5 被 Calcite 规范化为 FETCH NEXT 5 ROWS ONLY）
SELECT mask_phone(r.phone, 3, 4) AS phone, partial_mask(r.email, '@corp', TRUE, 2.5E0) AS email FROM (
SELECT c.phone, m.email
FROM crm.public.customer AS c
INNER JOIN crm.vip.member AS m ON m.id = c.id
WHERE c.status = 'vip'
ORDER BY c.phone DESC
FETCH NEXT 5 ROWS ONLY
) AS r;
```

### 8. DISTINCT

```sql
-- 前
SELECT DISTINCT phone FROM crm.public.customer

-- 后
SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
SELECT DISTINCT phone
FROM crm.public.customer
) AS r;
```

### 9. 聚合（count(phone) 是 phone 的派生血缘，仍脱敏）

```sql
-- 前
SELECT count(phone) AS c FROM crm.public.customer

-- 后
SELECT mask_phone(r.c, 3, 4) AS c FROM (
SELECT COUNT(phone) AS c
FROM crm.public.customer
) AS r;
```

### 10. 未起别名的表达式：包装层按位置命名（内层 SQL 逐字不动）

外层包装用 PostgreSQL 派生表**列别名表**（`FROM (...) AS r (a, b, c)`）按位置
给输出列命名：未命名表达式（真实 PG 里的 `?column?`，无法按名引用）命名为
`mask_col_N`，命名只发生在包装层，用户语句一个字都不改：

```sql
-- 前
SELECT phone || email FROM crm.public.customer

-- 后
SELECT mask_email(r.mask_col_1) AS mask_col_1 FROM (
SELECT phone || email
FROM crm.public.customer
) AS r (mask_col_1);
```

常量同样处理（哪怕本身不脱敏，包装后也要能引用）；别名表会列出全部输出列：

```sql
-- 前
SELECT phone, 1 + 1 FROM crm.public.customer

-- 后
SELECT mask_phone(r.phone, 3, 4) AS phone, r.mask_col_1 FROM (
SELECT phone, 1 + 1
FROM crm.public.customer
) AS r (phone, mask_col_1);
```

重复的输出列名同样被按位置命名（后续出现加 `_2` 后缀），不再报错：

```sql
-- 前
SELECT phone, phone FROM crm.public.customer

-- 后
SELECT mask_phone(r.phone, 3, 4) AS phone, mask_phone(r.phone_2, 3, 4) AS phone_2 FROM (
SELECT phone, phone
FROM crm.public.customer
) AS r (phone, phone_2);
```

### 10b. 起了别名则保留用户别名（tie-break：email < phone，取字典序最小列键的策略）

```sql
-- 前
SELECT phone || email AS contact FROM crm.public.customer

-- 后
SELECT mask_email(r.contact) AS contact FROM (
SELECT phone || email AS contact
FROM crm.public.customer
) AS r;
```

> 无需脱敏改写的语句（无策略命中）不包装、原样返回，`SELECT 1 + 1` 的输出列
> 名仍是 `?column?`。

### 11. 大小写敏感列（声明为 DisplayName，引用与包装都带双引号）

```sql
-- 前
SELECT "DisplayName" FROM crm.public.customer

-- 后
SELECT mask_name(r."DisplayName", '*') AS "DisplayName" FROM (
SELECT "DisplayName"
FROM crm.public.customer
) AS r;
```

### 12. 未引号大写折叠为小写（PostgreSQL 规范）

```sql
-- 前
SELECT PHONE FROM CRM.PUBLIC.CUSTOMER

-- 后（与 1 完全相同）
SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
SELECT phone
FROM crm.public.customer
) AS r;
```

### 13. 一段引用（搜索路径解析到 crm.public）

```sql
-- 前
SELECT phone FROM customer

-- 后
SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
SELECT phone
FROM customer
) AS r;
```

## 二、不命中：原样返回（解析后快照，非逐字节原文）

### 14. 全列无策略

```sql
-- 前
SELECT id, name FROM crm.public.customer

-- 后（仅补 ;）
SELECT id, name
FROM crm.public.customer;
```

### 15. 无命中时的规范化：count → COUNT，LIMIT → FETCH NEXT

```sql
-- 前
SELECT status, count(*) FROM crm.public.customer GROUP BY status ORDER BY status LIMIT 5

-- 后
SELECT status, COUNT(*)
FROM crm.public.customer
GROUP BY status
ORDER BY status
FETCH NEXT 5 ROWS ONLY;
```

### 16. 脱敏列只出现在 WHERE（不进投影则不包）

```sql
-- 前
SELECT id FROM crm.public.customer WHERE phone LIKE '138%'

-- 后
SELECT id
FROM crm.public.customer
WHERE phone LIKE '138%';
```

### 17. 脱敏列只出现在 ORDER BY

```sql
-- 前
SELECT id FROM crm.public.customer ORDER BY phone

-- 后
SELECT id
FROM crm.public.customer
ORDER BY phone;
```

### 18. 字符串里的分号与成对引号不影响切分

```sql
-- 前
SELECT note FROM crm.public.orders WHERE note = 'it''s;a;b'

-- 后
SELECT note
FROM crm.public.orders
WHERE note = 'it''s;a;b';
```

### 19. 带字符串/布尔/浮点参数的策略（浮点渲染为 2.5E0，合法 PG 浮点）

```sql
-- 前
SELECT email FROM crm.vip.member

-- 后
SELECT partial_mask(r.email, '@corp', TRUE, 2.5E0) AS email FROM (
SELECT email
FROM crm.vip.member
) AS r;
```

### 多语句

文件/文本里的多条语句逐条改写，`;` 结尾、空行分隔（下例第 3 条无命中原样返回）：

```sql
-- 前
SELECT phone FROM crm.public.customer; SELECT email FROM crm.public.customer; SELECT id FROM crm.public.customer

-- 后
SELECT mask_phone(r.phone, 3, 4) AS phone FROM (
SELECT phone
FROM crm.public.customer
) AS r;

SELECT mask_email(r.email) AS email FROM (
SELECT email
FROM crm.public.customer
) AS r;

SELECT id
FROM crm.public.customer;
```

## 三、语义说明

- **只动最外层投影**：内层保持原始表达式（如 `COUNT(phone)`、`phone || email`
  原样在内层），脱敏 UDF 只作用于最终输出列；
- **血缘可追溯是硬要求**：能追溯到声明列（含 CTE 展开、派生表、JOIN、表达式、
  聚合）即判定策略，追溯不到则报错拒绝改写，绝不静默放行。

## 四、报错场景（输入 → stderr）

| 输入 | 错误 |
| --- | --- |
| `SELECT phone FROM public.customer` | `VALIDATION_ERROR: ... Object 'public' not found`（两段 schema.table 引用不支持，用一段或三段） |
| `DELETE FROM crm.public.customer` | `UNSUPPORTED_STATEMENT: ... only SELECT and WITH ... SELECT queries are supported` |
| `WITH cte AS (SELECT id FROM cte) SELECT id FROM cte` | `LINEAGE_UNKNOWN: cannot trace lineage through recursive CTE 'cte'` |
| `SELECT id, (SELECT max(id) FROM crm.public.customer) AS m FROM crm.public.customer` | `LINEAGE_UNKNOWN: output column 1 ('m') has no safely traceable origin` |
| `SELECT note FROM crm.public.orders WHERE note = $$a;b$$` | `PARSE_ERROR`（babel 解析器不支持 `$$...$$` 美元引号串） |
