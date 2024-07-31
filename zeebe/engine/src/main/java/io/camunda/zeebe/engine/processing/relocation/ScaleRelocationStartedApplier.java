/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.engine.processing.relocation;

import io.camunda.zeebe.engine.state.TypedEventApplier;
import io.camunda.zeebe.engine.state.immutable.RelocationState.RoutingInfo;
import io.camunda.zeebe.engine.state.mutable.MutableRelocationState;
import io.camunda.zeebe.protocol.impl.record.value.scale.ScaleRecord;
import io.camunda.zeebe.protocol.record.intent.ScaleIntent;
import java.util.Set;

public class ScaleRelocationStartedApplier implements TypedEventApplier<ScaleIntent, ScaleRecord> {
  private final MutableRelocationState relocationState;

  public ScaleRelocationStartedApplier(final MutableRelocationState relocationState) {
    this.relocationState = relocationState;
  }

  @Override
  public void applyState(final long key, final ScaleRecord value) {
    // TODO: Do not simply overwrite, but merge to handle the case where this partition received
    // RELOCATION_START and RELOCATION_ON_PARTITION_COMPLETED out of order.
    relocationState.setRoutingInfo(
        new RoutingInfo(
            value.getRoutingInfo().currentPartitionCount(),
            value.getRoutingInfo().newPartitionCount(),
            Set.of()));
  }
}
