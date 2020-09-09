package com.salesforce.cantor.s3;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.util.IOUtils;
import com.google.gson.*;
import com.salesforce.cantor.Events;
import com.salesforce.cantor.s3.utils.S3Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.salesforce.cantor.common.EventsPreconditions.checkGet;
import static com.salesforce.cantor.common.EventsPreconditions.checkStore;

public class EventsOnS3 extends AbstractBaseS3Namespaceable implements Events {
    private static final Logger logger = LoggerFactory.getLogger(EventsOnS3.class);
    // custom gson parser to auto-convert payload to byte[]
    private static final Gson parser = new GsonBuilder()
            .registerTypeHierarchyAdapter(byte[].class, new ByteArrayHandler()).create();
    private static final Gson parserPayloadless = new GsonBuilder()
            // ignore parameters; empty payload field
            .registerTypeHierarchyAdapter(byte[].class, (JsonDeserializer<Object>) (i, i1, i2) -> new byte[0])
            .create();

    // cantor-namespace-<namespace>
    private static final String namespaceFileFormat = "cantor-events-namespace-%s";
    // cantor-events-[<namespace>]-<startTimestamp>-<endTimestamp>
    private static final String objectKeyPrefix = "cantor-events-[%s]-";
    private static final String objectKeyFormat = objectKeyPrefix + "%d-%d";
    private static final Pattern eventsObjectPattern = Pattern.compile("cantor-events-\\[(?<namespace>.*)]-(?<start>\\d+)-(?<end>\\d+)");
    private static final long chunkMillis = TimeUnit.HOURS.toMillis(1);

    public EventsOnS3(final AmazonS3 s3Client, final String bucketName) throws IOException {
        super(s3Client, bucketName);
    }

    @Override
    public void store(final String namespace, final Collection<Event> batch) throws IOException {
        checkStore(namespace, batch);
        try {
            doStore(namespace, batch);
        } catch (final AmazonS3Exception e) {
            logger.warn("exception storing events to namespace: " + namespace, e);
            throw new IOException("exception storing {} events to namespace: " + namespace, e);
        }
    }

    @Override
    public List<Event> get(final String namespace,
                           final long startTimestampMillis,
                           final long endTimestampMillis,
                           final Map<String, String> metadataQuery,
                           final Map<String, String> dimensionsQuery,
                           final boolean includePayloads,
                           final boolean ascending,
                           final int limit) throws IOException {
        checkGet(namespace, startTimestampMillis, endTimestampMillis, metadataQuery, dimensionsQuery);
        try {
            return doGet(namespace,
                         startTimestampMillis,
                         endTimestampMillis,
                         (metadataQuery != null) ? metadataQuery : Collections.emptyMap(),
                         (dimensionsQuery != null) ? dimensionsQuery : Collections.emptyMap(),
                         includePayloads,
                         ascending,
                         limit);
        } catch (final AmazonS3Exception e) {
            logger.warn("exception getting events from namespace: " + namespace, e);
            throw new IOException("exception storing {} events to namespace: " + namespace, e);
        }
    }

    @Override
    public int delete(final String namespace,
                      final long startTimestampMillis,
                      final long endTimestampMillis,
                      final Map<String, String> metadataQuery,
                      final Map<String, String> dimensionsQuery) throws IOException {
        // not implemented yet
        return 0;
    }

    @Override
    public Map<Long, Double> aggregate(final String namespace,
                                       final String dimension,
                                       final long startTimestampMillis,
                                       final long endTimestampMillis,
                                       final Map<String, String> metadataQuery,
                                       final Map<String, String> dimensionsQuery,
                                       final int aggregateIntervalMillis,
                                       final AggregationFunction aggregationFunction) throws IOException {
        // not implemented yet
        return null;
    }

    @Override
    public Set<String> metadata(final String namespace,
                                final String metadataKey,
                                final long startTimestampMillis,
                                final long endTimestampMillis,
                                final Map<String, String> metadataQuery,
                                final Map<String, String> dimensionsQuery) throws IOException {
        // not implemented yet
        return null;
    }

