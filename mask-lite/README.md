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
  vanilla PG 查询 + mask+rowfilter 合并策略 25 表；硬性 99/99（历史 4 条方言限制已修复），失败清单随构建输出）。
- `mvn verify` 另跑 failsafe 集成测试：
  - `ClassloaderIT`：shaded jar 经 URLClassLoader 反射加载执行；
  - `TpcdsRemotePgIT`：99 条改写产物在远程 PG（tpcds 库，sf=0.01
    数据 + mask UDF）上逐条执行并点验脱敏效果（改写输出 ≡ 手工调用 mask UDF）
    与行过滤效果（注入行数 ≡ 手工 WHERE 行数）。门控环境变量
    `MASK_LITE_REMOTE_PG=1` 且必须提供 `MASK_LITE_PG_PASSWORD`（凭据不入库，
    缺失即跳过）；主机地址经本机 SSH 隧道接入默认端口
    （可用 `MASK_LITE_PG_URL/USER/PASSWORD` 覆盖）。

方言补丁（相对 mask-engine 的增强，TPC-DS 全量语料实证驱动）：

- **`concat` 可变参**：从 PG library 列表剔除其自带 CONCAT（UNION 强制推导的
  严格路径下双候选歧义会拒绝 char 参数），保留本方言语义的可变参定义——
  PG 的 `concat` 对任意字符串类型成立；
- **`date ± integer`**：PG 以整数天进退日期（`date - integer → date`；
  `integer - date` 无此操作符），Calcite 标准只认 datetime + interval。
  放宽版 `+`/`-` 注册进操作符表：PLUS 认两侧、MINUS 只认 date 在左，
  整数族止步 int4（bigint 对 integer 无隐式转换），其余形态委托标准
  检查与返回类型推导（语义不变）——注意 deriveType 会按名字重新解析并
  替换调用上的操作符，且 BINARY 语法取链序第一个候选，因此放宽版必须
  排在 std 之前。PG 的裸 `INTERVAL '1 day'`（无限定词）在解析期规范化为
  等值的限定词形式（`'1 day'` → `INTERVAL '1' DAY`，跨族混合如
  `'1 year 1 day'` 与分数月保持拒绝）；`::interval` 转换仍是已知缺口，
  报 PARSE_ERROR（fail-closed 过拒绝，非走私风险）。
- **标量子查询输出的血缘判定**：投影含标量子查询时逐个递归校验其自身输出
  是否命中脱敏策略——命中（或无法证明不命中）仍整条 fail-closed，全部安全
  则含子查询的输出按「子查询位换 NULL 后重取 origins」判定（纯子查询列
  NO_ORIGIN 透传，混合列保留真实来源正常包装）。

远程执行战役：99 条改写产物中 97 条在 PG 上执行成功；q70/q86 的改写产物与
**原始查询**在 PG 上同样失败（语料在 ORDER BY 表达式里引用输出别名
`lochierarchy`，PG 严格禁止而 DuckDB 宽松）——属语料与 PG 的既有不兼容，
非改写引入（测试中以「原始查询同错」断言锁定该结论）。

## 注意

`c_phone` 策略绑定是历史基准配置的遗留物：TPC-DS customer 表并无该列（绑定
永远不命中，无查询投影它所以无害）；实际脱敏列为 customer 的
`c_email_address` / `c_last_name` / `c_first_name` / `c_customer_id` /
`c_birth_country` 与 customer_address 的 `ca_street_name`。
