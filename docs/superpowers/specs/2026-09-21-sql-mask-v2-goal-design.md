# sql-mask v2 目标与范围设计

- 日期：2026-09-21
- 前置：v1 目标定稿 → `docs/superpowers/specs/2026-09-21-sql-mask-v1-goal-design.md`（本 v2 设计以 v1 独立工程为基座）
- 状态：v2 目标定稿（服务化改造）

## 1. 目的与决策记录

v1 交付的是**工具**（CLI + fat jar + classloader 嵌入包）。v2 在 v1 基座上做**服务化**：变成 SpringBoot 服务，但**依旧只做改写、不做查询执行**；同时补齐 v1 遗留的 SQL 解析缺陷，建立工程化（代码质量检查、CI、Docker 部署）。

本次确认的关键决策：

| # | 决策点 | 结论 |
|---|---|---|
| D1 | 服务形态 | SpringBoot 服务，**只改写不查询** |
| D2 | 元数据来源 | 服务可连接五方言查询引擎，把表结构**导入并存储到服务内** |
| D3 | 元数据存储 | **内嵌 H2（默认，Docker 数据卷持久化）+ 外部 PostgreSQL profile（生产多副本可选）** |
| D4 | 连接密码 | **AES-256-GCM 加密落库 + 服务主密钥（env / 挂载文件）**；接口不回显、日志不落明文 |
| D5 | 策略形态 | **维持配置文件（YAML），不做改造**；策略不存库、不提供服务化 CRUD |
| D6 | 方言 | 延续 v1：PostgreSQL / MySQL / Trino / Hive / SparkSQL |
| D7 | 解析缺陷 | v1 基座已知缺陷清单化修复（见 §6） |
| D8 | 工程化 | 参考 Calcite / 常见 OSS 项目建立：checkstyle / spotbugs / spotless / license 检查 / 覆盖率门 / CI / Docker 交付（见 §7、§8） |

v2 也一样不做假设延伸：v2 方案里对服务的取舍均以 v2 自身需求为准，不做 v3 预设（v3 占位见 §13）。

## 2. 一句话目标

> v2 在 v1 之上交付一个 **SpringBoot 改写服务**：对外提供「SQL 改写」与「元数据导入」两类 REST API；服务**可连接 PostgreSQL / MySQL / Trino / Hive / SparkSQL 五引擎**把表结构导入并存储进服务（内嵌 H2 或外部 PostgreSQL），连接密码 **AES-256-GCM 加密落库**；策略维持 *YAML 配置文件*形态；同时修复 v1 基座的已知 SQL 解析缺陷，并以 **Calcite 为参照建立代码质量检查与工程化流水线**，服务以 **Docker** 形式交付部署。

## 3. 范围

### 3.1 v2 能力清单（in scope）

1. **SpringBoot 改写服务**：`/api/v1/rewrite`（复用 v1 改写管线，五个方言，输入输出同方言）。
2. **元数据导入**：五方言连接器（统一走 JDBC 只读系统目录）把表结构导入服务存储；导入基于实例的连接配置。
3. **元数据存储**：内嵌 H2（默认）+ 外部 PostgreSQL（profile）。存表结构（含类型、导入时间、来源实例、原始类型保真字段）与实例（连接信息 + 加密密码）。
4. **连接密码加密**：AES-256-GCM + 随机 IV + AAD；主密钥来自 `MASK_MASTER_KEY` / `MASK_MASTER_KEY_FILE`；密文带算法/版本标识；接口永不回传密码；支持密钥轮换（当前 + N 个历史密钥可解密）。
5. **策略仍为配置**：Ranger 式 YAML 由配置文件/挂载卷提供，服务启动加载、支持运行期重载；改写请求可引用实例 + 应用其策略，也可按请求内联 YAML（临时）。
6. **v1 解析缺陷修复**：§6 清单化，含验收。
7. **工程化**：代码质量检查（checkstyle / spotbugs / spotless / license 头 / 覆盖率门 / enforcer）、依赖漏洞检查、GitHub Actions CI、API 文档（springdoc）。
8. **Docker 部署**：多阶段 Dockerfile + docker-compose（服务 + 可选 PG + 数据卷 + 健康检查 + 非 root）+ 环境变量文档。

