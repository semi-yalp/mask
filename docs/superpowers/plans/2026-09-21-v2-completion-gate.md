# v2 完成度核对表（v3 开工闸门 Phase 0）

- 日期：2026-09-21 06:2x（v3 自动开工第一步）
- 依据：`docs/superpowers/specs/2026-09-21-sql-mask-v2-goal-design.md` §10 验收 / §11 里程碑
- 基线：HEAD `756414a`（audit-es-impl2 merge 后）；仓库基线 `mvn compile` 通过

## 逐条核对

| # | v2 §10 验收项 | 仓库现状（2026-09-21 06:20） | 判定 |
|---|---|---|---|
| 1 | 服务契约（rewrite/实例/导入/错误码矩阵；GET 实例不含密文明文） | mask-core 8080 `/api/rewrite` + instance 模式消费（`RewriteController`/`PolicyServiceConfigSource`/`InstanceConfigSources`）在位；`ConnectionSpec` 密码只走 JDBC properties，接口无密文回传 | ✅ 成立（v3 依赖项） |
| 2 | 元数据导入五方言 golden（rawType 保真） | `io.sqlmask.introspect`（mask-core 内）支持 **postgresql/mysql/trino**；**hive/spark 未有解析器/introspector/驱动**；rawType 仅存在于 `IntrospectionResult.ColumnInfo.originalPgType`，下游丢弃 | ⚠️ v2 未完成（hive/spark 属 v3 明确非目标 → **Deferred**） |
| 3 | 密码 AES-GCM 加密落库 + 轮换 | 仓库**无任何加密子系统**；全系统用 `passwordRef`（环境变量名）运行时解析（mask-metadata/mask-query/mask-policy-server 一致）；`System.getenv` 缺则 `METADATA_CREDENTIAL_UNAVAILABLE`/`CONNECTION_FAILED` | ⚠️ v2 未完成（v3 已决按 `passwordRef` 收敛 → 不引入加密，**v3 决策覆盖，记 Deferred/替换**） |
| 4 | 解析缺陷修复（v2 §6 D1–D13 回归） | 未发现对应逐项回归清单落地；v3 未把该项列为依赖 | ⚠️ v2 未完成（v3 非目标 → **Deferred**） |
| 5 | 工程化门（checkstyle/spotless/spotbugs/rat/jacoco/enforcer/-Werror） | 仅有 `mask-build-tools` 的 shade transformer（`SpringFactoriesTransformer`）；未见 checkstyle/spotbugs/rat/jacoco 门配置 | ⚠️ v2 未完成（v3 非目标 → **Deferred**） |
| 6 | Docker（多阶段 build/非 root/healthcheck/compose up/rewrite 冒烟；PG profile） | `docker/*.Dockerfile`（policy/metadata/query）+ `docker-compose.{policy,metadata,query,metrics}.yml` 在位；policy compose 含健康检查 / `AUDIT_ENABLED=false` | ✅ 基本成立（v3 复用） |
| 7 | CI（GitHub Actions ci/docker/security） | `.github/workflows` **缺失** | ⚠️ v2 未完成（v3 非目标 → **Deferred**） |
| 8 | 回归（v1 全部测试在 v2 基座绿） | 基线 `mvn compile` 通过；全测试回归由 v3 I 阶段统一跑 | ✅ 待 I3 全量回归确认 |

## 闸门结论

- **v3 依赖的 v2 基线成立**：instance-mode 消费（`PolicyServiceConfigSource`/`InstanceConfigSources`/`RewriteController.instance`）、三方言 `ConnectionSpec`/`MetadataIntrospectors`、PG + `spring.sql.init` 约定、Docker 编排，均在位且可编译。→ **直接进入 v3**。
- **Deferred（v2 未完成、v3 明确非目标，不顺手实现）**：
  1. Hive/SparkSQL 方言（解析器/introspector/驱动/DialectProfile 四件套缺失）
  2. AES-GCM 密码加密落库 + 主密钥轮换（v3 已决用 `passwordRef`）
  3. H2 默认/Flyway 迁移（v3 已决用 PG + `spring.sql.init`）
  4. `.github` CI 流水线
  5. TPC 99+22 golden / v1–v2 解析缺陷修复清单
- 无 v3 阻塞项；若运行中发现 v3 依赖缺口，按 v2 §11 最小口径先修再进。