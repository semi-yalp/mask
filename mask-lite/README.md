# mask-lite

从 sql-mask 抽出的**最简 SELECT 脱敏**独立模块：一个独立的 Maven 工程，
不依赖仓库里的任何其他模块（mask-core / mask-policy / mask-metadata），
也不依赖 Spring、picocli、JDBC 驱动。输入输出同为 PostgreSQL 方言，
元数据与脱敏策略沿用同一份 YAML。

做两件事（共享同一条解析-校验管线）：

1. **列脱敏**：把 SELECT（含 `WITH ... SELECT`）改写为
   「原始查询作为内层、最外层对输出列调用脱敏 UDF」的 SQL；
2. **行过滤**：凡 FROM 引用命中声明了 `rowFilter` 的表，校验前把该表引用
   替换为派生表 `(SELECT * FROM t WHERE <条件>) AS <别名>`，条件经 AST 白名单
   （列引用/字面量/比较/布尔/算术/IN 常量，禁子查询、函数、CAST、动态参数），
   与 mask-core 的行过滤行为逐字节一致。

不做主体/权限策略、不支持写语句；非 SELECT、血缘无法追溯的输出列、
无法安全注入行过滤的 FROM 形态直接报错，绝不静默放行。工具只解析和改写
SQL，从不执行它。

## 构建

```bash
cd mask-lite
mvn package        # 产出可执行 fat jar：target/mask-lite.jar
```

## 命令行用法

```bash
# --sql 直接给语句
java -jar target/mask-lite.jar --metadata metadata.yaml \
    --sql 'SELECT phone FROM crm.public.customer'

# 不给 --sql 则从 stdin 读
java -jar target/mask-lite.jar --metadata metadata.yaml < input.sql
```

脱敏后的 SQL 输出到 stdout（多语句以空行分隔、每条带 `;`）；诊断信息走
stderr，退出码：0 成功，1 处理错误，2 用法错误。

## 作为库使用

```java
MaskLite mask = MaskLite.fromYamlFile(Path.of("metadata.yaml"));
String masked = mask.rewrite("SELECT phone FROM crm.public.customer");
// SELECT mask_phone(r.phone, 3, 4) AS phone
// FROM (SELECT phone FROM crm.public.customer) AS r
```

## metadata.yaml 格式

与 sql-mask 的旧格式一致：`metadata.tables` 声明表结构，
`policies` 声明命名策略（UDF + 标量参数），`columns` 把列绑定到策略，
`rowFilter`（可选，表级）声明静态行过滤条件。

```yaml
metadata:
  tables:
    - catalog: crm
      schema: public
      name: customer
      rowFilter: "status = 'active'"   # 可选：查询该表时一律注入的静态条件
      columns:
        - name: id
          type: bigint
        - name: phone
          type: varchar

columns:
  - catalog: crm
    schema: public
    table: customer
    column: phone
    policy: phone_mask

policies:
  phone_mask:
    udf: mask_phone
    arguments: [3, 4]
```

行为要点（与 sql-mask 引擎一致）：

- 输出列血缘追溯到声明的列即脱敏：派生表达式（如 `upper(phone)`）、聚合
  （如 `count(phone)`）、子查询、CTE、JOIN 的列同样生效；
- **投影中的标量子查询可追溯血缘**：`(SELECT max(phone) FROM customer)` 的
  输出按子查询投影列的来源脱敏；EXISTS/IN/ANY 等非标量子查询仍然拒绝改写
  （fail-closed，防止无法证明血缘的静默放行）；
- 一个输出列追溯到多个命中列时，取字典序最小的列键的策略；
- 无策略命中的语句原样输出（准确说是**解析后快照**：Calcite 会做规范化，
  如 `count` → `COUNT`、`LIMIT n` → `FETCH NEXT n ROWS ONLY`）；常量列
  （无列来源）原样通过；
- 递归 CTE、血缘不可追溯（如投影里的 EXISTS/IN 子查询）→ 报错拒绝改写；
- **内层用户 SQL 逐字保留，命名全部在包装层完成**：外层用 PostgreSQL 派生表
  列别名表（`FROM (...) AS r (a, b, c)`）按位置命名输出列——未起别名的表达式
  列（PG 里不可引用的 `?column?`）命名为 `mask_col_N`，重复的输出列名加
  `_2/_3` 后缀。无策略命中的语句不包装、原样返回。

PostgreSQL 方言实测（TPC-DS 99 条全量回归）：

- `concat(a, b, ...)`：variadic、NULL 按空串处理的 PG 语义（Calcite PG
  library 自带，大小写不敏感解析）；
- **未知函数（如 `concat2`、`mask_idcard`）按宽松 UDF 处理**：任意数量/类型
  参数、返回类型取首参类型，血缘照常穿过参数（`concat2(phone)` 里的脱敏列
  会被命中）；函数表保证每个名字恰好一个候选——lib 与 std 同名者
  （如 `power`）只保留 std 版本，防止重复重载导致的解析失败；
- `date ± integer`（整数字面量）：按 PG 语义（加/减天数）校验通过，产物保留
  用户原文（`date + 5` 不改写为 interval 形式）；非字面量整数表达式或
  `timestamp ± integer`（PG 本身也不允许）仍然 fail-closed。

表引用形式（实测）：

| 写法 | 示例 | 支持 |
| --- | --- | --- |
| 三段 | `crm.public.customer` | ✓ |
| 一段（搜索路径解析） | `customer` | ✓ |
| 两段 `schema.table` | `public.customer` | ✗（`Object 'public' not found`） |

元数据 YAML 中 `catalog`/`schema`/`table`/`column` 四段均为必填：
PostgreSQL 的真实结构就是 database(=catalog) → schema → table → column，
schema 恒存在（默认 `public`），不存在"库.表.列"三层形态。

其他实测限制：

- `$$...$$` 美元引号字符串：语句分割器认得（内部分号不会切分），但
  Calcite babel 解析器不支持该语法，最终 PARSE_ERROR；
- 未加引号的大写标识符按 PostgreSQL 规范折叠为小写（`SELECT PHONE` 与
  `SELECT phone` 等价）；声明为大小写敏感的列（如 `DisplayName`）必须
  双引号引用，外层包装也会正确加引号；
- 策略数值参数按 Calcite 字面量渲染，浮点会输出科学计数法（`2.5` →
  `2.5E0`，PostgreSQL 合法浮点语法）。

## 测试

```bash
mvn test
```