### 3.2 明确非目标（v2 依旧不做）

- **查询执行**：服务不执行任何业务 SQL。改写只产出文本；唯一的真实数据库连接是**元数据导入**（只读系统目录 `information_schema` / `pg_catalog`，同 v1 采集语义）。两类连接在代码里分属不同链路，改写链路不持有任何 JDBC 资源。
- **策略服务化**：不做策略 CRUD / 按主体生效 / 策略 server（维持配置）。
- **审计与指标**：不引入 ES 审计或完整指标平台；仅 Spring Boot Actuator 内置 health / info（基础可观测，不开 Prometheus 指标体系）。
- **Web UI**：不做内置管理页面。
- **W8 之外的写语句扩展**：v2 不新增 v1 未覆盖的语句类型（续 v1 支持矩阵）。
- **跨引擎转写**：不复存在，输入输出同方言。

### 3.3 两条连接边界（重要）

| 链路 | 是否连接引擎 | 用途 | 只读 | 处理 |
|---|---|---|---|---|
| 改写管线 | 否 | 文本 → 文本 | — | 不持有 JDBC 依赖（v1 已去） |
| 元数据导入 | 是 | 拉取表结构 | 是（只读连接，仅系统目录） | 独立 bean / 独立事务，密码仅导入时解密使用 |

这条边界是 v2「服务化但只改写」的纪律，文档与代码都要显式约束（导入代码不入改写 classloader、改写请求不携带任何连接信息）。

## 4. 架构总览

v2 = v1 独立工程模块 + 新增服务模块：

```
mask-parser      # v1（codegen 解析器 + 关键字注册表）
mask-core        # v1（解析→校验→血缘→改写→渲染；方言 profile；YAML；行过滤）
mask-cli         # v1（CLI + fat jar，v1 交付物继续存在）
mask-embed-api   # v1（宿主侧瘦接口 jar）
mask-embed       # v1（classloader 隔离实现）
mask-server      # v2 新增：SpringBoot 服务（web 层 + 应用层 + 存储层）
mask-introspect  # v2 新增：五方言元数据导入连接器（JDBC 只读），供 server 调用，
                 #       也可被 CLI 复用（可选 `--import-metadata` 走服务或本地）
```

`mask-server` 内部分层（参考常见 OSS SpringBoot 服务结构）：

```
controller（REST 契约 / springdoc / 错误体)
  └─ application（实例管理、元数据导入编排、改写调用、密钥管理、策略加载)
     ├─ core-port（对 v1 mask-core / clause rewrite 的适配）
     ├─ secret（EncryptionKeyProvider → SecretCipher AES-GCM）
     ├─ policy（YAML 配置加载 + 重载 + 校验 —— 复用 v1 Ranger 式 loader）
     └─ store（JPA：InstanceEntity / TableEntity / ColumnEntity …）
数据库：H2（默认 /file:./data ）| PostgreSQL（spring profile `pg`）
```

模块依赖单向：`mask-server → {mask-core, mask-introspect}`；`mask-introspect → mask-core`（复用类型模型与 TypeResolver）。

## 5. 服务能力设计

### 5.1 REST 契约（草案，以 springdoc 收敛）

统一错误体 `{ "code": "...", "message": "...", "details": [] }`，HTTP 400 业务错误、401（可选 API Key）、503（依赖不可用）。

