/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.optimize.dto.optimize.query.collection;

import io.camunda.optimize.dto.optimize.RoleType;
import io.camunda.optimize.dto.optimize.query.entity.EntityResponseDto;
import java.time.OffsetDateTime;

public interface CollectionEntity {

  String getId();

  String getCollectionId();

  String getName();

  String getOwner();

  OffsetDateTime getLastModified();

  EntityResponseDto toEntityDto(final RoleType roleType);
}