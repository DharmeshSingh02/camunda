import React from 'react';
import {shallow} from 'enzyme';

import DecisionReportView from './DecisionReportView';
import {Table} from './views';

jest.mock('./service', () => {
  return {
    isEmpty: str => !str,
    getConfig: props => props
  };
});

const report = {
  combined: false,
  reportType: 'decision',
  data: {
    decisionDefinitionKey: 'aKey',
    decisionDefinitionVersion: '1',
    view: {
      operation: 'rawData'
    },
    groupBy: {
      type: 'none'
    },
    visualization: 'table',
    configuration: {}
  },
  result: 1234
};

it('should provide an errorMessage property to the component', () => {
  const node = shallow(<DecisionReportView report={report} errorMessage={'test'} />);
  expect(node.find(Table)).toHaveProp('errorMessage');
});

it('should pass the report Type to the visualization component', () => {
  const node = shallow(<DecisionReportView report={report} />);

  expect(node.find(Table)).toHaveProp('report', report);
});
