# sql-mask v3 状态与待办清单

- 日期：2026-09-22
- 分支：`new-main`（已推 `origin/new-main`，最新 `9da5c18`，工作区干净）
- 性质：v3 自动执行周期的收尾文档；后续迭代的单一事实源 = 本页 + `docs/superpowers/plans/2026-09-21-sql-mask-v3-policy-service.md`

## 1. v3 已交付（已完成，勿重复）

`mask-policy`（整包重建，契约与旧版逐字节一致）+ `GlobMatcher` 支持 `?`；
`mask-policy-server`（8081）全新实现：

- 引擎直连与连接状态机（`POST /api/connections/test`、建实例先测连通 fail-closed、重测/更新/`metadata-fetch`；postgresql/mysql/trino）
- 实例/策略/UDF CRUD；`accessType=SELECT` 强制
- **每策略不可变版本历史 + 回退**（回退=写新版本，历史只增；`CONCURRENT_MODIFICATION` 乐观冲突 400；按 `(instance_id, policy_name)` 键控；删除保留历史、同名重建续版本）
- **联想**（`/api/instances/{i}/suggest`：table/column/udf，快照优先/live 兜底）
- **glob 资源**（`*`/`?`，编译期展开为具体表/列，生效配置绝不出现通配符）
- 按主体编译 `/api/effective/{i}`（mask-core 消费者零改动字节兼容，configVersion 一致重读）
- passwordRef 凭据约定；**无认证**
- 存储：PostgreSQL + `spring.sql.init`（`schema.sql` 幂等 + 追加式 ALTER）

**验证**：`policy` 81 / `core` 550 / `policy-server` 103 / `metadata` 99 / `query` 29 / `sqlparser` 26 / `audit` 37 = **925 测试 0 失败**。

**终审结论**：独立 reviewer 过全分支，5 Important 全部 TDD 修复；8/9 Minor 收口，1 项保留（见 §3-9）。

## 2. Deferred（v2 非 v3 依赖，未实现）

来自 Phase 0 v2 完成度核对表（`docs/superpowers/plans/2026-09-21-v2-completion-gate.md`）。用户最初意愿「v2 没完成就先完成 v2」，这些按"v3 非目标"暂缓，**可作为新一轮计划素材**：

| # | 项 | 规模/依赖 | 备注 |
|---|---|---|---|
| D1 | **Hive/SparkSQL 方言四件套**（解析器 + introspector + JDBC 驱动 + DialectProfile） | 最大；需独立计划 | 仓库现仅 pg/mysql/trino |
| D2 | **TPC 99+22 全量 golden**（× 五方言回验） | 依赖 D1 | v2 §10 主线门 |
| D3 | **AES-GCM 密码加密落库** + 主密钥轮换 | 自足（~0.5-1 天） | 现用 passwordRef；是否引入由产品定 |
| D4 | **`.github` CI 流水线**（ci/docker/security） | 自足；zonky 在 CI 可跑嵌入式 PG | 现无 |
| D5 | **Flyway/H2 迁移** | 低；与 D4/存储策略相关 | 现 PG+spring.sql.init，见 §技术取向 |
| D6 | **v1/v2 解析缺陷修复清单**（v2 §6 D1–D13） | 中；在 mask-core/parser | 含 P0 歧义列/重复输出列名等 |

## 3. 跨进程端到端缺口（未跑）

「jar + PG 双服务」冒烟（起 policy 服务 + 真 PG → 建连接实例 → 策略版本回退 → effective → mask-core instance 改写）因**当前环境无 Docker、无本机 PG** 未能执行。已以分层验证替代：

- HTTP 全流程契约：`web.V3PolicyFlowTest`（MockMvc，InMemory）
- 真实 PG 存储层：`JdbcPolicyStoreTest`（embedded-postgres）
- 真实 PG 直连/拉表：`JdbcEngineAccessTest`（embedded-postgres）
- 消费者回归：mask-core 550（instance 模式 / LRU / stale / fail-closed）

**待办**：在有 Docker 或可达 PG 的环境补一次 `docker-compose.policy.yml`（或本地 jar + PG）端到端冒烟，并核对其 `policy.Dockerfile` 与 compose 仍有效。

## 4. 保留未修的终审 Minor #9

- **v2→v3 ALTER 升级部署的回退缺历史**：`schema.sql` 对已存在的旧库做追加式 ALTER（`current_version` 默认 1），但旧策略行没有 `policy_version` 历史 → 升级后立即回退会 `VERSION_NOT_FOUND`。全新部署无此问题。**保留不修**（属部署迁移策略，不引入迁移复杂度）；若将来要支持无缝升级，需补一次性历史回填。

## 5. 技术取向备忘（后续计划应延续）

- 存储沿用 **PostgreSQL + `spring.sql.init`**（幂等 + 追加式 ALTER）；重新评估 Flyway/H2 前先确认是否真要 H2 单机档。
- 密码沿用 **passwordRef**（环境变量名）；引入 AES-GCM 前先定主密钥运维与 fail-closed 语义。
- REST 沿用**无版本前缀**（`/api/...`）；`mask-core` 消费侧 `/api/effective/{i}` URL 与 `EffectiveConfigResponse` 契约不得破坏。
- `mask-policy`/`mask-policy-server` 为**整包重建**后的干净实现，评审 Ruling/修复均入 git 历史（`git log` 可查）。
- 编译需 `maven-compiler-plugin <parameters>true`（Spring Boot 3 反射取参名；mask-core 因只用 `@RequestBody` 未触发，勿删该插件配置）。

## 6. 后续方向占位（v4 候选，spec §11）

认证/多租户 · 非 SELECT accessType · Web 管理 UI · 推送式配置分发/长连接 · 策略成效可视化 · 审计事件。