| 方法/路径 | 用途 | 要点 |
|---|---|---|
| `POST /api/v1/rewrite` | 改写 | 请求 `{ sql, instance?, dialect?, metadataYaml?, policyYaml? }`；`instance` 给出时目标方言以实例为准，策略取该实例生效配置；否则按请求内联 YAML。返回逐语句 `{originalSql, rewrittenSql, masked, rowFiltered, unchanged}` |
| `POST /api/v1/instances` | 注册实例 | `{ name, engine, host, port, database, schema[], username, password, ssl? }`；`password` 立即加密落库，永不回传 |
| `GET /api/v1/instances` / `GET /api/v1/instances/{name}` | 查询 | 返回 `hasPassword: true`（无密文）；连接详情含脱敏信息 |
| `PUT /api/v1/instances/{name}` / `DELETE ...` | 更新/删除 | 密码更新走加密；删除级联其导入元数据 |
| `POST /api/v1/instances/{name}/import-metadata` | 元数据导入 | 使用实例连接配置拉取表结构入库存；返回 `{tableCount, columnCount, warnings[]}`；类型降级逐条告警 |
| `GET /api/v1/metadata/{instance}` | 读取导入结果 | 表/列（含 `rawType` 原始类型保真字段） |
| `POST /api/v1/metadata/pull` | 临时导入（不落库/可选） | 请求内联连接信息 + YAML 返回，供本地调试（与实例导入同一实现） |
| `GET /actuator/health` | 健康检查 | Docker HEALTHCHECK / 就绪探针使用 |

改写仍严格遵循 v1 支持矩阵：语句级 fail-closed 行为、原子失败（多语句任一失败整体 400）、错误码语义不变（`CONFIG_ERROR` / `PARSE_ERROR` / `VALIDATION_ERROR` / `UNSUPPORTED_STATEMENT` / `LINEAGE_UNKNOWN` / `REWRITE_ERROR`）。

### 5.2 元数据导入（连接器）

- **统一通道**：五个引擎全部走 **JDBC + `DatabaseMetaData`**（`getTables` / `getColumns`），只读、只查系统目录，不执行业务 SQL。DRIVER 依赖仅在 `mask-introspect` 中（不进改写链路）。
  - PostgreSQL `jdbc:postgresql://host:port/db`
  - MySQL `jdbc:mysql://host:port/db`
  - Trino `jdbc:trino://host:port/catalog`
  - Hive `jdbc:hive2://host:port/default`（HiveServer2）
  - Spark `jdbc:hive2://host:port/`（Spark ThriftServer，走 hive2 协议）
- **类型映射**：复用 v1 每方言 TypeResolver 与类型清单；清单外类型（如 `jsonb`、`array`、`map`）**降级为 `varchar` 并逐条告警**（与 v1 采集一致），同时把引擎原始类型写进 `rawType` 保真字段，不丢信息。
- **导入结果**：每次导入生成版本化快照（`imported_at` / `source` / `rawType`），幂等覆盖该实例的导入表集合；导入失败（连接/权限）不动存量元数据。
- **输入验证**：`schema[]` 过滤、`--include-views` 等价开关、连通性与只读校验在导入前执行。

### 5.3 元数据存储

- **实体**：`InstanceEntity`（连接信息 + `secretRef`）、`MetadataEntity`（实例 ↔ 表 ↔ 列，含 `rawType`、声明类型、行过滤占位）、`SecretRecord`（密文 + 算法 + keyVersion + nonce + createdAt）。
- **存储切换**：`spring.jpa` 默认 H2 文件库（`jdbc:h2:file:./data/mask;AUTO_SERVER=TRUE`，Docker 挂 `/data` 卷）；profile `pg` 切 PostgreSQL（`spring.datasource` + Flyway schema 迁移，两者共用同一套迁移脚本）。
- **迁移**：Flyway 管理 schema；H2/PG 共用 `V1__schema.sql`（方言兼容写法）。

### 5.4 密码加密子系统（AES-GCM + 主密钥）

