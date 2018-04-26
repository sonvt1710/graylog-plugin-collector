package org.graylog.plugins.collector.altConfigurations.rest.resources;

import com.google.common.hash.Hashing;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.graylog.plugins.collector.altConfigurations.BackendService;
import org.graylog.plugins.collector.altConfigurations.rest.models.CollectorBackend;
import org.graylog.plugins.collector.altConfigurations.rest.responses.CollectorBackendListResponse;
import org.graylog.plugins.collector.altConfigurations.rest.responses.CollectorBackendSummary;
import org.graylog.plugins.collector.altConfigurations.rest.responses.CollectorBackendSummaryResponse;
import org.graylog.plugins.collector.audit.CollectorAuditEventTypes;
import org.graylog.plugins.collector.configurations.rest.ConfigurationEtagService;
import org.graylog.plugins.collector.permissions.CollectorRestPermissions;
import org.graylog2.audit.jersey.AuditEvent;
import org.graylog2.plugin.rest.PluginRestResource;
import org.graylog2.shared.rest.resources.RestResource;

import javax.inject.Inject;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.CacheControl;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.EntityTag;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.stream.Collectors;

@Api(value = "Backends", description = "Manage collector backends")
@Path("/altconfiguration/backends")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class BackendResource extends RestResource implements PluginRestResource {
    private final BackendService backendService;
    private final ConfigurationEtagService etagService;

    @Inject
    public BackendResource(BackendService backendService,
                           ConfigurationEtagService etagService) {
        this.backendService = backendService;
        this.etagService = etagService;
    }

    @GET
    @Path("/{id}")
    @RequiresAuthentication
    @RequiresPermissions(CollectorRestPermissions.COLLECTORS_READ)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Show collector details")
    public CollectorBackend getBackend(@ApiParam(name = "id", required = true)
                                         @PathParam("id") String id) {
        return this.backendService.find(id);
    }

    @GET
    @RequiresAuthentication
    @RequiresPermissions(CollectorRestPermissions.COLLECTORS_READ)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "List all collector backends")
    public Response listBackends(@Context HttpHeaders httpHeaders) {
        String ifNoneMatch = httpHeaders.getHeaderString("If-None-Match");
        Boolean etagCached = false;
        Response.ResponseBuilder builder = Response.noContent();

        // check if client is up to date with a known valid etag
        if (ifNoneMatch != null) {
            EntityTag etag = new EntityTag(ifNoneMatch.replaceAll("\"", ""));
            if (etagService.isPresent(etag.toString())) {
                etagCached = true;
                builder = Response.notModified();
                builder.tag(etag);
            }
        }

        // fetch backend list from database if client is outdated
        if (!etagCached) {
            final List<CollectorBackend> result = this.backendService.all();
            CollectorBackendListResponse collectorBackendListResponse = CollectorBackendListResponse.create(result.size(), result);

            // add new etag to cache
            String etagString = backendsToEtag(collectorBackendListResponse);

            EntityTag collectorBackendsEtag = new EntityTag(etagString);
            builder = Response.ok(collectorBackendListResponse);
            builder.tag(collectorBackendsEtag);
            etagService.put(collectorBackendsEtag.toString());
        }

        // set cache control
        CacheControl cacheControl = new CacheControl();
        cacheControl.setNoTransform(true);
        cacheControl.setPrivate(true);
        builder.cacheControl(cacheControl);

        return builder.build();
    }

    @GET
    @Path("/summary")
    @RequiresAuthentication
    @RequiresPermissions(CollectorRestPermissions.COLLECTORS_READ)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "List a summary of all collector backends")
    public CollectorBackendSummaryResponse listSummary() {
        final List<CollectorBackendSummary> result = this.backendService.all().stream()
                .map(this::getCollectorBackendSummary)
                .collect(Collectors.toList());

        return CollectorBackendSummaryResponse.create(result.size(), result);

    }

    @POST
    @RequiresAuthentication
    @RequiresPermissions(CollectorRestPermissions.COLLECTORS_CREATE)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Create new collector backend")
    public CollectorBackend createBackend(@ApiParam(name = "JSON body", required = true)
                                          @Valid @NotNull CollectorBackend request) {
        etagService.invalidateAll();
        CollectorBackend collectorBackend = backendService.fromRequest(request);
        return backendService.save(collectorBackend);
    }

    private CollectorBackendSummary getCollectorBackendSummary(CollectorBackend backend) {
        return CollectorBackendSummary.create(
                backend.id(),
                backend.name(),
                backend.serviceType(),
                backend.nodeOperatingSystem());
    }

    @PUT
    @Path("/{id}")
    @RequiresAuthentication
    @RequiresPermissions(CollectorRestPermissions.COLLECTORS_UPDATE)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Update a collector")
    public CollectorBackend updateBackend(@ApiParam(name = "id", required = true)
                                          @PathParam("id") String id,
                                          @ApiParam(name = "JSON body", required = true)
                                          @Valid @NotNull CollectorBackend request) {
        etagService.invalidateAll();
        CollectorBackend collectorBackend = backendService.fromRequest(id, request);
        return backendService.save(collectorBackend);
    }

    @POST
    @Path("/{id}/{name}")
    @RequiresAuthentication
    @RequiresPermissions(CollectorRestPermissions.COLLECTORS_CREATE)
    @ApiOperation(value = "Create a collector copy")
    @AuditEvent(type = CollectorAuditEventTypes.CONFIGURATION_CLONE)
    public Response copyCollector(@ApiParam(name = "id", required = true)
                                  @PathParam("id") String id,
                                  @PathParam("name") String name) throws NotFoundException {
        etagService.invalidateAll();
        final CollectorBackend collectorBackend = backendService.copy(id, name);
        backendService.save(collectorBackend);
        return Response.accepted().build();
    }

    @DELETE
    @Path("/{id}")
    @RequiresAuthentication
    @RequiresPermissions(CollectorRestPermissions.COLLECTORS_UPDATE)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Delets a collector configuration")
    public Response deleteCollector(@ApiParam(name = "id", required = true)
                                    @PathParam("id") String id) {
        int deleted = backendService.delete(id);
        if (deleted == 0) {
            return Response.notModified().build();
        }
        etagService.invalidateAll();
        return Response.accepted().build();
    }

    private String backendsToEtag(CollectorBackendListResponse collectorBackends) {
        return Hashing.md5()
                .hashInt(collectorBackends.hashCode())  // avoid negative values
                .toString();
    }
}
