# mask-lite

从 sql-mask 抽出的**最简 SELECT 脱敏**独立模块：一个独立的 Maven 工程，
不依赖仓库里的任何其他模块（mask-core / mask-policy / mask-metadata），
也不依赖 Spring、picocli、JDBC 驱动。输入输出同为 PostgreSQL 方言，
元数据与脱敏策略沿用同一份 YAML。

只做一件事：把 SELECT（含 `WITH ... SELECT`）改写为
「原始查询作为内层、最外层对输出列调用脱敏 UDF」的 SQL。不做行过滤、
不做主体/权限策略、不支持写语句；非 SELECT 或血缘无法追溯的输出列
直接报错，绝不静默放行。工具只解析和改写 SQL，从不执行它。

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
`policies` 声明命名策略（UDF + 标量参数），`columns` 把列绑定到策略。

```yaml
metadata:
  tables:
    - catalog: crm
      schema: public
      name: customer
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
- 一个输出列追溯到多个命中列时，取字典序最小的列键的策略；
- 无策略命中的语句原样输出（准确说是**解析后快照**：Calcite 会做规范化，
  如 `count` → `COUNT`、`LIMIT n` → `FETCH NEXT n ROWS ONLY`）；常量列
  （无列来源）原样通过；
- 递归 CTE、血缘不可追溯（如投影里的标量子查询）→ 报错拒绝改写；
- **内层用户 SQL 逐字保留，命名全部在包装层完成**：外层用 PostgreSQL 派生表
  列别名表（`FROM (...) AS r (a, b, c)`）按位置命名输出列——未起别名的表达式
  列（PG 里不可引用的 `?column?`）命名为 `mask_col_N`，重复的输出列名加
  `_2/_3` 后缀。无策略命中的语句不包装、原样返回。

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
