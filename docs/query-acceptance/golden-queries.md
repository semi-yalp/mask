# mask-query 验收 golden 查询清单

对每个引擎断言完整链路「原始 SQL → 改写后 SQL → 真库执行 → 期望脱敏结果」。
清单是 mask-query 的验收基准，也是 **StarRocks 兼容口径的唯一定义**：
StarRocks 的承诺即「MySQL 方言改写产物的可执行子集，以本清单为准」。

约定：

- 「改写后 SQL」列为**形态示意**——外层脱敏包装的标识符引号、分页渲染按方言
  （MySQL 反引号、PostgreSQL 的 `LIMIT` 渲染为 `FETCH NEXT n ROWS ONLY`），
  内层是原始查询的原文快照；验收比对以「期望脱敏结果」列为准（比对
  `POST /api/v1/query` 响应的 `rows` / `rowCount` / `masked` / `rowFiltered` /
  `truncated`）；
- Hive / SparkSQL 属批 2（方言另立 spec），本清单不含；CI 无这些引擎服务时
  按清单手工/按需执行（与 `docker-compose.query.yml` 的 `query-acceptance`
  profile 一致）。

## 样例数据与策略（各引擎共用）

在目标引擎建样例表（类型名按方言微调：MySQL 用 `VARCHAR(20)`）：

```sql
CREATE TABLE customer (id bigint, phone varchar, email varchar, status varchar);
INSERT INTO customer VALUES
  (1, '13812345678', 'alice@example.com', 'active'),
  (2, '13900001111', 'bob@example.com',   'archived'),
  (3, '13711112222', 'carol@example.com', 'active');
```

在策略服务（mask-core 8080 的管理面，见 README「策略服务管理面（REST）」）按
两阶段登记策略——分阶段是为了让「基础脱敏」与「行过滤叠加」的期望互不污染：

- **阶段 A（仅 DATAMASK）**：`customer.phone` → UDF `mask_phone`、参数 `[3, 4]`、
  主体 `groups: ["*"]`；
- **阶段 B（追加 ROW_FILTER）**：`customer` 表级 → `filterExpr: "status = 'active'"`、
  主体 `groups: ["*"]`（行过滤命中项与用户 WHERE 按 AND 叠加，谓词始终用明文列）。

## 运行步骤

```bash
# 1) 构建（产出 mask-core / mask-metadata / mask-query 的 fat jar）
mvn -pl mask-core,mask-metadata,mask-query -am package

# 2) 起 mask-metadata（宿主 8082，含其存储 PG 5432）
docker compose -f docker-compose.metadata.yml up -d

# 3) 起 mask-core 服务模式（宿主 8080；策略服务与管理面同进程）
java -jar mask-core/target/sql-mask.jar \
  --sqlmask.metadata-service.base-url=http://127.0.0.1:8082 \
  --sqlmask.metadata-service.api-key=local-dev-key \
  --sqlmask.policy-service.base-url=http://127.0.0.1:8080
# 可选：SQLMASK_REWRITE_API_KEY=local-dev-rewrite-key（服务间调用鉴权）

# 4) 起验收引擎套件（不带 --profile 不会启动任何容器，profile 生效验证）
docker compose -f docker-compose.query.yml up   # 预期：不启动
docker compose -f docker-compose.query.yml --profile query-acceptance up -d --build
# mask-query 在 http://localhost:8083；mysql 3306 / trino 18080(宿主映射) / starrocks 9030

# 5) 各引擎装 UDF（下一节最小 DDL）+ 建样例表

# 6) mask-metadata 登记实例并导入表结构（见下），passwordRef 指向的
#    环境变量按 compose 注释注入 mask-query 容器

# 7) 策略服务登记 UDF 签名 + 两阶段策略（见下）

# 8) 逐条 curl 比对 golden 表
curl -s http://localhost:8083/api/v1/query \
  -H 'X-Api-Key: local-dev-query-key' -H 'Content-Type: application/json' \
  -d '{"instance":"mysql_shop","sql":"SELECT phone FROM customer","user":"alice","includeRewrittenSql":true}'
```

实例登记示例（其余引擎同理，`engine` 分别填 `postgresql` / `trino` /
`starrocks`；host 填 compose 服务名 `mysql` / `trino` / `starrocks`——JDBC 由
mask-query 从同一 compose 网络发起；PG 复用 metadata compose 的存储 PG 时填
`host.docker.internal`:5432。Trino 无密码认证，passwordRef 指向占位变量即可）：

```bash
curl -s -X POST http://localhost:8082/api/instances \
  -H 'X-Api-Key: local-dev-key' -H 'Content-Type: application/json' \
  -d '{"name":"mysql_shop","dialect":"mysql",
       "connection":{"host":"mysql","port":3306,"database":"shop","dbUser":"root",
                     "passwordRef":"SQLMASK_DS_MYSQL_SHOP_PASSWORD","sslmode":"disable",
                     "connectTimeoutSeconds":5,"schemas":["shop"],"includeViews":false}}'

# 导入表结构（改写依赖表目录；metadataYaml 只吃 metadata.tables）
curl -s -X POST http://localhost:8082/api/instances/import \
  -H 'X-Api-Key: local-dev-key' -H 'Content-Type: application/json' \
  -d '{"name":"mysql_shop","dialect":"mysql","metadataYaml":"metadata:\n  tables:\n    - catalog: shop\n      schema: shop\n      name: customer\n      columns:\n        - {name: id, type: bigint}\n        - {name: phone, type: varchar(20)}\n        - {name: email, type: varchar(64)}\n        - {name: status, type: varchar(16)}"}'
```

