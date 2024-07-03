/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.optimize.data.generation.generators.impl.process;

import io.camunda.optimize.data.generation.UserAndGroupProvider;
import io.camunda.optimize.test.util.client.SimpleEngineClient;
import java.util.HashMap;
import java.util.Map;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;

public class BookRequestNoBusinessKeyDataGenerator extends ProcessDataGenerator {

  private static final String DIAGRAM = "/diagrams/process/book-request.bpmn";

  public BookRequestNoBusinessKeyDataGenerator(
      final SimpleEngineClient engineClient,
      final Integer nVersions,
      final UserAndGroupProvider userAndGroupProvider) {
    super(engineClient, nVersions, userAndGroupProvider);
  }

  @Override
  protected BpmnModelInstance retrieveDiagram() {
    return readProcessDiagramAsInstance(DIAGRAM);
  }

  @Override
  protected String getBusinessKey() {
    return null;
  }

  @Override
  protected Map<String, Object> createVariables() {
    return new HashMap<>();
  }

  @Override
  protected String[] getCorrelationNames() {
    return new String[] {"ReceivedBookRequest", "HoldBook", "DeclineHold"};
  }
}