import {expect} from 'chai';
import {reducer, createUnsetElementAction, createToggleElementAction} from 'main/processDisplay/diagram/analytics/reducer';

describe('Analytics reducer', () => {
  const elementType = 'gateway';
  const elementId = 'gateway8';
  const CHANGE_ROUTE_ACTION = 'CHANGE_ROUTE_ACTION';

  it('should set the element id on toggle if nothing was set previously', () => {
    const {gateway} = reducer(undefined, createToggleElementAction(elementId, elementType));

    expect(gateway).to.eql(elementId);
  });

  it('should unset the element id on toggle if element was set previously', () => {
    const {gateway} = reducer({gateway: elementId}, createToggleElementAction(elementId, elementType));

    expect(gateway).to.be.null;
  });

  it('should replace a selected element', () => {
    const {gateway} = reducer({gateway: 'foobar'}, createToggleElementAction(elementId, elementType));

    expect(gateway).to.eql(elementId);
  });

  it('should unset an element', () => {
    const {gateway} = reducer({gateway: 'foobar'}, createUnsetElementAction(elementType));

    expect(gateway).to.be.null;
  });

  it('should unset selected elements when the view is switched', () => {
    const {gateway, endEvent} = reducer(
      {
        gateway: 'g1',
        endEvent: 'e2'
      },
      {
        type: CHANGE_ROUTE_ACTION,
        route: {
          params: {
            view: 'something-else'
          }
        }
      });

    expect(gateway).to.be.undefined;
    expect(endEvent).to.be.undefined;
  });
});
