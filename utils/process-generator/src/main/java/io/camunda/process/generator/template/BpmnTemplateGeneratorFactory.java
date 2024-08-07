/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.process.generator.template;

import io.camunda.process.generator.BpmnFactories;
import io.camunda.process.generator.GeneratorContext;
import java.util.List;

public class BpmnTemplateGeneratorFactory {

  private final GeneratorContext generatorContext;
  private final List<BpmnTemplateGenerator> templateGenerators;

  public BpmnTemplateGeneratorFactory(
      final GeneratorContext generatorContext, final BpmnFactories bpmnFactories) {
    this.generatorContext = generatorContext;
    final var elementSequenceGenerator =
        new BpmnElementSequenceGenerator(
            generatorContext, bpmnFactories.getElementGeneratorFactory());
    templateGenerators =
        List.of(
            elementSequenceGenerator,
            // new BpmnEmbeddedSubprocessGenerator(generatorContext, elementSequenceGenerator),
            new BpmnExclusiveGatewayGenerator(generatorContext, elementSequenceGenerator));
  }

  public BpmnTemplateGenerator getGenerator() {
    final var randomIndex = generatorContext.getRandomNumber(templateGenerators.size());
    return templateGenerators.get(randomIndex);
  }
}