    @Override
    public void expire(final String namespace, final long endTimestampMillis) throws IOException {
        // not implemented yet
    }

    @Override
    protected String getNamespaceKey(final String namespace) {
        return String.format(namespaceFileFormat, namespace);
    }

    @Override
    protected String getObjectKeyPrefix(final String namespace) {
        return String.format(objectKeyPrefix, namespace);
    }

    // storing each event in json lines format to conform to s3 selects preferred format; see https://docs.aws.amazon.com/AmazonS3/latest/dev/selecting-content-from-objects.html
    private void doStore(final String namespace, final Collection<Event> batch) throws IOException {
        if (!super.namespaceExists(namespace)) {
            throw new IOException(String.format("namespace '%s' doesn't exist; can't store object with key '%s'", namespace, getNamespaceKey(namespace)));
        }

        final Map<String, ByteArrayOutputStream> keyToObject = new HashMap<>();
        final Collection<String> eventsFiles = S3Utils.getKeys(this.s3Client, this.bucketName, getObjectKeyPrefix(namespace));
        for (final Event event : batch) {
            final String key = getObjectKey(eventsFiles, namespace, event.getTimestampMillis());
            try (final OutputStream objectStream = getOutputStream(this.bucketName, key, keyToObject)) {
                objectStream.write((parser.toJson(event) + "\n").getBytes());
            }
        }

        for (final Map.Entry<String, ByteArrayOutputStream> entry : keyToObject.entrySet()) {
            final ByteArrayInputStream content = new ByteArrayInputStream(entry.getValue().toByteArray());
            logger.info("storing stream with length={} at '{}.{}'", content.available(), this.bucketName, entry.getKey());
            final ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentLength(content.available());
            // if no exception is thrown, the object was put successfully - ignore response value
            S3Utils.putObject(this.s3Client, this.bucketName, entry.getKey(), content, metadata);
        }
    }

    private List<Event> doGet(final String namespace,
                              final long startTimestampMillis,
                              final long endTimestampMillis,
                              final Map<String, String> metadataQuery,
                              final Map<String, String> dimensionsQuery,
                              final boolean includePayloads,
                              final boolean ascending,
                              final int limit) throws IOException {
        if (!super.namespaceExists(namespace)) {
            throw new IOException(String.format("namespace '%s' doesn't exist; can't retrieve object with key '%s'", namespace, getNamespaceKey(namespace)));
        }

        final Collection<String> eventsFiles = S3Utils.getKeys(this.s3Client, this.bucketName, getObjectKeyPrefix(namespace));
        final List<String> matchingKeys = getMatchingKeys(eventsFiles, startTimestampMillis, endTimestampMillis, ascending);

        // using tree map for convenient sorting
        final Map<Long, Event> events = (ascending) ? new TreeMap<>() : new TreeMap<>(Collections.reverseOrder());
        final Map<String, Pattern> metadataPatterns = generateRegex(metadataQuery);
        for (final String matchingKey : matchingKeys) {
            final String query = generateQuery(startTimestampMillis, endTimestampMillis, metadataQuery, dimensionsQuery, limit);
            final InputStream jsonLines = S3Utils.S3Select.queryObject(this.s3Client, this.bucketName, matchingKey, query);
            final Scanner lineReader = new Scanner(jsonLines);
            // json events are stored in json lines format, so one json object per line
            while (lineReader.hasNext()) {
                final Event event;
                if (!includePayloads) {
                    event = parserPayloadless.fromJson(lineReader.nextLine(), Event.class);
                } else {
                    event = parser.fromJson(lineReader.nextLine(), Event.class);
                }

                if (validEvent(event, metadataPatterns)) {
                    events.put(event.getTimestampMillis(), event);
                }
            }
            // files are already sorted, so we are guaranteed to have all the correct events once we hit the limit
            if (limit > 0 && events.size() >= limit) {
                break;
            }
        }

        final ArrayList<Event> orderedEvents = new ArrayList<>(events.values());
        // events may include more than the limit as files must to read in their entirety as order is not guaranteed
        return (limit <= 0) ? orderedEvents : orderedEvents.subList(0, limit);
    }

