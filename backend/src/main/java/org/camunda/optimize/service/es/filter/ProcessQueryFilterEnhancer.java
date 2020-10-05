/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */
package org.camunda.optimize.service.es.filter;

import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.camunda.optimize.dto.optimize.query.report.single.filter.data.FilterDataDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.AssigneeFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.CanceledFlowNodeFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.CanceledInstancesOnlyFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.CandidateGroupFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.CompletedInstancesOnlyFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.DurationFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.EndDateFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.ExecutedFlowNodeFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.ExecutingFlowNodeFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.FlowNodeDurationFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.NonCanceledInstancesOnlyFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.NonSuspendedInstancesOnlyFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.ProcessFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.RunningInstancesOnlyFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.StartDateFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.SuspendedInstancesOnlyFilterDto;
import org.camunda.optimize.dto.optimize.query.report.single.process.filter.VariableFilterDto;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class ProcessQueryFilterEnhancer implements QueryFilterEnhancer<ProcessFilterDto<?>> {

  private final StartDateQueryFilter startDateQueryFilter;
  private final EndDateQueryFilter endDateQueryFilter;
  private final ProcessVariableQueryFilter variableQueryFilter;
  private final ExecutedFlowNodeQueryFilter executedFlowNodeQueryFilter;
  private final ExecutingFlowNodeQueryFilter executingFlowNodeQueryFilter;
  private final CanceledFlowNodeQueryFilter canceledFlowNodeQueryFilter;
  private final DurationQueryFilter durationQueryFilter;
  private final RunningInstancesOnlyQueryFilter runningInstancesOnlyQueryFilter;
  private final CompletedInstancesOnlyQueryFilter completedInstancesOnlyQueryFilter;
  private final CanceledInstancesOnlyQueryFilter canceledInstancesOnlyQueryFilter;
  private final NonCanceledInstancesOnlyQueryFilter nonCanceledInstancesOnlyQueryFilter;
  private final SuspendedInstancesOnlyQueryFilter suspendedInstancesOnlyQueryFilter;
  private final NonSuspendedInstancesOnlyQueryFilter nonSuspendedInstancesOnlyQueryFilter;
  private final FlowNodeDurationQueryFilter flowNodeDurationQueryFilter;
  private final AssigneeQueryFilter assigneeQueryFilter;
  private final CandidateGroupQueryFilter candidateGroupQueryFilter;

  @Override
  public void addFilterToQuery(BoolQueryBuilder query, List<ProcessFilterDto<?>> filters, final ZoneId timezone) {
    if (!CollectionUtils.isEmpty(filters)) {
      startDateQueryFilter.addFilters(query, extractFilters(filters, StartDateFilterDto.class), timezone);
      endDateQueryFilter.addFilters(query, extractFilters(filters, EndDateFilterDto.class), timezone);
      variableQueryFilter.addFilters(query, extractFilters(filters, VariableFilterDto.class), timezone);
      executedFlowNodeQueryFilter.addFilters(query, extractFilters(filters, ExecutedFlowNodeFilterDto.class), timezone);
      executingFlowNodeQueryFilter.addFilters(
        query,
        extractFilters(filters, ExecutingFlowNodeFilterDto.class),
        timezone
      );
      canceledFlowNodeQueryFilter.addFilters(query, extractFilters(filters, CanceledFlowNodeFilterDto.class), timezone);
      durationQueryFilter.addFilters(query, extractFilters(filters, DurationFilterDto.class), timezone);
      runningInstancesOnlyQueryFilter.addFilters(
        query,
        extractFilters(filters, RunningInstancesOnlyFilterDto.class),
        timezone
      );
      completedInstancesOnlyQueryFilter.addFilters(
        query, extractFilters(filters, CompletedInstancesOnlyFilterDto.class),
        timezone
      );
      canceledInstancesOnlyQueryFilter.addFilters(
        query,
        extractFilters(filters, CanceledInstancesOnlyFilterDto.class),
        timezone
      );
      nonCanceledInstancesOnlyQueryFilter.addFilters(
        query, extractFilters(filters, NonCanceledInstancesOnlyFilterDto.class),
        timezone
      );
      suspendedInstancesOnlyQueryFilter.addFilters(
        query, extractFilters(filters, SuspendedInstancesOnlyFilterDto.class),
        timezone
      );
      nonSuspendedInstancesOnlyQueryFilter.addFilters(
        query, extractFilters(filters, NonSuspendedInstancesOnlyFilterDto.class),
        timezone
      );
      flowNodeDurationQueryFilter.addFilters(query, extractFilters(filters, FlowNodeDurationFilterDto.class), timezone);
      assigneeQueryFilter.addFilters(query, extractFilters(filters, AssigneeFilterDto.class), timezone);
      candidateGroupQueryFilter.addFilters(query, extractFilters(filters, CandidateGroupFilterDto.class), timezone);
    }
  }

  public StartDateQueryFilter getStartDateQueryFilter() {
    return startDateQueryFilter;
  }

  public EndDateQueryFilter getEndDateQueryFilter() {
    return endDateQueryFilter;
  }

  @SuppressWarnings("unchecked")
  public <T extends FilterDataDto> List<T> extractFilters(final List<ProcessFilterDto<?>> filter,
                                                          final Class<? extends ProcessFilterDto<T>> clazz) {
    return filter
      .stream()
      .filter(clazz::isInstance)
      .map(dateFilter -> (T) dateFilter.getData())
      .collect(Collectors.toList());
  }
}
