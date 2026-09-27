# mask-lite（PG-only 最简抽取）设计

- 日期：2026-09-27
- 分支：`feature/mask-lite-pg`（自 `go`/`arch2` tip 6163c38 切出）
- 状态：已实现并验证

## 目标

从 mask-engine 抽出「列脱敏 + 行过滤」的最简版本：只支持 PostgreSQL 方言，
自包含单 jar，支持以 URLClassLoader 方式加载执行；以旧仓库基准的 99 条
TPC-DS 语句做全量改写回归，并在远程 PG（tpcds 库，
sf=0.01 数据 + mask UDF）上执行改写产物验证效果。最终合并到特性分支。

## 决策（经确认）

| 决策点 | 选择 | 理由 |
|---|---|---|
| 模块形态 | 独立抽取（复制+裁剪到 `io.masklite.*`） | 用户明确「抽出最简版本」；包名隔离使宿主类路径冲突归零，classloader 加载不需要 parent-first 妥协。fork 同步成本仅限特性分支维护，可接受 |
| 方言 | 仅 PostgreSQL（`DialectRegistry`/`DialectProfiles` 只注册 PG，其余名字报 CONFIG_ERROR） | 需求明示；解析器 codegen 原样保留（仅改包名），保证 PG 解析行为与 mask-engine 字节一致 |
| 策略模型 | 仅 legacy 元数据 YAML 内嵌策略（columns/rowFilter/policies），匿名主体 | 最简口径；Ranger 式 policies.yaml 与 Subject 编译路径整体裁掉 |
| 语句范围 | 只读（SELECT / WITH…SELECT，可带顶层 ORDER BY），写语句 fail-closed | 脱敏+行过滤的最简面即读路径；写语句/复制继承是 mask-engine 近期复杂度集中地 |
| TPC-DS 数据 | 远程 dsdgen sf=0.01 计划失败（dsdgen 2.10 小规模段错误），改用本地 DuckDB(ttpcds ext) 生成 CSV → scp → COPY 加载 | 确定性、免远程编译；数据规模 26MB |
| 远程 schema | binbjz/tpcds_pg（v3.2）DDL 重建，与语料/YAML 同源；DuckDB(v2.6) 数据经列清单位置映射加载（两处 v2.6→v3.2 改名：c_last_review_date_sk→c_last_review_date、s_tax_percentage→s_tax_precentage） | 语料查询与 YAML 均为 v3.2 命名，DB 必须同源 |
| 特性分支 | 新建 `feature/mask-lite-pg`，工作直接提交其上 | 现有 feature-row-filter 是旧单模块祖先分支，合并会造成混合布局 |

## 模块边界

保留（约 40 类）：`config`（YAML 加载/legacy 策略转换）、`dialect`（抽象基座 +
PG 适配器，849 行）、`error`、`lineage`、`metadata`、`rewrite`（引擎/包装服务/
计划/选择器）、`rowfilter`（派生表注入 + 白名单注册表）、`sql`（校验器工厂/
拆分器/CTE 展开）、`parser`（fmpp+javacc codegen，包名改 `io.masklite.parser`）、
`policy` 精简版（legacy 转换所需 ~14 个小类）。

裁掉：MySQL/Trino/Hive/SparkSQL 适配器（~1000 行）、`introspect`（JDBC 元数据
采集与全部 JDBC 驱动依赖）、写语句路径（INSERT/CTAS/复制继承 InheritDecider）、
`PolicyYamlLoader`/store、`PolicyResourceResolver`、jackson 注解。

依赖：`calcite-core` + `calcite-babel` 1.42.0、`snakeyaml` 2.2、JUnit/PG 驱动
（仅测试）。enforcer 维持 Spring-free/picocli-free。shade 出自包含 jar
（manifest Main-Class=io.masklite.MaskLite，CLI 入口）。

## 门面与加载

