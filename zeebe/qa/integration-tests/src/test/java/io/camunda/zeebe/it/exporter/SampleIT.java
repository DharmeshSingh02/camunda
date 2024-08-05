/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.it.exporter;

import io.camunda.plugin.search.header.CustomHeader;
import io.camunda.plugin.search.header.DatabaseCustomHeaderSupplier;
import io.camunda.zeebe.exporter.ElasticsearchExporter;
import io.camunda.zeebe.qa.util.cluster.TestStandaloneBroker;
import io.camunda.zeebe.qa.util.junit.ZeebeIntegration;
import io.camunda.zeebe.qa.util.junit.ZeebeIntegration.TestZeebe;
import io.camunda.zeebe.util.ReflectUtil;
import io.camunda.zeebe.util.jar.ExternalJarClassLoader;
import io.camunda.zeebe.util.jar.ExternalJarLoadException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

// TODO: refactor me and rename me
@ZeebeIntegration
public class SampleIT {
  @TestZeebe
  private final TestStandaloneBroker broker =
      new TestStandaloneBroker()
          .withExporter(
              "elastic",
              cfg -> {
                cfg.setClassName(ElasticsearchExporter.class.getName());
                final Map<String, Object> args = new HashMap<>();
                args.put(
                    "interceptorPlugins",
                    List.of(
                        Map.of(
                            "id",
                            "test",
                            "className",
                            "com.myplugin.MyCustomHeaderPlugin",
                            "jarPath",
                            // TODO: change my path
                            "/Users/igpetrov/Workspaces/ESTeam/ZeebeDBPlugin/target/ZeebeDBPlugin-1.0-SNAPSHOT.jar")));
                cfg.setArgs(args);
              });

  @Test
  void shouldTest() {
    Assertions.assertThat(false).isTrue();
  }

  @Test
  void shouldLoadClass() throws ExternalJarLoadException, ClassNotFoundException {
    final var classLoader =
        ExternalJarClassLoader.ofPath(
            Path.of(
                "/Users/igpetrov/Workspaces/ESTeam/ZeebeDBPlugin/target/ZeebeDBPlugin-1.0-SNAPSHOT.jar"));
    final var pack = classLoader.getDefinedPackage("com.myplugin");
    final Class<?> aClass = classLoader.loadClass("com.myplugin.MyCustomHeaderPlugin");
    final DatabaseCustomHeaderSupplier searchPlugin =
        (DatabaseCustomHeaderSupplier) ReflectUtil.newInstance(aClass);
    final CustomHeader elasticsearchCustomHeader = searchPlugin.getElasticsearchCustomHeader();
    Assertions.assertThat(elasticsearchCustomHeader).isNotNull();
  }
}
