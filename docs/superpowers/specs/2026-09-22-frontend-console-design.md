# 前端统一控制台设计(sql-mask console)

日期:2026-09-22
状态:已确认(范围/技术栈/风格/构建环境由使用者逐项选定)

## 1. 背景与目标

现有前端是两个互不相干的原生单文件页面:

- `frontend/policy-console.html`(852 行):策略管理台,nginx 反代到 mask-policy-server(8081);
- `mask-core/src/main/resources/static/index.html`(916 行):改写试验台,内嵌于 mask-core(8080)。

问题:无组件化、无类型、无构建;后端已有能力(改写 `/api/rewrite`、审计 `/api/audit/events`、查询 `/api/v1/query`)未被任何前端覆盖。

目标:重建 `frontend/` 为 **Vue 3 + Element Plus + Vite + TypeScript** 统一控制台,专业数据控制台风格(深色侧边栏 + 浅色内容区、蓝色主色、高信息密度),构建在远程主机(root@47.100.166.158,Ubuntu 24.04,Node 18.19,Docker 29)执行,本机零 JS 工具链。

范围外(本次不做):mask-core 内嵌页保留不动;mask-query(8083)不建页面;移动端适配只保证不破版。

## 2. 页面清单(功能无损迁移 + 新增)

| 路由 | 页面 | 内容 | 后端 |
|---|---|---|---|
| `/` | 总览 Dashboard | 实例/表/策略/UDF 统计卡、门禁徽章、快捷入口 | 8081 `/api/instances` 聚合 |
| `/instances` | 实例管理 | 实例列表 + CRUD + 载入示例实例 | 8081 |
| `/instances/:name` | 实例详情 | 四 Tab:表结构(可编辑表格+脏跟踪)、策略(Form+Drawer)、UDF(多签名)、生效配置(按主体编译预览 + 原始 JSON)、跨服务元数据导入 | 8081 |
| `/playground` | 改写试验台 | CodeMirror SQL 编辑器;instance 模式(选实例+user/groups)或内联模式(metadataYaml/policyYaml);改写结果逐语句展示 | 8080 `/api/rewrite` |
| `/audit` | 审计日志 | 过滤(eventType/outcome/instance/resourceType/action/user/时间范围≤7天)+ 分页表格;502 `AUDIT_SEARCH_UNAVAILABLE` 显示"审计未启用"引导卡片 | 8080 `/api/audit/events` |

`policy-console.html` 的全部既有行为必须保留:实例 CRUD、示例实例(crm + mask_phone)、表结构编辑(catalog/schema/name/rowFilter/列增删、脏状态、保存全部)、策略表单(datamask/row_filter、subjects users/groups、priority、isEnabled、UDF 参数数值字面量自动转型)、UDF 多签名编辑、生效配置按主体预览、metadata 导入表单、双 API Key 本地存储/清除与门禁徽章、toast 与页内错误双通道。

## 3. 构建与部署拓扑

```
Windows 本机(编辑代码,无 node)              远程 root@47.100.166.158
┌──────────────────────┐    deploy.sh     ┌─────────────────────────────────┐
│ frontend/ Vite 工程   │ --rsync--------→ │ ~/code/mask-frontend/            │
│                      │ (排除 node_modules│  npm install && npm run build    │
│                      │  与 dist)         │  → dist/                         │
└──────────────────────┘                  │ docker compose -f                 │
                                          │  docker-compose.frontend.yml      │
                                          │  up -d   (nginx:80 bind-mount dist)│
                                          └─────────────────────────────────┘
```

- 版本锁定 Node 18 兼容:Vite ^5.4、@vitejs/plugin-vue ^5、Vue ^3.5、vue-router ^4、pinia ^2、Element Plus ^2.8、@element-plus/icons-vue ^2、CodeMirror ^6 + @codemirror/lang-sql、TypeScript ~5.5、vue-tsc ^2、sass、vitest ^2、unplugin-auto-import + unplugin-vue-components(Element Plus 按需引入)。
- `frontend/deploy.sh`:rsync → 远程 `npm install --no-audit --no-fund` → `npm run build`(vue-tsc + vite build)→ `docker compose up -d frontend`。远程目录 `~/code/mask-frontend` 首次由脚本创建;ssh 参数 `root@47.100.166.158`(免密已验证)。
- `docker-compose.frontend.yml`:改为 bind-mount `./frontend/dist:/usr/share/nginx/html:ro` + 挂载 `frontend/nginx.conf`,每次迭代免重建镜像。
- `docker/nginx.Dockerfile`:COPY 源从 `frontend/` 收窄为 `frontend/dist/` + `frontend/nginx.conf`,保留纯镜像部署路径(生产可用多阶段构建,不再要求宿主机 node)。
- 远程 80 端口空闲(已确认,仅 9200/5432 在监听)。

## 4. API 路由修正(nginx.conf)

