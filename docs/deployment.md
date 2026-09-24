# sqlmask 安装部署文档（arch-v2 单体版）

> 一个 jar = 全部 API + 控制台；一个容器 = 可运行的完整平台。
> 默认姿态：**无认证、H2 文件库、审计入 JDBC**——先跑起来，再按需加固。

## 1. 形态一览

| 形态 | 适用 | 依赖 |
|---|---|---|
| docker compose 一键 | 推荐 | Docker |
| 裸 `java -jar` | 开发/演示 | JDK 17+（零配置,内嵌 H2） |
| 外接 PostgreSQL | 生产 | PG 16+ |

端口只有一个：`8080`（compose 映射到宿主 80）。

## 2. 一键部署（docker compose）

```bash
export MASK_STORAGE_PG_PASSWORD='change-me'
docker compose up -d --build
# 打开 http://<host>/
```

- 启动包含 `postgres` + `sqlmask` 两个服务；健康检查通过即可访问。
- 数据落在 `pgdata` 卷（结构/策略/授权/审计）与 `appdata` 卷（风控快照/H2 场景备用）。
- 可选 profile：
  - `--profile ldap`：OpenLDAP 演示目录（3890），配合 `MASK_AUTH_LDAP_URL=ldap://openldap:389`。
  - `--profile es`：审计走 Elasticsearch（需同时 `AUDIT_STORE=es`）。
  - `--profile tools`：Prometheus（抓 `sqlmask:8080/actuator/prometheus`）。

## 3. 裸跑（零配置）

```bash
mvn -pl mask-server -am package -DskipTests
java -jar mask-server/target/sqlmask-server.jar
# http://127.0.0.1:8080 —— H2 文件库在 ./data/sqlmask*
```

## 4. 环境变量全表

### 4.1 存储
| 变量 | 默认 | 说明 |
|---|---|---|
| `MASK_STORAGE_PG_URL` | _(空=H2 文件)_ | 如 `jdbc:postgresql://pg:5432/sqlmask` |
| `MASK_STORAGE_PG_USER` / `MASK_STORAGE_PG_PASSWORD` | — | PG 凭据 |

### 4.2 认证（`mask.auth.mode` / `MASK_AUTH_MODE`）
| 模式 | 必需变量 | 行为 |
|---|---|---|
| `none`（默认） | — | 全开放；审计 `authKind=ANONYMOUS` |
| `simple` | `MASK_AUTH_SECRET`(≥32B,建议) | 本地用户表；首启建 **admin/admin**（务必改密）;`/api/auth/users` 管理用户 |
| `ldap` | `MASK_AUTH_SECRET` + `MASK_AUTH_LDAP_URL` + `MASK_AUTH_LDAP_BASE_DN` | LDAP 登录；`MASK_AUTH_ADMIN_GROUPS`/`MASK_AUTH_AUDITOR_GROUPS` 映射角色 |

全部 `MASK_AUTH_*` 变量（TTL/绑 定 DN/过滤器等）与角色门禁规则见 `mask-auth` 模块 README 段落。

### 4.3 审计
| 变量 | 默认 | 说明 |
|---|---|---|
| `AUDIT_ENABLED` | `true`(compose) | 总开关 |
| `AUDIT_STORE` | `jdbc` | `jdbc` / `es` |
| `AUDIT_ES_URL` 等 `AUDIT_*` | — | 仅 `store=es` 时生效 |

### 4.4 风控 / 查询网关
| 变量 | 默认 | 说明 |
|---|---|---|
| `RISK_SEED_ON_START` | `false` | 演示数据播种 |
| `RISK_BLOCK_BASE_URL` | `http://sqlmask:8080` | 一键阻断的自环地址 |
| 实例级 `submitter` / `onRewriteFailure` / `topN` / `insertOverwrite` | `jdbc`/`REJECT`/—/— | 在"元数据服务"页按实例配置 |

## 5. 认证开启步骤（simple 模式示例）

```bash
export MASK_AUTH_MODE=simple
export MASK_AUTH_SECRET='please-generate-a-random-32byte-secret!!'
java -jar mask-server.jar
# 首启日志提示 admin/admin；登录后到 设置→用户管理 改密、建用户
```

LDAP 模式联调：`docker compose --profile ldap up -d` +
`MASK_AUTH_LDAP_URL=ldap://openldap:389`、`MASK_AUTH_LDAP_BASE_DN=dc=example,dc=org`
（演示用户 amy/bob/carol,见 `docker/auth/bootstrap.ldif`）。

## 6. 升级与备份

- **备份**：PG 部署 `pg_dump sqlmask`；H2 部署停进程后拷 `data/sqlmask.mv.db`。
- **升级**：换 jar/镜像重启即可；schema 由 `spring.sql.init` 幂等脚本自动补列（`ADD COLUMN IF NOT EXISTS`）。
- **降级**：schema 只增不改，旧版本读新库通常兼容，但新增功能列会被忽略。

## 7. 故障排查

| 现象 | 处置 |
|---|---|
| `/actuator/health` 不 UP | 看 `MASK_STORAGE_PG_*` 是否可达；compose 中等 postgres healthcheck |
| 控制台空白 | jar 内未打前端（本机构建时先 `cd frontend && npm run build` 再 package） |
| 查询台实例全部禁用 | 实例未登记连接：元数据服务→连接信息 |
| 改写报 `POLICY_…` | 实例在策略中心未同步结构：策略管理器→从元数据导入 |
| 审计无数据 | 确认 `AUDIT_ENABLED=true` 与 `AUDIT_STORE` 匹配（es 需要 `--profile es`） |
| LDAP 登录 503 | 目录不可达或 bind 配置错;先 `GET /api/auth/mode` 看 posture |

## 8. 从旧版（微服务）迁移

旧 5 服务 compose 已归档至 `deploy/legacy/`。数据迁移要点：
- 旧 `mask_policy`/`mask_metadata` 两个库的表结构与单体一致（同名表），可用 PG 逻辑复制/导出导入合并进单库。
- API Key 体系已退役；控制台改用 Bearer（或无认证）。
- 元数据管理面路径由 `/api/instances` 改为 `/api/meta/instances`（前端已适配）。
