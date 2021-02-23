package com.utopian.analytics.transforms;

import java.io.IOException;
import java.nio.ByteBuffer;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.google.common.base.Objects;
import io.confluent.connect.avro.AvroConverter;
import io.confluent.kafka.schemaregistry.ParsedSchema;
import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.subject.TopicNameStrategy;
import io.confluent.kafka.serializers.subject.strategy.SubjectNameStrategy;
import org.apache.camel.kafkaconnector.utils.SchemaHelper;
import org.apache.kafka.common.cache.Cache;
import org.apache.kafka.common.cache.LRUCache;
import org.apache.kafka.common.cache.SynchronizedCache;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.transforms.util.SimpleConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SchemaMetaDataTransformer<R extends ConnectRecord<R>> implements Transformation<R> {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaMetaDataTransformer.class);

    private static final byte MAGIC_BYTE = (byte) 0x0;

    public static final ConfigDef CONFIG_DEF;

    private static final String MSG_TOPIC = "MSG_TOPIC";
    private static final String MSG_KEY = "MSG_KEY";
    private static final String MSG_TIMESTAMP = "MSG_TIMESTAMP";
    public static final String SCHEMA_CAPACITY_FIELD_NAME = "schema.capacity";
    public static final String SCHEMA_CAPACITY_CONFIG_DOC = "The maximum amount of schemas to be stored for each Schema Registry client.";
    public static final Integer SCHEMA_CAPACITY_CONFIG_DEFAULT = 200;

    public static final String ADD_HEADERS_PREFIX = "add.headers.prefix";
    public static final String HEADERS_PREFIX_DOC = "Header prefix to be added to each header.";

    private Cache<Integer, SchemaInfo> schemaCache;
    private SchemaRegistryClient schemaRegistry;
    private SubjectNameStrategy subjectNameStrategy;
    private AvroConverter avroKeyConverter;
    private AvroConverter avroValueConverter;
    private String headersPrefix;

    static {
        CONFIG_DEF = (new ConfigDef())
                .define(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, ConfigDef.Type.STRING, ConfigDef.NO_DEFAULT_VALUE, ConfigDef.Importance.HIGH, AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_DOC)
                .define(AbstractKafkaSchemaSerDeConfig.BASIC_AUTH_CREDENTIALS_SOURCE, ConfigDef.Type.STRING, AbstractKafkaSchemaSerDeConfig.BASIC_AUTH_CREDENTIALS_SOURCE_DEFAULT, ConfigDef.Importance.MEDIUM,
                        AbstractKafkaSchemaSerDeConfig.BASIC_AUTH_CREDENTIALS_SOURCE_DOC)
                .define(AbstractKafkaSchemaSerDeConfig.USER_INFO_CONFIG, ConfigDef.Type.PASSWORD, AbstractKafkaSchemaSerDeConfig.USER_INFO_DEFAULT, ConfigDef.Importance.MEDIUM, AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_USER_INFO_DOC)
                .define(SCHEMA_CAPACITY_FIELD_NAME, ConfigDef.Type.INT, SCHEMA_CAPACITY_CONFIG_DEFAULT, ConfigDef.Importance.LOW, SCHEMA_CAPACITY_CONFIG_DOC)
                .define(ADD_HEADERS_PREFIX, ConfigDef.Type.STRING, "", ConfigDef.Importance.LOW, HEADERS_PREFIX_DOC)
        ;
    }

    @Override
    public void configure(final Map<String, ?> props) {
        SimpleConfig config = new SimpleConfig(CONFIG_DEF, props);

        final String sourceUrl = config.getString(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG);
        final Map<String, String> sourceProps = new HashMap<>();
        sourceProps.put(AbstractKafkaSchemaSerDeConfig.BASIC_AUTH_CREDENTIALS_SOURCE,
                config.getString(AbstractKafkaSchemaSerDeConfig.BASIC_AUTH_CREDENTIALS_SOURCE));
        sourceProps.put(AbstractKafkaSchemaSerDeConfig.USER_INFO_CONFIG,
                config.getPassword(AbstractKafkaSchemaSerDeConfig.USER_INFO_CONFIG).value());
        int schemaCapacity = config.getInt(SCHEMA_CAPACITY_FIELD_NAME);

        Map<String, String> converterConfig =
                Collections.singletonMap(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, sourceUrl);

        this.schemaCache = new SynchronizedCache<>(new LRUCache<>(schemaCapacity));
        this.schemaRegistry = new CachedSchemaRegistryClient(sourceUrl, schemaCapacity, sourceProps);
        this.avroKeyConverter = new AvroConverter(schemaRegistry);
        this.avroValueConverter = new AvroConverter(schemaRegistry);

        this.avroKeyConverter.configure(converterConfig, true);
        this.avroValueConverter.configure(converterConfig, false);

        // Strategy for the -key and -value subjects
        this.subjectNameStrategy = new TopicNameStrategy();
        this.headersPrefix = config.getString(ADD_HEADERS_PREFIX);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("sourceUrl: {}, schemaCapacity: {}, sourceProps: {}, headersPrefix: {}", sourceUrl, schemaCapacity, sourceProps, headersPrefix);
        }
    }

    @Override
    public R apply(final R record) {
        final String topic = record.topic();
        final Object key = record.key();
        final Schema keySchema = record.keySchema();
        final Object value = record.value();
        final Schema valueSchema = record.valueSchema();
        final Long timestamp = record.timestamp();

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("topic: {}, key: {}, value: {}, keySchema: {}, valueSchema: {}", topic, key, value, keySchema, valueSchema);
        }

        makeStaticHeaders(topic, key, timestamp).forEach(h -> record.headers().add(h));
        SchemaInfo schemaInfo = null;
        if (key != null) {
            byte[] keyAsBytes = avroKeyConverter.fromConnectData(topic, keySchema, key);
            schemaInfo = getSchemaInfo(keyAsBytes, topic, true);
            makeHeaders(schemaInfo).forEach(h -> record.headers().add(h));
        }

        if (value != null) {
            byte[] valueAsBytes = avroValueConverter.fromConnectData(topic, valueSchema, value);
            schemaInfo = getSchemaInfo(valueAsBytes, topic, false);
            makeHeaders(schemaInfo).forEach(h -> record.headers().add(h));
        }

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("schemaInfo: {}", schemaInfo);
            record.headers().forEach(h -> LOGGER.debug("Sink Record header: {}", h));
        }

        return record;
    }

    protected SchemaInfo getSchemaInfo(byte[] bytes, String topic, boolean isKey) {
        SchemaInfo schemaInfo;

        int keyByteLength = bytes.length;
        if (keyByteLength <= 5) {
            throw new SerializationException("Unexpected byte[] length " + keyByteLength + " for Avro record value.");
        }

        ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        if (byteBuffer.get() == MAGIC_BYTE) {
            int schemaId = byteBuffer.getInt();

            schemaInfo = schemaCache.get(schemaId);
            if (schemaInfo != null) {
                LOGGER.trace("Cache hit: Schema id {} has been seen before", schemaId);
            } else { // cache miss
                LOGGER.trace("Cache miss: Schema id {} has not been seen before", schemaId);
                String subject = subjectNameStrategy.subjectName(topic, isKey, null);
                schemaInfo = new SchemaInfo(schemaId, subject, isKey);
                try {
                    LOGGER.trace("Looking up schema with id {} in the source registry", schemaId);
                    // Can't do getBySubjectAndId because that requires a Schema object for the strategy
                    ParsedSchema parsedSchema = schemaRegistry.getSchemaById(schemaId);
                    schemaInfo.version = schemaRegistry.getVersion(schemaInfo.subject, parsedSchema);
                    schemaCache.put(schemaId, schemaInfo);
                } catch (IOException | RestClientException e) {
                    LOGGER.error(String.format("Unable to fetch source schema for id %d.", schemaId), e);
                    throw new ConnectException(e);
                }
            }
        } else {
            throw new SerializationException("Unknown magic byte!");
        }

        return schemaInfo;
    }

    /**
     * Create an Headers object which contains all static headers to be added.
     */
    private Headers makeStaticHeaders(String topic, Object msgKey, Long timestamp) {
        Headers connectHeaders = new ConnectHeaders();
        final Map<String, Object> headersMap = new HashMap<>();

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Adding to header map {}:{}, {}:{}, {}:{}", MSG_TOPIC, topic, MSG_KEY, msgKey, MSG_TIMESTAMP, timestamp);
        }
        headersMap.put(MSG_TOPIC, topic);
        headersMap.put(MSG_KEY, msgKey == null ? "" : msgKey.toString());
        headersMap.put(MSG_TIMESTAMP, timestamp == null ? 0L : timestamp);

        headersMap.forEach((key, value) -> connectHeaders
                .add(headersPrefix + key, value, SchemaHelper.buildSchemaBuilderForType(value)));
        return connectHeaders;
    }

    /**
     * Create an Headers object which contains all the headers to be added.
     */
    private Headers makeHeaders(SchemaInfo schemaInfo) {
        String prefixKeyOrValue = schemaInfo.isKey ? "KEY" : "VALUE";
        Headers connectHeaders = new ConnectHeaders();
        Map<String, Object> headersMap = new HashMap<>();
        headersMap.put("MSG_" + prefixKeyOrValue + "_SCHEMA_ID", schemaInfo.id);
        headersMap.put("MSG_" + prefixKeyOrValue + "_SCHEMA_VERSION", schemaInfo.version);
        headersMap.put("MSG_" + prefixKeyOrValue + "_SUBJECT", schemaInfo.subject);
        headersMap.forEach((key, value) -> connectHeaders
                .add(headersPrefix + key, value, SchemaHelper.buildSchemaBuilderForType(value)));

        return connectHeaders;
    }

    @Override
    public ConfigDef config() {
        return CONFIG_DEF;
    }

    @Override
    public void close() {
    }

    private static class SchemaInfo {
        private final int id;
        private int version;
        private final String subject;
        private final boolean isKey;

        SchemaInfo(int id, String subject, boolean isKey) {
            this.id = id;
            this.subject = subject;
            this.isKey = isKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            SchemaInfo that = (SchemaInfo) o;
            return id == that.id && version == that.version && isKey == that.isKey && Objects.equal(subject, that.subject);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(id, version, subject, isKey);
        }

        @Override
        public String toString() {
            return "SchemaInfo{" +
                    "id=" + id +
                    ", version=" + version +
                    ", subject='" + subject + '\'' +
                    ", isKey=" + isKey +
                    '}';
        }
    }
}
