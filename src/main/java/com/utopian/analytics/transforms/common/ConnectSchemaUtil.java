package com.utopian.analytics.transforms.common;

import java.io.IOException;
import io.confluent.kafka.schemaregistry.avro.AvroSchema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConnectSchemaUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectSchemaUtil.class);

    public static boolean isBytesSchema(Schema connectSchema) {
        return (connectSchema != null &&
                (connectSchema.type() == Schema.BYTES_SCHEMA.type() ||
                        connectSchema.type() == Schema.OPTIONAL_BYTES_SCHEMA.type()));
    }

    public static boolean isStructSchema(Schema connectSchema) {
        return (connectSchema != null && connectSchema.type() == Schema.Type.STRUCT);
    }

    public static void querySchemaRegistry(SchemaRegistryClient registryClient) {
        try {
            for (String subject : registryClient.getAllSubjects()) {
                for (Integer version : registryClient.getAllVersions(subject)) {
                    io.confluent.kafka.schemaregistry.client.rest.entities.Schema restEntitySchema =
                            registryClient.getByVersion(subject, version, false);
                    String schema = restEntitySchema.getSchema();
                    int schemaId = registryClient.getId(subject, new AvroSchema(schema));
                    int schemaVersion = registryClient.getVersion(subject, new AvroSchema(schema));
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("subject: {}, version: {}, schema: {}", subject, version, schema);
                        LOGGER.debug("schemaId: {}, schemaVersion: {}", schemaId, schemaVersion);
                    }
                }
            }
        } catch (IOException | RestClientException e) {
            LOGGER.error(e.getMessage(), e);
            throw new ConnectException(e);
        }
    }
}
