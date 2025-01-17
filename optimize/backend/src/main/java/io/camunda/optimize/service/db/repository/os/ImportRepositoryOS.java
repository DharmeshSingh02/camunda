/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.optimize.service.db.repository.os;

import static io.camunda.optimize.service.db.DatabaseConstants.IMPORT_INDEX_INDEX_NAME;
import static io.camunda.optimize.service.db.DatabaseConstants.LIST_FETCH_LIMIT;
import static io.camunda.optimize.service.db.DatabaseConstants.POSITION_BASED_IMPORT_INDEX_NAME;
import static io.camunda.optimize.service.db.DatabaseConstants.TIMESTAMP_BASED_IMPORT_INDEX_NAME;
import static io.camunda.optimize.service.db.os.externalcode.client.dsl.QueryDSL.stringTerms;
import static io.camunda.optimize.service.db.schema.index.index.TimestampBasedImportIndex.DB_TYPE_INDEX_REFERS_TO;
import static java.lang.String.format;

import io.camunda.optimize.dto.optimize.OptimizeDto;
import io.camunda.optimize.dto.optimize.datasource.DataSourceDto;
import io.camunda.optimize.dto.optimize.index.AllEntitiesBasedImportIndexDto;
import io.camunda.optimize.dto.optimize.index.EngineImportIndexDto;
import io.camunda.optimize.dto.optimize.index.ImportIndexDto;
import io.camunda.optimize.dto.optimize.index.PositionBasedImportIndexDto;
import io.camunda.optimize.dto.optimize.index.TimestampBasedImportIndexDto;
import io.camunda.optimize.service.db.os.OptimizeOpenSearchClient;
import io.camunda.optimize.service.db.repository.ImportRepository;
import io.camunda.optimize.service.db.schema.OptimizeIndexNameService;
import io.camunda.optimize.service.exceptions.OptimizeRuntimeException;
import io.camunda.optimize.service.util.DatabaseHelper;
import io.camunda.optimize.service.util.configuration.ConfigurationService;
import io.camunda.optimize.service.util.configuration.condition.OpenSearchCondition;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.core.GetResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
@Conditional(OpenSearchCondition.class)
public class ImportRepositoryOS implements ImportRepository {
  private final OptimizeOpenSearchClient osClient;
  private final ConfigurationService configurationService;
  private final OptimizeIndexNameService indexNameService;
  private final DateTimeFormatter dateTimeFormatter;

  @Override
  public List<TimestampBasedImportIndexDto> getAllTimestampBasedImportIndicesForTypes(
      List<String> indexTypes) {
    log.debug("Fetching timestamp based import indices of types '{}'", indexTypes);

    final SearchRequest.Builder requestBuilder =
        new SearchRequest.Builder()
            .index(
                indexNameService.getOptimizeIndexAliasForIndex(TIMESTAMP_BASED_IMPORT_INDEX_NAME))
            .query(stringTerms(DB_TYPE_INDEX_REFERS_TO, indexTypes))
            .size(LIST_FETCH_LIMIT);

    return osClient.searchValues(requestBuilder, TimestampBasedImportIndexDto.class);
  }

  @Override
  public <T extends ImportIndexDto<D>, D extends DataSourceDto> Optional<T> getImportIndex(
      final String indexName,
      final String indexType,
      final Class<T> importDTOClass,
      final String typeIndexComesFrom,
      final D dataSourceDto) {
    log.debug("Fetching {} import index of type '{}'", indexType, typeIndexComesFrom);
    final GetResponse<T> response =
        osClient.get(
            indexNameService.getOptimizeIndexAliasForIndex(indexName),
            DatabaseHelper.constructKey(typeIndexComesFrom, dataSourceDto),
            importDTOClass,
            format("Could not fetch %s import index", indexType));

    if (response.found()) {
      return Optional.ofNullable(response.source());
    } else {
      log.debug(
          "Was not able to retrieve {} import index for type [{}] and engine [{}] from opensearch.",
          indexType,
          typeIndexComesFrom,
          dataSourceDto);
      return Optional.empty();
    }
  }

