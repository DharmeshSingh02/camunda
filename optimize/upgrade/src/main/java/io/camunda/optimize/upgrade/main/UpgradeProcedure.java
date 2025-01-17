/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.optimize.upgrade.main;

import static io.camunda.optimize.upgrade.steps.UpgradeStepType.REINDEX;
import static io.camunda.optimize.upgrade.steps.UpgradeStepType.SCHEMA_DELETE_INDEX;

import com.vdurmont.semver4j.Semver;
import io.camunda.optimize.service.db.es.OptimizeElasticsearchClient;
import io.camunda.optimize.upgrade.es.SchemaUpgradeClient;
import io.camunda.optimize.upgrade.exception.UpgradeRuntimeException;
import io.camunda.optimize.upgrade.plan.UpgradePlan;
import io.camunda.optimize.upgrade.service.UpgradeStepLogEntryDto;
import io.camunda.optimize.upgrade.service.UpgradeStepLogService;
import io.camunda.optimize.upgrade.service.UpgradeValidationService;
import io.camunda.optimize.upgrade.steps.UpgradeStep;
import io.camunda.optimize.upgrade.steps.schema.DeleteIndexIfExistsStep;
import io.camunda.optimize.upgrade.steps.schema.ReindexStep;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UpgradeProcedure {

  protected final OptimizeElasticsearchClient esClient;
  protected final UpgradeValidationService upgradeValidationService;
  protected final SchemaUpgradeClient schemaUpgradeClient;
  protected final UpgradeStepLogService upgradeStepLogService;

  public UpgradeProcedure(
      final OptimizeElasticsearchClient esClient,
      final UpgradeValidationService upgradeValidationService,
      final SchemaUpgradeClient schemaUpgradeClient,
      final UpgradeStepLogService upgradeStepLogService) {
    this.esClient = esClient;
    this.upgradeValidationService = upgradeValidationService;
    this.schemaUpgradeClient = schemaUpgradeClient;
    this.upgradeStepLogService = upgradeStepLogService;
  }

  public void performUpgrade(final UpgradePlan upgradePlan) {
    final Semver targetVersion = upgradePlan.getToVersion();
    final Optional<String> optionalSchemaVersion = schemaUpgradeClient.getSchemaVersion();

    if (optionalSchemaVersion.isPresent()) {
      final Semver schemaVersion = new Semver(optionalSchemaVersion.get());
      if (schemaVersion.isLowerThan(targetVersion)) {
        validateVersions(schemaVersion, upgradePlan);
        try {
          upgradeStepLogService.initializeOrUpdate(schemaUpgradeClient);
          executeUpgradePlan(upgradePlan);
        } catch (Exception e) {
          log.error(
              "Error while executing update from {} to {}",
              upgradePlan.getFromVersion(),
              targetVersion,
              e);
          throw new UpgradeRuntimeException("Upgrade failed.", e);
        }
      } else {
        log.info(
            "Target schemaVersion or a newer version is already present, no update to perform to {}.",
            targetVersion);
      }
    } else {
      log.info(
          "No Connection to elasticsearch or no Optimize Metadata index found, skipping update to {}.",
          targetVersion);
    }
  }

  private void executeUpgradePlan(final UpgradePlan upgradePlan) {
    int currentStepCount = 1;
    final List<UpgradeStep> upgradeSteps = upgradePlan.getUpgradeSteps();
    Map<String, UpgradeStepLogEntryDto> appliedStepsById =
        upgradeStepLogService.getAllAppliedStepsForUpdateToById(
            schemaUpgradeClient, upgradePlan.getToVersion().toString());
    for (UpgradeStep step : upgradeSteps) {
      final UpgradeStepLogEntryDto logEntryDto =
          UpgradeStepLogEntryDto.builder()
              .indexName(getIndexNameForStep(step))
              .optimizeVersion(upgradePlan.getToVersion().toString())
              .stepType(step.getType())
              .stepNumber(currentStepCount)
              .build();
      final Optional<Instant> stepAppliedDate =
          Optional.ofNullable(appliedStepsById.get(logEntryDto.getId()))
              .map(UpgradeStepLogEntryDto::getAppliedDate);
      if (stepAppliedDate.isEmpty()) {
        log.info(
            "Starting step {}/{}: {} on index: {}",
            currentStepCount,
            upgradeSteps.size(),
            step.getClass().getSimpleName(),
            getIndexNameForStep(step));
        try {
          step.execute(schemaUpgradeClient);
          upgradeStepLogService.recordAppliedStep(schemaUpgradeClient, logEntryDto);
        } catch (UpgradeRuntimeException e) {
          log.error("The upgrade will be aborted. Please investigate the cause and retry it..");
          throw e;
        }
        log.info(
            "Successfully finished step {}/{}: {} on index: {}",
            currentStepCount,
            upgradeSteps.size(),
            step.getClass().getSimpleName(),
            getIndexNameForStep(step));
      } else {
        log.info(
            "Skipping Step {}/{}: {} on index: {} as it was found to be previously completed already at: {}.",
            currentStepCount,
            upgradeSteps.size(),
            step.getClass().getSimpleName(),
            getIndexNameForStep(step),
            stepAppliedDate.get());
      }
      currentStepCount++;
    }
    schemaUpgradeClient.updateOptimizeVersion(upgradePlan);
  }

  private void validateVersions(final Semver schemaVersion, final UpgradePlan upgradePlan) {
    upgradeValidationService.validateESVersion(esClient, upgradePlan.getToVersion().toString());
    upgradeValidationService.validateSchemaVersions(
        schemaVersion.getValue(),
        upgradePlan.getFromVersion().getValue(),
        upgradePlan.getToVersion().getValue());
  }

  private String getIndexNameForStep(final UpgradeStep step) {
    if (REINDEX.equals(step.getType())) {
      final ReindexStep reindexStep = (ReindexStep) step;
      return String.format(
          "%s-TO-%s",
          esClient
              .getIndexNameService()
              .getOptimizeIndexNameWithVersion(reindexStep.getSourceIndex()),
          esClient
              .getIndexNameService()
              .getOptimizeIndexNameWithVersion(reindexStep.getTargetIndex()));
    } else if (SCHEMA_DELETE_INDEX.equals(step.getType())) {
      return ((DeleteIndexIfExistsStep) step).getVersionedIndexName();
    } else {
      return esClient.getIndexNameService().getOptimizeIndexNameWithVersion(step.getIndex());
    }
  }
}
