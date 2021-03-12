/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */

import {IS_NEXT_FLOW_NODE_INSTANCES} from 'modules/feature-flags';

import {flowNodeInstanceStore as flowNodeInstanceStoreLegacy} from './flowNodeInstanceLegacy';
import {flowNodeInstanceStore} from './flowNodeInstance';

const currentFlowNodeInstanceStore = IS_NEXT_FLOW_NODE_INSTANCES
  ? flowNodeInstanceStore
  : flowNodeInstanceStoreLegacy;

export type {FlowNodeInstance} from './flowNodeInstance';
export {currentFlowNodeInstanceStore as flowNodeInstanceStore};
