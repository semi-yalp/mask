# LDAP 用户认证授权设计（sql-mask 控制台）

> 状态：定稿 v1（2026-09-24）。基线 `full-test@05c1be9`，实施于分支 `feat/ldap-auth`
> （隔离 worktree，主工作区同期在进行 v2 重构，见 `docs/refactor-plan.md`）。

## 0. 目标与非目标

**目标**

1. 控制台用户用企业 LDAP / AD 账号（用户名 + 密码）登录，取代"往设置页贴 API Key"的人工模式；
2. 授权：LDAP 组 → 控制台角色（ADMIN / AUDITOR / USER），按 路径 × 方法 × 最低角色 放行各 API 面；
3. 数据面主体绑定：登录用户的 user / groups 从令牌派生，策略 `subjects` 按**真实身份**生效，
   不再信任调用方自报的 `?user=&groups=` / 请求体字段；
4. 与既有 API Key 体系**共存**：服务间调用（core↔policy、query→metadata 等）继续走 `X-Api-Key`；
   不配置 LDAP 时各服务行为与现状逐字节一致（零回归，匿名模式部署形态不受影响）。

**非目标（YAGNI）**

- 不引入 Spring Security（与仓库手写过滤器 + 常量时间比较的既有惯例一致，避免整树依赖与语义变更）；
- 不做刷新令牌 / 服务端会话 / 令牌吊销列表（短 TTL 无状态令牌，登出 = 前端丢弃）；
- 不做 LDAP 用户同步入库（每次登录即时查组，无本地用户表、无 schema 变更）；
- OIDC / SAML / 多因素不在范围。

## 1. 架构

```
浏览器 ──(1) POST /api/auth/login {username,password}──▶ mask-policy-server(8081)
            │  LdapAuthenticator: bind 检索用户 DN → 用户 DN+密码 bind → 取组 → 映射角色
            ◀──(2) {token(HS256), user:{username,displayName,role,groups}, expiresIn} ──┘
浏览器 ──(3) Authorization: Bearer <token> ─▶ nginx/vite ─▶ 8080/8081/8082/8083
            BearerAuthFilter(order 0): 验签+过期+角色规则 → 请求属性(principal, audit.*)
            既有 ApiKeyFilter(order 1+): 见 Bearer 标记即放行（否则按 X-Api-Key 老路径）
数据面控制器: 请求属性有 principal → Subject(user,groups) 用令牌值，忽略调用方自报值
```

新增 Maven 模块 `mask-auth`（`io.sqlmask.auth`，纯库）：**不含任何 Spring 构造型注解**
（mask-core 按 `io.sqlmask` 通配扫描，库类绝不能被动注册），由各服务显式 `@Bean` 装配，
与现有 FilterRegistrationBean 手工装配风格一致。依赖只有
`com.unboundid:unboundid-ldapsdk`（MIT，含测试用内存 LDAP 服务器）、
`jackson-databind`（令牌 claims 编解码）与 `provided` 的 `jakarta.servlet-api`。

## 2. 组件

### 2.1 AuthTokenService —— JWT 兼容 HS256 令牌

手写紧凑 JWS（`base64url(header).base64url(claims).base64url(hmac)`），标准头
`{"alg":"HS256","typ":"JWT"}`，claims：`sub`（用户名）、`name`（显示名）、
`role`（ADMIN/AUDITOR/USER）、`groups`（LDAP 组名数组）、`iat`、`exp`。
HMAC-SHA256，签名比较走 `MessageDigest.isEqual`（常量时间，与既有过滤器同款纪律）。
校验规则：alg 必须为 HS256（拒绝 `none`）、签名必须匹配、`exp` 必须未过期、
`sub/role/groups` 必须非空合法。密钥来自 `MASK_AUTH_SECRET`（≥32 字节，短了启动即拒），
TTL `MASK_AUTH_TOKEN_TTL`（ISO-8601 duration，默认 `PT8H`）。
选 JWT 兼容格式而非不透明串：日后可无缝换标准库 / 对接网关，语义不锁死。

### 2.2 LdapAuthenticator —— UnboundID SDK

登录流程（全部 fail-closed，LDAP 不可达 = 登录失败，无旁路）：

1. 以可选的服务账号（`MASK_AUTH_LDAP_BIND_DN/_BIND_PASSWORD`，匿名检索亦可）在
   `MASK_AUTH_LDAP_USER_SEARCH_BASE`（缺省 = base dn）下按
   `MASK_AUTH_LDAP_USER_FILTER`（缺省 `(uid={0})`；AD 用 `(sAMAccountName={0})`）
   检索用户条目，命中 0 个 = 凭据无效，>1 个 = 配置歧义报错；
2. 用找到的用户 DN + 用户密码做 LDAP bind——LDAP 世界里唯一可信的密码验证方式；
   空密码直接拒绝（防匿名 bind 假成功）；
