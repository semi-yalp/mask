package io.sqlmask.policyserver.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlmask.error.SqlMaskException;
import io.sqlmask.policyserver.model.ColumnDef;
import io.sqlmask.policyserver.model.EngineInstance;
import io.sqlmask.policyserver.model.PolicyEntity;
import io.sqlmask.policyserver.model.PolicyType;
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
 * PostgreSQL-backed {@link PolicyStore}: instances and their table structures
 * live in {@code policy_instance}/{@code instance_table}/{@code instance_column},
 * policies in {@code policy} (see {@code schema.sql}), with {@code resource} and
 * {@code arguments} stored as JSONB via Jackson. Behavior mirrors
 * {@link InMemoryPolicyStore}: {@code config_version} starts at 1 and advances
 * by one inside the same transaction as every successful mutation; failed
 * mutations roll back and leave the version untouched. Declaration order is
 * preserved through the {@code position} columns and ascending {@code id}.
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
    KeyHolder keyHolder = new GeneratedKeyHolder();
    try {
      jdbc.update(con -> {
        PreparedStatement ps = con.prepareStatement(
            "INSERT INTO policy_instance (name, dialect) VALUES (?, ?)", new String[]{"id"});
        ps.setString(1, instance.name());
        ps.setString(2, instance.dialect());
        return ps;
      }, keyHolder);
    } catch (DuplicateKeyException e) {
      throw duplicateInstance(instance.name());
    }
    insertTables(keyOf(keyHolder), instance.tables());
    return instance;
  }

  @Override
  @Transactional
  public EngineInstance updateInstanceTables(String name, List<TableDef> tables) {
    InstanceRow existing = requireInstanceRow(name);
    jdbc.update("DELETE FROM instance_table WHERE instance_id = ?", existing.id());
    insertTables(existing.id(), tables);
    bumpVersion(name);
    return new EngineInstance(existing.name(), existing.dialect(), tables);
  }

  @Override
  public Optional<EngineInstance> findInstance(String name) {
    return lookupInstance(name)
        .map(row -> new EngineInstance(row.name(), row.dialect(), loadTables(row.id())));
  }

  @Override
  public List<EngineInstance> listInstances() {
    List<InstanceRow> rows = jdbc.query(
        "SELECT id, name, dialect FROM policy_instance ORDER BY id",
        (rs, n) -> new InstanceRow(rs.getLong("id"), rs.getString("name"), rs.getString("dialect")));
    List<EngineInstance> instances = new ArrayList<>(rows.size());
    for (InstanceRow row : rows) {
      instances.add(new EngineInstance(row.name(), row.dialect(), loadTables(row.id())));
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
    try {
      insertPolicy(instanceId, policy);
    } catch (DuplicateKeyException e) {
      throw duplicatePolicy(policy.name());
    }
    bumpVersion(instanceName);
    return policy;
  }

  @Override
  @Transactional
  public PolicyEntity updatePolicy(String instanceName, String policyName, PolicyEntity policy) {
    long instanceId = requireInstanceRow(instanceName).id();
    if (!policy.name().equals(policyName)) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR, "policy name mismatch: '"
          + policyName + "' cannot be renamed to '" + policy.name() + "'");
    }
    int updated = jdbc.update("UPDATE policy SET policy_type = ?, is_enabled = ?, priority = ?,"
            + " udf = ?, arguments = ?, filter_expr = ?, resource = ?,"
            + " subjects = ?, updated_at = now()"
            + " WHERE instance_id = ? AND name = ?",
        ps -> {
          ps.setString(1, policy.policyType().name());
          ps.setBoolean(2, policy.enabled());
          ps.setInt(3, policy.priority());
          setNullableString(ps, 4, policy.udf());
          setNullableJson(ps, 5, policy.arguments());
          setNullableString(ps, 6, policy.filterExpr());
          ps.setString(7, toJson(policy.resource()));
          ps.setString(8, toJson(policy.subjects()));
          ps.setLong(9, instanceId);
          ps.setString(10, policyName);
        });
    if (updated == 0) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "policy '" + policyName + "' not found");
    }
    bumpVersion(instanceName);
    return policy;
  }

  @Override
  public Optional<PolicyEntity> findPolicy(String instanceName, String policyName) {
    long instanceId = requireInstanceRow(instanceName).id();
    List<PolicyEntity> found = jdbc.query(selectPolicySql() + " WHERE instance_id = ? AND name = ?",
        (rs, n) -> mapPolicy(rs), instanceId, policyName);
    return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
  }

  @Override
  public List<PolicyEntity> listPolicies(String instanceName) {
    long instanceId = requireInstanceRow(instanceName).id();
    return List.copyOf(jdbc.query(selectPolicySql() + " WHERE instance_id = ? ORDER BY id",
        (rs, n) -> mapPolicy(rs), instanceId));
  }

  /**
   * Removes one policy and advances the instance's {@code config_version}.
   *
   * <p>Error contract: an unknown policy name is a
   * {@code SqlMaskException(CONFIG_ERROR)} ("policy 'x' not found") and leaves
   * the version untouched; an unknown instance is
   * {@code SqlMaskException(POLICY_INSTANCE_NOT_FOUND)}.
   */
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

  private record InstanceRow(long id, String name, String dialect) {
  }

  private Optional<InstanceRow> lookupInstance(String name) {
    return jdbc.query("SELECT id, name, dialect FROM policy_instance WHERE name = ?",
        (rs, n) -> new InstanceRow(rs.getLong("id"), rs.getString("name"), rs.getString("dialect")),
        name).stream().findFirst();
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
            rs.getString("schema_name"), rs.getString("table_name")),
        instanceId);
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

  private boolean udfExists(long instanceId, String udfName) {
    return !jdbc.queryForList("SELECT id FROM instance_udf WHERE instance_id = ? AND name = ?",
        Long.class, instanceId, udfName).isEmpty();
  }

  private void insertUdfSignatures(long instanceId, UdfDefinition udf) {
    for (int i = 0; i < udf.signatures().size(); i++) {
      UdfDefinition.UdfSignature signature = udf.signatures().get(i);
      int position = i;
      jdbc.update("INSERT INTO instance_udf (instance_id, name, param_types, return_type, position,"
              + " source, last_synced_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
          ps -> {
            ps.setLong(1, instanceId);
            ps.setString(2, udf.name());
            ps.setString(3, toJson(signature.params()));
            ps.setString(4, signature.returns());
            ps.setInt(5, position);
            ps.setString(6, udf.source() == null ? "REGISTERED" : udf.source());
            setNullableTimestamp(ps, 7, udf.lastSyncedAt());
          });
    }
  }

  private List<UdfDefinition> loadUdfs(long instanceId, String onlyName) {
    StringBuilder sql = new StringBuilder(
        "SELECT name, param_types, return_type, source, last_synced_at FROM instance_udf"
            + " WHERE instance_id = ?");
    List<Object> args = new ArrayList<>(List.of(instanceId));
    if (onlyName != null) {
      sql.append(" AND name = ?");
      args.add(onlyName);
    }
    sql.append(" ORDER BY name, position");
    // source/last_synced_at are per-definition attributes stored on every
    // signature row; the first row of each name carries the authoritative pair.
    Map<String, List<UdfDefinition.UdfSignature>> byName = new LinkedHashMap<>();
    Map<String, UdfRowMeta> metaByName = new LinkedHashMap<>();
    jdbc.query(sql.toString(), (rs, n) -> {
      String name = rs.getString("name");
      byName.computeIfAbsent(name, k -> new ArrayList<>())
          .add(new UdfDefinition.UdfSignature(stringsFrom(rs.getString("param_types")),
              rs.getString("return_type")));
      String source = rs.getString("source");
      Instant lastSyncedAt = instantOf(rs, "last_synced_at");
      metaByName.computeIfAbsent(name, k -> new UdfRowMeta(source, lastSyncedAt));
      return null;
    }, args.toArray());
    return byName.entrySet().stream()
        .map(e -> {
          UdfRowMeta meta = metaByName.get(e.getKey());
          return new UdfDefinition(e.getKey(), List.copyOf(e.getValue()),
              meta.source(), meta.lastSyncedAt());
        })
        .collect(Collectors.toList());
  }

  private record UdfRowMeta(String source, Instant lastSyncedAt) {
  }

  private static Instant instantOf(ResultSet rs, String column) {
    try {
      java.sql.Timestamp timestamp = rs.getTimestamp(column);
      return timestamp == null ? null : timestamp.toInstant();
    } catch (SQLException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "could not read udf " + column + ": " + e.getMessage());
    }
  }

  private List<String> stringsFrom(String json) {
    try {
      return mapper.readValue(json, new TypeReference<List<String>>() {
      });
    } catch (JsonProcessingException e) {
      throw new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
          "could not deserialize udf param types: " + e.getMessage());
    }
  }

  private SqlMaskException duplicateUdf(String name, String instanceName) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "udf '" + name + "' already exists in instance '" + instanceName + "'");
  }

  private SqlMaskException missingUdf(String name, String instanceName) {
    return new SqlMaskException(SqlMaskException.Code.CONFIG_ERROR,
        "udf '" + name + "' not found in instance '" + instanceName + "'");
  }

  private boolean policyExists(long instanceId, String policyName) {
    List<Long> ids = jdbc.queryForList("SELECT id FROM policy WHERE instance_id = ? AND name = ?",
        Long.class, instanceId, policyName);
    return !ids.isEmpty();
  }

  private void insertPolicy(long instanceId, PolicyEntity policy) {
    jdbc.update("INSERT INTO policy (instance_id, name, policy_type, is_enabled, priority,"
            + " udf, arguments, filter_expr, resource, subjects)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ps -> {
          ps.setLong(1, instanceId);
          ps.setString(2, policy.name());
          ps.setString(3, policy.policyType().name());
          ps.setBoolean(4, policy.enabled());
          ps.setInt(5, policy.priority());
          setNullableString(ps, 6, policy.udf());
          setNullableJson(ps, 7, policy.arguments());
          setNullableString(ps, 8, policy.filterExpr());
          ps.setString(9, toJson(policy.resource()));
          ps.setString(10, toJson(policy.subjects()));
        });
  }

  private PolicyEntity mapPolicy(ResultSet rs) throws SQLException {
    return new PolicyEntity(
        rs.getString("name"),
        PolicyType.valueOf(rs.getString("policy_type")),
        rs.getBoolean("is_enabled"),
        rs.getInt("priority"),
        resourceFrom(rs.getString("resource")),
        subjectFrom(rs.getString("subjects")),
        rs.getString("udf"),
        argumentsFrom(rs.getString("arguments")),
        rs.getString("filter_expr"));
  }

  private String selectPolicySql() {
    return "SELECT name, policy_type, is_enabled, priority, udf, arguments, filter_expr,"
        + " resource, subjects FROM policy";
  }

  private void setNullableString(PreparedStatement ps, int index, String value) throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, value);
    }
  }

  private static void setNullableTimestamp(PreparedStatement ps, int index, Instant value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, Types.TIMESTAMP);
    } else {
      ps.setTimestamp(index, java.sql.Timestamp.from(value));
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
}
