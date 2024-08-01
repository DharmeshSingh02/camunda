/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.engine.state.mutable;

import io.camunda.zeebe.engine.state.immutable.RelocationState;
import io.camunda.zeebe.protocol.impl.record.value.message.MessageRecord;
import io.camunda.zeebe.protocol.impl.record.value.message.MessageSubscriptionRecord;
import org.agrona.DirectBuffer;

public interface MutableRelocationState extends RelocationState {
  void setRoutingInfo(RoutingInfo routingInfo);

  void markAsRelocating(DirectBuffer correlationKey);

  void markAsDone(DirectBuffer correlationKey);

  void enqueue(MessageRecord messageRecord);

  void enqueue(MessageSubscriptionRecord messageSubscriptionRecord);
}