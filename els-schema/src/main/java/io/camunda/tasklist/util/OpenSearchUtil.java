/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a proprietary license.
 * See the License.txt file for more information. You may not use this file
 * except in compliance with the proprietary license.
 */
package io.camunda.tasklist.util;

import static io.camunda.tasklist.util.CollectionUtil.map;
import static io.camunda.tasklist.util.CollectionUtil.throwAwayNullElements;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.camunda.tasklist.entities.TasklistEntity;
import io.camunda.tasklist.exceptions.PersistenceException;
import io.camunda.tasklist.exceptions.TasklistRuntimeException;
import io.camunda.tasklist.schema.templates.TemplateDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch._types.Time;
import org.opensearch.client.opensearch._types.query_dsl.BoolQuery;
import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.opensearch._types.query_dsl.QueryVariant;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.ClearScrollRequest;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
import org.opensearch.client.opensearch.core.DeleteByQueryResponse;
import org.opensearch.client.opensearch.core.ReindexRequest;
import org.opensearch.client.opensearch.core.ReindexResponse;
import org.opensearch.client.opensearch.core.ScrollRequest;
import org.opensearch.client.opensearch.core.ScrollResponse;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.SearchResponse;
import org.opensearch.client.opensearch.core.UpdateRequest;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;
import org.opensearch.client.opensearch.indices.RefreshRequest;
import org.opensearch.client.opensearch.indices.RefreshResponse;
import org.opensearch.client.util.ObjectBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class OpenSearchUtil {

  public static final String ZEEBE_INDEX_DELIMITER = "_";
  public static final String SCROLL_KEEP_ALIVE_MS = "60000ms";
  public static final String INTERNAL_SCROLL_KEEP_ALIVE_MS =
      "30000ms"; // this scroll timeout value is used for reindex and delete q
  public static final int QUERY_MAX_SIZE = 10000;
  public static final int UPDATE_RETRY_COUNT = 3;
  public static final Function<Hit, Long> SEARCH_HIT_ID_TO_LONG = (hit) -> Long.valueOf(hit.id());
  public static final Function<Hit, String> SEARCH_HIT_ID_TO_STRING = Hit::id;
  private static final Logger LOGGER = LoggerFactory.getLogger(OpenSearchUtil.class);

  public static <T extends TasklistEntity> List<T> scroll(
      SearchRequest searchRequest, Class<T> clazz, OpenSearchClient osClient) throws IOException {

    String scrollId = null;
    try {
      SearchResponse<T> response = osClient.search(searchRequest, clazz);

      final List<T> result = new ArrayList<>();
      scrollId = response.scrollId();
      List<Hit<T>> hits = response.hits().hits();

      while (hits.size() != 0) {
        result.addAll(hits.stream().map(Hit::source).toList());

        final ScrollRequest scrollRequest =
            new ScrollRequest.Builder()
                .scrollId(scrollId)
                .scroll(Time.of(t -> t.time(SCROLL_KEEP_ALIVE_MS)))
                .build();

        response = osClient.scroll(scrollRequest, clazz);

        scrollId = response.scrollId();
        hits = response.hits().hits();
      }
      return result;

    } finally {
      if (scrollId != null) {
        clearScroll(scrollId, osClient);
      }
    }
  }

  public static void clearScroll(String scrollId, OpenSearchClient osClient) {
    if (scrollId != null) {
      // clear the scroll
      final ClearScrollRequest clearScrollRequest =
          new ClearScrollRequest.Builder().scrollId(scrollId).build();

      try {
        osClient.clearScroll(clearScrollRequest);
      } catch (Exception e) {
        LOGGER.warn("Error occurred when clearing the scroll with id [{}]", scrollId);
      }
    }
  }

  public static Query joinWithAnd(ObjectBuilder... queries) {
    final List<ObjectBuilder> notNullQueries = throwAwayNullElements(queries);
    if (notNullQueries.size() == 0) {
      return new Query.Builder().build();
    }
    final BoolQuery.Builder boolQ = boolQuery();
    for (ObjectBuilder queryBuilder : notNullQueries) {
      final var query = queryBuilder.build();

      if (query instanceof QueryVariant qv) {
        boolQ.must(qv._toQuery());
      } else if (query instanceof Query q) {
        boolQ.must(q);
      } else {
        throw new TasklistRuntimeException("Queries should be of type [Query] or [QueryVariant]");
      }
    }
    return new Query.Builder().bool(boolQ.build()).build();
  }

  public static Query.Builder joinQueryBuilderWithAnd(ObjectBuilder... queries) {
    final List<ObjectBuilder> notNullQueries = throwAwayNullElements(queries);
    final Query.Builder queryBuilder = new Query.Builder();
    switch (notNullQueries.size()) {
      case 0:
        return null;
      default:
        final BoolQuery.Builder boolQ = boolQuery();
        for (ObjectBuilder query : notNullQueries) {
          boolQ.must((Query) query.build());
        }
        queryBuilder.bool(boolQ.build());
        return queryBuilder;
    }
  }

  public static <T> T fromSearchHit(
      String searchHitString, ObjectMapper objectMapper, Class<T> clazz) {
    final T entity;
    try {
      entity = objectMapper.readValue(searchHitString, clazz);
    } catch (IOException e) {
      throw new TasklistRuntimeException(
          String.format(
              "Error while reading entity of type %s from Elasticsearch!", clazz.getName()),
          e);
    }
    return entity;
  }

  public static CompletableFuture<ScrollResponse<Object>> scrollAsync(
      final ScrollRequest scrollRequest,
      final Executor executor,
      final OpenSearchAsyncClient osClient) {
    final var searchFuture = new CompletableFuture<SearchResponse>();
    try {
      final CompletableFuture<ScrollResponse<Object>> response =
          osClient.scroll(scrollRequest, Object.class);
      return response;
    } catch (IOException e) {
      throw new TasklistRuntimeException(e);
    }
  }

  public static BoolQuery.Builder boolQuery() {
    return new BoolQuery.Builder();
  }

  public static CompletableFuture<DeleteByQueryResponse> deleteByQueryAsync(
      final DeleteByQueryRequest deleteRequest,
      final Executor executor,
      final OpenSearchAsyncClient osClient) {
    try {
      return osClient.deleteByQuery(deleteRequest);
    } catch (IOException e) {
      throw new TasklistRuntimeException(e);
    }
  }

  public static CompletableFuture<ReindexResponse> reindexAsync(
      final ReindexRequest reindexRequest,
      final Executor executor,
      final OpenSearchAsyncClient osClient) {
    try {
      return osClient.reindex(reindexRequest);
    } catch (IOException e) {
      throw new TasklistRuntimeException(e);
    }
  }

  public static void processBulkRequest(OpenSearchClient osClient, BulkRequest bulkRequest)
      throws PersistenceException {

    if (bulkRequest.operations().size() > 0) {
      try {
        LOGGER.debug("************* FLUSH BULK START *************");
        final BulkResponse bulkItemResponses = osClient.bulk(bulkRequest);
        final List<BulkResponseItem> items = bulkItemResponses.items();
        for (BulkResponseItem responseItem : items) {
          if (responseItem.error() != null) {
            LOGGER.error(
                String.format(
                    "%s failed for type [%s] and id [%s]: %s",
                    responseItem.operationType(),
                    responseItem.index(),
                    responseItem.id(),
                    responseItem.error().reason()),
                "error on OpenSearch BulkRequest");
            throw new PersistenceException(
                "Operation failed: " + responseItem.error().reason(),
                new TasklistRuntimeException(responseItem.error().reason()),
                Integer.valueOf(responseItem.id()));
          }
        }
        LOGGER.debug("************* FLUSH BULK FINISH *************");
      } catch (IOException ex) {
        throw new PersistenceException(
            "Error when processing bulk request against OpenSearch: " + ex.getMessage(), ex);
      }
    }
  }

  public static void refreshIndicesFor(final OpenSearchClient osClient, final String indexPattern) {
    final var refreshRequest = new RefreshRequest.Builder().index(List.of(indexPattern)).build();
    try {
      final RefreshResponse refresh = osClient.indices().refresh(refreshRequest);
      if (refresh.shards().failures().size() > 0) {
        LOGGER.warn("Unable to refresh indices: {}", indexPattern);
      }
    } catch (Exception ex) {
      LOGGER.warn(String.format("Unable to refresh indices: %s", indexPattern), ex);
    }
  }

  public static <T> List<T> mapSearchHits(
      List<Hit> searchHits, ObjectMapper objectMapper, JavaType valueType) {
    return map(searchHits, (searchHit) -> objectMapper.convertValue(searchHit.source(), valueType));
  }

  public static CompletableFuture<SearchResponse<Object>> searchAsync(
      final SearchRequest searchRequest,
      final Executor executor,
      final OpenSearchAsyncClient osClient) {
    final var searchFuture = new CompletableFuture<SearchResponse>();

    try {
      return osClient.search(searchRequest, Object.class);
    } catch (IOException e) {
      throw new TasklistRuntimeException(e);
    }
  }

  public static void scrollWith(
      SearchRequest.Builder searchRequest,
      OpenSearchClient osClient,
      Consumer<List<Hit>> searchHitsProcessor,
      Consumer<Map> aggsProcessor,
      Consumer<HitsMetadata> firstResponseConsumer)
      throws IOException {

    searchRequest.scroll(Time.of(t -> t.time(OpenSearchUtil.INTERNAL_SCROLL_KEEP_ALIVE_MS)));
    final SearchResponse response = osClient.search(searchRequest.build(), Object.class);

    if (firstResponseConsumer != null) {
      firstResponseConsumer.accept(response.hits());
    }

    if (aggsProcessor != null) {
      aggsProcessor.accept(response.aggregations());
    }

    String scrollId = response.scrollId();
    HitsMetadata hits = response.hits();
    try {
      while (hits.hits().size() != 0) {
        if (searchHitsProcessor != null) {
          searchHitsProcessor.accept(response.hits().hits());
        }

        final ScrollRequest.Builder scrollRequest = new ScrollRequest.Builder();
        scrollRequest.scrollId(scrollId);
        scrollRequest.scroll(Time.of(t -> t.time(SCROLL_KEEP_ALIVE_MS)));

        final ScrollResponse<Object> scrollResponse =
            osClient.scroll(scrollRequest.build(), Object.class);
        scrollId = scrollResponse.scrollId();
        hits = scrollResponse.hits();
      }
    } catch (Exception e) {
      throw new TasklistRuntimeException(e.getMessage());
    } finally {
      clearScroll(scrollId, osClient);
    }
  }

  public static String whereToSearch(
      TemplateDescriptor template, OpenSearchUtil.QueryType queryType) {
    switch (queryType) {
      case ONLY_RUNTIME:
        return template.getFullQualifiedName();
      case ALL:
      default:
        return template.getAlias();
    }
  }

  public enum QueryType {
    ONLY_RUNTIME,
    ALL
  }

  public static <T> List<T> mapSearchHits(
      List<Hit> searchHits, ObjectMapper objectMapper, Class<T> clazz) {
    return map(
        searchHits,
        (searchHit) -> fromSearchHit(searchHit.source().toString(), objectMapper, clazz));
  }

  public static <T> List<T> scrollFieldToList(
      SearchRequest.Builder request, String fieldName, OpenSearchClient esClient)
      throws IOException {
    final List<T> result = new ArrayList<>();

    final Function<Hit, T> searchHitFieldToString =
        (searchHit) ->
            (T)
                ((LinkedHashMap) searchHit.source())
                    .get(fieldName); // searchHit.getSourceAsMap().get(fieldName);

    final Consumer<List<Hit>> collectFields =
        (hits) -> result.addAll(map(hits, searchHitFieldToString));

    scrollWith(request, esClient, collectFields, null, null);
    return result;
  }

  public static SearchRequest.Builder createSearchRequest(TemplateDescriptor template) {
    return createSearchRequest(template, OpenSearchUtil.QueryType.ALL);
  }

  public static SearchRequest.Builder createSearchRequest(
      TemplateDescriptor template, OpenSearchUtil.QueryType queryType) {
    final SearchRequest.Builder builder = new SearchRequest.Builder();
    builder.index(whereToSearch(template, queryType));
    return builder;
  }

  public static <T extends TasklistEntity> List<T> scroll(
      SearchRequest.Builder searchRequest,
      Class<T> clazz,
      ObjectMapper objectMapper,
      OpenSearchClient osClient)
      throws IOException {
    return scroll(searchRequest, clazz, objectMapper, osClient, null, null);
  }

  public static <T extends TasklistEntity> List<T> scroll(
      SearchRequest.Builder searchRequest,
      Class<T> clazz,
      ObjectMapper objectMapper,
      OpenSearchClient osClient,
      Consumer<HitsMetadata> searchHitsProcessor,
      Consumer<Map> aggsProcessor)
      throws IOException {

    searchRequest.scroll(Time.of(t -> t.time(SCROLL_KEEP_ALIVE_MS)));
    SearchResponse<T> response = osClient.search(searchRequest.build(), clazz);
    final List<T> result = new ArrayList<>();
    String scrollId = response.scrollId();
    HitsMetadata hits = response.hits();

    while (hits.hits().size() != 0) {
      result.addAll(hits.hits().stream().map(m -> ((Hit) m).source()).toList());
      // call response processor
      if (searchHitsProcessor != null) {
        searchHitsProcessor.accept(response.hits());
      }

      final ScrollRequest.Builder scrollRequest = new ScrollRequest.Builder();
      scrollRequest.scrollId(scrollId);
      scrollRequest.scroll(Time.of(t -> t.time(SCROLL_KEEP_ALIVE_MS)));

      response = osClient.scroll(scrollRequest.build(), clazz);
      scrollId = response.scrollId();
      hits = response.hits();
    }
    clearScroll(scrollId, osClient);

    return result;
  }

  public static List<String> scrollIdsToList(
      SearchRequest.Builder request, OpenSearchClient osClient) throws IOException {
    final List<String> result = new ArrayList<>();

    final Consumer<List<Hit>> collectIds =
        (hits) -> result.addAll(map(hits, SEARCH_HIT_ID_TO_STRING));

    scrollWith(request, osClient, collectIds, null, null);
    return result;
  }

  public static void executeUpdate(OpenSearchClient osClient, UpdateRequest updateRequest)
      throws PersistenceException {
    try {

      osClient.update(updateRequest, Object.class);

    } catch (OpenSearchException | IOException e) {
      final String errorMessage =
          String.format(
              "Update request failed for [%s] and id [%s] with the message [%s].",
              updateRequest.index(), updateRequest.id(), e.getMessage());
      throw new PersistenceException(errorMessage, e);
    }
  }
}
