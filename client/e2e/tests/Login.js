/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */

import {config} from '../config';
import {screen} from '@testing-library/testcafe';
import {ClientFunction} from 'testcafe';
const getPathname = ClientFunction(() => window.location.hash);

fixture('Login')
  .page(config.endpoint)
  .beforeEach(async (t) => {
    await t.maximizeWindow();
  });

test('Log in with invalid user account', async (t) => {
  await t
    .expect(screen.getByLabelText('Password').getAttribute('type'))
    .eql('password');

  await t
    .typeText(screen.getByRole('textbox', 'Username'), 'demo')
    .typeText(screen.getByLabelText('Password'), 'wrong-password')
    .click(screen.getByRole('button', 'Log in'));
  await t
    .expect(screen.getByText('Username and Password do not match').exists)
    .ok();
  await t.expect(await getPathname()).eql('#/login');
});

test('Log in with valid user account', async (t) => {
  await t
    .typeText(screen.getByRole('textbox', 'Username'), 'demo')
    .typeText(screen.getByLabelText('Password'), 'demo')
    .click(screen.getByRole('button', 'Log in'));

  await t.expect(await getPathname()).eql('#/');
});

test('Log out', async (t) => {
  await t
    .typeText(screen.getByRole('textbox', 'Username'), 'demo')
    .typeText(screen.getByLabelText('Password'), 'demo')
    .click(screen.getByRole('button', 'Log in'));

  await t
    .click(screen.getByRole('button', {name: 'Demo user'}))
    .click(screen.getByRole('button', {name: 'Logout'}));

  await t.expect(await getPathname()).eql('#/login');
});

test('Redirect to initial page after login', async (t) => {
  await t.expect(await getPathname()).eql('#/login');
  await t.navigateTo(`${config.endpoint}/#/instances`);
  await t.expect(await getPathname()).eql('#/login');

  await t
    .typeText(screen.getByRole('textbox', 'Username'), 'demo')
    .typeText(screen.getByLabelText('Password'), 'demo')
    .click(screen.getByRole('button', 'Log in'));

  await t
    .expect(await getPathname())
    .eql('#/instances?filter={%22active%22:true,%22incidents%22:true}');
});
