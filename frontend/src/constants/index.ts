/** 控制台共享常量:方言/引擎清单与默认值,收敛此前散落在各视图的硬编码。 */

/** 策略服务(mask-policy-server)实例的方言 = 改写方言,5 种。 */
export const POLICY_DIALECTS = ["postgresql", "trino", "mysql", "hive", "sparksql"] as const;
export type PolicyDialect = (typeof POLICY_DIALECTS)[number];

/** 元数据服务(mask-metadata)支持在线采集的引擎(hive/sparksql 仅 YAML 导入)。 */
export const COLLECTABLE_ENGINES = ["postgresql", "mysql", "trino"] as const;

/** 元数据实例可登记的全部引擎(采集 + 仅导入)。 */
export const METADATA_ENGINES = ["postgresql", "mysql", "trino", "hive", "sparksql", "starrocks"] as const;

/** 引擎默认端口(与 mask-query QueryEngine 目录一致)。 */
export const ENGINE_DEFAULT_PORT: Record<string, number> = {
  postgresql: 5432,
  mysql: 3306,
  starrocks: 9030,
  trino: 8080,
  hive: 10000,
  sparksql: 10000
};

/** 实例/策略名规则:小写字母数字与连字符,多词用连字符(后端按名做 URL 段)。 */
export const NAME_PATTERN = /^[a-z][a-z0-9-]*$/;

/** 常用列类型提示(按方言分组)。 */
export const TYPE_HINTS: Record<string, string[]> = {
  postgresql: ["bigint", "integer", "varchar(20)", "text", "numeric(10,2)", "timestamp", "date", "boolean"],
  mysql: ["bigint", "int", "varchar(20)", "text", "decimal(10,2)", "datetime", "date", "boolean"],
  trino: ["bigint", "integer", "varchar", "double", "decimal(10,2)", "timestamp", "date", "boolean"],
  hive: ["bigint", "int", "string", "varchar(20)", "double", "timestamp", "date", "boolean"],
  sparksql: ["bigint", "int", "string", "varchar(20)", "double", "timestamp", "date", "boolean"]
};
