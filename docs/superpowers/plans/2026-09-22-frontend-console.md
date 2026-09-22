# 前端统一控制台实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `frontend/` 重建为 Vue 3 + Element Plus + Vite + TS 统一控制台(Dashboard/实例管理/改写试验台/审计),构建在远程主机执行,nginx 部署,功能无损迁移旧 policy-console.html。

**Architecture:** 标准 Vite SPA(createWebHistory)+ pinia(settings/instances)+ 按域拆分的类型化 api 模块;nginx bind-mount dist 并反代 8080/8081;deploy.sh 负责 rsync→远程构建→compose 启动。

**Tech Stack:** Vite ^5.4 / Vue ^3.5 / Element Plus ^2.8 / pinia ^2 / vue-router ^4 / CodeMirror ^6 / TypeScript ~5.5 / vitest ^2;远程 Node 18.19。

**Spec:** `docs/superpowers/specs/2026-09-22-frontend-console-design.md`

## Global Constraints

- 本机(Windows)无 node:**所有 npm/vitest/构建命令经 `bash frontend/deploy.sh sync && ssh root@47.100.166.158 "cd ~/code/mask-frontend && <cmd>"` 在远程执行**(Task 1 建立 sync 通道后可用;Task 1 自身先手动建目录)。
- 依赖版本必须 Node 18 兼容:Vite ^5.4(禁 6+/7)、TS ~5.5、pinia ^2(禁 3)。
- 保留行为清单见 spec §2(旧页全部功能无损)。
- 审计查询上游是 **8080**(mask-core),不是 8081。
- 主色 `#2563eb`,侧边栏 `#0f172a`,内容底 `#f4f6fb`;Element Plus zh-CN。
- TS strict;`npm run build` = `vue-tsc -b && vite build`,类型错误即失败。
- 每个任务结束 `git commit`;分支 `feature/frontend-console`(从当前 `feature/fix2` 切出)。

---

### Task 1: 工程骨架与远程构建通道

**Files:**
- Create: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/tsconfig.node.json`, `frontend/index.html`, `frontend/.gitignore`, `frontend/src/main.ts`, `frontend/src/App.vue`, `frontend/src/router/index.ts`, `frontend/src/styles/theme.scss`, `frontend/src/vite-env.d.ts`, `frontend/deploy.sh`
- Delete: 无(旧页面 Task 11 删)
- Modify: 无

**Interfaces:**
- Produces: 可空的 `App.vue` + router(4 路由占位视图);`deploy.sh` 子命令 `sync`(rsync 源码到 `root@47.100.166.158:~/code/mask-frontend`,排除 node_modules/dist)。后续所有任务的"远程验证"= `deploy.sh sync` + ssh 执行 npm 命令。

- [ ] **Step 1: 写 package.json 与配置**

```jsonc
// frontend/package.json (核心字段)
{
  "name": "sql-mask-console",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc -b && vite build",
    "test": "vitest run",
    "preview": "vite preview"
  },
  "dependencies": {
    "@codemirror/lang-sql": "^6.8.0",
    "@element-plus/icons-vue": "^2.3.1",
    "codemirror": "^6.0.1",
    "element-plus": "^2.8.8",
    "pinia": "^2.2.6",
    "vue": "^3.5.13",
    "vue-router": "^4.4.5"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.2.1",
    "@vue/test-utils": "^2.4.6",
    "jsdom": "^25.0.1",
    "sass": "^1.81.0",
    "typescript": "~5.5.4",
    "unplugin-auto-import": "^0.18.6",
    "unplugin-vue-components": "^0.27.5",
    "vite": "^5.4.11",
    "vitest": "^2.1.8",
    "vue-tsc": "^2.1.10"
  }
}
```

`vite.config.ts`:plugins `[vue(), AutoImport({imports:['vue','vue-router'],resolvers:[ElementPlusResolver()],dts:'src/auto-imports.d.ts'}), Components({resolvers:[ElementPlusResolver()],dts:'src/components.d.ts'})]`;`resolve.alias {'@': '/src'}`;`test` 段 `environment:'jsdom'`;dev `server.proxy`:`'/api/instances'`、`'/api/effective'` → `env.VITE_PROXY_8081 ?? 'http://127.0.0.1:8081'`,其余 `'/api'` → `env.VITE_PROXY_8080 ?? 'http://127.0.0.1:8080'`。

- [ ] **Step 2: 写入口/路由/主题占位**

`src/main.ts`:createApp + pinia + router + `element-plus/theme-chalk/el-... `经 unplugin 按需;locale 用 `zhCn`(`element-plus/es/locale/lang/zh-cn`)传入 `ElConfigProvider`(放 App.vue)。`router/index.ts`:`createWebHistory()`,路由 `/`(Dashboard)、`/instances`、`/instances/:name`(props:true)、`/playground`、`/audit`,全部懒加载 `() => import(...)`,外层 `ConsoleLayout`(先占位空组件)。`theme.scss`:CSS 变量 `--sm-primary:#2563eb; --sm-sidebar:#0f172a; --sm-bg:#f4f6fb;` 与全局 reset。