- **算法**：AES-256-GCM；每次加密生成随机 12 字节 nonce；`AAD = instanceName + fieldName` 绑定密文用途，防密文挪用；密文布局 `{version, algo, keyVersion, nonce, ciphertext}`。
- **主密钥**：`MASK_MASTER_KEY`（Base64 32B）或 `MASK_MASTER_KEY_FILE`；缺失时服务正常启动但「保存/使用凭据」能力禁用（实例 CRUD 带密码与导入接口返回明确 `CONFIG_ERROR`，**fail-closed**，不静默降级）。
- **密钥轮换**：`MASK_MASTER_KEY` 变化 + `MASK_MASTER_PREV_KEYS`（有序旧密钥列表）支持「旧密文可解、新密文用新钥」；提供维护端点/任务重加密存量密文；日志与审计只记 keyVersion 不记明文。
- **传输**：导入链路走 TLS（各驱动 ssl/sslmode 配置项）；客户端到服务走 HTTPS（Docker 部署交给反向代理/TLS 终止或服务内 TLS profile）。
- **纪律**：密码不进日志、不进错误消息、不进 URL、GET 永不回传；测试用 `passwordRef` 占位符不落明文断言。

### 5.5 策略（维持配置，不做改造）

- 策略仍是 Ranger 式 YAML：`POLICY_CONFIG_DIR`（挂载卷）内 `policies.yaml` 启动加载 + 校验（复用 v1 loader），支持 `POST /api/v1/policy/reload` 运行期重载（原子替换、失败保留旧配置）。
- 实例与策略的绑定：配置目录内按实例名分文件（`policies-<instance>.yaml` 或全局 `policies.yaml`），无实例名时应用默认策略。**不做**策略 CRUD / 主体维度管理 / 策略服务化。
- metadata 表结构的行过滤：v1 允许 `rowFilter` 字段于表声明；v2 中该字段由**导入生成的元数据 + 配置文件内策略**共同表达（配置里对某实例的某表声明 `rowFilterItems`），不引入新的行过滤来源。

## 6. v1 解析缺陷修复（v2 必须收敛）

> 清单来自 v1 基座（现仓库）已知行为与安全失败清单，v2 以「缺陷 → 期望 → 验收」逐项收敛。P0 为正确性/一致性，P1 为解析面覆盖，P2 为工程性。修复不改变「不支持即 fail-closed」的安全原则——修复的目标是**该支持的必须支持、该报错的必须清晰报错**。

| # | 优先级 | 缺陷（现状） | v2 期望 |
|---|---|---|---|
| D1 | P0 | 未限定列跨表同名的歧义 Calcite **静默解析**（如 TPC-DS E4 `c_customer_sk`），执行才对引擎报错 | 自建作用域级歧义检测：歧义引用显式失败（`VALIDATION_ERROR` 含列名），不猜测 |
| D2 | P0 | 需要包装时**重复输出列名**：PG 无法可靠区分 → 失败，但 MySQL（反引号）可处理，跨方言行为不一致 | 统一策略：确定性地重命名外层列（同 v1 包装规则），或在 PG 侧同样显式拒绝并给出可读诊断；文档写死行为 |
| D3 | P0 | `BETWEEN SYMMETRIC` 渲染后 Trino/MySQL **引擎不执行**（现状仅 PG 原生支持） | 覆盖渲染（如展开 `BETWEEN SYMMETRIC`）或显式拒绝；保证改写产物对目标引擎可解析 |
| D4 | P1 | `$$...$$` 美元引号字符串：语句分割器认得、解析器 PARSE_ERROR | PostgreSQL 方言支持美元引号；难以实现则以明确诊断拒绝 |
| D5 | P1 | `INSERT OVERWRITE ... PARTITION(...)`：v1 在 Hive/Spark 开启 overwrite 但 PARTITION 变体仍拒绝 | 明确边界：Hive 常用 `PARTITION` 形态按需支持，否则拒绝清单文档化 |
| D6 | P1 | CTAS 带表属性变体（MySQL）、Trino 带 `WITH(...)` 属性的 CTAS 一律拒绝 | 明确清单：能安全保留的保留，不能的清晰拒绝并带引擎提示 |
| D7 | P1 | `top(1)` 作函数调用被 TOP 前瞻优先吃掉 → PARSE_ERROR | 维持 fail-closed 但诊断明确（说明被 TOP 子句前瞻匹配）；SQL Server 非 v1/v2 目标方言，**不开 TOP** |
| D8 | P1 | MySQL 双引号字符串/标识符：双引号直接拒（fail-closed） | 维持（MySQL 语义下双引号非常规标识符），文档明确 |
| D9 | P1 | MySQL UDF 参数反斜杠边界：默认转义模式被引擎二次解释 | 参数规范化或文档 + 校验告警 |
| D10 | P1 | 二段名 `db.table`：PG/Trino 不支持、MySQL 支持，跨方言不一致 | 文档化 + 校验诊断统一表述 |
| D11 | P1 | 元数据导入类型降级（`array`/`json`/`map`/复合类型 → varchar）丢原始信息 | v2 导入保留 `rawType`（§5.2），改写层维持不支持清单并清晰告警 |
| D12 | P2 | 多 schema 同名表按字母序静默首匹配 | 诊断提示使用三段全名（行为维持，错误消息加引导） |
| D13 | P2 | 内层 SQL 以 Calcite 规范快照输出（丢注释/排版） | v1 如此；v2 不改语义，文档如实标注（不属缺陷） |

