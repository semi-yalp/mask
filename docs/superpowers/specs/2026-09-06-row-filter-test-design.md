# 行过滤功能测试用例设计

- 依据文档：
  - 设计方案：[`2026-09-06-row-filter-design.md`](./2026-09-06-row-filter-design.md)（下称 **设计§x**）
  - 评审一（阻断项视角）：[`2026-09-06-row-filter-design-review.md`](./2026-09-06-row-filter-design-review.md)（下称 **评审一 §x**）
  - 评审二（代码实证）：[`2026-09-06-row-filter-design-code-review.md`](./2026-09-06-row-filter-design-code-review.md)（下称 **评审二 Px**）
- 用途：实现前的完整测试清单（test list）。按 TDD 执行：每个用例先写失败测试再写实现；**H 组回归用例相反——必须先直接通过，作为行为锁**。
- 优先级：**P0** 安全阻断/实证缺陷；**P1** 核心语义与契约；**P2** 接口与易用性；**P3** 锦上添花。

## 0. 前置决策与默认期望

两轮评审均要求设计稿先定夺若干点。本文档按**评审推荐值**写死期望值；若设计稿修订时另有决策，仅需调整对应标注用例的断言，其余不受影响。

| 决策 | 选项 | 本文档默认期望（影响用例） |
| --- | --- | --- |
| D1（评审二 P7）谓词表达式约束 | AST 白名单 vs 收窄承诺 | **白名单**：会话/时间/随机/未知 UDF/动态参数一律 `CONFIG_ERROR`（REG-8） |
| D2（评审二 P8）两段名 `schema.table` | 不匹配 vs 规范化为三段名 | **不匹配**：行为与现状一致（`VALIDATION_ERROR: Object 'public' not found`），且不注入（REW-5） |
| D3（评审一 3.1）缺 `policies` 段 | loader 保持必填 vs 改为可选 | **保持必填**：缺失仍 `CONFIG_ERROR`；设计稿示例补 `policies: {}`（CFG-4） |
| D4（评审二 P9）根级集合操作 | 仅嵌套支持 vs 扩 classify | **仅嵌套**：根级维持 `UNSUPPORTED_STATEMENT`（REW-18） |
| D5（评审一 1.4）无别名+多段限定列引用 | 作用域改写 vs 显式拒绝 | **显式拒绝**：`UNSUPPORTED_STATEMENT`（REW-24） |
| D6（评审二 P6）`originalSql` 语义 | 重定义 vs 保持输入语义 | **保持**：读语句 = 替换前的校验前渲染；`unchanged()` 自然为 false（ENG-14） |

另有两项**先行修复**（评审二 P3，与行过滤同车）：CteExpander 作用域两个 bug 已实证（A 组为它们的 RED 测试，今日运行即失败）。

## 1. 测试装置（fixtures）

放置于 `src/test/resources/metadata/`（现有 integration.yaml 旁）：

| 装置 | 内容 | 服务对象 |
| --- | --- | --- |
| **F1 单表带过滤** | `crm.public.customer`，`rowFilter: "status = 'active'"`，列 id/phone/email/status；phone 绑定 `phone_mask`（mask_phone, [3,4]）；policies 必填 | C/D/E/G/H 组主装置 |
| **F2a/F2b 多候选** | F2a：`a.public.customer`（配 rowFilter）+ `b.public.customer`（不配）；F2b 为两者**调换声明顺序**。列一致（phone），policies 空 | REW-3/4 |
| **F3 CTE/作用域** | F1 的 customer + `crm.public.other`（id/tag 两列，不配过滤） | REW-9~12、A 组 |
| **F4 写语句** | `crm.public.orders`（id/region/amount，`rowFilter: "region = 'north'"`）；目标表 `archive` **不声明**；另备 `crm.public.customer` 也配 rowFilter 的变体用于目标命中场景 | ENG-5~8、REW-27/28 |
| **F5 多表过滤** | customer（status）+ orders（region）各配条件 | CFG-5、REG-10、ENG-16 |
| **F6 大小写/引号** | 声明 `crm.public."Customer"`（带引号原样）与 `crm.public.customer`（小写）两张表，仅后者配 rowFilter | REW-8 |

