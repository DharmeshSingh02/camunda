package org.camunda.optimize.service.importing;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.optimize.dto.engine.DecisionDefinitionEngineDto;
import org.camunda.optimize.dto.optimize.importing.DecisionInstanceDto;
import org.camunda.optimize.dto.optimize.importing.index.AllEntitiesBasedImportIndexDto;
import org.camunda.optimize.dto.optimize.importing.index.TimestampBasedImportIndexDto;
import org.camunda.optimize.test.it.rule.ElasticSearchIntegrationTestRule;
import org.camunda.optimize.test.it.rule.EmbeddedOptimizeRule;
import org.camunda.optimize.test.it.rule.EngineDatabaseRule;
import org.camunda.optimize.test.it.rule.EngineIntegrationRule;
import org.camunda.optimize.upgrade.es.ElasticsearchConstants;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map.Entry;

import static org.camunda.optimize.service.es.schema.type.index.TimestampBasedImportIndexType.ES_TYPE_INDEX_REFERS_TO;
import static org.camunda.optimize.service.es.schema.type.index.TimestampBasedImportIndexType.TIMESTAMP_BASED_IMPORT_INDEX_TYPE;
import static org.camunda.optimize.upgrade.es.ElasticsearchConstants.DECISION_DEFINITION_TYPE;
import static org.camunda.optimize.upgrade.es.ElasticsearchConstants.DECISION_INSTANCE_TYPE;
import static org.camunda.optimize.upgrade.es.ElasticsearchConstants.PROC_DEF_TYPE;
import static org.camunda.optimize.upgrade.es.ElasticsearchConstants.PROC_INSTANCE_TYPE;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.index.query.QueryBuilders.termsQuery;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;

public class DecisionImportIT {

  public EngineIntegrationRule engineRule = new EngineIntegrationRule();
  public ElasticSearchIntegrationTestRule elasticSearchRule = new ElasticSearchIntegrationTestRule();
  public EmbeddedOptimizeRule embeddedOptimizeRule = new EmbeddedOptimizeRule();
  public EngineDatabaseRule engineDatabaseRule = new EngineDatabaseRule();

  @Rule
  public RuleChain chain = RuleChain
    .outerRule(elasticSearchRule).around(engineRule).around(embeddedOptimizeRule).around(engineDatabaseRule);

  @Test
  public void importOfDecisionDataCanBeDisabled() {
    // given
    embeddedOptimizeRule.getConfigurationService().setImportDmnDataEnabled(false);
    embeddedOptimizeRule.reloadConfiguration();
    engineRule.deployAndStartDecisionDefinition();
    BpmnModelInstance exampleProcess = Bpmn.createExecutableProcess().name("foo").startEvent().endEvent().done();
    engineRule.deployAndStartProcess(exampleProcess);

    // when
    embeddedOptimizeRule.importAllEngineEntitiesFromScratch();
    elasticSearchRule.refreshOptimizeIndexInElasticsearch();

    // then
    allEntriesInElasticsearchHaveAllDataWithCount(DECISION_DEFINITION_TYPE, 0L);
    allEntriesInElasticsearchHaveAllDataWithCount(DECISION_INSTANCE_TYPE, 0L);
    allEntriesInElasticsearchHaveAllDataWithCount(PROC_INSTANCE_TYPE, 1L);
    allEntriesInElasticsearchHaveAllDataWithCount(PROC_DEF_TYPE, 1L);

    // cleanup
    embeddedOptimizeRule.getConfigurationService().setImportDmnDataEnabled(true);
    embeddedOptimizeRule.reloadConfiguration();
  }

  @Test
  public void allDecisionDefinitionFieldDataOfImportIsAvailable() {
    //given
    engineRule.deployDecisionDefinition();
    engineRule.deployDecisionDefinition();

    //when
    embeddedOptimizeRule.importAllEngineEntitiesFromScratch();
    elasticSearchRule.refreshOptimizeIndexInElasticsearch();

    //then
    allEntriesInElasticsearchHaveAllDataWithCount(DECISION_DEFINITION_TYPE, 2L);
  }

