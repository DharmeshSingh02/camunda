/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.optimize.upgrade.plan.factories;

import io.camunda.optimize.service.db.es.OptimizeElasticsearchClient;
import io.camunda.optimize.service.db.es.schema.index.SettingsIndexES;
import io.camunda.optimize.service.exceptions.OptimizeRuntimeException;
import io.camunda.optimize.upgrade.plan.UpgradeExecutionDependencies;
import io.camunda.optimize.upgrade.plan.UpgradePlan;
import io.camunda.optimize.upgrade.plan.UpgradePlanBuilder;
import io.camunda.optimize.upgrade.steps.schema.DeleteIndexIfExistsStep;
import io.camunda.optimize.upgrade.steps.schema.UpdateIndexStep;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Upgrade313To86PlanFactory implements UpgradePlanFactory {
  private static final String PROCESS_INSTANCE_ARCHIVE_INDEX_PREFIX =
      "optimize-process-instance-archive-";

  @Override
  public UpgradePlan createUpgradePlan(final UpgradeExecutionDependencies dependencies) {
    return UpgradePlanBuilder.createUpgradePlan()
        .fromVersion("3.13")
        .toVersion("8.6.0")
        .addUpgradeStep(new DeleteIndexIfExistsStep("onboarding-state", 2))
        .addUpgradeStep(new DeleteIndexIfExistsStep("license", 3))
        .addUpgradeStep(deleteLastModifierAndTelemetryInitializedSettingFields())
        .addUpgradeSteps(
            deleteProcessInstanceArchiveIndexIfExists(
                retrieveAllProcessInstanceArchiveIndexKeys(dependencies.esClient())))
        .build();
  }

  private static UpdateIndexStep deleteLastModifierAndTelemetryInitializedSettingFields() {
    return new UpdateIndexStep(
        new SettingsIndexES(),
        "ctx._source.remove(\"metadataTelemetryEnabled\"); ctx._source.remove(\"lastModifier\");");
  }

  private static List<DeleteIndexIfExistsStep> deleteProcessInstanceArchiveIndexIfExists(
      final List<String> archiveIndexKeys) {
    return archiveIndexKeys.stream()
        .map(
            key ->
                new DeleteIndexIfExistsStep(
                    PROCESS_INSTANCE_ARCHIVE_INDEX_PREFIX + key.toLowerCase(Locale.ENGLISH), 8))
        .toList();
  }

  private List<String> retrieveAllProcessInstanceArchiveIndexKeys(
      final OptimizeElasticsearchClient databaseClient) {
    try {
      return new ArrayList<>(
          databaseClient.getAllIndicesForAlias(PROCESS_INSTANCE_ARCHIVE_INDEX_PREFIX + "*").stream()
              .map(
                  fullAliasName ->
                      fullAliasName.substring(
                          // remove the shared index prefix
                          fullAliasName.indexOf(PROCESS_INSTANCE_ARCHIVE_INDEX_PREFIX)
                              + PROCESS_INSTANCE_ARCHIVE_INDEX_PREFIX.length(),
                          // remove the version suffix (we know its "_v8")
                          fullAliasName.length() - 3))
              .toList());
    } catch (OptimizeRuntimeException e) {
      log.error(
          "Unable to retrieve keys of process instance archive indices for index deletion. Returning empty instead.");
      return Collections.emptyList();
    }
  }
}
