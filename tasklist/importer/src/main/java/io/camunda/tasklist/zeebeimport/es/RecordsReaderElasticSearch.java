/*
 * Copyright Camunda Services GmbH
 *
 * BY INSTALLING, DOWNLOADING, ACCESSING, USING, OR DISTRIBUTING THE SOFTWARE (“USE”), YOU INDICATE YOUR ACCEPTANCE TO AND ARE ENTERING INTO A CONTRACT WITH, THE LICENSOR ON THE TERMS SET OUT IN THIS AGREEMENT. IF YOU DO NOT AGREE TO THESE TERMS, YOU MUST NOT USE THE SOFTWARE. IF YOU ARE RECEIVING THE SOFTWARE ON BEHALF OF A LEGAL ENTITY, YOU REPRESENT AND WARRANT THAT YOU HAVE THE ACTUAL AUTHORITY TO AGREE TO THE TERMS AND CONDITIONS OF THIS AGREEMENT ON BEHALF OF SUCH ENTITY.
 * “Licensee” means you, an individual, or the entity on whose behalf you receive the Software.
 *
 * Permission is hereby granted, free of charge, to the Licensee obtaining a copy of this Software and associated documentation files to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject in each case to the following conditions:
 * Condition 1: If the Licensee distributes the Software or any derivative works of the Software, the Licensee must attach this Agreement.
 * Condition 2: Without limiting other conditions in this Agreement, the grant of rights is solely for non-production use as defined below.
 * "Non-production use" means any use of the Software that is not directly related to creating products, services, or systems that generate revenue or other direct or indirect economic benefits.  Examples of permitted non-production use include personal use, educational use, research, and development. Examples of prohibited production use include, without limitation, use for commercial, for-profit, or publicly accessible systems or use for commercial or revenue-generating purposes.
 *
 * If the Licensee is in breach of the Conditions, this Agreement, including the rights granted under it, will automatically terminate with immediate effect.
 *
 * SUBJECT AS SET OUT BELOW, THE SOFTWARE IS PROVIDED “AS IS”, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 * NOTHING IN THIS AGREEMENT EXCLUDES OR RESTRICTS A PARTY’S LIABILITY FOR (A) DEATH OR PERSONAL INJURY CAUSED BY THAT PARTY’S NEGLIGENCE, (B) FRAUD, OR (C) ANY OTHER LIABILITY TO THE EXTENT THAT IT CANNOT BE LAWFULLY EXCLUDED OR RESTRICTED.
 */
package io.camunda.tasklist.zeebeimport.es;

import static io.camunda.tasklist.util.ElasticsearchUtil.*;
import static org.elasticsearch.index.query.QueryBuilders.rangeQuery;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.springframework.beans.factory.config.BeanDefinition.SCOPE_PROTOTYPE;

import io.camunda.tasklist.Metrics;
import io.camunda.tasklist.data.conditionals.ElasticSearchCondition;
import io.camunda.tasklist.exceptions.NoSuchIndexException;
import io.camunda.tasklist.exceptions.TasklistRuntimeException;
import io.camunda.tasklist.schema.indices.ImportPositionIndex;
import io.camunda.tasklist.zeebe.ImportValueType;
import io.camunda.tasklist.zeebeimport.ImportBatch;
import io.camunda.tasklist.zeebeimport.RecordsReaderAbstract;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

/**
 * Represents Zeebe data reader for one partition and one value type. After reading the data is also
 * schedules the jobs for import execution. Each reader can have it's own backoff, so that we make a
 * pause in case there is no data currently for given partition and value type.
 */
@Component
@Scope(SCOPE_PROTOTYPE)
@Conditional(ElasticSearchCondition.class)
public class RecordsReaderElasticSearch extends RecordsReaderAbstract {

  private static final Logger LOGGER = LoggerFactory.getLogger(RecordsReaderElasticSearch.class);

  @Autowired
  @Qualifier("zeebeEsClient")
  private RestHighLevelClient zeebeEsClient;