    // creates an s3 select compatible query; see https://docs.aws.amazon.com/AmazonS3/latest/dev/s3-glacier-select-sql-reference-select.html
    private String generateQuery(final long startTimestampMillis,
                                 final long endTimestampMillis,
                                 final Map<String, String> metadataQuery,
                                 final Map<String, String> dimensionsQuery,
                                 final int limit) {
        final String timestampClause = String.format("s.timestampMillis BETWEEN %d AND %d", startTimestampMillis, endTimestampMillis);
        return String.format("SELECT * FROM s3object[*] s WHERE %s %s %s %s",
                timestampClause,
                getMetadataQuerySql(metadataQuery),
                getDimensionQuerySql(dimensionsQuery),
                // omitting limit since we cannot control order of return
                "" // (limit <= 0) ? "" : "LIMIT " + limit
        );
    }

    // do full regex evaluation server side as s3 select only supports limited regex
    private boolean validEvent(final Event event, final Map<String, Pattern> metadataPatterns) {
        for (final Map.Entry<String, Pattern> metaRegex : metadataPatterns.entrySet()) {
            final String metadataValue = event.getMetadata().get(metaRegex.getKey().substring(2));
            if (metadataValue == null) {
                return false;
            }

            final Matcher regex = metaRegex.getValue().matcher(metadataValue);
            if (metaRegex.getKey().startsWith("_~") && !regex.matches()) {
                return false;
            } else if (metaRegex.getKey().startsWith("!~") && regex.matches()) {
                return false;
            }
        }
        return true;
    }

    // simple caching logic to prevent numerous copies of the object that events will be added to
    private ByteArrayOutputStream getOutputStream(final String bucketName,
                                                  final String key,
                                                  final Map<String, ByteArrayOutputStream> localCache) throws IOException {
        if (localCache.containsKey(key)) {
            return localCache.get(key);
        }

        localCache.put(key, new ByteArrayOutputStream());
        try (final InputStream objectStream = S3Utils.getObjectStream(this.s3Client, bucketName, key)) {
            if (objectStream != null) {
                IOUtils.copy(objectStream, localCache.get(key));
            }
            return localCache.get(key);
        }
    }

    // attempts to get key which container the timestamp within its bounds
    private String getObjectKey(final Collection<String> keys,
                                final String namespace,
                                final long eventTimestampMillis) throws IOException {
        final Optional<String> existingKey = keys.stream().filter(key -> {
            final Matcher matcher = eventsObjectPattern.matcher(key);
            if (matcher.matches()) {
                final long start = Long.parseLong(matcher.group("start"));
                final long end = Long.parseLong(matcher.group("end"));
                return start <= eventTimestampMillis && eventTimestampMillis <= end;
            }
            return false;
        }).findFirst();

        // else gets default form of the key that would contain the timestamp
        return existingKey.orElse(
                String.format(objectKeyFormat,
                        namespace,
                        getFloorForChunk(eventTimestampMillis),
                        getCeilingForChunk(eventTimestampMillis)
                )
        );
    }