  @Override
  public void importPositionBasedIndices(
      final String importItemName, final List<PositionBasedImportIndexDto> importIndexDtos) {
    osClient.doImportBulkRequestWithList(
        importItemName,
        importIndexDtos,
        this::addPositionBasedImportIndexRequest,
        configurationService.getSkipDataAfterNestedDocLimitReached());
  }

  @Override
  public void importIndices(
      final String importItemName, final List<EngineImportIndexDto> engineImportIndexDtos) {
    osClient.doImportBulkRequestWithList(
        importItemName,
        engineImportIndexDtos,
        this::addImportIndexRequest,
        configurationService.getSkipDataAfterNestedDocLimitReached());
  }

  private BulkOperation addPositionBasedImportIndexRequest(
      PositionBasedImportIndexDto optimizeDto) {
    log.debug(
        "Writing position based import index of type [{}] with position [{}] to opensearch",
        optimizeDto.getEsTypeIndexRefersTo(),
        optimizeDto.getPositionOfLastEntity());
    // leaving the prefix "es" although it is valid for ES and OS,
    // since changing this would require data migration and the cost/benefit of the change is not
    // worth the effort
    return new BulkOperation.Builder()
        .index(
            new IndexOperation.Builder<PositionBasedImportIndexDto>()
                .index(
                    indexNameService.getOptimizeIndexAliasForIndex(
                        POSITION_BASED_IMPORT_INDEX_NAME))
                .id(
                    DatabaseHelper.constructKey(
                        optimizeDto.getEsTypeIndexRefersTo(), optimizeDto.getDataSource()))
                .document(optimizeDto)
                .build())
        .build();
  }

  private BulkOperation addImportIndexRequest(OptimizeDto optimizeDto) {
    if (optimizeDto instanceof TimestampBasedImportIndexDto timestampBasedIndexDto) {
      return createTimestampBasedRequest(timestampBasedIndexDto);
    } else if (optimizeDto instanceof AllEntitiesBasedImportIndexDto entitiesBasedIndexDto) {
      return createAllEntitiesBasedRequest(entitiesBasedIndexDto);
    } else {
      throw new OptimizeRuntimeException(
          format(
              "Import bulk operation is not supported for %s", optimizeDto.getClass().getName()));
    }
  }

  private BulkOperation createTimestampBasedRequest(TimestampBasedImportIndexDto importIndex) {
    String currentTimeStamp = dateTimeFormatter.format(importIndex.getTimestampOfLastEntity());
    log.debug(
        "Writing timestamp based import index [{}] of type [{}] with execution timestamp [{}] to opensearch",
        currentTimeStamp,
        importIndex.getEsTypeIndexRefersTo(),
        importIndex.getLastImportExecutionTimestamp());
    return new BulkOperation.Builder()
        .index(
            new IndexOperation.Builder<TimestampBasedImportIndexDto>()
                .index(
                    indexNameService.getOptimizeIndexAliasForIndex(
                        TIMESTAMP_BASED_IMPORT_INDEX_NAME))
                .id(getId(importIndex))
                .document(importIndex)
                .build())
        .build();
  }

  private String getId(EngineImportIndexDto importIndex) {
    return DatabaseHelper.constructKey(
        importIndex.getEsTypeIndexRefersTo(), importIndex.getEngine());
  }

  private BulkOperation createAllEntitiesBasedRequest(AllEntitiesBasedImportIndexDto importIndex) {
    record Doc(String engine, long importIndex) {}
    log.debug(
        "Writing all entities based import index type [{}] to opensearch. Starting from [{}]",
        importIndex.getEsTypeIndexRefersTo(),
        importIndex.getImportIndex());
    return new BulkOperation.Builder()
        .index(
            new IndexOperation.Builder<Doc>()
                .index(indexNameService.getOptimizeIndexAliasForIndex(IMPORT_INDEX_INDEX_NAME))
                .id(getId(importIndex))
                .document(new Doc(importIndex.getEngine(), importIndex.getImportIndex()))
                .build())
        .build();
  }
}
