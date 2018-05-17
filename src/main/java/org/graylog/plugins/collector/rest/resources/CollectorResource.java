package org.graylog.plugins.collector.rest.resources;

import com.codahale.metrics.annotation.Timed;
import com.google.common.base.Function;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableMap;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.graylog.plugins.collector.services.ActionService;
import org.graylog.plugins.collector.services.CollectorService;
import org.graylog.plugins.collector.mapper.CollectorStatusMapper;
import org.graylog.plugins.collector.filter.ActiveCollectorFilter;
import org.graylog.plugins.collector.rest.models.Collector;
import org.graylog.plugins.collector.rest.models.CollectorAction;
import org.graylog.plugins.collector.rest.models.CollectorActions;
import org.graylog.plugins.collector.rest.requests.CollectorRegistrationRequest;
import org.graylog.plugins.collector.rest.requests.ConfigurationAssignment;
import org.graylog.plugins.collector.rest.models.NodeConfiguration;
import org.graylog.plugins.collector.rest.requests.NodeConfigurationRequest;
import org.graylog.plugins.collector.rest.responses.CollectorListResponse;
import org.graylog.plugins.collector.rest.models.CollectorRegistrationConfiguration;
import org.graylog.plugins.collector.rest.responses.CollectorRegistrationResponse;
import org.graylog.plugins.collector.rest.models.CollectorSummary;
import org.graylog.plugins.collector.permissions.CollectorRestPermissions;
import org.graylog.plugins.collector.system.CollectorSystemConfiguration;
import org.graylog2.audit.jersey.NoAuditEvent;
import org.graylog2.database.PaginatedList;
import org.graylog2.plugin.rest.PluginRestResource;
import org.graylog2.search.SearchQuery;
import org.graylog2.search.SearchQueryField;
import org.graylog2.search.SearchQueryParser;
import org.graylog2.shared.rest.resources.RestResource;
import org.hibernate.validator.constraints.NotEmpty;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Api(value = "AltCollector", description = "Manage collector fleet")
@Path("/sidecar/collectors")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class CollectorResource extends RestResource implements PluginRestResource {
    private static final Logger LOG = LoggerFactory.getLogger(CollectorResource.class);
    protected static final ImmutableMap<String, SearchQueryField> SEARCH_FIELD_MAPPING = ImmutableMap.<String, SearchQueryField>builder()
            .put("id", SearchQueryField.create(Collector.FIELD_ID))
            .put("node_id", SearchQueryField.create(Collector.FIELD_NODE_ID))
            .put("name", SearchQueryField.create(Collector.FIELD_NODE_NAME))
            .put("collector_version", SearchQueryField.create(Collector.FIELD_COLLECTOR_VERSION))
            .put("last_seen", SearchQueryField.create(Collector.FIELD_LAST_SEEN, SearchQueryField.Type.DATE))
            .put("operating_system", SearchQueryField.create(Collector.FIELD_OPERATING_SYSTEM))
            .put("status", SearchQueryField.create(Collector.FIELD_STATUS, SearchQueryField.Type.INT))
            .build();

    private final CollectorService collectorService;
    private final ActionService actionService;
    private final ActiveCollectorFilter activeCollectorFilter;
    private final SearchQueryParser searchQueryParser;
    private final Supplier<CollectorSystemConfiguration> configSupplier;
    private final CollectorStatusMapper collectorStatusMapper;

    @Inject
    public CollectorResource(CollectorService collectorService,
                             ActionService actionService,
                             Supplier<CollectorSystemConfiguration> configSupplier,
                             CollectorStatusMapper collectorStatusMapper) {
        this.collectorService = collectorService;
        this.actionService = actionService;
        this.activeCollectorFilter = new ActiveCollectorFilter(configSupplier.get().collectorInactiveThreshold());
        this.configSupplier = configSupplier;
        this.collectorStatusMapper = collectorStatusMapper;
        this.searchQueryParser = new SearchQueryParser(Collector.FIELD_NODE_NAME, SEARCH_FIELD_MAPPING);
    }

    @GET
    @Timed
    @Path("/all")
    @ApiOperation(value = "Lists all existing collector registrations")
    @RequiresAuthentication
    @RequiresPermissions(CollectorRestPermissions.COLLECTORS_READ)
    public CollectorListResponse all() {
        final List<Collector> collectors = collectorService.all();
        final List<CollectorSummary> collectorSummaries = collectorService.toSummaryList(collectors, activeCollectorFilter);
        return CollectorListResponse.create("",
                PaginatedList.PaginationInfo.create(collectorSummaries.size(),
                        collectorSummaries.size(),
                        1,
                        collectorSummaries.size()),
                false,
                null,
                null,
                collectorSummaries);
    }

    @GET
    @Timed
    @ApiOperation(value = "Lists existing collector registrations using pagination")
    @RequiresAuthentication
    @RequiresPermissions(CollectorRestPermissions.COLLECTORS_READ)
    public CollectorListResponse collectors(@ApiParam(name = "page") @QueryParam("page") @DefaultValue("1") int page,
                                            @ApiParam(name = "per_page") @QueryParam("per_page") @DefaultValue("50") int perPage,
                                            @ApiParam(name = "query") @QueryParam("query") @DefaultValue("") String query,
                                            @ApiParam(name = "sort",
                                                    value = "The field to sort the result on",
                                                    required = true,
                                                    allowableValues = "title,description,name,id")
                                            @DefaultValue(Collector.FIELD_NODE_NAME) @QueryParam("sort") String sort,
                                            @ApiParam(name = "order", value = "The sort direction", allowableValues = "asc, desc")
                                            @DefaultValue("asc") @QueryParam("order") String order,
                                            @ApiParam(name = "only_active") @QueryParam("only_active") @DefaultValue("false") boolean onlyActive) {
        final String mappedQuery = collectorStatusMapper.replaceStringStatusSearchQuery(query);
        final SearchQuery searchQuery = searchQueryParser.parse(mappedQuery);
        final PaginatedList<Collector> collectors = onlyActive ?
                collectorService.findPaginated(searchQuery, activeCollectorFilter, page, perPage, sort, order) :
                collectorService.findPaginated(searchQuery, page, perPage, sort, order);
        final List<CollectorSummary> collectorSummaries = collectorService.toSummaryList(collectors, activeCollectorFilter);
        return CollectorListResponse.create(query, collectors.pagination(), onlyActive, sort, order, collectorSummaries);
    }

    @GET
    @Timed
    @Path("/{collectorId}")
    @ApiOperation(value = "Returns at most one collector summary for the specified collector id")
    @ApiResponses(value = {
            @ApiResponse(code = 404, message = "No collector with the specified id exists")
    })
    @RequiresAuthentication
    @RequiresPermissions(CollectorRestPermissions.COLLECTORS_READ)
    public CollectorSummary get(@ApiParam(name = "collectorId", required = true)
                                @PathParam("collectorId") @NotEmpty String collectorId) {
        final Collector collector = collectorService.findByNodeId(collectorId);
        if (collector != null) {
            return collector.toSummary(activeCollectorFilter);
        } else {
            throw new NotFoundException("Collector <" + collectorId + "> not found!");
        }
    }

    @PUT
    @Timed
    @Path("/{collectorId}")
    @ApiOperation(value = "Create/update a collector registration",
            notes = "This is a stateless method which upserts a collector registration")
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "The supplied request is not valid.")
    })
    @RequiresAuthentication
    @RequiresPermissions(CollectorRestPermissions.COLLECTORS_UPDATE)
    @NoAuditEvent("this is only a ping from collectors, and would overflow the audit log")
    public Response register(@ApiParam(name = "collectorId", value = "The collector id this collector is registering as.", required = true)
                             @PathParam("collectorId") @NotEmpty String collectorId,
                             @ApiParam(name = "JSON body", required = true)
                             @Valid @NotNull CollectorRegistrationRequest request,
                             @HeaderParam(value = "X-Graylog-Collector-Version") @NotEmpty String collectorVersion) {
        final Collector newCollector;
        final Collector oldCollector = collectorService.findByNodeId(collectorId);
        List<ConfigurationAssignment> assignments = null;
        if (oldCollector != null) {
            assignments = oldCollector.assignments();
            newCollector = oldCollector.toBuilder()
                    .nodeName(request.nodeName())
                    .nodeDetails(request.nodeDetails())
                    .collectorVersion(collectorVersion)
                    .lastSeen(DateTime.now(DateTimeZone.UTC))
                    .build();
        } else {
            newCollector = collectorService.fromRequest(collectorId, request, collectorVersion);
        }
        collectorService.save(newCollector);

        final CollectorActions collectorActions = actionService.findActionByCollector(collectorId, true);
        List<CollectorAction> collectorAction = null;
        if (collectorActions != null) {
            collectorAction = collectorActions.action();
        }
        final CollectorSystemConfiguration collectorSystemConfiguration = configSupplier.get();
        CollectorRegistrationResponse collectorRegistrationResponse = CollectorRegistrationResponse.create(
                CollectorRegistrationConfiguration.create(
                        collectorSystemConfiguration.collectorUpdateInterval().toStandardDuration().getStandardSeconds(),
                        collectorSystemConfiguration.collectorSendStatus()),
                collectorSystemConfiguration.collectorConfigurationOverride(),
                collectorAction,
                assignments);
        return Response.accepted(collectorRegistrationResponse).build();
    }

    @PUT
    @Timed
    @Path("/configurations")
    @ApiOperation(value = "Assign configurations to collector backends")
    @RequiresAuthentication
    @RequiresPermissions(CollectorRestPermissions.COLLECTORS_UPDATE)
    public Response assignConfiguration(@ApiParam(name = "JSON body", required = true)
                                         @Valid @NotNull NodeConfigurationRequest request) throws NotFoundException {
        List<String> nodeIdList = request.nodes().stream()
                .filter(distinctByKey(NodeConfiguration::nodeId))
                .map(NodeConfiguration::nodeId)
                .collect(Collectors.toList());

        for (String nodeId : nodeIdList) {
            List<ConfigurationAssignment> nodeRelations = request.nodes().stream()
                    .filter(a -> a.nodeId().equals(nodeId))
                    .flatMap(a -> a.assignments().stream())
                    .collect(Collectors.toList());
            try {
                Collector collector = collectorService.assignConfiguration(nodeId, nodeRelations);
                collectorService.save(collector);
            } catch (org.graylog2.database.NotFoundException e) {
                throw new NotFoundException(e.getMessage());
            }
        }

        return Response.accepted().build();
    }

    private static <T> Predicate<T> distinctByKey(Function<? super T, Object> keyExtractor) {
        Map<Object, Boolean> map = new ConcurrentHashMap<>();
        return t -> map.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }
}