3. 取组：配置了 `MASK_AUTH_LDAP_GROUP_SEARCH_BASE` → 搜
   `MASK_AUTH_LDAP_GROUP_FILTER`（缺省 `(member={dn})`）取组条目；未配置 → 读用户条目
   `memberOf` 属性（AD 风格）。两种模式的组标识统一取组 DN 的**首个 RDN 值**
   （`cn=mask-admins,ou=groups,...` → `mask-admins`）；
4. 角色映射：组 ∈ `MASK_AUTH_ADMIN_GROUPS` → ADMIN；∈ `MASK_AUTH_AUDITOR_GROUPS` →
   AUDITOR；否则 USER。两组皆空时所有登录用户均为 USER（写操作无人可用，配置期即暴露）。

连接超时 `MASK_AUTH_LDAP_CONNECT_TIMEOUT` / 响应超时 `MASK_AUTH_LDAP_RESPONSE_TIMEOUT`
（默认 3s / 5s）；`ldaps://` URL 即走 TLS（信任 JVM 缺省信任库）。密码只存在于
`char[]`，不落日志、不进审计。

### 2.3 BearerAuthFilter + AuthRule —— 每服务装配的鉴权过滤器

注册于 order 0（先于既有 ApiKeyFilter 的 order 1/2）。语义：

- **无 `Authorization: Bearer` 头** → 原样放行，走既有 API Key / 开放路径，行为不变；
- **有且有效** → 写请求属性 `auth.principal`（AuthPrincipal）、`audit.authKind=LDAP`、
  `audit.user`、`audit.groups`，并打 `auth.bearer` 标记（既有各 ApiKeyFilter 顶部加
  一个 2 行豁免：见标记即放行）；随后按该服务注册的规则表校验
  路径 × 方法 × 最低角色，不足 → `403 {"code":"FORBIDDEN",...}`；
- **有但无效 / 过期** → `401 {"code":"UNAUTHORIZED",...}`，**不回退** API Key（防降级）；
- 规则未覆盖的路径 → 放行（与"未列路径不设闸"的既有姿态一致；规则表按服务显式声明）。

规则 API：`AuthRules.builder().prefix(path, role).readOnly(path, readRole, writeRole).build()`；
路径匹配用 `getServletPath()` 前缀比较（与既有过滤器同一防御姿势：解码路径、
不吃 `%69` 编码绕过与 context-path 部署）。

### 2.4 登录端点（仅 policy-server 装配）

- `POST /api/auth/login` `{username, password}` → `200 {token, user:{username,
  displayName, role, groups}, expiresInSeconds}`；凭据错 → `401 INVALID_CREDENTIALS`
  （不区分"无此用户/密码错"，防用户枚举）；LDAP 不可达 → `503 LDAP_UNAVAILABLE`；
  未配置 LDAP → `501 LDAP_NOT_CONFIGURED`。
- `GET /api/auth/me`（带令牌）→ 当前 principal；令牌无效 401。
- `GET /api/auth/mode` → `{"ldap":true|false,"loginRequired":...}`：公开端点，
  前端据此决定是否强制登录（LDAP 未启用时保持 API Key 模式，老部署无感）。
- `/api/auth/**` 不在 policy-server 既有 ApiKeyFilter 的 URL pattern 内，天然免 key。

### 2.5 各服务接入与规则表

| 服务 | 规则表（prefix × 方法 → 最低角色） | 主体绑定控制器 |
|---|---|---|
| policy-server 8081 | `/api/instances/**` 读→USER、写→ADMIN；`/api/effective/**`→USER | EffectiveConfigController（?user/?groups 被令牌覆盖） |
| mask-core 8080 | `/api/audit/**`→AUDITOR；`/api/rewrite/instances/**`→USER；`/api/rewrite`→USER | InstanceRewriteController（body user/groups 被令牌覆盖） |
| mask-query 8083 | `/api/v1/query`→USER | QueryController（body user/groups 被令牌覆盖） |
| mask-metadata 8082 | `/api/**` 读→USER、写→ADMIN | — |

主体绑定统一写法：`AuthTokens.principal(request)` 非空 → `Subject.of(principal.username(),
principal.groups())`，否则沿用请求参数（API Key 调用方 / 匿名路径行为不变）。
审计随之自动正确：EFFECTIVE_PULL / REWRITE / QUERY 事件里的 user/groups 即真实登录身份，
`authKind=LDAP`。

### 2.6 配置清单（环境变量，沿用 `SQLMASK_*` 手工装配惯例）

