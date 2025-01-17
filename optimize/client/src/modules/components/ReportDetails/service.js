/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */

import {post} from 'request';

export async function loadTenants(definitions, type) {
  const params = {definitions};
  const response = await post(`api/definition/${type}/_resolveTenantsForVersions`, params);

  return await response.json();
}
