/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.bpmn.behavior;

import static io.camunda.zeebe.util.buffer.BufferUtil.wrapString;

import io.camunda.zeebe.el.Expression;
import io.camunda.zeebe.engine.metrics.JobMetrics;
import io.camunda.zeebe.engine.processing.bpmn.BpmnElementContext;
import io.camunda.zeebe.engine.processing.common.ExpressionProcessor;
import io.camunda.zeebe.engine.processing.common.Failure;
import io.camunda.zeebe.engine.processing.deployment.model.element.ExecutableJobWorkerElement;
import io.camunda.zeebe.engine.processing.deployment.model.transformer.ExpressionTransformer;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.camunda.zeebe.engine.state.deployment.PersistedForm;
import io.camunda.zeebe.engine.state.immutable.FormState;
import io.camunda.zeebe.engine.state.immutable.JobState;
import io.camunda.zeebe.engine.state.immutable.JobState.State;
import io.camunda.zeebe.engine.state.instance.ElementInstance;
import io.camunda.zeebe.msgpack.spec.MsgPackWriter;
import io.camunda.zeebe.msgpack.value.DocumentValue;
import io.camunda.zeebe.protocol.Protocol;
import io.camunda.zeebe.protocol.impl.record.value.job.JobRecord;
import io.camunda.zeebe.protocol.record.intent.JobIntent;
import io.camunda.zeebe.protocol.record.value.ErrorType;
import io.camunda.zeebe.stream.api.state.KeyGenerator;
import io.camunda.zeebe.util.Either;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BpmnJobBehavior {

  private static final Logger LOGGER =
      LoggerFactory.getLogger(BpmnJobBehavior.class.getPackageName());
  private static final Set<State> CANCELABLE_STATES =
      EnumSet.of(State.ACTIVATABLE, State.ACTIVATED, State.FAILED, State.ERROR_THROWN);

  private final JobRecord jobRecord = new JobRecord().setVariables(DocumentValue.EMPTY_DOCUMENT);
  private final HeaderEncoder headerEncoder = new HeaderEncoder();

  private final KeyGenerator keyGenerator;
  private final StateWriter stateWriter;
  private final JobState jobState;
  private final ExpressionProcessor expressionBehavior;
  private final BpmnStateBehavior stateBehavior;
  private final BpmnIncidentBehavior incidentBehavior;
  private final JobMetrics jobMetrics;
  private final BpmnJobActivationBehavior jobActivationBehavior;
  private final FormState formState;

  public BpmnJobBehavior(
      final KeyGenerator keyGenerator,
      final JobState jobState,
      final Writers writers,
      final ExpressionProcessor expressionBehavior,
      final BpmnStateBehavior stateBehavior,
      final BpmnIncidentBehavior incidentBehavior,
      final BpmnJobActivationBehavior jobActivationBehavior,
      final JobMetrics jobMetrics,
      final FormState formState) {
    this.keyGenerator = keyGenerator;
    this.jobState = jobState;
    this.expressionBehavior = expressionBehavior;
    stateWriter = writers.state();
    this.stateBehavior = stateBehavior;
    this.incidentBehavior = incidentBehavior;
    this.jobMetrics = jobMetrics;
    this.jobActivationBehavior = jobActivationBehavior;
    this.formState = formState;
  }

  public Either<Failure, JobProperties> evaluateJobExpressions(
      final ExecutableJobWorkerElement element, final BpmnElementContext context) {
    final var jobWorkerProps = element.getJobWorkerProperties();
    final var scopeKey = context.getElementInstanceKey();
    final var tenantId = context.getTenantId();
    return Either.<Failure, JobProperties>right(new JobProperties())
        .flatMap(p -> evalTypeExp(jobWorkerProps.getType(), scopeKey).map(p::type))
        .flatMap(p -> evalRetriesExp(jobWorkerProps.getRetries(), scopeKey).map(p::retries))
        .flatMap(p -> evalAssigneeExp(jobWorkerProps.getAssignee(), scopeKey).map(p::assignee))
        .flatMap(
            p ->
                evalCandidateGroupsExp(jobWorkerProps.getCandidateGroups(), scopeKey)
                    .map(p::candidateGroups))
        .flatMap(
            p ->
                evalCandidateUsersExp(jobWorkerProps.getCandidateUsers(), scopeKey)
                    .map(p::candidateUsers))
        .flatMap(p -> evalDateExp(jobWorkerProps.getDueDate(), scopeKey).map(p::dueDate))
        .flatMap(p -> evalDateExp(jobWorkerProps.getFollowUpDate(), scopeKey).map(p::followUpDate))
        .flatMap(
            p -> evalFormIdExp(jobWorkerProps.getFormId(), scopeKey, tenantId).map(p::formKey));
  }

  public void createNewJob(
      final BpmnElementContext context,
      final ExecutableJobWorkerElement element,
      final JobProperties jobProperties) {
    writeJobCreatedEvent(context, element, jobProperties);
    jobMetrics.jobCreated(jobProperties.getType());
  }

  public void createNewUserTaskJob(
      final BpmnElementContext context,
      final ExecutableJobWorkerElement element,
      final JobProperties jobProperties,
      final String jobType,
      final long userTaskKey) {

    final var taskHeaders = element.getJobWorkerProperties().getTaskHeaders();
    final var encodedHeaders = encodeHeaders(taskHeaders, jobProperties);

    jobRecord
        .setType(jobType)
        .setRetries(jobProperties.getRetries().intValue())
        .setCustomHeaders(encodedHeaders)
        .setBpmnProcessId(context.getBpmnProcessId())
        .setProcessDefinitionVersion(context.getProcessVersion())
        .setProcessDefinitionKey(context.getProcessDefinitionKey())
        .setProcessInstanceKey(context.getProcessInstanceKey())
        .setElementId(element.getId())
        .setElementInstanceKey(context.getElementInstanceKey())
        .setTenantId(context.getTenantId())
        .getUserTask()
        .setUserTaskKey(userTaskKey);

    final var jobKey = keyGenerator.nextKey();
    stateWriter.appendFollowUpEvent(jobKey, JobIntent.CREATED, jobRecord);

    jobActivationBehavior.publishWork(jobKey, jobRecord);

    jobMetrics.jobCreated(jobProperties.getType());
  }

  private Either<Failure, String> evalTypeExp(final Expression type, final long scopeKey) {
    return expressionBehavior.evaluateStringExpression(type, scopeKey);
  }

  private Either<Failure, Long> evalRetriesExp(final Expression retries, final long scopeKey) {
    return expressionBehavior.evaluateLongExpression(retries, scopeKey);
  }

  private Either<Failure, String> evalAssigneeExp(final Expression assignee, final long scopeKey) {
    if (assignee == null) {
      return Either.right(null);
    }
    return expressionBehavior.evaluateStringExpression(assignee, scopeKey);
  }

  private Either<Failure, String> evalCandidateGroupsExp(
      final Expression candidateGroups, final long scopeKey) {
    if (candidateGroups == null) {
      return Either.right(null);
    }
    return expressionBehavior
        .evaluateArrayOfStringsExpression(candidateGroups, scopeKey)
        .map(ExpressionTransformer::asListLiteral);
  }

  private Either<Failure, String> evalCandidateUsersExp(
      final Expression candidateUsers, final long scopeKey) {
    if (candidateUsers == null) {
      return Either.right(null);
    }
    return expressionBehavior
        .evaluateArrayOfStringsExpression(candidateUsers, scopeKey)
        .map(ExpressionTransformer::asListLiteral);
  }

  private Either<Failure, String> evalDateExp(final Expression date, final long scopeKey) {
    if (date == null) {
      return Either.right(null);
    }
    return expressionBehavior
        .evaluateDateTimeExpression(date, scopeKey, true)
        .map(optionalDate -> optionalDate.map(ZonedDateTime::toString).orElse(null));
  }

  private Either<Failure, String> evalFormIdExp(
      final Expression formIdExp, final long scopeKey, final String tenantId) {
    if (formIdExp == null) {
      return Either.right(null);
    }
    return expressionBehavior
        .evaluateStringExpression(formIdExp, scopeKey)
        .flatMap(
            formId -> {
              final Optional<PersistedForm> latestFormById =
                  formState.findLatestFormById(wrapString(formId), tenantId);
              return latestFormById
                  .<Either<Failure, String>>map(
                      persistedForm -> Either.right(String.valueOf(persistedForm.getFormKey())))
                  .orElseGet(
                      () ->
                          Either.left(
                              new Failure(
                                  String.format(
                                      "Expected to find a form with id '%s',"
                                          + " but no form with this id is found,"
                                          + " at least a form with this id should be available."
                                          + " To resolve the Incident please deploy a form with the same id",
                                      formId),
                                  ErrorType.FORM_NOT_FOUND,
                                  scopeKey)));
            });
  }

  private void writeJobCreatedEvent(
      final BpmnElementContext context,
      final ExecutableJobWorkerElement jobWorkerElement,
      final JobProperties props) {

    final var taskHeaders = jobWorkerElement.getJobWorkerProperties().getTaskHeaders();
    final var encodedHeaders = encodeHeaders(taskHeaders, props);

    jobRecord
        .setType(props.getType())
        .setRetries(props.getRetries().intValue())
        .setCustomHeaders(encodedHeaders)
        .setBpmnProcessId(context.getBpmnProcessId())
        .setProcessDefinitionVersion(context.getProcessVersion())
        .setProcessDefinitionKey(context.getProcessDefinitionKey())
        .setProcessInstanceKey(context.getProcessInstanceKey())
        .setElementId(jobWorkerElement.getId())
        .setElementInstanceKey(context.getElementInstanceKey())
        .setTenantId(context.getTenantId());

    final var jobKey = keyGenerator.nextKey();
    stateWriter.appendFollowUpEvent(jobKey, JobIntent.CREATED, jobRecord);

    jobActivationBehavior.publishWork(jobKey, jobRecord);
  }

  private DirectBuffer encodeHeaders(
      final Map<String, String> taskHeaders, final JobProperties props) {
    final var headers = new HashMap<>(taskHeaders);
    final String assignee = props.getAssignee();
    final String candidateGroups = props.getCandidateGroups();
    final String candidateUsers = props.getCandidateUsers();
    final String dueDate = props.getDueDate();
    final String followUpDate = props.getFollowUpDate();
    final String formKey = props.getFormKey();

    if (assignee != null && !assignee.isEmpty()) {
      headers.put(Protocol.USER_TASK_ASSIGNEE_HEADER_NAME, assignee);
    }
    if (candidateGroups != null && !candidateGroups.isEmpty()) {
      headers.put(Protocol.USER_TASK_CANDIDATE_GROUPS_HEADER_NAME, candidateGroups);
    }
    if (candidateUsers != null && !candidateUsers.isEmpty()) {
      headers.put(Protocol.USER_TASK_CANDIDATE_USERS_HEADER_NAME, candidateUsers);
    }
    if (dueDate != null && !dueDate.isEmpty()) {
      headers.put(Protocol.USER_TASK_DUE_DATE_HEADER_NAME, dueDate);
    }
    if (followUpDate != null && !followUpDate.isEmpty()) {
      headers.put(Protocol.USER_TASK_FOLLOW_UP_DATE_HEADER_NAME, followUpDate);
    }
    if (formKey != null && !formKey.isEmpty()) {
      headers.put(Protocol.USER_TASK_FORM_KEY_HEADER_NAME, formKey);
    }
    return headerEncoder.encode(headers);
  }

  public void cancelJob(final BpmnElementContext context) {
    final var elementInstance = stateBehavior.getElementInstance(context);
    cancelJob(elementInstance);
  }

  public void cancelJob(final ElementInstance elementInstance) {
    final long jobKey = elementInstance.getJobKey();
    if (jobKey > 0) {
      writeJobCanceled(jobKey);
      incidentBehavior.resolveJobIncident(jobKey);
    }
  }

  private void writeJobCanceled(final long jobKey) {
    final State state = jobState.getState(jobKey);

    if (CANCELABLE_STATES.contains(state)) {
      final JobRecord job = jobState.getJob(jobKey);
      // Note that this logic is duplicated in JobCancelProcessor, if you change this please change
      // it there as well.
      stateWriter.appendFollowUpEvent(jobKey, JobIntent.CANCELED, job);
      jobMetrics.jobCanceled(job.getType());
    }
  }

  public static final class JobProperties {
    private String type;
    private Long retries;
    private String assignee;
    private String candidateGroups;
    private String candidateUsers;
    private String dueDate;
    private String followUpDate;
    private String formKey;

    public JobProperties type(final String type) {
      this.type = type;
      return this;
    }

    public String getType() {
      return type;
    }

    public JobProperties retries(final Long retries) {
      this.retries = retries;
      return this;
    }

    public Long getRetries() {
      return retries;
    }

    public JobProperties assignee(final String assignee) {
      this.assignee = assignee;
      return this;
    }

    public String getAssignee() {
      return assignee;
    }

    public JobProperties candidateGroups(final String candidateGroups) {
      this.candidateGroups = candidateGroups;
      return this;
    }

    public String getCandidateGroups() {
      return candidateGroups;
    }

    public JobProperties candidateUsers(final String candidateUsers) {
      this.candidateUsers = candidateUsers;
      return this;
    }

    public String getCandidateUsers() {
      return candidateUsers;
    }

    public JobProperties dueDate(final String dueDate) {
      this.dueDate = dueDate;
      return this;
    }

    public String getDueDate() {
      return dueDate;
    }

    public JobProperties followUpDate(final String followUpDate) {
      this.followUpDate = followUpDate;
      return this;
    }

    public String getFollowUpDate() {
      return followUpDate;
    }

    public JobProperties formKey(final String formId) {
      formKey = formId;
      return this;
    }

    public String getFormKey() {
      return formKey;
    }
  }

  private static final class HeaderEncoder {

    private static final int INITIAL_SIZE_KEY_VALUE_PAIR = 128;

    private final MsgPackWriter msgPackWriter = new MsgPackWriter();

    public DirectBuffer encode(final Map<String, String> taskHeaders) {
      if (taskHeaders == null || taskHeaders.isEmpty()) {
        return JobRecord.NO_HEADERS;
      }

      final MutableDirectBuffer buffer = new UnsafeBuffer(0, 0);

      final var validHeaders =
          taskHeaders.entrySet().stream()
              .filter(entry -> isValidHeader(entry.getKey(), entry.getValue()))
              .collect(Collectors.toMap(Entry::getKey, Entry::getValue));

      if (validHeaders.size() != taskHeaders.size()) {
        LOGGER.debug("Ignored {} invalid headers.", taskHeaders.size() - validHeaders.size());
      }

      final ExpandableArrayBuffer expandableBuffer =
          new ExpandableArrayBuffer(INITIAL_SIZE_KEY_VALUE_PAIR * validHeaders.size());

      msgPackWriter.wrap(expandableBuffer, 0);
      msgPackWriter.writeMapHeader(validHeaders.size());

      validHeaders.forEach(
          (k, v) -> {
            final DirectBuffer key = wrapString(k);
            msgPackWriter.writeString(key);

            final DirectBuffer value = wrapString(v);
            msgPackWriter.writeString(value);
          });

      buffer.wrap(expandableBuffer.byteArray(), 0, msgPackWriter.getOffset());

      return buffer;
    }

    private boolean isValidHeader(final String key, final String value) {
      return key != null && !key.isEmpty() && value != null && !value.isEmpty();
    }
  }
}
