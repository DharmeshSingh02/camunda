/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.optimize.service.importing.engine.fetcher.definition;

import static io.camunda.optimize.service.util.importing.EngineConstants.DECISION_DEFINITION_ENDPOINT;

import io.camunda.optimize.dto.engine.definition.DecisionDefinitionEngineDto;
import io.camunda.optimize.rest.engine.EngineContext;
import jakarta.ws.rs.core.GenericType;
import java.util.List;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class DecisionDefinitionFetcher extends DefinitionFetcher<DecisionDefinitionEngineDto> {

  public DecisionDefinitionFetcher(final EngineContext engineContext) {
    super(engineContext);
  }

  @Override
  protected GenericType<List<DecisionDefinitionEngineDto>> getResponseType() {
    return new GenericType<>() {};
  }

  @Override
  protected String getDefinitionEndpoint() {
    return DECISION_DEFINITION_ENDPOINT;
  }

  @Override
  protected int getMaxPageSize() {
    return configurationService.getEngineImportDecisionDefinitionMaxPageSize();
  }
}