  @Test
  public void directlyExecutedDecisionInstanceFieldDataOfImportIsAvailable() {
    //given
    engineRule.deployAndStartDecisionDefinition();

    //when
    embeddedOptimizeRule.importAllEngineEntitiesFromScratch();
    elasticSearchRule.refreshOptimizeIndexInElasticsearch();

    //then
    final SearchResponse idsResp = getSearchResponseForAllDocumentsOfType(DECISION_INSTANCE_TYPE);
    assertThat(idsResp.getHits().getTotalHits(), is(1L));

    final SearchHit hit = idsResp.getHits().getHits()[0];
    assertDecisionInstanceFieldSetAsExpected(hit);
  }

  @Test
  public void multipleDecisionInstancesAreImported() {
    //given
    DecisionDefinitionEngineDto decisionDefinitionEngineDto = engineRule.deployAndStartDecisionDefinition();
    engineRule.startDecisionInstance(decisionDefinitionEngineDto.getId());

    //when
    embeddedOptimizeRule.importAllEngineEntitiesFromScratch();
    elasticSearchRule.refreshOptimizeIndexInElasticsearch();

    //then
    allEntriesInElasticsearchHaveAllDataWithCount(DECISION_INSTANCE_TYPE, 2L);
  }

  @Test
  public void decisionImportIndexesAreStored() {
    // given
    engineRule.deployAndStartDecisionDefinition();
    engineRule.deployAndStartDecisionDefinition();
    engineRule.deployAndStartDecisionDefinition();

    // when
    embeddedOptimizeRule.importAllEngineEntitiesFromScratch();
    embeddedOptimizeRule.storeImportIndexesToElasticsearch();
    elasticSearchRule.refreshOptimizeIndexInElasticsearch();

    // then
    SearchResponse searchDecisionInstanceTimestampBasedIndexResponse = getDecisionInstanceIndexResponse();
    assertThat(searchDecisionInstanceTimestampBasedIndexResponse.getHits().getTotalHits(), is(1L));
    final TimestampBasedImportIndexDto decisionInstanceDto = parseToDto(
      searchDecisionInstanceTimestampBasedIndexResponse.getHits().getHits()[0], TimestampBasedImportIndexDto.class
    );
    assertThat(decisionInstanceDto.getTimestampOfLastEntity(), is(lessThan(OffsetDateTime.now())));

    final String decisionDefinitionIndexId = DECISION_DEFINITION_TYPE + "-1";
    SearchResponse searchDecisionDefinitionIndexResponse = getDecisionDefinitionIndexById(decisionDefinitionIndexId);
    assertThat(searchDecisionDefinitionIndexResponse.getHits().getTotalHits(), is(1L));
    final AllEntitiesBasedImportIndexDto definitionImportIndex = parseToDto(
      searchDecisionDefinitionIndexResponse.getHits().getHits()[0],
      AllEntitiesBasedImportIndexDto.class
    );
    assertThat(definitionImportIndex.getImportIndex(), is(3L));
  }

  private SearchResponse getDecisionDefinitionIndexById(final String decisionDefinitionIndexId) {
    return elasticSearchRule.getClient()
      .prepareSearch(
        elasticSearchRule.getOptimizeIndex(ElasticsearchConstants.IMPORT_INDEX_TYPE)
      )
      .setTypes(ElasticsearchConstants.IMPORT_INDEX_TYPE)
      .setQuery(termsQuery("_id", decisionDefinitionIndexId))
      .setSize(100)
      .get();
  }

  private SearchResponse getDecisionInstanceIndexResponse() {
    return elasticSearchRule.getClient()
      .prepareSearch(elasticSearchRule.getOptimizeIndex(TIMESTAMP_BASED_IMPORT_INDEX_TYPE))
      .setTypes(TIMESTAMP_BASED_IMPORT_INDEX_TYPE)
      .setQuery(termsQuery(ES_TYPE_INDEX_REFERS_TO, DECISION_INSTANCE_TYPE))
      .setSize(100)
      .get();
  }


