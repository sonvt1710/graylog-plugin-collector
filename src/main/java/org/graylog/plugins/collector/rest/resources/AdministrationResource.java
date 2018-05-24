package org.graylog.plugins.collector.rest.resources;

import com.codahale.metrics.annotation.Timed;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.graylog.plugins.collector.filter.AdministrationFiltersFactory;
import org.graylog.plugins.collector.rest.models.Collector;
import org.graylog.plugins.collector.rest.models.Sidecar;
import org.graylog.plugins.collector.rest.requests.AdministrationRequest;
import org.graylog.plugins.collector.rest.responses.SidecarListResponse;
import org.graylog.plugins.collector.services.SidecarService;
import org.graylog.plugins.collector.services.ConfigurationService;
import org.graylog.plugins.collector.services.CollectorService;
import org.graylog.plugins.collector.filter.ActiveSidecarFilter;
import org.graylog.plugins.collector.filter.AdministrationFilter;
import org.graylog.plugins.collector.rest.models.Configuration;
import org.graylog.plugins.collector.rest.models.SidecarSummary;
import org.graylog.plugins.collector.permissions.SidecarRestPermissions;
import org.graylog.plugins.collector.system.SidecarSystemConfiguration;
import org.graylog2.audit.jersey.NoAuditEvent;
import org.graylog2.database.PaginatedList;
import org.graylog2.plugin.rest.PluginRestResource;
import org.graylog2.search.SearchQuery;
import org.graylog2.search.SearchQueryParser;
import org.graylog2.shared.rest.resources.RestResource;

import javax.inject.Inject;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Api(value = "Sidecar Administration", description = "Administrate collectors")
@Path("/sidecar/administration")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class AdministrationResource extends RestResource implements PluginRestResource {
    private final SidecarService sidecarService;
    private final ConfigurationService configurationService;
    private final CollectorService collectorService;
    private final SearchQueryParser searchQueryParser;
    private final AdministrationFiltersFactory administrationFiltersFactory;
    private final ActiveSidecarFilter activeSidecarFilter;

    @Inject
    public AdministrationResource(SidecarService sidecarService,
                                  ConfigurationService configurationService,
                                  CollectorService collectorService,
                                  AdministrationFiltersFactory administrationFiltersFactory,
                                  Supplier<SidecarSystemConfiguration> configSupplier) {
        this.sidecarService = sidecarService;
        this.configurationService = configurationService;
        this.collectorService = collectorService;
        this.administrationFiltersFactory = administrationFiltersFactory;
        this.activeSidecarFilter = new ActiveSidecarFilter(configSupplier.get().sidecarInactiveThreshold());
        this.searchQueryParser = new SearchQueryParser(Sidecar.FIELD_NODE_NAME, SidecarResource.SEARCH_FIELD_MAPPING);
    }

    @POST
    @Timed
    @ApiOperation(value = "Lists existing Sidecar registrations including compatible collectors using pagination")
    @RequiresAuthentication
    @RequiresPermissions(SidecarRestPermissions.SIDECARS_READ)
    @NoAuditEvent("this is not changing any data")
    public SidecarListResponse administration(@ApiParam(name = "JSON body", required = true)
                                                @Valid @NotNull AdministrationRequest request) {
        final String sort = Sidecar.FIELD_NODE_NAME;
        final String order = "asc";
        final SearchQuery searchQuery = searchQueryParser.parse(request.query());

        final Optional<Predicate<Sidecar>> filters = administrationFiltersFactory.getFilters(request.filters());

        final List<Collector> collectors = getCollectors(request.filters());
        final PaginatedList<Sidecar> sidecars = sidecarService.findPaginated(searchQuery, filters.orElse(null), request.page(), request.perPage(), sort, order);
        final List<SidecarSummary> sidecarSummaries = sidecarService.toSummaryList(sidecars, activeSidecarFilter);

        final List<SidecarSummary> summariesWithCollectors = sidecarSummaries.stream()
                .map(collector -> {
                    final List<String> compatibleCollectors = collectors.stream()
                            .filter(c -> c.nodeOperatingSystem().equalsIgnoreCase(collector.nodeDetails().operatingSystem()))
                            .map(Collector::id)
                            .collect(Collectors.toList());
                    return collector.toBuilder()
                            .backends(compatibleCollectors)
                            .build();
                })
                .filter(collectorSummary -> !filters.isPresent() || collectorSummary.backends().size() > 0)
                .collect(Collectors.toList());

        return SidecarListResponse.create(request.query(), sidecars.pagination(), false, sort, order, summariesWithCollectors, request.filters());
    }

    private List<Collector> getCollectors(Map<String, String> filters) {
        final String collectorKey = AdministrationFilter.Type.COLLECTOR.toString().toLowerCase();
        final String configurationKey = AdministrationFilter.Type.CONFIGURATION.toString().toLowerCase();

        final List<String> collectorIds = new ArrayList<>();

        if (filters.containsKey(collectorKey)) {
            collectorIds.add(filters.get(collectorKey));
        }
        if (filters.containsKey(configurationKey)) {
            final Configuration configuration = configurationService.find(filters.get(configurationKey));
            if (!collectorIds.contains(configuration.backendId())) {
                collectorIds.add(configuration.backendId());
            }
        }

        switch (collectorIds.size()) {
            case 0:
                return collectorService.all();
            case 1:
                return ImmutableList.of(collectorService.find(collectorIds.get(0)));
            default:
                return new ArrayList<>();
        }
    }
}