- [ ] **Step 3: 写 deploy.sh(先只做 sync 子命令)**

```bash
#!/usr/bin/env bash
# frontend/deploy.sh — rsync 源码到远程构建主机
set -euo pipefail
HOST="${DEPLOY_HOST:-root@47.100.166.158}"
DEST="${DEPLOY_DEST:-~/code/mask-frontend}"
cd "$(dirname "$0")"
ssh "$HOST" "mkdir -p ${DEST}"
rsync -az --delete --exclude node_modules --exclude dist --exclude '.git' \
  ./ "$HOST:${DEST}/"
echo "synced to ${HOST}:${DEST}"
```

- [ ] **Step 4: 远程验证骨架**

Run: `cd frontend && bash deploy.sh sync && ssh root@47.100.166.158 "cd ~/code/mask-frontend && npm install --no-audit --no-fund && npm run build"`
Expected: install 无 error,build 产出 `dist/`(含 index.html)。

- [ ] **Step 5: Commit**

```bash
git add frontend && git commit -m "feat(frontend): Vite+Vue3+TS 工程骨架与远程构建通道"
```

### Task 2: domain 类型、http 层、settings store(TDD)

**Files:**
- Create: `src/types/domain.ts`, `src/api/http.ts`, `src/stores/settings.ts`
- Test: `tests/http.spec.ts`, `tests/settings.spec.ts`

**Interfaces:**
- Produces: `call<T>(method, url, body?, role?: 'admin'|'data'): Promise<T>`(错误 throw `Error('[CODE] message')`);`useSettingsStore()`:`{adminKey, dataKey, persist(), load(), getHeader(role)}`;domain 类型 `InstanceInfo{name,dialect,tables:TableDef[]}`, `TableDef{catalog,schema,name,rowFilter?,columns:ColumnDef[]}`, `ColumnDef{name,type}`, `Policy`, `Udf`, `EffectiveConfig`, `AuditEvent`, `RewriteResponse{statements,rewrittenSql}`(字段名逐一对齐旧页 collect/openPolicyForm 所读写的 JSON:`policyType,isEnabled,priority,resource{catalog,schema,table,columns},subjects{users,groups},udf,arguments,filterExpr;signatures[{params,returns}]`)。

- [ ] **Step 1: 写失败测试**(断言:http 非 2xx 且响应体 `{code,message}` → throw `[CODE] message`;body 无 code → `HTTP <status>`;X-Api-Key 仅在 key 非空时注入,admin/data 按角色;settings persist→load 往返、门禁派生 `gateConfigured`)

- [ ] **Step 2: 远程跑测试确认失败**(`deploy.sh sync && ssh ... "npm test"`,Expected: FAIL 模块不存在)

- [ ] **Step 3: 最小实现**(`http.ts` 用全局 fetch;`settings.ts` pinia + localStorage key `"mask-policy-console-keys"`——与旧页一致,老用户无感)

- [ ] **Step 4: 远程跑测试通过** → **Step 5: Commit** `feat(frontend): 类型化 http 层与 API Key settings store`

### Task 3: 按域 api 模块(TDD:audit 查询构造)

**Files:**
- Create: `src/api/instances.ts`, `src/api/policies.ts`, `src/api/udfs.ts`, `src/api/effective.ts`, `src/api/rewrite.ts`, `src/api/audit.ts`
- Test: `tests/audit-query.spec.ts`

**Interfaces:**
- Consumes: Task 2 `call`。
- Produces:
  - `instances.ts`:`listInstances()`, `getInstance(name)`, `createInstance(name,dialect)`, `deleteInstance(name)`, `putTables(name,tables)`, `importMetadata(name,{metadataBaseUrl,metadataInstance,metadataApiKey})`(POST `/api/instances/{name}/import-metadata`)
  - `policies.ts`:list/create/update(name 路径参数 encodeURIComponent)/remove;`udfs.ts` 同构;`effective.ts`:`getEffective(instance,{user,groups})`(groups 逐个 `groups=` query 参数,role:'data')
  - `rewrite.ts`:`rewrite({metadataYaml?,policyYaml?,instance?,user?,groups?,sql})` POST `/api/rewrite`
  - `audit.ts`:纯函数 `buildAuditQuery(filters,page,size): string`(默认 to=now、from=to-24h;from<to;范围>7d 抛 `Error('时间范围不能超过 7 天')`;page≥0,size 1..200)+ `searchAudit(...)` GET `/api/audit/events?...`
