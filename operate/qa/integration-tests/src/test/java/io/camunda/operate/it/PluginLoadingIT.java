/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.operate.it;

import io.camunda.plugin.search.header.CustomHeader;
import io.camunda.plugin.search.header.DatabaseCustomHeaderSupplier;
import io.camunda.zeebe.util.ReflectUtil;
import io.camunda.zeebe.util.jar.ExternalJarClassLoader;
import io.camunda.zeebe.util.jar.ExternalJarLoadException;
import java.nio.file.Path;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class PluginLoadingIT {
  @Test
  void shouldLoadClass() throws ExternalJarLoadException, ClassNotFoundException {
    final var classLoader =
        ExternalJarClassLoader.ofPath(
            Path.of(
                "/Users/igpetrov/Workspaces/ESTeam/ZeebeDBPlugin/target/ZeebeDBPlugin-1.0-SNAPSHOT.jar"));
    final Class<?> aClass = classLoader.loadClass("com.myplugin.MyCustomHeaderPlugin");
    final DatabaseCustomHeaderSupplier searchPlugin =
        (DatabaseCustomHeaderSupplier) ReflectUtil.newInstance(aClass);
    final CustomHeader elasticsearchCustomHeader = searchPlugin.getElasticsearchCustomHeader();
    Assertions.assertThat(elasticsearchCustomHeader).isNotNull();
  }
}