## 2. 用例矩阵

### A 组：CteExpander 既有缺陷修复（先行，P0）

落点：新增 `sql/CteExpanderTest.java`（直接单测 expander + adapter 校验；现状不通过，RED 已实证）。

| ID | 名称 | 输入 | 期望 | 优先级 |
| --- | --- | --- | --- | --- |
| EXP-1 | 嵌套同名 CTE 内层遮蔽生效 | `WITH x AS (SELECT phone FROM crm.public.customer) SELECT tag FROM (WITH x AS (SELECT 't' AS tag) SELECT tag FROM x) AS q` | 校验通过（内层 x 生效，tag 可解析）。现状：`Column 'tag' not found`（评审二 P3 实证） | P0 |
| EXP-2 | 外层 CTE 体内定义同名内层 CTE 不误判递归 | `WITH x AS (SELECT phone FROM (WITH x AS (SELECT phone FROM crm.public.customer) SELECT phone FROM x) AS q) SELECT phone FROM q` | 校验通过。现状：`LINEAGE_UNKNOWN: recursive CTE 'x'`（实证） | P0 |
| EXP-3 | 同一 CTE 多处引用不共享 body 节点 | `WITH c AS (SELECT id, phone FROM crm.public.customer) SELECT a.phone FROM c a JOIN c b ON a.id = b.id` | expand 后两个派生表的 body 子树为**不同实例**（白盒断言引用不等）；校验通过 | P0 |
| EXP-4 | 既有 CTE 语义回归 | 现有 `cteShadowsTableName`/`recursiveCteFails`/TPC-DS S2 等全部用例 | 全部保持通过（修复不改变已正确行为） | P0 |

### B 组：配置加载（`YamlConfigLoaderTest` 扩展）

| ID | 名称 | 输入 | 期望 | 优先级 |
| --- | --- | --- | --- | --- |
| CFG-1 | rowFilter 字符串加载 | F1 YAML | `TableMetadata.rowFilter()` 返回 `"status = 'active'"` | P1 |
| CFG-2 | 空/空白视为未配置 | `rowFilter: ""`、`rowFilter: "  "` | 等价于未配置（null 或空，按实现约定），后续流程与现状一致 | P1 |
| CFG-3 | 非字符串类型 | `rowFilter: [1,2]`、`rowFilter: 123`、`rowFilter: true` | `CONFIG_ERROR`，消息含 YAML 路径（如 `metadata.tables[0].rowFilter`） | P1 |
| CFG-4 | 缺 policies 段（D3） | 仅 metadata.tables + rowFilter，无 policies | `CONFIG_ERROR`（与现状一致）；设计稿示例同步补 `policies: {}` | P2 |
| CFG-5 | 多表条件互不干扰 | F5 | 两表各自 rowFilter 独立加载 | P1 |
| CFG-6 | TableMetadata 构造迁移 | 全仓库编译 | 加字段后 `TableMetadata.of(...)` 等工厂与既有调用点全部适配（编译期即验证） | P2 |

### C 组：RowFilterRegistry（新增 `rowfilter/RowFilterRegistryTest.java`）

通用输入：按 F1/F5 构建 registry；错误断言统一要求错误码 + 消息前缀 `table 'crm.public.customer': row filter ...`（设计§8）。

