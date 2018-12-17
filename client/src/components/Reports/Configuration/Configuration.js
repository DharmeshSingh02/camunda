import React from 'react';

import {Popover, Icon, Button} from 'components';
import * as visualizations from './visualizations';

import './Configuration.scss';

export default class Configuration extends React.Component {
  resetToDefaults = () => {
    const Component = visualizations[this.props.type] || {};

    const defaults = this.getDefaults(Component);

    this.props.onChange({
      configuration: {
        ...this.props.configuration,
        ...defaults
      }
    });
  };

  updateConfiguration = (prop, value) => {
    this.props.onChange({
      configuration: {
        ...this.props.configuration,
        [prop]: value
      }
    });
  };

  getDefaults = Component => {
    return typeof Component.defaults === 'function'
      ? Component.defaults(this.props)
      : Component.defaults || {};
  };

  componentDidUpdate(prevProps) {
    const Component = visualizations[this.props.type];

    if (Component && Component.onUpdate) {
      const updates = Component.onUpdate(prevProps, this.props);

      if (updates) {
        this.props.onChange({
          configuration: {
            ...this.props.configuration,
            ...updates
          }
        });
      }
    }
  }

  render() {
    const {report, type, configuration} = this.props;
    const Component = visualizations[type];

    const defaults = Component ? this.getDefaults(Component) : {};

    const disabledComponent = Component && Component.isDisabled && Component.isDisabled(report);

    return (
      <li className="Configuration">
        <Popover title={<Icon type="settings" />} disabled={!type || disabledComponent}>
          <div className="content">
            {Component && (
              <Component
                configuration={{...defaults, ...configuration}}
                report={report}
                onChange={this.updateConfiguration}
              />
            )}
            <Button className="resetButton" onClick={this.resetToDefaults}>
              Reset to Defaults
            </Button>
          </div>
        </Popover>
      </li>
    );
  }
}
