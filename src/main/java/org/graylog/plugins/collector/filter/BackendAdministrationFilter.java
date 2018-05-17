package org.graylog.plugins.collector.filter;

import com.google.inject.assistedinject.Assisted;
import org.graylog.plugins.collector.services.BackendService;
import org.graylog.plugins.collector.rest.models.Collector;
import org.graylog.plugins.collector.rest.models.CollectorBackend;

import javax.inject.Inject;

public class BackendAdministrationFilter implements AdministrationFilter {
    private final CollectorBackend backend;

    @Inject
    public BackendAdministrationFilter(BackendService backendService,
                                       @Assisted String backendId) {
        this.backend = backendService.find(backendId);
    }

    @Override
    public boolean test(Collector collector) {
        return backend.nodeOperatingSystem().equalsIgnoreCase(collector.nodeDetails().operatingSystem());
    }
}