- 数值参数转型沿用旧页:`arguments` 逐项 `^-?\d+(\.\d+)?$` → int/float,否则字符串。

- [ ] **Step 1: 写 audit 查询构造失败测试**(默认窗口、显式 from/to、超 7 天报错、size 越界报错、groups 多值编码)
- [ ] **Step 2: 远程确认失败** → **Step 3: 实现 6 个 api 模块** → **Step 4: 远程测试通过** → **Step 5: Commit** `feat(frontend): 按域类型化 API 客户端(含审计查询构造)`

### Task 4: ConsoleLayout(侧边栏/门禁/Key 对话框)

**Files:**
- Create: `src/layouts/ConsoleLayout.vue`, `src/components/CodeBlock.vue`(pre.json 等价物), `src/components/ErrorAlert.vue`, `src/components/EmptyHint.vue`
- Modify: `src/router/index.ts`(children 挂到 ConsoleLayout)

**Interfaces:**
- Consumes: `useSettingsStore`。
- Produces: 全局布局——深色侧边栏(logo "sql-mask · 控制台"、4 导航项 router-link、底部门禁徽章 + "API Key" 按钮 → el-dialog 双密码框 + 保存/清除);内容区 `<router-view/>`。`ErrorAlert{error:string}` 封装页内红条;`CodeBlock{code:string}` 深色 pre。

- [ ] **Step 1: 实现 4 组件 + 布局**(结构对齐旧页 header 的 keybox/gate-badge 交互:任一 key 非空 → 徽章"门禁:已配置"高亮,否则"门禁:未配置(开放)")
- [ ] **Step 2: 远程 build 通过**(`npm run build`,类型即门禁)→ **Step 3: Commit** `feat(frontend): 控制台布局与共享组件`

### Task 5: 实例列表页 + Dashboard

**Files:**
- Create: `src/stores/instances.ts`, `src/views/instances/InstanceList.vue`, `src/views/Dashboard.vue`
- Modify: `src/api/instances.ts`(如需补字段)

**Interfaces:**
- Consumes: instances api + settings store。
- Produces: `useInstancesStore(): {list, loading, load(), current, select(name)}`;InstanceList = el-card 列表(名称/dialect/表数、active 高亮、hover 删除确认 ElMessageBox)+ 顶部新建行(名称+方言 select postgresql/trino/mysql)+ "载入示例实例"按钮(POST crm 示例:customer 表 4 列 + mask_phone UDF + mask_phone_policy,与旧页逐字段一致);Dashboard = 4 统计卡(实例/表/策略/UDF 数,前两项来自 list,策略与 UDF 按当前实例统计并注明口径)+ 快捷入口卡 + 门禁状态卡。

- [ ] **Step 1: 实现 store + 两页面** → **Step 2: 远程 build 通过** → **Step 3: Commit** `feat(frontend): Dashboard 与实例列表`

### Task 6: 实例详情·表结构 Tab

**Files:**
- Create: `src/views/instances/InstanceDetail.vue`, `src/views/instances/TablesTab.vue`

**Interfaces:**
- Consumes: `useInstancesStore.select`、`putTables`。
- Produces: 详情骨架(el-page-header 返回 + el-tabs:tables/policies/udfs/effective);TablesTab = 每表一张 el-card(catalog/schema/name/rowFilter 输入 + 列行增删)+ 脏状态跟踪(改动即"有未保存修改",保存全部 PUT 后清除+刷新 store)+ "添加表"(默认值逻辑:mysql→catalog "shop",否则 "crm",schema "public")。行为逐一对齐旧页 renderTables/事件委托逻辑。

- [ ] **Step 1–4: 实现 → 远程 build → Commit** `feat(frontend): 实例详情骨架与表结构编辑`

### Task 7: 实例详情·策略 Tab

**Files:**
- Create: `src/views/instances/PoliciesTab.vue`

**Interfaces:**
- Consumes: policies api、`InstanceInfo`。
- Produces: 策略卡列表(名称/启用徽章/类型徽章/priority/资源/UDF(args)/主体行,编辑+删除)+ el-drawer 表单(新建/编辑复用;字段:名称、类型 select、启用 switch、priority、catalog/schema/table/columns、users/groups、udf、arguments、filterExpr;row_filter 时 UDF 区灰显)。提交走 create/update,错误进 drawer 内 status 行。

- [ ] **Step 1–4: 实现 → 远程 build → Commit** `feat(frontend): 策略管理与抽屉表单`

