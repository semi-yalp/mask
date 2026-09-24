package io.sqlmask.introspect.udf;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MySQL UDF discovery: {@code information_schema.ROUTINES} lists the stored
 * functions of one database ({@code ROUTINE_TYPE = 'FUNCTION'} keeps
 * procedures out) with {@code DTD_IDENTIFIER} as the return type;
 * {@code information_schema.PARAMETERS} contributes the {@code IN} parameters
 * ordered by {@code ORDINAL_POSITION} (the return value row carries
 * {@code PARAMETER_MODE IS NULL} and {@code ORDINAL_POSITION = 0}, so it is
 * filtered by the IN predicate).
 */
public class MysqlUdfIntrospector implements UdfIntrospector {

  private static final String ROUTINES_SQL = """
      SELECT r.ROUTINE_NAME AS name, r.DTD_IDENTIFIER AS ret
      FROM information_schema.ROUTINES r
      WHERE r.ROUTINE_SCHEMA = ? AND r.ROUTINE_TYPE = 'FUNCTION'
      ORDER BY r.ROUTINE_NAME""";

  private static final String PARAMETERS_SQL = """
      SELECT p.SPECIFIC_NAME AS name, p.PARAMETER_NAME AS param, p.DTD_IDENTIFIER AS dtd,
             p.ORDINAL_POSITION AS pos
      FROM information_schema.PARAMETERS p
      WHERE p.SPECIFIC_SCHEMA = ? AND p.ROUTINE_TYPE = 'FUNCTION'
        AND p.PARAMETER_MODE = 'IN'
      ORDER BY p.SPECIFIC_NAME, p.ORDINAL_POSITION""";

  @Override
  public List<UdfSignature> introspect(Connection connection, String database) throws SQLException {
    Map<String, List<String>> paramsByFunction = new LinkedHashMap<>();
    try (PreparedStatement statement = connection.prepareStatement(PARAMETERS_SQL)) {
      statement.setString(1, database);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          paramsByFunction.computeIfAbsent(rs.getString("name"), k -> new ArrayList<>())
              .add(rs.getString("dtd"));
        }
      }
    }
    List<UdfSignature> signatures = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(ROUTINES_SQL)) {
      statement.setString(1, database);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          String name = rs.getString("name");
          signatures.add(new UdfSignature(name,
              paramsByFunction.getOrDefault(name, List.of()), rs.getString("ret")));
        }
      }
    }
    return signatures;
  }
}