> 修复顺序随 M5 里程碑；每一项都有独立回归用例纳入验收门。

## 7. 工程化能力（参考 Calcite / 常见 OSS 项目）

### 7.1 代码质量检查（Maven 生命周期，`mvn verify` 全绿为门）

| 维度 | 工具 | 说明 |
|---|---|---|
| 代码风格 | **Maven Checkstyle** | 单配置文件 `config/checkstyle/checkstyle.xml`；模块差异化 ignore 走 `suppressions.xml` |
| 格式固化 | **Spotless (google-java-format)** | `spotless:check` 进 verify（可 `spotless:apply` 一键整理） |
| 静态分析 | **SpotBugs** | 高置信规则集；门禁 exclude 走 `spotbugs-exclude.xml` |
| License 头 | **maven-rat-plugin (Apache RAT)** | 全源码与资源必须带项目 license 头（对照 Calcite 的 RAT 门禁） |
| 覆盖率 | **JaCoCo** | core/parser 行覆盖 ≥ 85%（服务层放宽 ≥ 70%），`jacoco:check` 进 verify |
| 依赖纪律 | **maven-enforcer-plugin** | Java 17、依赖收敛、禁止测试依赖泄漏进 runtime classpath |
| 依赖漏洞 | **OWASP dependency-check** | CI 周任务或主流水线可选门禁（不容忍 Critical） |
| 字节码门 | `maven.compiler -Werror` | 编译警告即失败（对照 Calcite 编译纪律） |

### 7.2 测试与契约

- 单元（JUnit 5）+ 集成分层：core 解析/改写/血缘/行过滤 → server 控制器（MockMvc）→ 存储（H2 真实库）→ 导入（嵌入式/测试容器型 DB 或自旋 JDBC 桩）。
- **契约测试**：REST 请求/错误体契约（错误码矩阵、GET 实例不含密文断言、导入 type 降级告警结构）。
- **黄金测试**：v1 的 TPC 全量 + 方言改写成例在服务端复用（同一 core，一次执行）。
- **安全测试**：明文断言（日志/响应/错误消息里无密码）、密文不可复用、密钥缺失 fail-closed。

### 7.3 CI/CD（GitHub Actions）

- `ci.yml`（PR + main push）：`setup-java@17` → `mvn -B verify`（含 §7.1 全部检查）→ 上传 JaCoCo 报告 → 失败即红。
- `docker.yml`（main push / tag `v*`）：build → SBOM 注解 → 推 `ghcr.io/<org>/mask-server`；tag 同时出版本镜像。
- `security.yml`（每周）：OWASP dependency-check + GITHUB token 报告。
- **发布**：Conventional Commits + git tag 语义化版本；`mvn versions:set` 由 tag 决定；`docs` 生成 API 文档随 tag 归档。
- 代码评审纪律：PR 必须过 `verify` + 契约测试。

### 7.4 API 文档与观测

