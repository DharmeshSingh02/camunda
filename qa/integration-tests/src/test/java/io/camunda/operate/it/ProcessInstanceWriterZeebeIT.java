/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a proprietary license.
 * See the License.txt file for more information. You may not use this file
 * except in compliance with the proprietary license.
 */
package io.camunda.operate.it;

import io.camunda.operate.util.searchrepository.TestSearchRepository;
import io.camunda.operate.schema.templates.OperationTemplate;
import io.camunda.operate.schema.templates.ProcessInstanceDependant;
import io.camunda.operate.store.NotFoundException;
import io.camunda.operate.util.OperateZeebeAbstractIT;
import io.camunda.operate.webapp.elasticsearch.reader.ProcessInstanceReader;
import io.camunda.operate.webapp.writer.ProcessInstanceWriter;
import io.camunda.operate.webapp.zeebe.operation.CancelProcessInstanceHandler;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

public class ProcessInstanceWriterZeebeIT extends OperateZeebeAbstractIT {

  @Autowired
  private ProcessInstanceWriter processInstanceWriter;

  @Autowired
  private List<ProcessInstanceDependant> processInstanceDependants;

  @Autowired
  private TestSearchRepository testSearchRepository;

  @Autowired
  private ProcessInstanceReader processInstanceReader;

  @Autowired
  private CancelProcessInstanceHandler cancelProcessInstanceHandler;

  @Before
  public void before() {
    super.before();
    cancelProcessInstanceHandler.setZeebeClient(super.getClient());
    tester.deployProcess("single-task.bpmn")
        .waitUntil().processIsDeployed();
  }

  @Test
  public void shouldDeleteFinishedInstanceById() throws IOException {
    // given
    final Long finishedProcessInstanceKey =
        tester.startProcessInstance("process", null)
            .and().completeTask("task", "task", null)
            .waitUntil()
            .processInstanceIsFinished()
            .getProcessInstanceKey();
    // when
    processInstanceWriter.deleteInstanceById(finishedProcessInstanceKey);
    // and indices are updated
    searchTestRule.refreshSerchIndexes();
    // then
    assertThatProcessInstanceIsDeleted(finishedProcessInstanceKey);
    // and
    assertThatDependantsAreAlsoDeleted(finishedProcessInstanceKey);
  }

  @Test
  public void shouldDeleteCanceledInstanceById() throws Exception {
    // given
    final Long canceledProcessInstanceKey =
        tester.startProcessInstance("process", null)
            .waitUntil()
            .processInstanceIsStarted()
            .and()
            .cancelProcessInstanceOperation()
            .waitUntil().operationIsCompleted()
            .getProcessInstanceKey();
    // when
    processInstanceWriter.deleteInstanceById(canceledProcessInstanceKey);
    // and indices are updated
    searchTestRule.refreshSerchIndexes();
    // then
    assertThatProcessInstanceIsDeleted(canceledProcessInstanceKey);
    // and
    assertThatDependantsAreAlsoDeleted(canceledProcessInstanceKey);
  }

  @Test(expected = NotFoundException.class)
  public void shouldFailDeleteWithNotExistingId() throws IOException {
    // given nothing
    // when
    processInstanceWriter.deleteInstanceById(42L);
    // then throw exception
  }

  @Test(expected = IllegalArgumentException.class)
  public void shouldFailDeleteInstanceByIdWithInvalidState() throws IOException {
    // given
    final Long runningProcessInstance = tester.deployProcess("single-task.bpmn")
        .waitUntil().processIsDeployed()
        .startProcessInstance("process", null)
        .and()
        .waitUntil().processInstanceIsStarted()
        .getProcessInstanceKey();
    // when
    processInstanceWriter.deleteInstanceById(runningProcessInstance);
    // then throw exception
  }

  private void assertThatProcessInstanceIsDeleted(final Long canceledProcessInstanceKey) {
    try {
      processInstanceReader.getProcessInstanceByKey(canceledProcessInstanceKey);
      Assert.fail("Process instance wasn't deleted");
    } catch (NotFoundException nfe) {
      // should be thrown
    }
  }

  private void assertThatDependantsAreAlsoDeleted(final long finishedProcessInstanceKey) {
    processInstanceDependants.stream()
      .filter(t -> !(t instanceof OperationTemplate))
      .forEach(dependant -> {
        var index = dependant.getFullQualifiedName() + "*";
        var field = ProcessInstanceDependant.PROCESS_INSTANCE_KEY;
        var value = finishedProcessInstanceKey;
        try {
          var response = testSearchRepository.searchTerm(index, field, value, Object.class, 100);
          assertThat(response.size()).isZero();
        } catch (IOException e) {
          throw new RuntimeException("Test failed with exception");
        }
      });
  }

}