    // returns all keys that overlap with the timeframe
    public <R> List<R> getMatchingKeys(final Collection<R> keys,
                                       final long startTimestampMillis,
                                       final long endTimestampMillis,
                                       final boolean ascending) {
        final long windowStart = getFloorForChunk(startTimestampMillis);
        final long windowEnd = (endTimestampMillis <= Long.MAX_VALUE - chunkMillis)
                ? getCeilingForChunk(endTimestampMillis)
                : endTimestampMillis;
        final Stream<R> keysStream = keys.stream().filter(key -> {
            final Matcher matcher = eventsObjectPattern.matcher(key.toString());
            if (matcher.matches()) {
                final long fileStart = Long.parseLong(matcher.group("start"));
                final long fileEnd = Long.parseLong(matcher.group("end"));
                // -------s-------------e---------  <- start and end parameters
                // ssssssssssssssssssssss           <- first check
                //        eeeeeeeeeeeeeeeeeeeeeeee  <- second check
                // any combination of s and e the file overlaps the timeframe
                return fileStart <= windowEnd && fileEnd >= windowStart;
            }
            return false;
        });

        final List<R> matchingKeys;
        if (ascending) {
            matchingKeys = keysStream.sorted(this::ascendingSort).collect(Collectors.toList());
        } else {
            matchingKeys = keysStream.sorted(this::descendingSort).collect(Collectors.toList());
        }
        return matchingKeys;
    }

    private <R> int ascendingSort(final R keyA, final R keyB) {
        final Matcher matcherA = eventsObjectPattern.matcher(keyA.toString());
        final Matcher matcherB = eventsObjectPattern.matcher(keyB.toString());
        if (matcherA.matches() && matcherB.matches()) {
            final long fileStartA = Long.parseLong(matcherA.group("start"));
            final long fileStartB = Long.parseLong(matcherB.group("start"));
            return Long.compare(fileStartA, fileStartB);
        }
        return 0;
    }

    private <R> int descendingSort(final R keyA, final R keyB) {
        final Matcher matcherA = eventsObjectPattern.matcher(keyA.toString());
        final Matcher matcherB = eventsObjectPattern.matcher(keyB.toString());
        if (matcherA.matches() && matcherB.matches()) {
            final long fileStartA = Long.parseLong(matcherA.group("start"));
            final long fileStartB = Long.parseLong(matcherB.group("start"));
            return Long.compare(fileStartB, fileStartA);
        }
        return 0;
    }

    // full regex is not supported by s3 select, so some evaluation must be done server side
    private Map<String, Pattern> generateRegex(final Map<String, String> metadataQuery) {
        final Map<String, Pattern> regexes = new HashMap<>();
        for (final Map.Entry<String, String> entry : metadataQuery.entrySet()) {
            try {
                final String maybeRegex = entry.getValue();
                // add prefix to key for easy differentiation later
                if (maybeRegex.startsWith("~")) {
                    final String fullRegex = maybeRegex.substring(1).replaceAll("(\\.?)\\*", ".*");
                    regexes.put("_~" + entry.getKey(), Pattern.compile(fullRegex));
                } else if (maybeRegex.startsWith("!~")) {
                    final String fullRegex = maybeRegex.substring(2).replaceAll("(\\.?)\\*", ".*");
                    regexes.put("!~" + entry.getKey(), Pattern.compile(fullRegex));
                }
            } catch (final PatternSyntaxException pse) {
                //TODO: we could add logic to explicitly look for limit regex, but it's simpler to just let it fall into this exception
                logger.warn("invalid regex pattern caught; will allow as limited regex may cause this exception", pse);
            }
        }
        return regexes;
    }

    // the metadata query object can contain these patterns:
    // '' (just a string): equals - 'user-id' => 'user-1'
    // '=': equals - 'user-id' => '=user-1'
    // '!=': not equals - 'user-id' => '!=user-1'
    // '~': limited regex like - 'user-id' => '~user-*'
    // '!~': inverted limited  regex like - 'user-id' => '!~user-*'
    private String getMetadataQuerySql(final Map<String, String> metadataQuery) {
        if (metadataQuery.isEmpty()) {
            return "";
        }
        final StringBuilder sql = new StringBuilder();
        for (final Map.Entry<String, String> entry : metadataQuery.entrySet()) {
            final String metadataName = prefixMetadata(entry.getKey());
            final String query = entry.getValue();
            // s3 select only supports limited regex
            if (query.startsWith("~") || query.startsWith("!~")) {
                sql.append(" AND ").append(String.format(" %s LIKE %s", metadataName, quote("%")));
            } else if (query.startsWith("=")) {
                sql.append(" AND ").append(metadataName).append(" = ").append(quote(query.substring(1)));
            } else if (query.startsWith("!=")) {
                sql.append(" AND ").append(metadataName).append(" != ").append(quote(query.substring(2)));
            } else {
                sql.append(" AND ").append(metadataName).append(" = ").append(quote(query));
            }
        }
        return sql.toString();
    }