### Task 8: UDF Tab、生效配置 Tab、导入与示例

**Files:**
- Create: `src/views/instances/UdfsTab.vue`, `src/views/instances/EffectiveTab.vue`
- Modify: `InstanceDetail.vue`(总览卡:实例信息 + metadata 导入表单)

**Interfaces:**
- Consumes: udfs/effective/instances api。
- Produces: UdfTab(签名行组件化增删,至少保留 1 行;returns 空默认 "varchar");EffectiveTab(user/groups 输入 → 拉取 → 元信息行 + 表结构/列绑定/策略 UDF 三类卡片 + el-collapse 原始 JSON);总览卡含导入表单(metadataBaseUrl 默认 `http://127.0.0.1:8082`、metadataInstance 必填校验、metadataApiKey 可选)。删除实例按钮在本页(ElMessageBox 确认)。

- [ ] **Step 1–4: 实现 → 远程 build → Commit** `feat(frontend): UDF/生效配置/元数据导入`

### Task 9: 改写试验台

**Files:**
- Create: `src/components/SqlEditor.vue`, `src/views/playground/Playground.vue`

**Interfaces:**
- Consumes: `rewrite` api。
- Produces: `SqlEditor v-model:value`(CodeMirror 6 + `sql(){}` 方言,深色主题 CSS 变量);Playground 左右分栏——左:模式 el-radio-group(instance/内联),instance 模式选实例+user+groups,内联模式两个 SqlEditor(YAML);SQL 编辑器 + 提交按钮;右:逐语句卡(原始/改写)+ `rewrittenSql` CodeBlock。错误保留输入展示于 ErrorAlert。

- [ ] **Step 1–4: 实现 → 远程 build → Commit** `feat(frontend): SQL 改写试验台`

### Task 10: 审计日志页

**Files:**
- Create: `src/views/audit/AuditView.vue`

**Interfaces:**
- Consumes: `buildAuditQuery`、`searchAudit`。
- Produces: 过滤表单(eventType/outcome select(ENABLED/DISABLED/FAILURE 或留空)、instance、resourceType、action、user、时间范围 el-date-picker daterange→ISO、默认近 24h)+ el-table(时间/eventType/outcome/instance/resourceType/action/user)+ el-pagination(size 默认 50,上限 200)+ 502 `AUDIT_SEARCH_UNAVAILABLE` 渲染"审计未启用"引导卡(说明 `audit.enabled=true` 与 ES 依赖),其余错误走 ErrorAlert。

- [ ] **Step 1–4: 实现 → 远程 build → Commit** `feat(frontend): 审计日志查询页`

### Task 11: 部署资产与旧页退役

**Files:**
- Modify: `frontend/nginx.conf`(启用 `/api/rewrite`→8080;`/api/audit`→**8080**;注释保留 8082/8083 预留;`location /` 加 `try_files $uri /index.html`;`index index.html`)
- Modify: `docker/nginx.Dockerfile`(COPY `frontend/dist/` 与 `frontend/nginx.conf`)
- Modify: `docker-compose.frontend.yml`(bind-mount `./frontend/dist:/usr/share/nginx/html:ro` + `./frontend/nginx.conf`)
- Delete: `frontend/policy-console.html`

- [ ] **Step 1: 改 4 文件、删旧页** → **Step 2: 远程重建 + 远程 compose 起 nginx,`curl localhost:80` 拿到 index、`curl localhost:80/api/instances` 通到 8081(无后端时 502 也证明路由命中)→ **Step 3: Commit** `feat(frontend): nginx 路由修正/SPA 兜底/容器部署,退役旧策略台页面`

### Task 12: 全量单测 + 构建门禁

- [ ] **Step 1: 远程 `npm test` 全绿、`npm run build` 全绿** → **Step 2: 修复发现的问题** → **Step 3: Commit**(如有)

### Task 13: 端到端目检

- [ ] **Step 1: 本机起后端** `mvn -pl mask-core,mask-policy-server -am -DskipTests package` 后台起 8080/8081(`java -jar`,ES 不可用→审计走降级路径属预期)
- [ ] **Step 2: SSH 反向隧道** `ssh -N -R 18080:127.0.0.1:8080 -R 18081:127.0.0.1:8081 root@47.100.166.158`,远程 compose 的 nginx upstream 在隧道场景指 1808x(临时 override,验证后还原);正向隧道 `-L 18000:127.0.0.1:80` 供本机浏览器访问
- [ ] **Step 3: 浏览器走查**(browser-use):示例实例→表结构增删→策略/UDF→生效配置→改写出结果→审计降级卡;截图交视觉验收
- [ ] **Step 4: 问题修复循环** → 最终 Commit
