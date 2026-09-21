package io.sqlmask.policyserver.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.model.AccessType;
import io.sqlmask.policyserver.model.ChangeType;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.ConnectionConfig;
import io.sqlmask.policyserver.model.ConnectionStatus;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
import io.sqlmask.policyserver.model.PolicyVersion;
import io.sqlmask.policyserver.model.ResourceSelector;
import io.sqlmask.policyserver.model.TableDef;
import io.sqlmask.policyserver.model.UdfDefinition;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PostgreSQL-backed {@link PolicyStore}: instances and their connections plus
 * table structures live in {@code policy_instance}/{@code instance_table}/
 * {@code instance_column}, policies in {@code policy}, immutable version
 * history in {@code policy_version}, UDFs in {@code instance_udf} (see
 * {@code schema.sql}). {@code connection}/{@code resource}/{@code subjects}/
 * {@code arguments}/{@code content} are JSONB via Jackson. Behavior mirrors
 * {@link InMemoryPolicyStore}: {@code config_version} starts at 1 and advances
 * by one inside the same transaction as every successful mutation; the policy
 * version history is append-only and drives optimistic updates.
 */
public class JdbcPolicyStore implements PolicyStore {

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper = new ObjectMapper();

  public JdbcPolicyStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public EngineInstance createInstance(EngineInstance instance) {
    if (lookupInstance(instance.name()).isPresent()) {
      throw duplicateInstance(instance.name());
    }
    try {
      jdbc.update("INSERT INTO policy_instance (name, dialect, connection, connection_status)"
              + " VALUES (?, ?, ?::jsonb, ?)",
          ps -> {
            ps.setString(1, instance.name());
            ps.setString(2, instance.dialect());
            setNullableJson(ps, 3, instance.connection());
            ps.setString(4, (instance.status() == null ? ConnectionStatus.UNCONNECTED
                : instance.status()).name());
          });
    } catch (DuplicateKeyException e) {
      throw duplicateInstance(instance.name());
    }
    insertTables(requireInstanceRow(instance.name()).id(), instance.tables());
    return instance;
  }

  @Override
  @Transactional
  public EngineInstance updateInstanceConnection(String name, ConnectionConfig cfg,
      ConnectionStatus status) {
    InstanceRow existing = requireInstanceRow(name);
    ConnectionStatus effective = status == null ? ConnectionStatus.UNCONNECTED : status;
    jdbc.update("UPDATE policy_instance SET connection = ?::jsonb, connection_status = ?,"
            + " updated_at = now() WHERE id = ?",
        toJson(cfg), effective.name(), existing.id());
    bumpVersion(name);
    return new EngineInstance(existing.name(), existing.dialect(), cfg, effective,
        loadTables(existing.id()));
  }

  @Override
  @Transactional
  public EngineInstance updateInstanceTables(String name, List<TableDef> tables) {
    InstanceRow existing = requireInstanceRow(name);
    jdbc.update("DELETE FROM instance_table WHERE instance_id = ?", existing.id());
    insertTables(existing.id(), tables);
    bumpVersion(name);
    return new EngineInstance(existing.name(), existing.dialect(), existing.connectionConfig(),
        existing.status(), tables);
  }

  @Override
  public Optional<EngineInstance> findInstance(String name) {
    return lookupInstance(name)
        .map(row -> new EngineInstance(row.name(), row.dialect(), row.connectionConfig(),
            row.status(), loadTables(row.id())));
  }

  @Override
  public List<EngineInstance> listInstances() {
    List<InstanceRow> rows = jdbc.query(
        "SELECT id, name, dialect, connection, connection_status FROM policy_instance ORDER BY id",
        (rs, n) -> mapInstanceRow(rs));
    List<EngineInstance> instances = new ArrayList<>(rows.size());
    for (InstanceRow row : rows) {
      instances.add(new EngineInstance(row.name(), row.dialect(), row.connectionConfig(),
          row.status(), loadTables(row.id())));
    }
    return List.copyOf(instances);
  }

