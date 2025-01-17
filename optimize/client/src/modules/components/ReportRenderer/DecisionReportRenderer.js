/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */

import React from 'react';

import {processResult} from 'services';

import {getFormatter} from './service';
import {Table, Number, Chart, DecisionTable} from './visualizations';

const getComponent = (groupBy, visualization) => {
  switch (visualization) {
    case 'number':
      return Number;
    case 'table':
      return groupBy === 'matchedRule' ? DecisionTable : Table;
    case 'bar':
    case 'line':
    case 'pie':
      return Chart;
    default:
      return;
  }
};

export default function DecisionReportRenderer(props) {
  const {
    visualization,
    view,
    groupBy: {type},
  } = props.report.data;
  const Component = getComponent(type, visualization);

  return (
    <div className="component">
      <Component
        {...props}
        formatter={getFormatter(view.properties[0])}
        report={{...props.report, result: processResult(props.report)}}
      />
    </div>
  );
}