调查结论:`/api/audit/events` 的实现在 mask-core(`mask-core/.../server/AuditQueryController.java:24`),mask-policy-server 无审计查询端点;现 nginx.conf 把 `/api/audit` 指到 8081 是错误路由。修正并启用:

| 前缀 | 上游 | 变化 |
|---|---|---|
| `/api/instances`、`/api/effective` | 8081 | 不变 |
| `/api/rewrite` | 8080 | 由注释变启用 |
| `/api/audit` | **8080** | 由 8081 改指 8080 并启用 |
| `/api/v1/` | 8083 | 保持注释(未建页面,预留) |
| `/api/metadata`、`=/api/metadata/pull` | 8082 / 8080 | 保持注释(元数据导入走 8081 的 `/api/instances/{name}/import-metadata`,由服务端回调 8082) |
| `/` | 静态 | 加 `try_files $uri /index.html`(SPA history 路由兜底) |

## 5. 前端内部结构

```
frontend/
  package.json  vite.config.ts  tsconfig.json  tsconfig.node.json  index.html
  deploy.sh  nginx.conf  .gitignore
  src/
    main.ts  App.vue
    router/index.ts                # createWebHistory,4 路由
    stores/settings.ts             # adminKey/dataKey + localStorage 持久化 + 门禁派生
    stores/instances.ts            # 列表 + 当前实例 + 加载态
    api/http.ts                    # fetch 封装:X-Api-Key 注入、非 2xx 规范化 {code,message}
    api/{instances,policies,udfs,effective,rewrite,audit}.ts   # 全部带 TS 类型
    types/domain.ts                # Instance/TableDef/Policy/Udf/EffectiveConfig/AuditEvent/RewriteResult…
    layouts/ConsoleLayout.vue      # 深色侧边栏(logo/导航/门禁徽章/Key 设置对话框)+ 顶栏
    views/Dashboard.vue
    views/instances/InstanceList.vue
    views/instances/InstanceDetail.vue   # el-tabs 四页
    views/instances/{TablesTab,PoliciesTab,UdfsTab,EffectiveTab}.vue
    views/playground/Playground.vue
    views/audit/AuditView.vue
    components/{SqlEditor,CodeBlock,ErrorAlert,EmptyHint}.vue
    styles/theme.scss              # CSS 变量:主色 #2563eb、侧边栏 #0f172a、内容底 #f4f6fb
  tests/
    http.spec.ts  settings.spec.ts  audit-query.spec.ts
```

- API Key 存 localStorage(`mask-policy-console-keys`,沿用现 key 名,老用户无感);X-Api-Key 按 admin(实例/策略/UDF/改写内联)与 data(/api/effective)两个角色注入。
- Element Plus zh-CN locale; ElMessage/ElMessageBox 承担 toast 与确认框(替换原生 confirm)。
- vite dev 代理:`/api/instances|/api/effective` → `127.0.0.1:8081`,其余 `/api` → `127.0.0.1:8080`,可用环境变量覆盖(远程验证时经 SSH 反向隧道指到隧道端口)。
- TS strict 开启;vue-tsc 随 build 执行,类型错误即构建失败。

## 6. 错误处理

- 后端错误体 `{code, message}` 规范化为 `[CODE] message` 展示(与现页面一致)。
- 网络/服务不可达:页内 ErrorAlert + ElMessage 双通道;列表页失败不阻塞其他 Tab。
- 审计 502/`AUDIT_SEARCH_UNAVAILABLE`:转为"审计未启用"引导卡片(说明 `audit.enabled=true` + ES 依赖),不算错误弹窗。
- 改写失败(解析错误等)在结果区展示完整错误信息,保留输入不丢失。

## 7. 测试与验收

1. 单元测试(vitest):http.ts 错误规范化;settings store 持久化与门禁派生;audit 查询参数构造(默认 24h 窗口、7 天上限、分页边界)。
2. 构建门禁:远程 Node 18.19 上 `npm run build` 通过(含 vue-tsc)。
3. 端到端目检:本机 `mvn -pl mask-core,mask-policy-server -am package` 后起 8080/8081 两服务,SSH 反向隧道暴露到远程,浏览器经隧道访问远程 nginx/ vite 页面,覆盖:示例实例创建→表结构编辑→策略/UDF 增删改→生效配置预览→改写试验台出结果→审计降级卡片;截图过视觉验收。
4. 回归底线:老页面删除后,`frontend/nginx.conf` 首页指向新控制台 `/`。

## 8. 已定决策记录

- 范围:统一控制台(含改写试验台与审计);不合并 mask-core 内嵌页。
- 技术栈:Vite + Vue 3 + Element Plus + TS。
- 视觉:专业数据控制台(深侧边栏/浅内容、#2563eb 主色、高密度)。
- 构建环境:远程主机 root@47.100.166.158(不在本机装 node)。
