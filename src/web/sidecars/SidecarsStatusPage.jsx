import PropTypes from 'prop-types';
import React from 'react';

import { Button, ButtonToolbar } from 'react-bootstrap';
import { LinkContainer } from 'react-router-bootstrap';

import { DocumentTitle, PageHeader, Spinner } from 'components/common';
import DocsHelper from 'util/DocsHelper';
import DocumentationLink from 'components/support/DocumentationLink';

import SidecarsActions from 'sidecars/SidecarsActions';

import Routes from 'routing/Routes';
import SidecarStatus from './SidecarStatus';

const SidecarsStatusPage = React.createClass({
  propTypes: {
    params: PropTypes.object.isRequired,
  },

  getInitialState() {
    return {
      sidecar: undefined,
    };
  },

  componentDidMount() {
    this.reloadSidecar();
    this.interval = setInterval(this.reloadSidecar, 5000);
  },

  componentWillUnmount() {
    if (this.interval) {
      clearInterval(this.interval);
    }
  },

  reloadSidecar() {
    SidecarsActions.getSidecar(this.props.params.id).then(sidecar => this.setState({ sidecar }));
  },

  render() {
    const sidecar = this.state.sidecar;
    const isLoading = !sidecar;

    if (isLoading) {
      return <DocumentTitle title="Sidecar status"><Spinner /></DocumentTitle>;
    }

    return (
      <DocumentTitle title={`Sidecar status ${sidecar.node_name}`}>
        <span>
          <PageHeader title={<span>Sidecar Status <em>{sidecar.node_name}</em></span>}>
            <span>
              A status overview of the Graylog Sidecar.
            </span>

            <span>
              Read more about sidecars and how to set them up in the
              {' '}<DocumentationLink page={DocsHelper.PAGES.COLLECTOR_STATUS} text="Graylog documentation"/>.
            </span>

            <ButtonToolbar>
              <LinkContainer to={Routes.pluginRoute('SYSTEM_SIDECARS')}>
                <Button bsStyle="info" className="active">Overview</Button>
              </LinkContainer>
              <LinkContainer to={Routes.pluginRoute('SYSTEM_SIDECARS_ADMINISTRATION')}>
                <Button bsStyle="info">Administration</Button>
              </LinkContainer>
              <LinkContainer to={Routes.pluginRoute('SYSTEM_SIDECARS_CONFIGURATION')}>
                <Button bsStyle="info">Configuration</Button>
              </LinkContainer>
            </ButtonToolbar>
          </PageHeader>

          <SidecarStatus sidecar={sidecar} />
        </span>
      </DocumentTitle>
    );
  },
});

export default SidecarsStatusPage;