- **springdoc-openapi**：服务启动内嵌 Swagger UI；契约同步到 `docs/api/mask-server-openapi.yaml` 归档。
- **可观测（最小）**：SLF4J 结构化日志（request-id 经 MDC，日志过滤器对密码字段 `***`）、Actuator health/info、优雅停机（`server.shutdown=graceful`）。

## 8. Docker 部署

### 8.1 镜像

多阶段 `Dockerfile`（对照常见 OSS 服务镜像惯例）：

```dockerfile
# build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY . .
RUN mvn -B -pl mask-server -am package -DskipTests
# runtime
FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S mask && adduser -S mask -G mask
USER mask
WORKDIR /app
COPY --from=build /src/mask-server/target/*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar app.jar"]
```

- 非 root（`USER mask`）、`-XX:MaxRAMPercentage`、healthcheck、OCI label + SBOM 注解；`.dockerignore` 排除 target/.git/docs 卷内容。

### 8.2 docker-compose

```yaml
services:
  mask-server:
    image: ghcr.io/<org>/mask-server:latest
    ports: ["8080:8080"]
    environment:
      MASK_MASTER_KEY_FILE: /run/secrets/mask-master-key
      POLICY_CONFIG_DIR: /app/config
      SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-}
    volumes:
      - mask-data:/app/data        # H2 持久化
      - ./config:/app/config:ro      # policies.yaml 挂载（只读）
    secrets:
      - mask-master-key
    depends_on:
      postgres: { condition: service_healthy }   # 仅 profile=pg 时启用
      ...
  postgres:        # 可选：profile=pg 时的元数据库
    image: postgres:16-alpine
    ...
```

- 配置项与环境变量表见 §8.3；主密钥经 Docker secret 或挂载文件注入；`POLICY_CONFIG_DIR` 为只读配置卷（维持「策略是配置」）。
- 部署文档包含：自签名 TLS 说明、`AUTO_SERVER` H2 单机限制（多副本 → PG profile）、备份（`/data` 卷 + 密文不可异地解密，需同主密钥）。

### 8.3 关键配置与环境变量

| 变量 | 缺省 | 说明 |
|---|---|---|
| `MASK_MASTER_KEY` / `MASK_MASTER_KEY_FILE` | 空 | 主密钥（Base64 32B）或文件；缺失禁用凭据能力（fail-closed） |
| `MASK_MASTER_PREV_KEYS` | 空 | 旧密钥列表（顺位），支持轮换期解密 |
| `POLICY_CONFIG_DIR` | `./config` | 策略 YAML 目录（只读挂载） |
| `SPRING_PROFILES_ACTIVE` | 空 | `pg` 切外部 PostgreSQL |
| `MASK_DS_URL/...`（pg profile） | — | 外部库连接 |
| `MASK_API_KEY` | 空 | 可选 `X-Api-Key` 校验（配置后 401） |
| `MASK_IMPORT_TIMEOUT_SECONDS` | 30 | 元数据导入超时 |

## 9. 与 v1 的衔接（复用映射）

| v1 工程 | 复用方式 |
|---|---|
| mask-core（改写管线/方言/血缘/行过滤/YAML） | 原样作为依赖引入 `mask-server`，不改语义；仅新增 Port 适配 |
| mask-parser（解析器 + 关键字注册表） | 原样；§6 缺陷修复即在此层做 |
| mask-cli / mask-embed-* | 保留 v1 交付物；v2 服务不依赖它们（embed 供引擎宿主后续版本用） |
| mask-introspect | 新增（服务 + 可选 CLI 共用） |

v1 文档 §13 的 v2 占位指向本文档。

## 10. 验收标准