  private <T> T parseToDto(final SearchHit searchHit, Class<T> dtoClass) {
    try {
      return elasticSearchRule.getObjectMapper().readValue(searchHit.getSourceAsString(), dtoClass);
    } catch (IOException e) {
      throw new RuntimeException("Failed parsing dto: " + dtoClass.getSimpleName());
    }
  }

  private void allEntriesInElasticsearchHaveAllDataWithCount(final String elasticsearchType,
                                                             final long count) {
    SearchResponse idsResp = getSearchResponseForAllDocumentsOfType(elasticsearchType);

    assertThat(idsResp.getHits().getTotalHits(), is(count));
    for (SearchHit searchHit : idsResp.getHits().getHits()) {
      if (DECISION_INSTANCE_TYPE.equals(elasticsearchType)) {
        assertDecisionInstanceFieldSetAsExpected(searchHit);
      } else {
        assertAllFieldsSet(searchHit);
      }
    }
  }

  private void assertDecisionInstanceFieldSetAsExpected(final SearchHit hit) {
    final DecisionInstanceDto dto = parseToDto(hit, DecisionInstanceDto.class);
    assertThat(dto.getProcessDefinitionId(), is(nullValue()));
    assertThat(dto.getProcessDefinitionKey(), is(nullValue()));
    assertThat(dto.getDecisionDefinitionId(), is(notNullValue()));
    assertThat(dto.getDecisionDefinitionKey(), is(notNullValue()));
    assertThat(dto.getDecisionDefinitionVersion(), is(notNullValue()));
    assertThat(dto.getEvaluationDateTime(), is(notNullValue()));
    assertThat(dto.getProcessInstanceId(), is(nullValue()));
    assertThat(dto.getRootProcessInstanceId(), is(nullValue()));
    assertThat(dto.getActivityId(), is(nullValue()));
    assertThat(dto.getCollectResultValue(), is(nullValue()));
    assertThat(dto.getRootDecisionInstanceId(), is(nullValue()));
    assertThat(dto.getInputs().size(), is(2));
    dto.getInputs().forEach(inputInstanceDto -> {
      assertThat(inputInstanceDto.getId(), is(notNullValue()));
      assertThat(inputInstanceDto.getClauseId(), is(notNullValue()));
      assertThat(inputInstanceDto.getClauseName(), is(notNullValue()));
      assertThat(inputInstanceDto.getType(), is(notNullValue()));
      assertThat(inputInstanceDto.getValue(), is(notNullValue()));
    });
    assertThat(dto.getOutputs().size(), is(2));
    dto.getOutputs().forEach(outputInstanceDto -> {
      assertThat(outputInstanceDto.getId(), is(notNullValue()));
      assertThat(outputInstanceDto.getClauseId(), is(notNullValue()));
      assertThat(outputInstanceDto.getClauseName(), is(notNullValue()));
      assertThat(outputInstanceDto.getType(), is(notNullValue()));
      assertThat(outputInstanceDto.getValue(), is(notNullValue()));
      assertThat(outputInstanceDto.getRuleId(), is(notNullValue()));
      assertThat(outputInstanceDto.getRuleOrder(), is(notNullValue()));
    });
    assertThat(dto.getEngine(), is(notNullValue()));
  }

  private void assertAllFieldsSet(final SearchHit searchHit) {
    for (Entry searchHitField : searchHit.getSourceAsMap().entrySet()) {
      String errorMessage = "Something went wrong during fetching of field: " + searchHitField.getKey() +
        ". Should actually have a value!";
      assertThat(errorMessage, searchHitField.getValue(), is(notNullValue()));
      if (searchHitField.getValue() instanceof String) {
        String value = (String) searchHitField.getValue();
        assertThat(errorMessage, value.isEmpty(), is(false));
      }
    }
  }

  private SearchResponse getSearchResponseForAllDocumentsOfType(final String elasticsearchType) {
    QueryBuilder qb = matchAllQuery();

    return elasticSearchRule.getClient().prepareSearch(elasticSearchRule.getOptimizeIndex(elasticsearchType))
      .setTypes(elasticsearchType)
      .setQuery(qb)
      .setSize(100)
      .get();
  }

}
