package io.sqlmask.query.submit;

import io.sqlmask.query.service.QueryModels.QueryResult;

import java.sql.SQLException;

/**
 * One way of getting a rewritten SELECT to an engine and its rows back.
 * Registered by type ("jdbc", "http", ...) and chosen per instance; the
 * registry defaults unknown/blank types to {@code jdbc}.
 */
public interface QuerySubmitter {

  String type();

  QueryResult submit(SubmitRequest request) throws SQLException;
}