| ID | 名称 | 输入（rowFilter 值） | 期望 | 优先级 |
| --- | --- | --- | --- | --- |
| REG-1 | 合法条件 | `status = 'active'` | 构建成功，可取条件模板 | P1 |
| REG-2 | 解析失败 | `status =` | `CONFIG_ERROR` 带表名前缀 | P1 |
| REG-3 | 含子查询三形态 | `id IN (SELECT id FROM crm.public.orders)`；`EXISTS (SELECT 1 FROM crm.public.orders)`；`status = (SELECT 'x')` | 全部 `CONFIG_ERROR`（设计§2.3/§3.2） | P0 |
| REG-4 | 未知列 | `nope = 1` | `CONFIG_ERROR` | P1 |
| REG-5 | 非布尔条件 | `1 + 1` | `CONFIG_ERROR` | P1 |
| REG-6 | 类型不匹配 | `status = 123`（varchar 与整型比较） | `CONFIG_ERROR`（校验器复用） | P2 |
| REG-7 | NULL 语义可用 | `status IS NOT DISTINCT FROM 'active'` | 构建成功（设计§2.3 提示的能力可用） | P2 |
| REG-8 | 谓词白名单（D1） | `current_user = 'a'`；`is_allowed(status)`；`random() < 0.5`；`current_timestamp < expires_at`；`status = $1` | 全部 `CONFIG_ERROR`，消息指明被拒成分（评审二 P7/评审一 2.5） | P0 |
| REG-9 | 模板不被校验污染 | 连续构建两次 registry，或构建后连续取两次注入拷贝 | 两次取得的谓词树互不影响（配合 REW-21 行为断言） | P0 |
| REG-10 | 多表独立报错 | F5 中 customer 条件非法 | 错误消息指向 customer 而非 orders | P1 |
| REG-11 | 无任何 rowFilter | 现有 integration.yaml | registry 为空；整轮改写与现状一致（衔接 GOLD-1） | P1 |

### D 组：RowFilterRewriter（新增 `rowfilter/RowFilterRewriterTest.java`）

名称解析：

| ID | 名称 | 输入 | 期望 | 优先级 |
| --- | --- | --- | --- | --- |
| REW-1 | 三段名注入 | `SELECT id FROM crm.public.customer` | FROM 变为 `(SELECT * FROM crm.public.customer WHERE status = 'active') AS customer` | P1 |
| REW-2 | 一段名唯一命中 | `SELECT id FROM customer` | 同上注入 | P1 |
| REW-3 | 一段名多候选显式失败 | F2a：`SELECT phone FROM customer` | 抛错（建议 `VALIDATION_ERROR`），消息列明候选；**不静默跳过**（评审二 P1/评审一 1.2，fail-open 防线） | P0 |
| REW-4 | 多候选与声明顺序无关 | F2b（顺序调换）同 SQL | 与 REW-3 相同错误 | P0 |
| REW-5 | 两段名不注入（D2） | `SELECT phone FROM public.customer` | 不注入任何派生表；最终 `VALIDATION_ERROR: Object 'public' not found`（与现状一致） | P1 |
| REW-6 | 未声明表走现状 | `SELECT x FROM nope_table`（F1） | 不替换，报错消息与现状一致 | P2 |
| REW-7 | 未配过滤的声明表零改动 | `SELECT id FROM crm.public.other`（F3） | 返回**同一节点实例**（引用相等，设计§5.2） | P1 |
| REW-8 | 引号/大小写匹配 | `FROM "Customer"`、`FROM Customer`、`FROM "customer"`（F6） | `"Customer"` 精确匹配带引号声明（未配过滤→零改动）；`Customer` 折叠小写匹配 customer→注入；`"customer"` 不误匹配 `"Customer"` 声明 | P1 |

CTE 作用域（评审一 1.1/评审二 P2）：

| ID | 名称 | 输入 | 期望 | 优先级 |
| --- | --- | --- | --- | --- |
| REW-9 | 同名 CTE 遮蔽基表 | `WITH customer AS (SELECT id FROM crm.public.other) SELECT id FROM customer` | 主查询 `FROM customer` 绑定 CTE，**不注入**；输出 CTE 原样 | P0 |
| REW-10 | 嵌套 WITH 遮蔽 | 外层 WITH 定义 `customer` CTE，内层子查询再定义同名 CTE 后引用 | 内层引用按内层 CTE 解析，均不注入基表过滤 | P0 |
| REW-11 | CTE item 前向引用维持现状 | `WITH a AS (SELECT id FROM b), b AS (...) ...` | 维持既有失败（不因行过滤改变） | P2 |
| REW-12 | CTE 体内注入、引用处不重复 | `WITH c AS (SELECT * FROM crm.public.customer) SELECT id FROM c` | 仅 CTE 体内一份注入；`FROM c` 处零改动；内联后派生表携带过滤（设计§5.1） | P0 |

注入形态与遍历覆盖：

