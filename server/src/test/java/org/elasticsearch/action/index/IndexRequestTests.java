/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.action.index;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.replication.ReplicationResponse;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.DataStreamTestHelper;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.time.DateFormatter;
import org.elasticsearch.common.time.FormatNames;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.core.Tuple;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.index.seqno.SequenceNumbers;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.VersionUtils;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

public class IndexRequestTests extends ESTestCase {
    public void testIndexRequestOpTypeFromString() throws Exception {
        String create = "create";
        String index = "index";
        String createUpper = "CREATE";
        String indexUpper = "INDEX";

        IndexRequest indexRequest = new IndexRequest("");
        indexRequest.opType(create);
        assertThat(indexRequest.opType(), equalTo(DocWriteRequest.OpType.CREATE));
        indexRequest.opType(createUpper);
        assertThat(indexRequest.opType(), equalTo(DocWriteRequest.OpType.CREATE));
        indexRequest.opType(index);
        assertThat(indexRequest.opType(), equalTo(DocWriteRequest.OpType.INDEX));
        indexRequest.opType(indexUpper);
        assertThat(indexRequest.opType(), equalTo(DocWriteRequest.OpType.INDEX));
    }

    public void testReadIncorrectOpType() {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> new IndexRequest("").opType("foobar"));
        assertThat(e.getMessage(), equalTo("opType must be 'create' or 'index', found: [foobar]"));
    }

    public void testCreateOperationRejectsVersions() {
        Set<VersionType> allButInternalSet = new HashSet<>(Arrays.asList(VersionType.values()));
        allButInternalSet.remove(VersionType.INTERNAL);
        VersionType[] allButInternal = allButInternalSet.toArray(new VersionType[] {});
        IndexRequest request = new IndexRequest("index").id("1");
        request.opType(IndexRequest.OpType.CREATE);
        request.versionType(randomFrom(allButInternal));
        assertThat(request.validate().validationErrors(), not(empty()));

        request.versionType(VersionType.INTERNAL);
        request.version(randomIntBetween(0, Integer.MAX_VALUE));
        assertThat(request.validate().validationErrors(), not(empty()));
    }

    public void testIndexingRejectsLongIds() {
        String id = randomAlphaOfLength(511);
        IndexRequest request = new IndexRequest("index").id(id);
        request.source("{}", XContentType.JSON);
        ActionRequestValidationException validate = request.validate();
        assertNull(validate);

        id = randomAlphaOfLength(512);
        request = new IndexRequest("index").id(id);
        request.source("{}", XContentType.JSON);
        validate = request.validate();
        assertNull(validate);

        id = randomAlphaOfLength(513);
        request = new IndexRequest("index").id(id);
        request.source("{}", XContentType.JSON);
        validate = request.validate();
        assertThat(validate, notNullValue());
        assertThat(validate.getMessage(), containsString("id [" + id + "] is too long, must be no longer than 512 bytes but was: 513"));
    }

    public void testWaitForActiveShards() {
        IndexRequest request = new IndexRequest("index");
        final int count = randomIntBetween(0, 10);
        request.waitForActiveShards(ActiveShardCount.from(count));
        assertEquals(request.waitForActiveShards(), ActiveShardCount.from(count));
        // test negative shard count value not allowed
        expectThrows(IllegalArgumentException.class, () -> request.waitForActiveShards(ActiveShardCount.from(randomIntBetween(-10, -1))));
    }

    public void testAutoGenIdTimestampIsSet() {
        IndexRequest request = new IndexRequest("index");
        request.process();
        assertTrue("expected > 0 but got: " + request.getAutoGeneratedTimestamp(), request.getAutoGeneratedTimestamp() > 0);
        request = new IndexRequest("index").id("1");
        request.process();
        assertEquals(IndexRequest.UNSET_AUTO_GENERATED_TIMESTAMP, request.getAutoGeneratedTimestamp());
    }

    public void testIndexResponse() {
        ShardId shardId = new ShardId(randomAlphaOfLengthBetween(3, 10), randomAlphaOfLengthBetween(3, 10), randomIntBetween(0, 1000));
        String id = randomAlphaOfLengthBetween(3, 10);
        long version = randomLong();
        boolean created = randomBoolean();
        IndexResponse indexResponse = new IndexResponse(shardId, id, SequenceNumbers.UNASSIGNED_SEQ_NO, 0, version, created);
        int total = randomIntBetween(1, 10);
        int successful = randomIntBetween(1, 10);
        ReplicationResponse.ShardInfo shardInfo = new ReplicationResponse.ShardInfo(total, successful);
        indexResponse.setShardInfo(shardInfo);
        boolean forcedRefresh = false;
        if (randomBoolean()) {
            forcedRefresh = randomBoolean();
            indexResponse.setForcedRefresh(forcedRefresh);
        }
        assertEquals(id, indexResponse.getId());
        assertEquals(version, indexResponse.getVersion());
        assertEquals(shardId, indexResponse.getShardId());
        assertEquals(created ? RestStatus.CREATED : RestStatus.OK, indexResponse.status());
        assertEquals(total, indexResponse.getShardInfo().getTotal());
        assertEquals(successful, indexResponse.getShardInfo().getSuccessful());
        assertEquals(forcedRefresh, indexResponse.forcedRefresh());
        assertEquals(
            """
                IndexResponse[index=%s,id=%s,version=%s,result=%s,seqNo=%s,primaryTerm=%s,shards=\
                {"total":%s,"successful":%s,"failed":0}]\
                """.formatted(
                shardId.getIndexName(),
                id,
                version,
                created ? "created" : "updated",
                SequenceNumbers.UNASSIGNED_SEQ_NO,
                0,
                total,
                successful
            ),
            indexResponse.toString()
        );
    }

    public void testIndexRequestXContentSerialization() throws IOException {
        IndexRequest indexRequest = new IndexRequest("foo").id("1");
        boolean isRequireAlias = randomBoolean();
        Map<String, String> dynamicTemplates = IntStream.range(0, randomIntBetween(0, 10))
            .boxed()
            .collect(Collectors.toMap(n -> "field-" + n, n -> "name-" + n));
        indexRequest.source("{}", XContentType.JSON);
        indexRequest.setDynamicTemplates(dynamicTemplates);
        indexRequest.setRequireAlias(isRequireAlias);
        assertEquals(XContentType.JSON, indexRequest.getContentType());

        BytesStreamOutput out = new BytesStreamOutput();
        indexRequest.writeTo(out);
        StreamInput in = StreamInput.wrap(out.bytes().toBytesRef().bytes);
        IndexRequest serialized = new IndexRequest(in);
        assertEquals(XContentType.JSON, serialized.getContentType());
        assertEquals(new BytesArray("{}"), serialized.source());
        assertEquals(isRequireAlias, serialized.isRequireAlias());
        assertThat(serialized.getDynamicTemplates(), equalTo(dynamicTemplates));
    }

    // reindex makes use of index requests without a source so this needs to be handled
    public void testSerializationOfEmptyRequestWorks() throws IOException {
        IndexRequest request = new IndexRequest("index");
        assertNull(request.getContentType());
        assertEquals("index", request.index());

        try (BytesStreamOutput out = new BytesStreamOutput()) {
            request.writeTo(out);
            try (StreamInput in = out.bytes().streamInput()) {
                IndexRequest serialized = new IndexRequest(in);
                assertNull(serialized.getContentType());
                assertEquals("index", serialized.index());
            }
        }
    }

    public void testSerializeDynamicTemplates() throws Exception {
        IndexRequest indexRequest = new IndexRequest("foo").id("1");
        indexRequest.source("{}", XContentType.JSON);
        // Empty dynamic templates
        {
            if (randomBoolean()) {
                indexRequest.setDynamicTemplates(Map.of());
            }
            Version ver = VersionUtils.randomCompatibleVersion(random(), Version.CURRENT);
            BytesStreamOutput out = new BytesStreamOutput();
            out.setVersion(ver);
            indexRequest.writeTo(out);
            StreamInput in = StreamInput.wrap(out.bytes().toBytesRef().bytes);
            in.setVersion(ver);
            IndexRequest serialized = new IndexRequest(in);
            assertThat(serialized.getDynamicTemplates(), anEmptyMap());
        }
        // old version
        {
            Map<String, String> dynamicTemplates = IntStream.range(0, randomIntBetween(1, 10))
                .boxed()
                .collect(Collectors.toMap(n -> "field-" + n, n -> "name-" + n));
            indexRequest.setDynamicTemplates(dynamicTemplates);
            Version ver = VersionUtils.randomVersionBetween(random(), Version.V_7_0_0, VersionUtils.getPreviousVersion(Version.V_7_13_0));
            BytesStreamOutput out = new BytesStreamOutput();
            out.setVersion(ver);
            IllegalArgumentException error = expectThrows(IllegalArgumentException.class, () -> indexRequest.writeTo(out));
            assertThat(
                error.getMessage(),
                equalTo("[dynamic_templates] parameter requires all nodes on " + Version.V_7_13_0 + " or later")
            );
        }
        // new version
        {
            Map<String, String> dynamicTemplates = IntStream.range(0, randomIntBetween(0, 10))
                .boxed()
                .collect(Collectors.toMap(n -> "field-" + n, n -> "name-" + n));
            indexRequest.setDynamicTemplates(dynamicTemplates);
            Version ver = VersionUtils.randomVersionBetween(random(), Version.V_7_13_0, Version.CURRENT);
            BytesStreamOutput out = new BytesStreamOutput();
            out.setVersion(ver);
            indexRequest.writeTo(out);
            StreamInput in = StreamInput.wrap(out.bytes().toBytesRef().bytes);
            in.setVersion(ver);
            IndexRequest serialized = new IndexRequest(in);
            assertThat(serialized.getDynamicTemplates(), equalTo(dynamicTemplates));
        }
    }

    public void testToStringSizeLimit() throws UnsupportedEncodingException {
        IndexRequest request = new IndexRequest("index");

        String source = "{\"name\":\"value\"}";
        request.source(source, XContentType.JSON);
        assertEquals("index {[index][null], source[" + source + "]}", request.toString());

        source = """
            {"name":"%s"}
            """.formatted(randomUnicodeOfLength(IndexRequest.MAX_SOURCE_LENGTH_IN_TOSTRING));
        request.source(source, XContentType.JSON);
        int actualBytes = source.getBytes("UTF-8").length;
        assertEquals(
            "index {[index][null], source[n/a, actual length: ["
                + new ByteSizeValue(actualBytes).toString()
                + "], max length: "
                + new ByteSizeValue(IndexRequest.MAX_SOURCE_LENGTH_IN_TOSTRING).toString()
                + "]}",
            request.toString()
        );
    }

    public void testRejectsEmptyStringPipeline() {
        IndexRequest request = new IndexRequest("index");
        request.source("{}", XContentType.JSON);
        request.setPipeline("");
        ActionRequestValidationException validate = request.validate();
        assertThat(validate, notNullValue());
        assertThat(validate.getMessage(), containsString("pipeline cannot be an empty string"));
    }

    public void testGetConcreteWriteIndex() {
        Instant currentTime = Instant.now();
        Instant start1 = currentTime.minus(6, ChronoUnit.HOURS);
        Instant end1 = currentTime.minus(2, ChronoUnit.HOURS);
        Instant start2 = currentTime.minus(2, ChronoUnit.HOURS);
        Instant end2 = currentTime.plus(2, ChronoUnit.HOURS);

        String tsdbDataStream = "logs_my-app_prod";
        var clusterState = DataStreamTestHelper.getClusterStateWithDataStream(
            tsdbDataStream,
            List.of(Tuple.tuple(start1, end1), Tuple.tuple(start2, end2))
        );
        var metadata = clusterState.getMetadata();

        String source = """
            {
                "@timestamp": $time
            }""";
        {
            // Not a create request => resolve to the latest backing index
            IndexRequest request = new IndexRequest(tsdbDataStream);
            request.source(renderSource(source, start1), XContentType.JSON);

            var result = request.getConcreteWriteIndex(metadata.getIndicesLookup().get(tsdbDataStream), metadata);
            assertThat(result, equalTo(metadata.dataStreams().get(tsdbDataStream).getIndices().get(1)));
        }
        {
            // Target is a regular index => resolve to this index only
            String indexName = metadata.getIndices().keySet().iterator().next();
            IndexRequest request = new IndexRequest(indexName);
            request.source(renderSource(source, randomFrom(start1, end1, start2, end2)), XContentType.JSON);

            var result = request.getConcreteWriteIndex(metadata.getIndicesLookup().get(indexName), metadata);
            assertThat(result.getName(), equalTo(indexName));
        }
        {
            String regularDataStream = "logs_another-app_prod";
            var backingIndex1 = DataStreamTestHelper.createBackingIndex(regularDataStream, 1).build();
            var backingIndex2 = DataStreamTestHelper.createBackingIndex(regularDataStream, 2).build();
            var metadata2 = Metadata.builder(metadata)
                .put(backingIndex1, true)
                .put(backingIndex2, true)
                .put(
                    new DataStream(
                        regularDataStream,
                        new DataStream.TimestampField("@timestamp"),
                        List.of(backingIndex1.getIndex(), backingIndex2.getIndex()),
                        2,
                        null
                    )
                )
                .build();
            // Target is a regular data stream => always resolve to the latest backing index
            IndexRequest request = new IndexRequest(regularDataStream);
            request.source(renderSource(source, randomFrom(start1, end1, start2, end2)), XContentType.JSON);

            var result = request.getConcreteWriteIndex(metadata2.getIndicesLookup().get(regularDataStream), metadata2);
            assertThat(result.getName(), equalTo(backingIndex2.getIndex().getName()));
        }
        {
            // provided timestamp resolves to the first backing index
            IndexRequest request = new IndexRequest(tsdbDataStream);
            request.opType(DocWriteRequest.OpType.CREATE);
            request.source(renderSource(source, start1), XContentType.JSON);

            var result = request.getConcreteWriteIndex(metadata.getIndicesLookup().get(tsdbDataStream), metadata);
            assertThat(result, equalTo(metadata.dataStreams().get(tsdbDataStream).getIndices().get(0)));
        }
        {
            // provided timestamp as millis since epoch resolves to the first backing index
            IndexRequest request = new IndexRequest(tsdbDataStream);
            request.opType(DocWriteRequest.OpType.CREATE);
            request.source(source.replace("$time", "" + start1.toEpochMilli()), XContentType.JSON);

            var result = request.getConcreteWriteIndex(metadata.getIndicesLookup().get(tsdbDataStream), metadata);
            assertThat(result, equalTo(metadata.dataStreams().get(tsdbDataStream).getIndices().get(0)));
        }
        {
            // provided timestamp resolves to the latest backing index
            IndexRequest request = new IndexRequest(tsdbDataStream);
            request.opType(DocWriteRequest.OpType.CREATE);
            request.source(renderSource(source, start2), XContentType.JSON);

            var result = request.getConcreteWriteIndex(metadata.getIndicesLookup().get(tsdbDataStream), metadata);
            assertThat(result, equalTo(metadata.dataStreams().get(tsdbDataStream).getIndices().get(1)));
        }
        {
            // provided timestamp resolves to no index => fail with an exception
            IndexRequest request = new IndexRequest(tsdbDataStream);
            request.opType(DocWriteRequest.OpType.CREATE);
            request.source(renderSource(source, end2), XContentType.JSON);

            var e = expectThrows(
                IllegalArgumentException.class,
                () -> request.getConcreteWriteIndex(metadata.getIndicesLookup().get(tsdbDataStream), metadata)
            );
            assertThat(
                e.getMessage(),
                equalTo(
                    "the document timestamp [$time] is outside of ranges of currently writable indices [[$start1,$end1][$start2,$end2]]"
                        .replace("$time", formatInstant(end2))
                        .replace("$start1", formatInstant(start1))
                        .replace("$end1", formatInstant(end1))
                        .replace("$start2", formatInstant(start2))
                        .replace("$end2", formatInstant(end2))
                )
            );
        }
    }

    static String renderSource(String sourceTemplate, Instant instant) {
        return sourceTemplate.replace("$time", "\"" + formatInstant(instant) + "\"");
    }

    static String formatInstant(Instant instant) {
        return DateFormatter.forPattern(FormatNames.STRICT_DATE_OPTIONAL_TIME.getName()).format(instant);
    }
}
