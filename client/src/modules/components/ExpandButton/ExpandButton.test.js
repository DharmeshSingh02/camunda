/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */

import React from 'react';
import {shallow} from 'enzyme';
import ExpandButton from './ExpandButton';
import * as Styled from './styled';

describe('ExpandButton', () => {
  it('should render correct colors based on theme', () => {
    // should/can we test?
  });

  it('should render arrow icon', () => {
    // given
    const node = shallow(<ExpandButton isExpanded={false} expandTheme="" />);

    // when
    const RightIconNode = node.find(Styled.RightIcon);

    // then
    expect(RightIconNode).toExist();
  });

  it('should render arrow down when isExpanded is true', () => {});

  it('should render provided children inside the button', () => {
    // given
    const node = shallow(
      <ExpandButton expandTheme="">
        <div id="child1">child node 1</div>
        <div id="child2">child node 2</div>
      </ExpandButton>
    );

    // when
    const RightIconNode = node.find(Styled.RightIcon);
    const ChildNode1 = node.find('#child1');
    const ChildNode2 = node.find('#child2');

    // then
    expect(RightIconNode).toExist();
    expect(ChildNode1).toExist();
    expect(ChildNode2).toExist();
    expect(node.children()).toHaveLength(3);
  });
});
