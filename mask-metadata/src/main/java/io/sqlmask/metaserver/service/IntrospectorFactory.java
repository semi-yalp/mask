package io.sqlmask.metaserver.service;

import io.sqlmask.introspect.MetadataIntrospector;

/** Indirection over the static engine registry so tests can inject fakes. */
@FunctionalInterface
public interface IntrospectorFactory {

  MetadataIntrospector byEngine(String engine);
}