`io.masklite.MaskLite`：`fromYaml(String)` / `fromYamlFile(Path)` /
`rewriteStatements(sql)` / `rewrite(sql)`（脚本拼接）。加载方式：宿主以
`new URLClassLoader(new URL[]{shadedJar}, ClassLoader.getPlatformClassLoader())`
加载（不委托应用类路径），`Class.forName("io.masklite.MaskLite", true, loader)`
后反射调用——jar 内含全部 calcite/snakeyaml/生成解析器类，隔离由 `ClassloaderIT`
断言（MaskLite 与 org.apache.calcite.sql.SqlSelect 均须由该 loader 装载）。

## 验证结果（2026-09-27，方言补丁后更新）

- 离线改写回归：**99/99** 成功（15 masked / 91 rowFiltered）。初始结论为
  95/99（与旧基准一致），随后修复三条方言限制并提升到全量：
  1. q05/q80：Calcite PG library 自带 CONCAT 与自定义 vararg CONCAT 双候选，
     UNION 强制推导的严格路径下拒绝 char 参数——从 library 列表剔除，
     仅保留 vararg 定义；
  2. q72：PG 的 `date ± integer`（整数进退天数）标准 Calcite 不支持——放宽版
     `+`/`-` 注册进操作符表首位（deriveType 按名字重新解析并替换调用上的
     操作符，BINARY 语法取链序第一个候选；numeric 形态委托 std 语义不变）；
  3. q09：投影含标量子查询时整条 fail-closed——改为逐个递归校验子查询自身
     输出是否命中脱敏策略：命中仍 fail-closed（防走私受保护列），全部安全则
     按「子查询位换 NULL 后重取 origins」判定放行。
- 远程执行战役：99 条改写产物 **97 条在 PG 16 上执行成功**；q70/q86 的
  原始查询在 PG 上同样失败（语料在 ORDER BY 表达式引用输出别名
  lochierarchy，PG 严格禁止、DuckDB 宽松）——IT 以「原始查询同错」
  parity 断言锁定，证明改写未引入任何新的执行失败。
- 行过滤效果：`customer_address` 500→484（= 手工 WHERE 行数），
  `date_dim` 73049→37619（= 手工 WHERE 行数），逐表精确对齐。
- 脱敏效果：改写输出逐值 ≡ 手工调用 mask UDF（mask_email/mask_name）。

## 已知注记

- 历史基准配置里的 `customer.c_phone` 绑定是遗留物（TPC-DS customer 表无此列，
  绑定永不命中）；实际脱敏列为 c_email_address/c_last_name/c_first_name/
  c_customer_id/c_birth_country 与 ca_street_name。
- 远程 tpcds 库由本特性分支的测试流程搭建：v3.2 DDL + DuckDB 生成的 sf=0.01
  数据 + `deploy/local-e2e/01_udf.sql` 的 mask UDF（tpcds 库内）。

## review 修复轮（2026-09-28，feature/mask-lite-pg-2）

独立代码 review（含对照 Calcite 1.42 源码逐条验证 fail-closed 链）结论：
**无可构造的受保护数据走私路径**；6 条 Important 与全部 Minor 修复落地：

- 安全底线：对抗性 fail-closed 单测入住本模块（子查询走私/歧义列/两段名/
  递归 CTE/白名单三连/原子性/EXPR\$N/拆分器 PG 词法边界）；入库默认密码与
  主机信息移除（IT 无凭据即跳过）。
- 方言口径：MINUS 只认 date 在左、整数族止步 int4、优先级对齐 std(40)；
  `WITH…SELECT` 无顶层 ORDER BY 的过拒绝修复（语料 99 条全带 ORDER BY
  从未暴露）；裸 `INTERVAL '1 day'` 与 `'str'::interval`/`CAST('str' AS
  INTERVAL)` 解析期规范化为等值限定词形式（拆掉 babel 旗标产出的
  INTERVAL SECOND 值腐蚀地雷；typmod/字段范围形态保持拒绝）。
- 清理：写语句死代码/死策略方法/死错误码删除（-336 行），策略层收敛为
  dataMask 单模型，行过滤单一事实来源（TableMetadata.rowFilter）。
- 测试强度：离线回归锁定 15 masked / 91 rowFiltered 位图与 wrapper 形状
  断言；全量 77 单测 + ClassloaderIT 绿；TPC-DS 99/99 与位图不变。