    // the dimension query object can contain these patterns:
    // '' (just a number): equals - 'cpu' => '90'
    // '=': equals - 'cpu' => '=90'
    // '!=': not equals - 'cpu' => '!=90'
    // '..': between - 'cpu' => '90..100'
    // '>': greater than - 'cpu' => '>90'
    // '>=': greater than or equals - 'cpu' => '>=90'
    // '<': less than - 'cpu' => '<90'
    // '<=': less than or equals - 'cpu' => '<=90'
    private String getDimensionQuerySql(final Map<String, String> dimensionsQuery) {
        if (dimensionsQuery.isEmpty()) {
            return "";
        }
        final StringBuilder sql = new StringBuilder();
        for (final Map.Entry<String, String> entry : dimensionsQuery.entrySet()) {
            final String dimensionName = prefixDimension(entry.getKey());
            final String query = entry.getValue();
            if (query.contains("..")) {
                sql.append(" AND ")
                    .append(dimensionName)
                    .append(" BETWEEN ")
                    .append(Double.valueOf(query.substring(0, query.indexOf(".."))))
                    .append(" AND ")
                    .append(Double.valueOf(query.substring(query.indexOf("..") + 2)));
            } else if (query.startsWith(">=")) {
                sql.append(" AND ").append(dimensionName).append(" >= ").append(query.substring(2));
            } else if (query.startsWith("<=")) {
                sql.append(" AND ").append(dimensionName).append(" <= ").append(query.substring(2));
            } else if (query.startsWith(">")) {
                sql.append(" AND ").append(dimensionName).append(" > ").append(query.substring(1));
            } else if (query.startsWith("<")) {
                sql.append(" AND ").append(dimensionName).append(" < ").append(query.substring(1));
            } else if (query.startsWith("!=")) {
                sql.append(" AND ").append(dimensionName).append(" != ").append(query.substring(2));
            } else if (query.startsWith("=")) {
                sql.append(" AND ").append(dimensionName).append(" = ").append(query.substring(1));
            } else {
                sql.append(" AND ").append(dimensionName).append(" = ").append(query);
            }
        }
        return sql.toString();
    }

    private String quote(final String key) {
        return String.format("'%s'", key);
    }

    private String prefixMetadata(final String key) {
        return String.format("s.metadata.\"%s\"", key);
    }

    private String prefixDimension(final String key) {
        return String.format("CAST ( s.dimensions.\"%s\" as decimal)", key);
    }

    private long getFloorForChunk(final long timestampMillis) {
        return (timestampMillis / chunkMillis) * chunkMillis;
    }

    private long getCeilingForChunk(final long timestampMillis) {
        if (timestampMillis >= Long.MAX_VALUE - chunkMillis) {
            return Long.MAX_VALUE;
        }
        return getFloorForChunk(timestampMillis) + chunkMillis - 1;
    }

    /**
     * Serialize override to allow conversion of payload string to byte array
     */
    private static class ByteArrayHandler implements JsonSerializer<byte[]>, JsonDeserializer<byte[]> {

        @Override
        public byte[] deserialize(final JsonElement jsonElement, final Type type, final JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
            return Base64.getDecoder().decode(jsonElement.getAsString());
        }

        @Override
        public JsonElement serialize(final byte[] bytes, final Type type, final JsonSerializationContext jsonSerializationContext) {
            final String encodedString = Base64.getEncoder().encodeToString(bytes);
            return new JsonPrimitive(encodedString);
        }
    }
}