策略登记示例（在 mask-core 8080 上；先注册 UDF 签名——DATAMASK 策略写入时按
注册表校验签名与列类型；其余实例同理）：

```bash
curl -s -X POST http://localhost:8080/api/instances/mysql_shop/udfs \
  -H 'Content-Type: application/json' \
  -d '{"name":"mask_phone","signatures":[{"params":["varchar","integer","integer"],"returns":"varchar"}]}'

# 阶段 A：列脱敏
curl -s -X POST http://localhost:8080/api/instances/mysql_shop/policies \
  -H 'Content-Type: application/json' \
  -d '{"name":"mask-customer-phone","policyType":"DATAMASK","isEnabled":true,
       "resource":{"catalog":"shop","schema":"shop","table":"customer","columns":["phone"]},
       "subjects":{"users":[],"groups":["*"]},"udf":"mask_phone","arguments":[3,4]}'

# 阶段 B：行过滤（跑完阶段 A 的 golden 后再登记）
curl -s -X POST http://localhost:8080/api/instances/mysql_shop/policies \
  -H 'Content-Type: application/json' \
  -d '{"name":"filter-archived","policyType":"ROW_FILTER","isEnabled":true,
       "resource":{"catalog":"shop","schema":"shop","table":"customer"},
       "subjects":{"users":[],"groups":["*"]},"filterExpr":"status = '"'"'active'"'"'"}'
```

## 各引擎装 UDF 的最小 DDL

### PostgreSQL

```sql
CREATE FUNCTION mask_phone(v varchar, keep_first int, keep_last int)
RETURNS varchar AS $$
  SELECT left(v, keep_first) || repeat('*', 4) || right(v, keep_last)
$$ LANGUAGE sql;

-- 聚合列（count(phone) → bigint）需要同名重载，golden G2 依赖：
CREATE FUNCTION mask_phone(v bigint, keep_first int, keep_last int)
RETURNS varchar AS $$
  SELECT left(v::varchar, keep_first) || repeat('*', 4) || right(v::varchar, keep_last)
$$ LANGUAGE sql;
```

### MySQL

MySQL 存储函数不支持同名重载，但调用点对 `bigint` 实参做隐式类型转换，
G2 可直接复用下面的标量签名（预期 `'3'` → `'3****3'`）：

```sql
DELIMITER $$
CREATE FUNCTION mask_phone(v VARCHAR(255), keep_first INT, keep_last INT)
RETURNS VARCHAR(255) DETERMINISTIC
BEGIN
  RETURN CONCAT(LEFT(v, keep_first), '****', RIGHT(v, keep_last));
END$$
DELIMITER ;
```

（启用 binlog 的实例需 `SET GLOBAL log_bin_trust_function_creators = 1` 或如上
声明 `DETERMINISTIC`。）

### StarRocks

StarRocks 3.x Java UDF（安装与 `CREATE GLOBAL FUNCTION ... PROPERTIES
("symbol"="…", "type"="Java_UDF")` 语法以官方文档为准）：
<https://docs.starrocks.io/docs/developers/java-udf/>
（SQL 参考：<https://docs.starrocks.io/docs/sql-reference/sql-functions/java-udf/>）。
Java UDF 支持重载，G2 需提供 `bigint` 签名。

### Trino

Trino 脱敏函数以 Java 插件交付（SPI 函数，见
<https://trino.io/docs/current/develop/plugin.html> 与
<https://trino.io/docs/current/develop/functions.html>）：实现
`SqlFunction`、打包为 plugin 目录结构、拷入 coordinator 的 `plugin/sqlmask/`
后重启。函数绑定按精确类型，G2 需提供 `bigint` 重载。

## Golden：PostgreSQL

实例 `pg_dev`（复用 `docker-compose.metadata.yml` 的存储 PG 时，样例表建在
`mask_metadata` 库，catalog/schema 为 `postgres/public`）。

### 阶段 A（仅 DATAMASK：`mask_phone(phone, 3, 4)`）

