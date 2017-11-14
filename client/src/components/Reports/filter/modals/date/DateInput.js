import React from 'react';
import moment from 'moment';

import './DateInput.css';

export default class DateInput extends React.PureComponent {
  constructor(props) {
    super(props);

    this.state = {
      stringDate: this.props.date.format(this.props.format),
      error: false
    };
  }

  componentWillReceiveProps({date, format}) {
    const newStringDate = date.format(format);

    // prevents unnecessary state change
    if (this.state.stringDate !== newStringDate) {
      this.setState({
        stringDate: newStringDate,
        error: false
      });
    }
  }

  render() {
    return <input type="text"
                  ref={this.props.reference}
                  className={'DateInput ' + this.props.className + (this.state.error ? ' DateInput--error' : '')}
                  value={this.state.stringDate}
                  onClick={this.onClick}
                  onChange={this.onInputChange} />;
  }

  onClick = event => {
    // onClick property is optional, so there need to be safeguard
    if (typeof this.props.onClick === 'function') {
      this.props.onClick(event);
    }
  }

  onInputChange = event => {
    const value = event.target.value;
    const date = moment(value, this.props.format);
    const isValid = date.isValid() && date.format(this.props.format) === value;

    this.setState({
      stringDate: value,
      error: !isValid
    });

    if (!date.isSame(this.props.date) && isValid) {
      this.props.onDateChange(date);
    }
  }
}
