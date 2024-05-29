/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.data.transformers.query;

import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.json.JsonData;
import io.camunda.data.clients.query.DataStoreRangeQuery;
import io.camunda.data.transformers.ElasticsearchTransformers;

public final class RangeQueryTransformer
    extends QueryVariantTransformer<DataStoreRangeQuery, RangeQuery> {

  public RangeQueryTransformer(final ElasticsearchTransformers transformers) {
    super(transformers);
  }

  @Override
  public RangeQuery apply(final DataStoreRangeQuery value) {
    final var field = value.field();
    final var from = value.from();
    final var to = value.to();

    return QueryBuilders.range()
        .field(field)
        .gt(of(value.gt()))
        .gte(of(value.gte()))
        .lt(of(value.lt()))
        .lte(of(value.lte()))
        .from(from)
        .to(to)
        .build();
  }

  private <T> JsonData of(T value) {
    return JsonData.of(value);
  }
}