| ID | 名称 | 输入 | 期望 | 优先级 |
| --- | --- | --- | --- | --- |
| REW-13 | 别名保留 | `FROM crm.public.customer AS c` + `c.id` 限定引用 | 派生表别名 `c`，限定引用可解析（设计§4.2） | P1 |
| REW-14 | 无别名取表名作别名 | `FROM crm.public.customer` + `customer.id` | `... AS customer`，一段限定引用可解析 | P1 |
| REW-15 | 自连接两处独立注入 | 设计§4.2 示例（一处别名 a、一处无别名） | 输出与设计§4.2 期望一致 | P1 |
| REW-16 | `AS c(id, phone)` 列别名保留 | `FROM crm.public.customer AS c(id, phone)` | 只替换表操作数，列别名列表原样保留，无双重 AS（评审一 2.4） | P1 |
| REW-17 | JOIN 两侧/逗号连接 | `FROM crm.public.customer c JOIN crm.public.orders o ON ...`（F5） | 两侧各注入各自条件 | P1 |
| REW-18 | 集合操作（D4） | 嵌套：派生表内/CTE 体内 `SELECT ... UNION ALL SELECT ...`；根级 `SELECT ... UNION SELECT ...` | 嵌套位置各分支注入；根级维持 `UNSUPPORTED_STATEMENT`（现状） | P1 |
| REW-19 | 表达式子查询覆盖 | WHERE `EXISTS (SELECT 1 FROM crm.public.customer c WHERE ...)`；SELECT 列表标量子查询；JOIN ON 内 EXISTS/IN | 子查询内部 FROM 注入（设计§3.1/评审一 2.3） | P0 |
| REW-20 | 其他子query位置 | HAVING、GROUP BY/ORDER BY 表达式内子查询（方言可解析形态） | 注入或不静默漏过 | P2 |
| REW-21 | 深拷贝独立性 | 同语句两处引用同一受控表（自连接） | 校验通过且两处谓词互不影响（可白盒断言节点引用不等；评审二 P4） | P0 |
| REW-22 | 不再入新构造节点 | 任意命中用例 | 注入产物恰好一层派生表，内部 `FROM crm.public.customer` 不被二次包装；重复调用 rewriter 幂等 | P0 |
| REW-23 | registry 为空零改动 | 现有 integration.yaml + 任一语句 | 返回同一节点实例（设计§5.2） | P1 |
| REW-24 | 三段限定列引用（D5） | `SELECT crm.public.customer.id FROM crm.public.customer` | `UNSUPPORTED_STATEMENT`（fail-closed，不产生可校验失败或静默错绑） | P1 |
| REW-25 | 点名拒绝位置 | `FROM crm.public.customer TABLESAMPLE ...`、LATERAL、UNNEST 引用受控表 | `UNSUPPORTED_STATEMENT`（设计§3.2） | P0 |
| REW-26 | 未知 FROM 形态 fail-closed | 单测：向 rewriter 的 from-item 分发喂白名单外的合成 SqlCall（含受控表子树） | 拒绝而非静默跳过（评审一 1.3 白名单原则） | P0 |
| REW-27 | 写语句目标不遍历 | F4 变体：`archive` 若同名声明且配 rowFilter，`INSERT INTO archive SELECT * FROM crm.public.orders` | 目标零改动、源注入；目标不产生注入（评审一 1.6） | P0 |
| REW-28 | CTAS 名与受控表同名 | `CREATE TABLE customer AS SELECT * FROM crm.public.other`（F3，目标名撞受控表） | 目标不注入，仅源处理 | P0 |

### E 组：引擎集成（新增 `integration/RowFilterIntegrationTest.java`）

