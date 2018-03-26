package org.graylog.plugins.collector.altConfigurations;

import com.google.common.collect.Iterables;
import com.mongodb.BasicDBObject;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.bson.types.ObjectId;
import org.graylog.plugins.collector.altConfigurations.directives.IndentTemplateDirective;
import org.graylog.plugins.collector.altConfigurations.loader.MongoDbTemplateLoader;
import org.graylog.plugins.collector.altConfigurations.rest.models.Collector;
import org.graylog.plugins.collector.altConfigurations.rest.models.CollectorConfiguration;
import org.graylog2.bindings.providers.MongoJackObjectMapperProvider;
import org.graylog2.database.MongoConnection;
import org.mongojack.DBCursor;
import org.mongojack.DBQuery;
import org.mongojack.JacksonDBCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Singleton
public class AltConfigurationService {
    private static final Logger LOG = LoggerFactory.getLogger(AltConfigurationService.class);
    private static Configuration templateConfiguration = new Configuration(Configuration.VERSION_2_3_27);

    private static final String COLLECTION_NAME = "collector_configurations";
    private final JacksonDBCollection<CollectorConfiguration, ObjectId> dbCollection;

    @Inject
    public AltConfigurationService(MongoConnection mongoConnection,
                                   MongoJackObjectMapperProvider mapper) {
        dbCollection = JacksonDBCollection.wrap(
                mongoConnection.getDatabase().getCollection(COLLECTION_NAME),
                CollectorConfiguration.class,
                ObjectId.class,
                mapper.get());
        templateConfiguration.setTemplateLoader(new MongoDbTemplateLoader(dbCollection));
        templateConfiguration.setSharedVariable("indent", new IndentTemplateDirective());
    }

    public CollectorConfiguration load(String id) {
        return dbCollection.findOne(DBQuery.is("_id", id));
    }

    public List<CollectorConfiguration> loadAll() {
        final DBCursor<CollectorConfiguration> cursor = dbCollection.find();
        final List<CollectorConfiguration> collectorConfigurationList = new ArrayList<>();
        Iterables.addAll(collectorConfigurationList, cursor);
        return collectorConfigurationList;
    }

    public CollectorConfiguration save(CollectorConfiguration configuration) {
        return dbCollection.findAndModify(DBQuery.is("_id", configuration.id()), new BasicDBObject(),
                new BasicDBObject(), false, configuration, true, true);
    }

    public int delete(String id) {
        return dbCollection.remove(DBQuery.is("_id", id)).getN();
    }

    public CollectorConfiguration fromRequest(CollectorConfiguration request) {
        CollectorConfiguration collectorConfiguration = CollectorConfiguration.create(
                request.backendId(),
                request.name(),
                request.template());
        return collectorConfiguration;
    }

    public CollectorConfiguration fromRequest(String id, CollectorConfiguration request) {
        CollectorConfiguration collectorConfiguration = CollectorConfiguration.create(
                id,
                request.backendId(),
                request.name(),
                request.template());
        return collectorConfiguration;
    }

    public CollectorConfiguration renderConfigurationForCollector(Collector collector, String configurationId) {
        Map<String, Object> context = new HashMap<>();

        context.put("nodeId", collector.nodeId());
        context.put("nodeName", collector.nodeName());
        context.put("collectorVersion", collector.collectorVersion());
        context.put("operatingSystem", collector.nodeDetails().operatingSystem());
        context.put("ip", collector.nodeDetails().ip());
        if (collector.nodeDetails().metrics().cpuIdle() != null) {
            context.put("cpuIdle", collector.nodeDetails().metrics().cpuIdle());
        }
        if (collector.nodeDetails().metrics().load1() != null) {
            context.put("load1", collector.nodeDetails().metrics().load1());
        }

        CollectorConfiguration configuration = load(configurationId);
        return CollectorConfiguration.create(
                configuration.id(),
                configuration.backendId(),
                configuration.name(),
                renderTemplate(configuration.id(), context)
        );
    }

    public String renderPreview(String configurationId) {
        Map<String, Object> context = new HashMap<>();

        context.put("collectorId", "<collector id>");
        context.put("nodeId", "<node id>");
        context.put("collectorVersion", "<version>");
        context.put("operatingSystem", "<operating system>");
        context.put("ip", "<ip>");
        context.put("cpuIdle", "<cpu idle>");
        context.put("load1", "<load 1>");

        return renderTemplate(configurationId, context);

    }

    private String renderTemplate(String configurationId, Map<String, Object> context) {
        Writer writer = new StringWriter();
        try {
            Template compiledTemplate = templateConfiguration.getTemplate(configurationId);
            compiledTemplate.process(context, writer);
        } catch (TemplateException | IOException e) {
            LOG.error("Failed to render template: ", e);
            return null;
        }

        return writer.toString();
    }
}
