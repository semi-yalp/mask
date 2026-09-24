# arch-v2 合并推送后的项目问题分析报告

> 时点：2026-09-25，origin/main @ `423f9cb`（feature/arch-v2 合并后）。
> 本报告是推送后的新一轮全面问题分析，接续 `docs/known-issues-v2.md`。

## 1. 状态总览

- origin/main 已包含 arch-v2 全部 11 个提交（模块化单体重构 + 分类分级 + UDF 中心 +
  授权 + 查询网关 SPI + 审计 JDBC + 认证可选化 + 前端适配 + 部署 + 文档 + 真机修复）。
- 全仓 `mvn verify` 绿（约 1000+ 用例：内核 432、metadata 123、policy-admin 152、
  query 48、server 168、audit 46、其余若干）；前端 vitest 55 + vue-tsc 严格构建通过。
- 验证记录：本地 H2 裸跑 E2E（改写/分类/授权/风控桥）+ 远程真机（47.100.166.158
  PG16 + 真实 crm 数据）脱敏/行过滤/UDF 发现全部通过。

## 2. 高优先级问题（建议尽快处理）

| # | 问题 | 影响 | 建议路径 |
|---|---|---|---|
| H1 | **rr 分支（继承策略功能）未适配新架构** | 主工作区所在的 rr 分支含 3 个提交（InheritedPolicyRegistrar、继承语句 fail-closed 等），基于旧微服务布局（mask-core/policy-server 路径）。这些功能不在 main | 以 arch-v2 拓扑重放：InheritedPolicyRegistrar 移入 mask-server；涉及 legacy 改写通道与策略审计 detail 的改动按新包结构重做；合并冲突点已在 abort 时确认（3 个文件位置冲突） |
| H2 | **主工作区 stash 未恢复** | stash@{0} 保存了合并前 rr 工作区的未提交改动（monitoring-metrics 实施等）。其中 risk/query 指标部分 arch-v2 已另行实现，其余（compose metrics 改动等）可能仍有价值 | 在 rr 分支 `git stash pop` 逐文件甄别；指标埋点部分建议对照新实现后丢弃 |
| H3 | **一键 compose 镜像未实际构建过** | docker/server.Dockerfile 为多阶段构建（node→maven→jre），依赖网络拉包；本机无 docker CLI 未能实测。`mvn package` 产物本身已验证 | 在有 Docker 的机器跑 `docker compose up -d --build`，修正可能的构建期细节（如 frontend build 内存、mvn go-offline 对 fmpp/javacc 生成的兼容） |
| H4 | **审计默认态与文档口径** | jar 默认 `AUDIT_ENABLED=false`（审计不落库），但进程内风控检测不受此开关影响（有意设计）。裸跑用户可能误以为"审计开=检测开" | deployment.md 已写差异；可在 `/actuator/health` 或启动日志显式打印当前审计/风控姿态 |

## 3. 中优先级问题

| # | 问题 | 说明 |
|---|---|---|
| M1 | **授权预检未接线** | grants 模型/编译/矩阵可用，但查询网关不比对授权（只做脱敏/行过滤）。接线点：QueryService 改写判定后，用 FROM 提取比对 grant_entry（风控 SqlFeatureExtractor 可复用，需下沉 mask-common） |
| M2 | **BlockService 仍走 HTTP 自环** | 单体默认 `risk.block.base-url=http://127.0.0.1:8080`；无认证时可用，**开启 simple/ldap 认证后自环 401**。需要 mask-risk 定义 BlockPolicyTarget 接口由 mask-server 绑定 PolicyService |
| M3 | **UDF 导入与已绑定函数的签名冲突** | 远程验证暴露：注册表已有 REGISTERED 签名（varchar(20)）时，引擎导入的 text 签名走 skipped 而不合并。可改进为"按列绑定需要做类型同族合并"或导入时提供 merge 策略参数 |
| M4 | **分类分级与风控敏感资产未打通** | 两套敏感数据（meta_classification 与 risk 敏感列注册表）并存；建议 classification HIGH 列一键同步为风控 SensitiveColumn |
| M5 | **分类分级启发式为英文列名规则** | 中文列名/注释识别（手机号/身份证等）未覆盖；规则表已集中，便于扩展 |
| M6 | **策略/元数据域的变更审计已接、授权/分类分级未接** | 写路径少一条 ADMIN_CHANGE 事件；接 AuditAdminHelper 即可 |
| M7 | **bench 全量 99×3 矩阵未在新 jar 重跑** | 资产已恢复；run-case.sh 仍按 lite/core 双引擎写死，需要改为 sqlmask 单体 CLI 口径后重跑并刷新 report/ |
| M8 | **远程真机仍是旧微服务** | 新架构以 9090 旁路进程验证（/opt/sqlmask/v2），正式切换需要停旧 4 服务、迁移两库数据到单库、更新 systemd 单元 |

## 4. 低优先级 / 长期

| # | 事项 |
|---|---|
| L1 | 前端响应式断点、risk 配色并入设计令牌、暗色模式、Playwright E2E |
| L2 | UDF 发现扩展 trino（system.metadata.functions）/hive（metastore）；导入类型映射放宽可配置 |
| L3 | 授权 apply 接 hive/sparksql/trino（Ranger/Trino access-control 适配器） |
| L4 | 风控 PG store、告警 webhook 模板化、审计 ES ILM |
| L5 | 内核发行形态（嵌入查询引擎的 shaded/spi 包装）与 Trino coordinator 插件试点 |
| L6 | 新方言：doris 独立 profile、sqlserver（TOP 已备）、presto |
| L7 | 授权 GROUP 与 LDAP 组自动同步；策略的行过滤条件白名单扩展（LIKE/BETWEEN） |
| L8 | OIDC/IAM 对接（AuthProvider 接口已留，mask.auth.mode 扩展点） |

## 5. 值得注意的正面变化（问题面收窄）

- 改写热路径零 HTTP：原先 30s 轮询 + stale-but-available 的一致性窗口彻底消失，
  变为 configVersion/metadataVersion 版本探测的即时失效。
- API-Key 三面分钥匙匙管理、fail-open/fail-closed 语义混乱问题随体系退役而消失。
- 审计/风控/查询指标统一到单体 actuator；此前 query/risk 监控盲区消除。
- 合并冲突残留、README 与代码不一致等历史卫生问题清零。

## 6. 建议的处理顺序

1. rr 分支适配 + stash 甄别（H1/H2）——把主线工作收回新架构。
2. 有 Docker 的环境实测 compose（H3）并固化镜像版本。
3. M1 授权预检 + M2 阻断进程内化（安全闭环的两块短板）。
4. M4/M5 分类分级联动与中文名识别（数据资产运营价值）。
5. M7 bench 重跑 + M8 远程正式切换（带数据迁移演练）。
