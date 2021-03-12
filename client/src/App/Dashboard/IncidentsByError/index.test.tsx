/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */

import React from 'react';
import {Router} from 'react-router-dom';
import {
  render,
  fireEvent,
  within,
  screen,
  waitForElementToBeRemoved,
} from '@testing-library/react';
import {createMemoryHistory} from 'history';
import {IncidentsByError} from './index';
import {
  mockIncidentsByError,
  mockErrorResponse,
  mockEmptyResponse,
} from './index.setup';
import {rest} from 'msw';
import {mockServer} from 'modules/mock-server/node';
import {ThemeProvider} from 'modules/theme/ThemeProvider';

const createWrapper = (historyMock = createMemoryHistory()) => ({
  children,
}: any) => (
  <ThemeProvider>
    <Router history={historyMock}>{children}</Router>
  </ThemeProvider>
);

describe('IncidentsByError', () => {
  it('should display skeleton when loading', async () => {
    mockServer.use(
      rest.get('/api/incidents/byError', (_, res, ctx) =>
        res.once(ctx.json(mockIncidentsByError))
      )
    );

    render(<IncidentsByError />, {
      wrapper: createWrapper(),
    });

    expect(screen.getByTestId('skeleton')).toBeInTheDocument();

    await waitForElementToBeRemoved(screen.getByTestId('skeleton'));
  });

  it('should handle server errors', async () => {
    mockServer.use(
      rest.get('/api/incidents/byError', (_, res, ctx) =>
        res.once(ctx.status(500), ctx.json(mockErrorResponse))
      )
    );

    render(<IncidentsByError />, {
      wrapper: createWrapper(),
    });

    expect(
      await screen.findByText('Incidents by Error Message could not be fetched')
    ).toBeInTheDocument();
  });

  it('should handle network errors', async () => {
    mockServer.use(
      rest.get('/api/incidents/byError', (_, res) =>
        res.networkError('A network error')
      )
    );

    render(<IncidentsByError />, {
      wrapper: createWrapper(),
    });

    expect(
      await screen.findByText('Incidents by Error Message could not be fetched')
    ).toBeInTheDocument();
  });

  it('should display information message when there are no workflows', async () => {
    mockServer.use(
      rest.get('/api/incidents/byError', (_, res, ctx) =>
        res.once(ctx.json(mockEmptyResponse))
      )
    );

    render(<IncidentsByError />, {
      wrapper: createWrapper(),
    });

    expect(
      await screen.findByText('There are no Instances with Incidents')
    ).toBeInTheDocument();
  });

  it('should render incidents by error message', async () => {
    mockServer.use(
      rest.get('/api/incidents/byError', (_, res, ctx) =>
        res.once(ctx.json(mockIncidentsByError))
      )
    );

    const historyMock = createMemoryHistory();
    render(<IncidentsByError />, {
      wrapper: createWrapper(historyMock),
    });

    const withinIncident = within(
      await screen.findByTestId('incident-byError-0')
    );

    const expandButton = withinIncident.getByTitle(
      "Expand 36 Instances with error JSON path '$.paid' has no result."
    );
    expect(expandButton).toBeInTheDocument();

    fireEvent.click(
      withinIncident.getByTitle(
        "View 36 Instances with error JSON path '$.paid' has no result."
      )
    );
    expect(historyMock.location.search).toBe(
      '?filter=%7B%22errorMessage%22%3A%22JSON+path+%27%24.paid%27+has+no+result.%22%2C%22incidents%22%3Atrue%7D'
    );

    fireEvent.click(expandButton);

    const firstVersion = withinIncident.getByTitle(
      "View 37 Instances with error JSON path '$.paid' has no result. in version 1 of Workflow mockWorkflow"
    );
    expect(
      within(firstVersion).getByTestId('incident-instances-badge')
    ).toHaveTextContent('37');
    expect(
      within(firstVersion).getByText('mockWorkflow – Version 1')
    ).toBeInTheDocument();

    fireEvent.click(firstVersion);
    expect(historyMock.location.search).toBe(
      '?filter=%7B%22workflow%22%3A%22mockWorkflow%22%2C%22version%22%3A%221%22%2C%22errorMessage%22%3A%22JSON+path+%27%24.paid%27+has+no+result.%22%2C%22incidents%22%3Atrue%7D'
    );
  });

  it('should navigate to correct urls when gseUrl is provided', async () => {
    mockServer.use(
      rest.get('/api/incidents/byError', (_, res, ctx) =>
        res.once(ctx.json(mockIncidentsByError))
      )
    );

    const historyMock = createMemoryHistory({
      initialEntries: ['/?gseUrl=https://www.testUrl.com'],
    });

    render(<IncidentsByError />, {
      wrapper: createWrapper(historyMock),
    });

    const withinIncident = within(
      await screen.findByTestId('incident-byError-0')
    );

    const expandButton = withinIncident.getByTitle(
      "Expand 36 Instances with error JSON path '$.paid' has no result."
    );

    fireEvent.click(
      withinIncident.getByTitle(
        "View 36 Instances with error JSON path '$.paid' has no result."
      )
    );
    expect(historyMock.location.search).toBe(
      `?filter=%7B%22errorMessage%22%3A%22JSON+path+%27%24.paid%27+has+no+result.%22%2C%22incidents%22%3Atrue%7D&gseUrl=https%3A%2F%2Fwww.testUrl.com`
    );

    fireEvent.click(expandButton);

    const firstVersion = withinIncident.getByTitle(
      "View 37 Instances with error JSON path '$.paid' has no result. in version 1 of Workflow mockWorkflow"
    );

    fireEvent.click(firstVersion);
    expect(historyMock.location.search).toBe(
      '?filter=%7B%22workflow%22%3A%22mockWorkflow%22%2C%22version%22%3A%221%22%2C%22errorMessage%22%3A%22JSON+path+%27%24.paid%27+has+no+result.%22%2C%22incidents%22%3Atrue%7D&gseUrl=https%3A%2F%2Fwww.testUrl.com'
    );
  });
});
