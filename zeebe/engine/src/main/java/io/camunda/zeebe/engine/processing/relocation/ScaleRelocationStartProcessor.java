/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.engine.processing.relocation;

import io.camunda.zeebe.engine.processing.distribution.CommandDistributionBehavior;
import io.camunda.zeebe.engine.processing.streamprocessor.DistributedTypedRecordProcessor;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedCommandWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.camunda.zeebe.protocol.impl.record.value.scale.ScaleRecord;
import io.camunda.zeebe.protocol.record.intent.ScaleIntent;
import io.camunda.zeebe.stream.api.records.TypedRecord;
import io.camunda.zeebe.stream.api.state.KeyGenerator;

public class ScaleRelocationStartProcessor implements DistributedTypedRecordProcessor<ScaleRecord> {

  private final KeyGenerator keyGenerator;
  private final TypedCommandWriter commandWriter;
  private final StateWriter stateWriter;
  private final TypedResponseWriter responseWriter;
  private final CommandDistributionBehavior commandDistributionBehavior;

  public ScaleRelocationStartProcessor(
      final KeyGenerator keyGenerator,
      final Writers writers,
      final CommandDistributionBehavior commandDistributionBehavior) {
    this.keyGenerator = keyGenerator;
    commandWriter = writers.command();
    stateWriter = writers.state();
    responseWriter = writers.response();
    this.commandDistributionBehavior = commandDistributionBehavior;
  }

  @Override
  public void processNewCommand(final TypedRecord<ScaleRecord> command) {
    // TODO: Handle duplicate requests
    final var distributionKey = keyGenerator.nextKey();
    // TODO: Use distribution key locally too, as unique key to identify this relocation?
    processDistributedCommand(command);
    commandDistributionBehavior.distributeCommand(distributionKey, command);
    responseWriter.writeEventOnCommand(
        -1, ScaleIntent.RELOCATION_STARTED, command.getValue(), command);
  }

  @Override
  public void processDistributedCommand(final TypedRecord<ScaleRecord> command) {
    stateWriter.appendFollowUpEvent(
        command.getKey(), ScaleIntent.RELOCATION_STARTED, command.getValue());
    commandWriter.appendFollowUpCommand(
        command.getKey(), ScaleIntent.RELOCATE_NEXT_CORRELATION_KEY, new ScaleRecord());
  }
}