| 变量 | 缺省 | 说明 |
|---|---|---|
| `MASK_AUTH_SECRET` | 空 | ≥32 字节；设置后才启用令牌签发/校验 |
| `MASK_AUTH_TOKEN_TTL` | `PT8H` | ISO-8601 duration |
| `MASK_AUTH_LDAP_URL` | 空 | `ldap://` / `ldaps://`；设置后才启用登录 |
| `MASK_AUTH_LDAP_BASE_DN` | — | 如 `dc=example,dc=org` |
| `MASK_AUTH_LDAP_BIND_DN` / `_PASSWORD` | 空 | 服务账号（可选，匿名检索亦可） |
| `MASK_AUTH_LDAP_USER_SEARCH_BASE` | base dn | 如 `ou=people` |
| `MASK_AUTH_LDAP_USER_FILTER` | `(uid={0})` | AD：`(sAMAccountName={0})` |
| `MASK_AUTH_LDAP_GROUP_SEARCH_BASE` | 空 | 设置→组检索模式；空→memberOf 模式 |
| `MASK_AUTH_LDAP_GROUP_FILTER` | `(member={dn})` | 仅组检索模式 |
| `MASK_AUTH_ADMIN_GROUPS` | 空 | 逗号分隔，命中→ADMIN |
| `MASK_AUTH_AUDITOR_GROUPS` | 空 | 逗号分隔，命中→AUDITOR |
| `MASK_AUTH_LDAP_CONNECT_TIMEOUT` / `_RESPONSE_TIMEOUT` | `PT3S` / `PT5S` | — |

## 3. 前端

- `src/stores/auth.ts`：token + user + 过期时间戳，持久化 `localStorage["mask-console-auth"]`；
  `login/logout/isLoggedIn/role`；`expiresIn` 到点自动视为未登录。
- `src/views/login/LoginView.vue`：登录页（不走 ConsoleLayout）；LDAP 未启用时提示并放行进入控制台（API Key 模式）。
- 路由：`/login` 独立路由 + 全局 `beforeEach`：LDAP 模式且未登录 → `/login?redirect=...`；
  已登录访问 `/login` → `/`。模式探测 `GET /api/auth/mode` 结果缓存在 store，探测失败按
  API Key 模式放行（后端不可用时不能把人锁死在登录页）。
- `http.ts`：有 token → 注入 `Authorization: Bearer`（优先）；无 token → 沿用按角色注入
  `X-Api-Key`。带 token 收到 401 → 清会话并跳登录页（会话过期统一体验）。
- `ConsoleLayout`：侧栏底部当前用户 + 角色徽标 + 退出登录；导航按角色显隐
  （访问管理/设置→ADMIN；审计→AUDITOR+；总览/试验台→USER+）。API Key 模式（无用户）显示全部。
- 网关：vite 代理与两份 nginx 配置加 `/api/auth` → 8081（置于泛 `/api` 规则之前）。

## 4. 部署样例

`docker/auth/`：`docker-compose.auth.yml` 起 OpenLDAP（osixia 镜像）+ 预置 `bootstrap.ldif`
（`ou=people` 三用户 amy(admin)/bob(auditor)/carol(user)、`ou=groups` 三组
`mask-admins`/`mask-auditors`/`mask-users`），README 写明各服务接入环境变量与 AD 差异要点。

## 5. 测试

- mask-auth 单测：令牌往返 / 篡改 / 过期 / 错密钥 / alg=none 拒绝；InMemoryDirectoryServer
  （UnboundID 自带）覆盖登录成功 / 错密码 / 无此用户 / 服务账号检索 / memberOf 与组检索
  双模式 / 角色映射 / 空密码拒绝；过滤器无头放行、有效放行 + 属性、403、无效 401。
- 服务级 MockMvc（policy-server 全链路 @SpringBootTest + 内存 LDAP）：
  登录 → 令牌访问管理面（GET/POST 越权矩阵）→ effective 主体被令牌覆盖（具名策略按登录者命中）→
  API Key 老路径回归；mask-core 审计面角色；mask-query 主体覆盖 + 审计带真实身份。
- 前端 vitest：auth store（登录态/过期/角色）、http（Bearer 注入、401 清会话）、路由守卫重定向。

## 6. 风险与对策

| 风险 | 对策 |
|---|---|
| 与在途 v2 重构（filter 收敛进 mask-common）合并冲突 | 本特性 95% 是新增文件；既有过滤器只加 2 行豁免；重构合入时把豁免逻辑并进统一 ApiKeyFilter 即可（已在重构方案 §2.2 的延长线上） |
| 令牌密钥泄露 | ≥32 字节强随机 + 只经环境变量注入 + 不进日志；轮换 = 换 `MASK_AUTH_SECRET`（旧令牌全部失效） |
| LDAP 探测/组提取与真实 AD 行为偏差 | 双模式（组检索 / memberOf）+ RDN 归一化；样例 compose 提供可复现环境 |
| 登录端点爆破 | 统一 401 不枚举用户；LDAP bind 天然按目录侧密码策略计数；后续可加限流（非本期） |
| 前端把人锁死在登录页 | 模式探测失败按 API Key 模式放行；`/login` 页提供返回入口 |