  public RecordsReaderElasticSearch(
      int partitionId, ImportValueType importValueType, int queueSize) {
    super(partitionId, importValueType, queueSize);
  }

  public ImportBatch readNextBatchBySequence(final Long fromSequence, final Long toSequence)
      throws NoSuchIndexException {
    final String aliasName =
        importValueType.getAliasName(tasklistProperties.getZeebeElasticsearch().getPrefix());
    final int batchSize = tasklistProperties.getZeebeElasticsearch().getBatchSize();
    final long lessThanEqualsSequence;
    final int maxNumberOfHits;

    if (toSequence != null && toSequence > 0) {
      // in worst case all the records are duplicated
      maxNumberOfHits = (int) ((toSequence - fromSequence) * 2);
      lessThanEqualsSequence = toSequence;
      LOGGER.debug(
          "Import batch reread was called. Data type {}, partitionId {}, sequence {}, lastSequence {}, maxNumberOfHits {}.",
          importValueType,
          partitionId,
          fromSequence,
          toSequence,
          maxNumberOfHits);
    } else {
      maxNumberOfHits = batchSize;
      lessThanEqualsSequence = fromSequence + batchSize;
    }

    final SearchSourceBuilder searchSourceBuilder =
        new SearchSourceBuilder()
            .sort(ImportPositionIndex.SEQUENCE, SortOrder.ASC)
            .query(
                rangeQuery(ImportPositionIndex.SEQUENCE)
                    .gt(fromSequence)
                    .lte(lessThanEqualsSequence))
            .size(maxNumberOfHits >= QUERY_MAX_SIZE ? QUERY_MAX_SIZE : maxNumberOfHits);

    final SearchRequest searchRequest =
        new SearchRequest(aliasName)
            .source(searchSourceBuilder)
            .routing(String.valueOf(partitionId))
            .requestCache(false);

    try {
      final SearchHit[] hits =
          withTimerSearchHits(() -> read(searchRequest, maxNumberOfHits >= QUERY_MAX_SIZE));
      return createImportBatch(hits);
    } catch (ElasticsearchStatusException ex) {
      if (ex.getMessage().contains("no such index")) {
        throw new NoSuchIndexException();
      } else {
        final String message =
            String.format(
                "Exception occurred, while obtaining next Zeebe records batch: %s",
                ex.getMessage());
        throw new TasklistRuntimeException(message, ex);
      }
    } catch (Exception e) {
      final String message =
          String.format(
              "Exception occurred, while obtaining next Zeebe records batch: %s", e.getMessage());
      throw new TasklistRuntimeException(message, e);
    }
  }

  private SearchHit[] withTimerSearchHits(Callable<SearchHit[]> callable) throws Exception {
    return metrics
        .getTimer(
            Metrics.TIMER_NAME_IMPORT_QUERY,
            Metrics.TAG_KEY_TYPE,
            importValueType.name(),
            Metrics.TAG_KEY_PARTITION,
            String.valueOf(partitionId))
        .recordCallable(callable);
  }

  private ImportBatchElasticSearch createImportBatch(SearchResponse searchResponse) {
    final SearchHit[] hits = searchResponse.getHits().getHits();
    String indexName = null;
    if (hits.length > 0) {
      indexName = hits[hits.length - 1].getIndex();
    }
    return new ImportBatchElasticSearch(
        partitionId, importValueType, Arrays.asList(hits), indexName);
  }

  private ImportBatchElasticSearch createImportBatch(SearchHit[] hits) {
    String indexName = null;
    if (hits.length > 0) {
      indexName = hits[hits.length - 1].getIndex();
    }
    return new ImportBatchElasticSearch(
        partitionId, importValueType, Arrays.asList(hits), indexName);
  }

