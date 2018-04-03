import React from 'react';
import PropTypes from 'prop-types';
import lodash from 'lodash';
import { Button } from 'react-bootstrap';

import { SelectPopover } from 'components/common';
import { BootstrapModalConfirm } from 'components/bootstrap';
import { naturalSortIgnoreCase } from 'util/SortUtils';
import CollectorIndicator from '../sidecars/CollectorIndicator';

class CollectorConfigurationSelector extends React.Component {
  static propTypes = {
    collectors: PropTypes.array.isRequired,
    configurations: PropTypes.array.isRequired,
    selectedCollectors: PropTypes.array.isRequired,
    onConfigurationSelectionChange: PropTypes.func.isRequired,
  };

  constructor(props) {
    super(props);
    this.state = {
      selectedConfiguration: undefined,
    };
  }

  handleConfigurationSelect = (configurationIds, hideCallback) => {
    hideCallback();
    let configuration;
    if (configurationIds.length > 0) {
      configuration = this.props.configurations.find(c => configurationIds[0] === c.id);
    }
    this.setState({ selectedConfiguration: configuration }, this.modal.open);
  };

  confirmConfigurationChange = (doneCallback) => {
    this.props.onConfigurationSelectionChange(this.state.selectedConfiguration, doneCallback);
  };

  cancelConfigurationChange = () => {
    this.setState({ selectedConfiguration: undefined });
  };

  renderConfigurationSummary = (selectedConfiguration, selectedCollectors) => {
    const formattedSummary = selectedCollectors.map(({ id, collector }) => {
      return (
        <dd key={id}>{collector.sidecar.node_name}, {collector.collector.name}</dd>
      );
    });

    return (
      <BootstrapModalConfirm ref={(c) => { this.modal = c; }}
                             title="Configuration summary"
                             onConfirm={this.confirmConfigurationChange}
                             onCancel={this.cancelConfigurationChange}>
        <div>
          <p>
            {selectedConfiguration ?
              <span>You are going to <b>apply</b> the <em>{selectedConfiguration.name}</em> configuration to:</span> :
              <span>You are going to <b>remove</b> the configuration for:</span>
            }
          </p>

          <dl>
            {formattedSummary}
          </dl>

          <p>Are you sure you want to proceed with this action?</p>
        </div>
      </BootstrapModalConfirm>
    );
  };

  render() {
    const { selectedConfiguration } = this.state;
    const { collectors, configurations, selectedCollectors } = this.props;

    const configurationIds = configurations
      .sort((c1, c2) => naturalSortIgnoreCase(c1.name, c2.name))
      .map(c => c.id);

    const selectedAssignments = selectedCollectors
      .map(({ collector }) => {
        const assignments = collector.sidecar.assignments;
        return assignments.find(assignment => assignment.backend_id === collector.collector.id);
      })
      .filter(assignment => assignment !== undefined);

    const selectedConfigurationIds = lodash.uniq(selectedAssignments.map(assignment => assignment.configuration_id));

    const configurationFormatter = (configurationId) => {
      const configuration = configurations.find(c => c.id === configurationId);
      const collector = collectors.find(b => b.id === configuration.backend_id);
      return (
        <span>
          {configuration.name}&emsp;
          <small>
            {collector ?
              <CollectorIndicator collector={collector.name} operatingSystem={collector.node_operating_system} /> :
              <em>Unknown collector</em>
            }
          </small>
        </span>
      );
    };

    return (
      <span>
        <SelectPopover id="status-filter"
                       title="Apply configuration"
                       triggerNode={<Button bsSize="small" bsStyle="link">Configure <span className="caret" /></Button>}
                       items={configurationIds}
                       itemFormatter={configurationFormatter}
                       onItemSelect={this.handleConfigurationSelect}
                       selectedItems={selectedConfigurationIds}
                       filterPlaceholder="Filter by configuration" />
        {this.renderConfigurationSummary(selectedConfiguration, selectedCollectors)}
      </span>
    );
  }
}

export default CollectorConfigurationSelector;
