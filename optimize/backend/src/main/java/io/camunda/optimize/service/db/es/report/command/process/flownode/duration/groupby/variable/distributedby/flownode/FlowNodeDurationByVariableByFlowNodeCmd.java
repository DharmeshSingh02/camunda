/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.optimize.service.db.es.report.command.process.flownode.duration.groupby.variable.distributedby.flownode;

import io.camunda.optimize.dto.optimize.query.report.single.result.hyper.HyperMapResultEntryDto;
import io.camunda.optimize.service.db.es.report.command.ProcessCmd;
import io.camunda.optimize.service.db.es.report.command.exec.ProcessReportCmdExecutionPlan;
import io.camunda.optimize.service.db.es.report.command.exec.builder.ReportCmdExecutionPlanBuilder;
import io.camunda.optimize.service.db.es.report.command.modules.distributed_by.process.ProcessDistributedByFlowNode;
import io.camunda.optimize.service.db.es.report.command.modules.group_by.process.ProcessGroupByVariable;
import io.camunda.optimize.service.db.es.report.command.modules.view.process.duration.ProcessViewFlowNodeDuration;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FlowNodeDurationByVariableByFlowNodeCmd
    extends ProcessCmd<List<HyperMapResultEntryDto>> {

  public FlowNodeDurationByVariableByFlowNodeCmd(final ReportCmdExecutionPlanBuilder builder) {
    super(builder);
  }

  @Override
  protected ProcessReportCmdExecutionPlan<List<HyperMapResultEntryDto>> buildExecutionPlan(
      final ReportCmdExecutionPlanBuilder builder) {
    return builder
        .createExecutionPlan()
        .processCommand()
        .view(ProcessViewFlowNodeDuration.class)
        .groupBy(ProcessGroupByVariable.class)
        .distributedBy(ProcessDistributedByFlowNode.class)
        .resultAsHyperMap()
        .build();
  }
}