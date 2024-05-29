/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.data.transformers.query;

import io.camunda.data.clients.query.DataStorePrefixQuery;
import io.camunda.data.transformers.OpensearchTransformers;
import org.opensearch.client.opensearch._types.query_dsl.PrefixQuery;
import org.opensearch.client.opensearch._types.query_dsl.QueryBuilders;

public final class PrefixQueryTransformer
    extends QueryVariantTransformer<DataStorePrefixQuery, PrefixQuery> {

  public PrefixQueryTransformer(final OpensearchTransformers mappers) {
    super(mappers);
  }

  @Override
  public PrefixQuery apply(final DataStorePrefixQuery value) {
    return QueryBuilders.prefix().field(value.field()).value(value.value()).build();
  }
}