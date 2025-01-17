/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */

import React from 'react';
import {shallow} from 'enzyme';

import DecisionReportRenderer from './DecisionReportRenderer';
import {Table} from './visualizations';

jest.mock('./service', () => {
  return {
    isEmpty: (str) => !str,
    getFormatter: (view) => (v) => v,
    processResult: ({result}) => result,
  };
});

const report = {
  combined: false,
  reportType: 'decision',
  data: {
    decisionDefinitionKey: 'aKey',
    decisionDefinitionVersion: '1',
    view: {
      properties: ['rawData'],
    },
    groupBy: {
      type: 'none',
    },
    visualization: 'table',
    configuration: {},
  },
  result: {data: 1234},
};

it('should provide an errorMessage property to the component', () => {
  const node = shallow(<DecisionReportRenderer report={report} errorMessage={'test'} />);
  expect(node.find(Table)).toHaveProp('errorMessage');
});

it('should pass the report Type to the visualization component', () => {
  const node = shallow(<DecisionReportRenderer report={report} />);

  expect(node.find(Table)).toHaveProp('report', report);
});