  private SearchHit[] read(SearchRequest searchRequest, boolean scrollNeeded) throws IOException {
    String scrollId = null;
    try {
      final List<SearchHit> searchHits = new ArrayList<>();

      if (scrollNeeded) {
        searchRequest.scroll(TimeValue.timeValueMillis(SCROLL_KEEP_ALIVE_MS));
      }
      SearchResponse response = zeebeEsClient.search(searchRequest, RequestOptions.DEFAULT);

      searchHits.addAll(List.of(response.getHits().getHits()));

      if (scrollNeeded) {
        scrollId = response.getScrollId();
        do {
          final SearchScrollRequest scrollRequest = new SearchScrollRequest(scrollId);
          scrollRequest.scroll(TimeValue.timeValueMillis(SCROLL_KEEP_ALIVE_MS));

          response = zeebeEsClient.scroll(scrollRequest, RequestOptions.DEFAULT);

          scrollId = response.getScrollId();
          searchHits.addAll(List.of(response.getHits().getHits()));
        } while (response.getHits().getHits().length != 0);
      }
      return searchHits.toArray(new SearchHit[0]);
    } finally {
      if (scrollId != null) {
        clearScroll(scrollId, zeebeEsClient);
      }
    }
  }

  public ImportBatch readNextBatchByPositionAndPartition(long positionFrom, Long positionTo)
      throws NoSuchIndexException {
    final String aliasName =
        importValueType.getAliasName(tasklistProperties.getZeebeElasticsearch().getPrefix());
    try {

      final SearchRequest searchRequest = createSearchQuery(aliasName, positionFrom, positionTo);

      final SearchResponse searchResponse =
          withTimer(() -> zeebeEsClient.search(searchRequest, RequestOptions.DEFAULT));

      return createImportBatch(searchResponse);

    } catch (ElasticsearchStatusException ex) {
      if (ex.getMessage().contains("no such index")) {
        LOGGER.debug("No index found for alias {}", aliasName);
        throw new NoSuchIndexException();
      } else {
        final String message =
            String.format(
                "Exception occurred for alias [%s], while obtaining next Zeebe records batch: %s",
                aliasName, ex.getMessage());
        throw new TasklistRuntimeException(message, ex);
      }
    } catch (Exception e) {
      final String message =
          String.format(
              "Exception occurred for alias [%s], while obtaining next Zeebe records batch: %s",
              aliasName, e.getMessage());
      throw new TasklistRuntimeException(message, e);
    }
  }

  private SearchResponse withTimer(Callable<SearchResponse> callable) throws Exception {
    return metrics.getTimer(Metrics.TIMER_NAME_IMPORT_QUERY).recordCallable(callable);
  }

  private SearchRequest createSearchQuery(String aliasName, long positionFrom, Long positionTo) {
    RangeQueryBuilder positionQ = rangeQuery(ImportPositionIndex.POSITION).gt(positionFrom);
    if (positionTo != null) {
      positionQ = positionQ.lte(positionTo);
    }
    final QueryBuilder queryBuilder =
        joinWithAnd(positionQ, termQuery(PARTITION_ID_FIELD_NAME, partitionId));

    SearchSourceBuilder searchSourceBuilder =
        new SearchSourceBuilder()
            .query(queryBuilder)
            .sort(ImportPositionIndex.POSITION, SortOrder.ASC);
    if (positionTo == null) {
      searchSourceBuilder =
          searchSourceBuilder.size(tasklistProperties.getZeebeElasticsearch().getBatchSize());
    } else {
      LOGGER.debug(
          "Import batch reread was called. Data type {}, partitionId {}, positionFrom {}, positionTo {}.",
          importValueType,
          partitionId,
          positionFrom,
          positionTo);
      final int size = (int) (positionTo - positionFrom);
      searchSourceBuilder =
          searchSourceBuilder.size(
              size <= 0 || size > QUERY_MAX_SIZE
                  ? QUERY_MAX_SIZE
                  : size); // this size will be bigger than needed
    }
    return new SearchRequest(aliasName)
        .source(searchSourceBuilder)
        .routing(String.valueOf(partitionId))
        .requestCache(false);
  }
}