| 原始 SQL | 改写后 SQL | 期望脱敏结果 |
|---|---|---|
| `SELECT phone FROM customer` | `SELECT mask_phone(r.phone, 3, 4) AS phone FROM (SELECT phone FROM customer) AS r` | `rows = [["138****5678"], ["139****1111"], ["137****2222"]]`，`rowCount=3`，`masked=true`，`rowFiltered=false`，`truncated=false` |
| `SELECT count(phone) FROM customer` | `SELECT mask_phone(r.EXPR$0, 3, 4) AS EXPR$0 FROM (SELECT COUNT(phone) AS EXPR$0 FROM customer) AS r`（无别名聚合的输出列名由改写器生成，实际以 `includeRewrittenSql` 回显为准） | `rows = [["3****3"]]`，`rowCount=1`，`masked=true`。**类型重载前提**：聚合列的包装目标是 bigint，引擎侧 UDF 必须有能接收 bigint 的签名（见上文 DDL），否则引擎报「函数不存在」——属 UDF 部署前提缺失，不判为改写缺陷 |
| `SELECT phone FROM customer ORDER BY id LIMIT 2` | `SELECT mask_phone(r.phone, 3, 4) AS phone FROM (SELECT phone FROM customer ORDER BY id LIMIT 2) AS r`（内层保留原文；外层分页按 PG 渲染） | `rows = [["138****5678"], ["139****1111"]]`，`rowCount=2`，`truncated=false` |
| `SELECT phone FROM customer` + 请求 `maxRows: 1` | 同第 1 行 | `rows = [["138****5678"]]`，`rowCount=1`，**`truncated=true`**（不是错误） |

### 阶段 B（追加 ROW_FILTER：`status = 'active'`）

| 原始 SQL | 改写后 SQL | 期望脱敏结果 |
|---|---|---|
| `SELECT phone FROM customer` | `SELECT mask_phone(r.phone, 3, 4) AS phone FROM (SELECT phone FROM customer WHERE status = 'active') AS r` | `rows = [["138****5678"], ["137****2222"]]`（archived 行被行过滤排除），`masked=true`，`rowFiltered=true` |
| `SELECT phone FROM customer WHERE id = 1` | 行过滤注入进 customer 引用位置、用户 WHERE 原样保留，两者按 AND 叠加（`... FROM (SELECT * FROM customer WHERE status = 'active') AS customer WHERE id = 1` 外再包脱敏层） | `rows = [["138****5678"]]`，`rowCount=1`，`masked=true`，`rowFiltered=true` |

## Golden：MySQL

实例 `mysql_shop`（catalog/schema = `shop/shop`）。包装层标识符按 MySQL 方言
渲染为反引号（示意列从简）。阶段 A：

| 原始 SQL | 改写后 SQL | 期望脱敏结果 |
|---|---|---|
| `SELECT phone FROM customer` | ``SELECT mask_phone(r.`phone`, 3, 4) AS `phone` FROM (SELECT phone FROM customer) AS r`` | 同 PG 阶段 A 第 1 行：3 行 `138****5678` / `139****1111` / `137****2222`，`masked=true`，`rowFiltered=false` |
| `SELECT count(phone) FROM customer` | 同形（聚合输出列名以回显为准） | `rows = [["3****3"]]`（bigint 实参经隐式转换进入存储函数），`masked=true`；无可用签名时引擎报函数不存在——同「类型重载前提」注记 |
| `SELECT phone FROM customer ORDER BY id LIMIT 2` | 内层保留 `LIMIT 2`（MySQL 方言原样渲染） | 同 PG 阶段 A 第 3 行 |
| `SELECT phone FROM customer` + `maxRows: 1` | 同第 1 行 | `truncated=true` |

阶段 B（行过滤叠加）：期望与 PG 阶段 B 两行完全一致（archived 行排除；行过滤
与用户 WHERE 叠加后仅剩 id=1 的脱敏行）。

## Golden：Trino

实例 `trino_crm`（catalog = 实例 database，如 `crm`；schema `public`；Trino 无
密码认证）。改写后 SQL 同 PG 形态（双引号按需、内层原文快照）。阶段 A 的四行
与阶段 B 的两行期望结果均与 PostgreSQL 对应行一致（`count(phone)` 依赖插件
提供的 bigint 重载）。Trino 特有注意：驱动天然分页拉取，`fetchSize` 护栏无
PG 的 `autoCommit=false` 前置条件。

## Golden：StarRocks（专节）

**兼容口径**：StarRocks 无独立改写方言——mask-query 对它复用 MySQL 方言改写
产物。兼容承诺即「MySQL 方言改写产物的可执行子集，以本清单为准」：下列
golden 通过即在承诺范围内；清单外的 MySQL 语法不承诺可执行。

实例 `sr_warehouse`（`engine: starrocks`、dialect `mysql`，端口 9030）。在本
清单验证的子集：

| 原始 SQL | 改写后 SQL | 期望脱敏结果 |
|---|---|---|
| `SELECT phone FROM customer` | 同 MySQL 第 1 行（MySQL 方言产物） | 3 行 `138****5678` / `139****1111` / `137****2222`，`masked=true` |
| `SELECT phone FROM customer WHERE status = 'active'`（含 ROW_FILTER 策略） | 同 PG 阶段 B 第 1 行形态 | `rows = [["138****5678"], ["137****2222"]]`，`masked=true`，`rowFiltered=true` |
| `SELECT phone FROM customer ORDER BY id LIMIT 2` | 同 MySQL 第 3 行（`LIMIT` 原样保留） | 2 行 `138****5678` / `139****1111`，`truncated=false` |
| `SELECT count(phone) FROM customer` | 同 MySQL 第 2 行 | `rows = [["3****3"]]`（依赖 Java UDF 的 bigint 重载，见 StarRocks UDF 一节） |