| ID | 名称 | 输入 | 期望 | 优先级 |
| --- | --- | --- | --- | --- |
| ENG-1 | 过滤+脱敏叠加（设计§4.1 精确断言） | F1 + §4.1 输入 | 输出与 §4.1 逐字符一致：外层 `mask_phone(r.phone, 3, 4)`、内层派生表含 `WHERE status = 'active'`、`FETCH NEXT 10 ROWS ONLY` 在内层 | P1 |
| ENG-2 | 仅过滤无策略命中 | F1 + `SELECT id FROM crm.public.customer`（id 无策略） | rewrittenSql = 替换后语句的 Calcite 渲染；masked=false；rowFiltered=true | P1 |
| ENG-3 | 全不命中原样 | F1 + `SELECT 1` | unchanged=true、masked=false、rowFiltered=false | P1 |
| ENG-4 | WITH...SELECT | `WITH active_c AS (SELECT * FROM crm.public.customer) SELECT id FROM active_c` | CTE 体内注入；整体可执行输出 | P1 |
| ENG-5 | INSERT...SELECT（设计§4.3） | F4：`INSERT INTO archive SELECT * FROM crm.public.orders` | 输出 `INSERT INTO archive SELECT * FROM (SELECT * FROM crm.public.orders WHERE region = 'north') AS orders`；无策略不加外层包装 | P1 |
| ENG-6 | CTAS + 修饰保留 | `CREATE TABLE IF NOT EXISTS t (a, b) AS SELECT ... FROM crm.public.orders` | 目标/列清单/IF NOT EXISTS 原样；源注入 | P1 |
| ENG-7 | INSERT...VALUES 直通 | F4 + `INSERT INTO archive VALUES (1, 'x')` | 原样直通；rowFiltered=false；masked=false（设计§3.2） | P1 |
| ENG-8 | VALUES 藏子查询维持失败 | 现有用例形态 | `UNSUPPORTED_STATEMENT` 不变 | P2 |
| ENG-9 | 既有失败行为不变 | UPDATE/DELETE/WITH RECURSIVE | 现状错误码与消息不变 | P2 |
| ENG-10 | 多语句独立标记 | 三条语句：命中过滤/命中脱敏/全不命中 | 三条 rowFiltered/masked 各自正确（设计§5.3） | P1 |
| ENG-11 | 血缘穿透派生表 | F1 + `SELECT phone FROM crm.public.customer` | phone 策略仍命中，外层仍 mask（注入不破坏脱敏） | P0 |
| ENG-12 | 复合条件 | `rowFilter: "status = 'active' AND region = 'north'"` | 条件完整注入 | P2 |
| ENG-13 | 写语句渲染文本用校验前快照 | F4 + 带 ORDER BY/LIMIT 的源查询 | 输出无重复 ORDER BY/FETCH（EXPECTED.md 缺陷#3 不复发） | P1 |
| ENG-14 | 结果契约（D6，评审一 2.1/评审二 P6） | ENG-2 场景 | `originalSql` = 替换前渲染（真实输入语义）；`rewrittenSql` 含过滤；`unchanged()==false`；masked=false；rowFiltered=true | P0 |
| ENG-15 | 双标记契约 | ENG-1 场景 | masked=true 且 rowFiltered=true；unchanged=false | P1 |

### F 组：API 与页面（扩展 `RewriteControllerTest`/`ConfigControllerTest`）

| ID | 名称 | 输入 | 期望 | 优先级 |
| --- | --- | --- | --- | --- |
| API-1 | rowFiltered 字段 | /api/rewrite + F1 语句 | 响应 statements[].rowFiltered 布尔正确 | P1 |
| API-2 | config/parse 回读 | F1 YAML | tables[].rowFilter 返回条件串；未配置返回空 | P1 |
| API-3 | round-trip 含空值 | YAML→parse→（前端生成）YAML→parse | 空白 rowFilter 归一后等价，不丢字段 | P2 |
| API-4 | 页面四象限标记 | 手工/页面清单：(masked,rowFiltered)∈{(0,0),(0,1),(1,0),(1,1)} | 标签分别显示 原样输出/已行过滤/已脱敏/已脱敏+已行过滤 | P2 |
| API-5 | 原始语句可见性 | ENG-2 场景经 API | unchanged=false → 页面「查看原始语句」折叠块出现（index.html:619 回归） | P1 |

### G 组：CLI（扩展 `SqlMaskRunnerTest`/`SqlMaskApplicationTest`）

