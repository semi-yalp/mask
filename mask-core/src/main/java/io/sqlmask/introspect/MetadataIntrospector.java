package io.sqlmask.introspect;

/** Engine-agnostic metadata pull contract; one implementation per JDBC engine. */
public interface MetadataIntrospector {
  IntrospectionResult introspect(ConnectionSpec spec);
}
