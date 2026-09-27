# mask-lite

脱敏 + 行过滤改写内核的**最简抽取版**：只支持 **PostgreSQL 方言**，自包含单 jar，
可经 `URLClassLoader` 加载后以反射调用。源码从 `mask-engine` 抽取裁剪，包名整体
迁至 `io.masklite.*`，对 `io.sqlmask` 零依赖——宿主类路径上同时存在 mask-engine
也不会冲突。

## 能力范围

- **列脱敏**：输出列按策略用 PG UDF 包装（`SELECT mask_phone(r.phone, 3, 4) AS phone
  FROM ( <原始查询> ) AS r`），血缘分析保证只包装有出处的列，无安全出处的输出列
  fail-closed。
- **行过滤**：声明的表在 FROM 中被替换为
  `(SELECT * FROM t WHERE <条件>) AS t`（派生表注入，覆盖裸表/别名/JOIN 操作数/
  子查询/集合分支），条件经白名单校验（仅 AND/OR/NOT、比较、IS [NOT] [DISTINCT]
  NULL、常量 IN、算术）。
- **只读**：只接受 SELECT / WITH…SELECT（可带顶层 ORDER BY）；写语句一律拒绝。
- 策略来源仅 legacy 元数据 YAML 内嵌策略（`columns` 绑定 / `rowFilter` /
  `policies` UDF 声明），对所有人无条件生效；无主体（Subject）维度。

裁剪掉的部分（相对 mask-engine）：MySQL/Trino/Hive/SparkSQL 方言、JDBC 元数据
采集（`introspect`，含全部 JDBC 驱动依赖）、写语句改写与复制继承、Ranger 式
`policies.yaml` 与主体编译（`mask-policy` 的 store/ 路径）。解析器 codegen
（fmpp + javacc，基于 calcite-babel 1.42.0）与 mask-sqlparser 同源，仅改包名，
保证 PG 解析行为与 mask-engine 字节一致。

## 使用

```java
import io.masklite.MaskLite;

MaskLite mask = MaskLite.fromYamlFile(Path.of("metadata.yaml"));
String rewritten = mask.rewrite("SELECT c_email_address FROM customer");
List<MaskLite.StatementRewrite> perStatement = mask.rewriteStatements(sqlText);
```

CLI（自包含 jar 直接可用，退出码 0/1/2）：

```bash
java -jar mask-lite/target/mask-lite-0.1.0-SNAPSHOT.jar \
  --metadata metadata.yaml --sql 'SELECT c_email_address FROM customer'
```

YAML 形态（与历史 mask-core legacy 格式逐字节兼容）：

```yaml
metadata:
  tables:
    - catalog: crm
      schema: public
      name: customer
      rowFilter: "status = 'active'"
      columns:
        - { name: id, type: bigint }
        - { name: phone, type: varchar(20) }
        - { name: status, type: varchar(10) }
policies:
  mask_phone:
    udf: mask_phone
    arguments: [3, 4]
columns:
  - { catalog: crm, schema: public, table: customer, column: phone, policy: mask_phone }
```

## Classloader 加载执行

`mvn package` 产出自包含 shaded jar（calcite + snakeyaml + 生成解析器全部内联，
无任何外部依赖）。宿主用**不委托应用类路径**的加载器加载即可：

```java
URL jar = Path.of("mask-lite-0.1.0-SNAPSHOT.jar").toUri().toURL();
try (URLClassLoader loader = new URLClassLoader(new URL[]{jar},
    ClassLoader.getPlatformClassLoader())) {
  Class<?> maskLite = Class.forName("io.masklite.MaskLite", true, loader);
  Object mask = maskLite.getMethod("fromYaml", String.class).invoke(null, yaml);
  String rewritten = (String) maskLite.getMethod("rewrite", String.class)
      .invoke(mask, "SELECT ...");
}
```

`ClassloaderIT` 在 `mvn verify` 中自动验证该路径（含 calcite 类亦来自 jar 的隔离断言）。

## 测试

- `mvn test`：门面单测 + **TPC-DS 离线全量改写回归**（旧仓库基准语料 99 条
  vanilla PG 查询 + mask+rowfilter 合并策略 25 表；底线 ≥95/99，失败清单随构建输出）。
- `mvn verify` 另跑 failsafe 集成测试：
  - `ClassloaderIT`：shaded jar 经 URLClassLoader 反射加载执行；
  - `TpcdsRemotePgIT`：99 条改写产物在远程 PG（47.100.166.158 tpcds 库，sf=0.01
    数据 + mask UDF）上逐条执行并点验脱敏效果（改写输出 ≡ 手工调用 mask UDF）
    与行过滤效果（注入行数 ≡ 手工 WHERE 行数）。门控环境变量
    `MASK_LITE_REMOTE_PG=1`；默认走本机隧道 `ssh -N -L 15432:127.0.0.1:5432
    root@47.100.166.158`（可用 `MASK_LITE_PG_URL/USER/PASSWORD` 覆盖）。

已知的 4 条改写失败为内核固有方言限制（与 mask-engine 一致，历史基准同结论）：
q05/q80（`CONCAT` 对 char 无签名）、q72（`date + integer`）、q09（CASE 派生列
无可追溯血缘，fail-closed）。

远程执行战役：95 条改写产物中 93 条在 PG 上执行成功；q70/q86 的改写产物与
**原始查询**在 PG 上同样失败（语料在 ORDER BY 表达式里引用输出别名
`lochierarchy`，PG 严格禁止而 DuckDB 宽松）——属语料与 PG 的既有不兼容，
非改写引入（测试中以「原始查询同错」断言锁定该结论）。

## 注意

`c_phone` 策略绑定是历史基准配置的遗留物：TPC-DS customer 表并无该列（绑定
永远不命中，无查询投影它所以无害）；实际脱敏列为 customer 的
`c_email_address` / `c_last_name` / `c_first_name` / `c_customer_id` /
`c_birth_country` 与 customer_address 的 `ca_street_name`。