| ID | 名称 | 期望 | 优先级 |
| --- | --- | --- | --- |
| CLI-1 | stdout 纯 SQL 格式不变：语句间空行、`;` 结尾、无新增字段输出 | P1 |
| CLI-2 | 不新增命令行参数；现有参数行为与 `--help` 不变 | P2 |
| CLI-3 | F1 场景输出含注入语句，退出码 0 | P1 |

### H 组：回归（行为锁，先写必须直接通过）

落点：新增 `regression/GoldenOutputTest.java` + `src/test/resources/golden/*.sql`（不做任何空白归一化的逐字节对比；现有测试 `flat()` 归一化空白，无法发现字节差异——评审一 3.5/评审二第四节）。

| ID | 名称 | 输入（元数据均**不含** rowFilter） | 期望 | 优先级 |
| --- | --- | --- | --- | --- |
| GOLD-1 | 形态覆盖字节级不变 | 普通 SELECT / WITH / 已脱敏 SELECT / INSERT...SELECT / CTAS / 无策略直通 / ORDER BY+LIMIT | 与 golden 文件**逐字节**一致 | P0 |
| GOLD-2 | TPC-DS 全量不变 | tpcds/queries/*.sql 全部用例 | 输出与提交的期望输出逐字节一致（EXPECTED.md 全表通过） | P0 |
| GOLD-3 | 既有单测全绿 | mvn test | 全部通过 | P0 |

## 3. TDD 执行顺序

1. **A 组**（CteExpander 先行修复）：EXP-1/2/3 为已实证 RED，先修 expander 至全绿，EXP-4 守住回归；
2. **B → C 组**（配置面）：loader 字段 → registry 校验（REG-8 白名单需先在代码里落 D1 决策）；
3. **D 组**按安全优先子序：名称解析（REW-1~8）→ CTE 作用域（REW-9~12）→ fail-closed（REW-22/24/25/26/27/28）→ 注入形态（REW-13~21）；
4. **E 组**引擎接线（ENG-14 契约先行，防 originalSql 语义走偏）；
5. **F/G 组**接口与 CLI；
6. **H 组**最后提交 golden 锁定（实现完成后生成 golden 并在 CI 保持）。

## 4. 覆盖追溯表

| 来源 | 用例 |
| --- | --- |
| 设计§3.1 支持范围 | REW-1/2/9/12/13/14/15/17/18/19、ENG-1~5、CFG-1 |
| 设计§3.2 不支持范围 | REG-3、REW-25、ENG-7/8/9、REW-18（根级） |
| 设计§4 示例 | ENG-1（§4.1）、REW-15（§4.2）、ENG-5/6（§4.3） |
| 设计§5.2/§5.3 解析与记录 | REW-1~8/23、ENG-2/3/10/14/15 |
| 设计§6 配置 | CFG-1~5 |
| 设计§7/§8 组件与错误映射 | REG-1~11、REW-25/26 |
| 设计§9/§10 API/CLI | API-1~5、CLI-1~3 |
| 设计§11 测试重点 | 已全部吸收并扩充 |
| 评审一 1.1/1.2/1.3/1.4/1.5/1.6 | REW-9~12、REW-3/4、REW-25/26、REW-24、REW-22、REW-27/28 |
| 评审一 2.1~2.8 | ENG-14、REW-21/REG-9、REW-19/20、REW-16、REG-8、REW-18、REW-5、REG-2~6 |
| 评审一 3.1~3.5 | CFG-4、CFG-6、ENG-5/6/13、API-3、GOLD-1~3 |
| 评审二 P1~P9 | REW-3/4、REW-9~12、A 组、REW-21/REG-9、REW-19/22、ENG-14、REG-8、REW-5、REW-18 |
| 评审二第四节（中低优先级） | REW-26、REW-24、REW-16、CFG-4、CFG-6、ENG-13、GOLD-1、API-3 |

## 5. 验收门槛

- **P0 用例全绿**（含 A 组先行修复与 GOLD 回归锁）；
- P1/P2 用例全绿；P3（REW-20 等）可带 TODO 入后续迭代；
- `mvn test` 整体绿，输出无告警；
- GOLD-1/2 golden 文件入库，作为后续所有改动的行为基线。