  @Override
  @Transactional
  public void deleteInstance(String name) {
    long instanceId = requireInstanceRow(name).id();
    Long policyCount = jdbc.queryForObject(
        "SELECT COUNT(*) FROM policy WHERE instance_id = ?", Long.class, instanceId);
    if (policyCount != null && policyCount > 0) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "instance '" + name
          + "' still has " + policyCount + " policy(ies); delete them first");
    }
    jdbc.update("DELETE FROM policy_instance WHERE id = ?", instanceId);
  }

  @Override
  @Transactional
  public PolicyEntity createPolicy(String instanceName, PolicyEntity policy) {
    long instanceId = requireInstanceRow(instanceName).id();
    if (policyExists(instanceId, policy.name())) {
      throw duplicatePolicy(policy.name());
    }
    // A deleted policy leaves history behind; a recreate continues the sequence.
    int nextVersion = nextVersion(instanceId, policy.name());
    PolicyEntity created = withVersion(policy, nextVersion);
    try {
      insertPolicy(instanceId, created);
    } catch (DuplicateKeyException e) {
      throw duplicatePolicy(policy.name());
    }
    insertVersion(instanceId, policy.name(), nextVersion, ChangeType.CREATE, created, null);
    bumpVersion(instanceName);
    return created;
  }

  @Override
  @Transactional
  public PolicyEntity updatePolicy(String instanceName, String policyName, PolicyEntity policy) {
    long instanceId = requireInstanceRow(instanceName).id();
    StoredPolicy stored = requirePolicy(instanceId, policyName);
    if (stored.currentVersion() != policy.currentVersion()) {
      throw new SqlMaskException(SqlMaskException.Code.CONCURRENT_MODIFICATION,
          "policy '" + policyName + "' was modified concurrently: expected version "
              + stored.currentVersion() + " but got " + policy.currentVersion()
              + "; reload and retry");
    }
    int newVersion = stored.currentVersion() + 1;
    PolicyEntity updated = withVersion(policy, newVersion);
    jdbc.update("UPDATE policy SET access_type = ?, policy_type = ?, is_enabled = ?, priority = ?,"
            + " udf = ?, arguments = ?::jsonb, filter_expr = ?, resource = ?::jsonb,"
            + " subjects = ?::jsonb, current_version = ?, updated_at = now()"
            + " WHERE id = ?",
        ps -> {
          ps.setString(1, updated.accessType().name());
          ps.setString(2, updated.policyType().name());
          ps.setBoolean(3, updated.enabled());
          ps.setInt(4, updated.priority());
          setNullableString(ps, 5, updated.udf());
          setNullableJson(ps, 6, updated.arguments());
          setNullableString(ps, 7, updated.filterExpr());
          ps.setString(8, toJson(updated.resource()));
          ps.setString(9, toJson(updated.subjects()));
          ps.setInt(10, newVersion);
          ps.setLong(11, stored.id());
        });
    insertVersion(instanceId, policyName, newVersion, ChangeType.UPDATE, updated, null);
    bumpVersion(instanceName);
    return updated;
  }

  @Override
  @Transactional
  public PolicyEntity rollbackPolicy(String instanceName, String policyName, int targetVersion) {
    long instanceId = requireInstanceRow(instanceName).id();
    StoredPolicy stored = requirePolicy(instanceId, policyName);
    List<PolicyEntity> found = jdbc.query(
        "SELECT content FROM policy_version WHERE instance_id = ? AND policy_name = ?"
            + " AND version = ?",
        (rs, n) -> policyFromJson(rs.getString("content")), instanceId, policyName,
        targetVersion);
    if (found.isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.VERSION_NOT_FOUND,
          "policy '" + policyName + "' has no version " + targetVersion);
    }
    PolicyEntity target = found.get(0);
    int newVersion = stored.currentVersion() + 1;
    PolicyEntity rolled = new PolicyEntity(policyName, target.accessType(), target.policyType(),
        target.enabled(), target.priority(), target.resource(), target.subjects(), target.udf(),
        target.arguments(), target.filterExpr(), newVersion);
    jdbc.update("UPDATE policy SET access_type = ?, policy_type = ?, is_enabled = ?, priority = ?,"
            + " udf = ?, arguments = ?::jsonb, filter_expr = ?, resource = ?::jsonb,"
            + " subjects = ?::jsonb, current_version = ?, updated_at = now() WHERE id = ?",
        ps -> {
          ps.setString(1, rolled.accessType().name());
          ps.setString(2, rolled.policyType().name());
          ps.setBoolean(3, rolled.enabled());
          ps.setInt(4, rolled.priority());
          setNullableString(ps, 5, rolled.udf());
          setNullableJson(ps, 6, rolled.arguments());
          setNullableString(ps, 7, rolled.filterExpr());
          ps.setString(8, toJson(rolled.resource()));
          ps.setString(9, toJson(rolled.subjects()));
          ps.setInt(10, newVersion);
          ps.setLong(11, stored.id());
        });
    insertVersion(instanceId, policyName, newVersion, ChangeType.ROLLBACK, rolled, targetVersion);
    bumpVersion(instanceName);
    return rolled;
  }

  @Override
  public Optional<PolicyEntity> findPolicy(String instanceName, String policyName) {
    long instanceId = requireInstanceRow(instanceName).id();
    List<PolicyEntity> found = jdbc.query(
        selectPolicySql() + " WHERE instance_id = ? AND name = ?",
        (rs, n) -> mapPolicy(rs), instanceId, policyName);
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  @Override
  public List<PolicyEntity> listPolicies(String instanceName) {
    long instanceId = requireInstanceRow(instanceName).id();
    return List.copyOf(jdbc.query(
        selectPolicySql() + " WHERE instance_id = ? ORDER BY id",
        (rs, n) -> mapPolicy(rs), instanceId));
  }

  @Override
  public List<PolicyVersion> policyVersions(String instanceName, String policyName) {
    long instanceId = requireInstanceRow(instanceName).id();
    return List.copyOf(jdbc.query(
        "SELECT version, change_type, content, source_version, created_at FROM policy_version"
            + " WHERE instance_id = ? AND policy_name = ? ORDER BY version",
        (rs, n) -> new PolicyVersion(
            rs.getInt("version"),
            ChangeType.valueOf(rs.getString("change_type")),
            policyFromJson(rs.getString("content")),
            (Integer) rs.getObject("source_version"),
            rs.getTimestamp("created_at").toInstant()),
        instanceId, policyName));
  }

  @Override
  @Transactional
  public void deletePolicy(String instanceName, String policyName) {
    long instanceId = requireInstanceRow(instanceName).id();
    int deleted = jdbc.update("DELETE FROM policy WHERE instance_id = ? AND name = ?",
        instanceId, policyName);
    if (deleted == 0) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy '" + policyName + "' not found");
    }
    bumpVersion(instanceName);
  }

  @Override
  @Transactional
  public UdfDefinition createUdf(String instanceName, UdfDefinition udf) {
    long instanceId = requireInstanceRow(instanceName).id();
    if (udfExists(instanceId, udf.name())) {
      throw duplicateUdf(udf.name(), instanceName);
    }
    try {
      insertUdfSignatures(instanceId, udf);
    } catch (DuplicateKeyException e) {
      throw duplicateUdf(udf.name(), instanceName);
    }
    bumpVersion(instanceName);
    return udf;
  }

  @Override
  @Transactional
  public UdfDefinition replaceUdf(String instanceName, String udfName, UdfDefinition udf) {
    long instanceId = requireInstanceRow(instanceName).id();
    if (!udf.name().equals(udfName)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "udf name mismatch: '"
          + udfName + "' cannot be renamed to '" + udf.name() + "'");
    }
    if (!udfExists(instanceId, udfName)) {
      throw missingUdf(udfName, instanceName);
    }
    jdbc.update("DELETE FROM instance_udf WHERE instance_id = ? AND name = ?", instanceId, udfName);
    insertUdfSignatures(instanceId, udf);
    bumpVersion(instanceName);
    return udf;
  }

  @Override
  public Optional<UdfDefinition> findUdf(String instanceName, String udfName) {
    long instanceId = requireInstanceRow(instanceName).id();
    return loadUdfs(instanceId, udfName).stream().findFirst();
  }

  @Override
  public List<UdfDefinition> listUdfs(String instanceName) {
    long instanceId = requireInstanceRow(instanceName).id();
    return loadUdfs(instanceId, null);
  }

  @Override
  @Transactional
  public void deleteUdf(String instanceName, String udfName) {
    long instanceId = requireInstanceRow(instanceName).id();
    int deleted = jdbc.update("DELETE FROM instance_udf WHERE instance_id = ? AND name = ?",
        instanceId, udfName);
    if (deleted == 0) {
      throw missingUdf(udfName, instanceName);
    }
    bumpVersion(instanceName);
  }

  @Override
  public long currentVersion(String instanceName) {
    List<Long> versions = jdbc.queryForList(
        "SELECT config_version FROM policy_instance WHERE name = ?", Long.class, instanceName);
    if (versions.isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND,
          "instance '" + instanceName + "' not found");
    }
    return versions.get(0);
  }

  // ---- low-level helpers ----

  private record InstanceRow(long id, String name, String dialect, ConnectionConfig connectionConfig,
      ConnectionStatus status) {
  }

  private InstanceRow mapInstanceRow(ResultSet rs) throws SQLException {
    return new InstanceRow(rs.getLong("id"), rs.getString("name"), rs.getString("dialect"),
        connectionFrom(rs.getString("connection")),
        statusFrom(rs.getString("connection_status")));
  }

  private ConnectionStatus statusFrom(String value) {
    return value == null || value.isBlank()
        ? ConnectionStatus.UNCONNECTED
        : ConnectionStatus.valueOf(value);
  }

  private ConnectionConfig connectionFrom(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return mapper.readValue(json, ConnectionConfig.class);
    } catch (JsonProcessingException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "could not deserialize instance connection: " + e.getMessage(), e);
    }
  }

  private record StoredPolicy(long id, int currentVersion) {
  }

  private StoredPolicy requirePolicy(long instanceId, String policyName) {
    List<StoredPolicy> found = jdbc.query(
        "SELECT id, current_version FROM policy WHERE instance_id = ? AND name = ?",
        (rs, n) -> new StoredPolicy(rs.getLong("id"), rs.getInt("current_version")),
        instanceId, policyName);
    if (found.isEmpty()) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy '" + policyName + "' not found");
    }
    return found.get(0);
  }

  private Optional<InstanceRow> lookupInstance(String name) {
    return jdbc.query(
        "SELECT id, name, dialect, connection, connection_status FROM policy_instance WHERE name = ?",
        (rs, n) -> mapInstanceRow(rs), name).stream().findFirst();
  }

  private InstanceRow requireInstanceRow(String name) {
    return lookupInstance(name).orElseThrow(() -> new SqlMaskException(
        SqlMaskException.Code.POLICY_INSTANCE_NOT_FOUND, "instance '" + name + "' not found"));
  }

  private void bumpVersion(String instanceName) {
    jdbc.update("UPDATE policy_instance SET config_version = config_version + 1, updated_at = now()"
        + " WHERE name = ?", instanceName);
  }

  private void insertTables(long instanceId, List<TableDef> tables) {
    for (int i = 0; i < tables.size(); i++) {
      TableDef table = tables.get(i);
      int position = i;
      KeyHolder keyHolder = new GeneratedKeyHolder();
      jdbc.update(con -> {
        PreparedStatement ps = con.prepareStatement(
            "INSERT INTO instance_table (instance_id, catalog, schema_name, table_name, position)"
                + " VALUES (?, ?, ?, ?, ?)", new String[]{"id"});
        ps.setLong(1, instanceId);
        ps.setString(2, table.catalog());
        ps.setString(3, table.schema());
        ps.setString(4, table.name());
        ps.setInt(5, position);
        return ps;
      }, keyHolder);
      insertColumns(keyOf(keyHolder), table.columns());
    }
  }

  private void insertColumns(long tableId, List<ColumnDef> columns) {
    for (int i = 0; i < columns.size(); i++) {
      ColumnDef column = columns.get(i);
      jdbc.update("INSERT INTO instance_column (table_id, name, type_declaration, position)"
              + " VALUES (?, ?, ?, ?)",
          tableId, column.name(), column.typeDeclaration(), i);
    }
  }

  private List<TableDef> loadTables(long instanceId) {
    record TableRow(long id, String catalog, String schema, String name) {
    }
    List<TableRow> rows = jdbc.query(
        "SELECT id, catalog, schema_name, table_name FROM instance_table"
            + " WHERE instance_id = ? ORDER BY position",
        (rs, n) -> new TableRow(rs.getLong("id"), rs.getString("catalog"),
            rs.getString("schema_name"), rs.getString("table_name")), instanceId);
    List<TableDef> tables = new ArrayList<>(rows.size());
    for (TableRow row : rows) {
      List<ColumnDef> columns = jdbc.query(
          "SELECT name, type_declaration FROM instance_column WHERE table_id = ? ORDER BY position",
          (rs, n) -> new ColumnDef(rs.getString("name"), rs.getString("type_declaration")),
          row.id());
      tables.add(new TableDef(row.catalog(), row.schema(), row.name(), columns));
    }
    return tables;
  }

  private boolean policyExists(long instanceId, String policyName) {
    return !jdbc.queryForList("SELECT id FROM policy WHERE instance_id = ? AND name = ?",
        Long.class, instanceId, policyName).isEmpty();
  }

  private long insertPolicy(long instanceId, PolicyEntity policy) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbc.update(con -> {
      PreparedStatement ps = con.prepareStatement(
          "INSERT INTO policy (instance_id, name, access_type, policy_type, is_enabled, priority,"
              + " udf, arguments, filter_expr, resource, subjects, current_version)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?)",
          new String[]{"id"});
      ps.setLong(1, instanceId);
      ps.setString(2, policy.name());
      ps.setString(3, policy.accessType().name());
      ps.setString(4, policy.policyType().name());
      ps.setBoolean(5, policy.enabled());
      ps.setInt(6, policy.priority());
      setNullableString(ps, 7, policy.udf());
      setNullableJson(ps, 8, policy.arguments());
      setNullableString(ps, 9, policy.filterExpr());
      ps.setString(10, toJson(policy.resource()));
      ps.setString(11, toJson(policy.subjects()));
      ps.setInt(12, policy.currentVersion());
      return ps;
    }, keyHolder);
    return keyOf(keyHolder);
  }

  /** Next version number for a policy name: highest history version plus one (empty history → 1). */
  private int nextVersion(long instanceId, String policyName) {
    Integer max = jdbc.queryForObject(
        "SELECT COALESCE(MAX(version), 0) FROM policy_version WHERE instance_id = ?"
            + " AND policy_name = ?",
        Integer.class, instanceId, policyName);
    return (max == null ? 0 : max) + 1;
  }

  private void insertVersion(long instanceId, String policyName, int version,
      ChangeType changeType, PolicyEntity content, Integer sourceVersion) {
    jdbc.update("INSERT INTO policy_version (instance_id, policy_name, version, change_type,"
            + " content, source_version) VALUES (?, ?, ?, ?, ?::jsonb, ?)",
        ps -> {
          ps.setLong(1, instanceId);
          ps.setString(2, policyName);
          ps.setInt(3, version);
          ps.setString(4, changeType.name());
          ps.setString(5, toJson(content));
          if (sourceVersion == null) {
            ps.setNull(6, Types.INTEGER);
          } else {
            ps.setInt(6, sourceVersion);
          }
        });
  }

  private boolean udfExists(long instanceId, String udfName) {
    return !jdbc.queryForList("SELECT id FROM instance_udf WHERE instance_id = ? AND name = ?",
        Long.class, instanceId, udfName).isEmpty();
  }

  private void insertUdfSignatures(long instanceId, UdfDefinition udf) {
    for (int i = 0; i < udf.signatures().size(); i++) {
      UdfDefinition.UdfSignature signature = udf.signatures().get(i);
      jdbc.update("INSERT INTO instance_udf (instance_id, name, param_types, return_type, position)"
              + " VALUES (?, ?, ?, ?, ?)",
          instanceId, udf.name(), toJson(signature.params()), signature.returns(), i);
    }
  }

  private List<UdfDefinition> loadUdfs(long instanceId, String onlyName) {
    StringBuilder sql = new StringBuilder(
        "SELECT name, param_types, return_type FROM instance_udf WHERE instance_id = ?");
    List<Object> args = new ArrayList<>(List.of(instanceId));
    if (onlyName != null) {
      sql.append(" AND name = ?");
      args.add(onlyName);
    }
    sql.append(" ORDER BY name, position");
    Map<String, List<UdfDefinition.UdfSignature>> byName = new LinkedHashMap<>();
    jdbc.query(sql.toString(), (rs, n) -> {
      byName.computeIfAbsent(rs.getString("name"), k -> new ArrayList<>())
          .add(new UdfDefinition.UdfSignature(stringsFrom(rs.getString("param_types")),
              rs.getString("return_type")));
      return null;
    }, args.toArray());
    return byName.entrySet().stream()
        .map(e -> new UdfDefinition(e.getKey(), List.copyOf(e.getValue())))
        .collect(Collectors.toList());
  }

  private List<String> stringsFrom(String json) {
    try {
      return mapper.readValue(json, new TypeReference<List<String>>() {
      });
    } catch (JsonProcessingException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "could not deserialize udf param types: " + e.getMessage(), e);
    }
  }

  private PolicyEntity mapPolicy(ResultSet rs) throws SQLException {
    return new PolicyEntity(
        rs.getString("name"),
        AccessType.valueOf(rs.getString("access_type")),
        PolicyType.valueOf(rs.getString("policy_type")),
        rs.getBoolean("is_enabled"),
        rs.getInt("priority"),
        resourceFrom(rs.getString("resource")),
        subjectFrom(rs.getString("subjects")),
        rs.getString("udf"),
        argumentsFrom(rs.getString("arguments")),
        rs.getString("filter_expr"),
        rs.getInt("current_version"));
  }

  private String selectPolicySql() {
    return "SELECT name, access_type, policy_type, is_enabled, priority, udf, arguments,"
        + " filter_expr, resource, subjects, current_version FROM policy";
  }

  private PolicyEntity policyFromJson(String json) {
    if (json == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy version content column is null");
    }
    try {
      return mapper.readValue(json, PolicyEntity.class);
    } catch (JsonProcessingException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "could not deserialize policy version content: " + e.getMessage(), e);
    }
  }

  private void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, value);
    }
  }

  private void setNullableJson(PreparedStatement ps, int index, Object value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, toJson(value));
    }
  }

  private String toJson(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "could not serialize policy payload to JSON: " + e.getMessage(), e);
    }
  }

  private List<Object> argumentsFrom(String json) {
    if (json == null) {
      return List.of();
    }
    try {
      return mapper.readValue(json, new TypeReference<List<Object>>() {
      });
    } catch (JsonProcessingException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "could not deserialize policy arguments: " + e.getMessage(), e);
    }
  }

  private ResourceSelector resourceFrom(String json) {
    if (json == null) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy resource column is null");
    }
    try {
      return mapper.readValue(json, ResourceSelector.class);
    } catch (JsonProcessingException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "could not deserialize policy resource: " + e.getMessage(), e);
    }
  }

  /** Null-tolerant: a missing/blank column (pre-migration row) reads as everyone. */
  private io.sqlmask.policy.model.SubjectSelector subjectFrom(String json) {
    if (json == null || json.isBlank()) {
      return new io.sqlmask.policy.model.SubjectSelector(Set.of("*"), Set.of());
    }
    try {
      return mapper.readValue(json, io.sqlmask.policy.model.SubjectSelector.class);
    } catch (JsonProcessingException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "could not deserialize policy subjects: " + e.getMessage(), e);
    }
  }

  private static PolicyEntity withVersion(PolicyEntity policy, int version) {
    return new PolicyEntity(policy.name(), policy.accessType(), policy.policyType(),
        policy.enabled(), policy.priority(), policy.resource(), policy.subjects(), policy.udf(),
        policy.arguments(), policy.filterExpr(), version);
  }

  private long keyOf(KeyHolder keyHolder) {
    Number key = keyHolder.getKey();
    if (key == null) {
      throw new IllegalStateException("no generated key returned for insert");
    }
    return key.longValue();
  }

  private SqlMaskException duplicateInstance(String name) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "instance '" + name + "' already exists");
  }

  private SqlMaskException duplicatePolicy(String name) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "policy '" + name + "' already exists");
  }

  private SqlMaskException duplicateUdf(String name, String instanceName) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "udf '" + name + "' already exists in instance '" + instanceName + "'");
  }

  private SqlMaskException missingUdf(String name, String instanceName) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "udf '" + name + "' not found in instance '" + instanceName + "'");
  }
}