1. **服务契约**：§5.1 REST 契约测试全绿（改写/实例/导入/错误码矩阵）；GET 实例不含密文明文断言通过。
2. **元数据导入**：五方言导入对着嵌入/桩库 golden（表/列/类型一致、`rawType` 保真、降级告警结构正确）；导入失败不动存量元数据。
3. **密码安全**：落库为 AES-GCM 密文；日志/响应/错误消息无明文断言；密钥缺失时凭据/导入接口 fail-closed；轮换（新旧钥）测试通过。
4. **解析缺陷修复**：§6 清单逐项有回归用例且通过（P0 全过、P1 全过、P2 文档化收敛）。
5. **工程化门**：`mvn -B verify` 全绿（checkstyle/spotless-check/spotbugs/rat/jacoco 阈值/enforcer/-Werror）；覆盖率达到阈值。
6. **Docker**：`docker build`（多阶段）成功 → 非 root 启动 → healthcheck 绿 → `docker compose up` 起服务 → 端到端 rewrite 冒烟；PG profile 可用。
7. **CI**：GitHub Actions 流水线绿（ci / docker / security）。
8. **回归**：v1 全部测试在 v2 基座上保持绿（服务化不回归改写语义）。

## 11. 任务拆分（粗粒度里程碑）

| 里程碑 | 内容 | 依赖/说明 |
|---|---|---|
| **M0** | v1 基座交付（独立工程全量绿） | v2 前置 |
| **M1** | SpringBoot 服务骨架：REST 契约、错误体、springdoc、Actuator；内嵌 H2 + Flyway | |
| **M2** | 元数据导入：实例 CRUD、五方言连接器（JDBC 只读）、类型映射 + `rawType` 保真、导入版本化 | |
| **M3** | 密码加密子系统：AES-GCM + 主密钥 + 不回传/日志纪律 + 轮换；安全测试 | |
| **M4** | 改写管线挂接：端口适配、策略配置加载/重载、实例 ↔ 策略绑定 | 依赖 M1 + v1 core |
| **M5** | v1 解析缺陷修复：§6 D1–D13 清单化 + 回归 | 在 mask-core/parser |
| **M6** | 工程化：checkstyle/spotless/spotbugs/rat/jacoco/enforcer/OWASP；GitHub Actions 全套 | |
| **M7** | Docker 交付：Dockerfile/compose/env 表/健康检查/补 DB profile | |
| **M8** | 验收与文档：§10 全门绿；API 文档归档；部署与运维 doc | |

## 12. 决策日志与风险

| # | 类型 | 内容 | 处置 |
|---|---|---|---|
| R1 | 边界纪律 | 「只改写不查询」容易在服务化后被破坏 | §3.3 双链路隔离：改写链路零 JDBC；导入链路口只在导入用例 |
| R2 | 安全 | 主密钥管理是运维责任，丢失/泄露即不可解或失密 | 文档 + Docker secret/挂载；轮换流程；`fail-closed` 缺失即禁凭据 |
| R3 | 安全 | AI 明文纪律：密码不得进日志/URL/错误消息/响应 | 日志过滤器 + 契约断言 + 安全测试（§7.2） |
| R4 | 数据 | H2 单机限制（多副本不一致） | `pg` profile；文档注明单副本用 H2 |
| R5 | 兼容 | Hive/Spark 走 HiveServer2(Docker)/ThriftServer JDBC，元数据表达有引擎差异（复杂类型、分区表） | rawType 保真 + 降级告警；分区/视图开关文档化 |
| R6 | 依赖 | SpringBoot 全家桶进服务，与经典 fat jar 体积/启动差异 | 服务与 CLI 双交付物；CLI 保持轻量 |
| R7 | 范围纪律 | audit / metrics / 策略服务化 / UI 不进 v2 | §3.2 显式排除 |
| R8 | 缺陷收敛 | §6 P1/P2 部分项是「维持 + 文档化」而非「修复」 | 以验收口径锁定，避免无界扩散 |

## 13. 未决 / v3 占位

v3 目标与范围设计同日期定稿：见 `docs/superpowers/specs/2026-09-21-sql-mask-v3-goal-design.md`。v3 在 v2 基座上做**策略服务化**（独立策略微服务 + 多实例 + 每策略版本回退 + 引擎直连 + 联想 + 通配符），显式推翻本设计的「策略维持配置文件、不做服务化」决策（本设计 §1 D5 / §3.2 / §5.5）。