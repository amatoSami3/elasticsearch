/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.indexlifecycle;

import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.xpack.ccr.ESCCRRestTestCase;
import org.elasticsearch.xpack.core.indexlifecycle.LifecycleAction;
import org.elasticsearch.xpack.core.indexlifecycle.LifecyclePolicy;
import org.elasticsearch.xpack.core.indexlifecycle.Phase;
import org.elasticsearch.xpack.core.indexlifecycle.UnfollowAction;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.singletonMap;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class CCRIndexLifecycleIT extends ESCCRRestTestCase {

    private static final Logger LOGGER = LogManager.getLogger(CCRIndexLifecycleIT.class);

    public void testBasicCCRAndILMIntegration() throws Exception {
        String indexName = "logs-1";

        String policyName = "basic-test";
        if ("leader".equals(targetCluster)) {
            putILMPolicy(policyName, "50GB", null, TimeValue.timeValueHours(7*24));
            Settings indexSettings = Settings.builder()
                .put("index.soft_deletes.enabled", true)
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", 0)
                .put("index.lifecycle.name", policyName)
                .put("index.lifecycle.rollover_alias", "logs")
                .build();
            createIndex(indexName, indexSettings, "", "\"logs\": { }");
            ensureGreen(indexName);
        } else if ("follow".equals(targetCluster)) {
            // Policy with the same name must exist in follower cluster too:
            putILMPolicy(policyName, "50GB", null, TimeValue.timeValueHours(7*24));
            followIndex(indexName, indexName);
            // Aliases are not copied from leader index, so we need to add that for the rollover action in follower cluster:
            client().performRequest(new Request("PUT", "/" + indexName + "/_alias/logs"));

            try (RestClient leaderClient = buildLeaderClient()) {
                index(leaderClient, indexName, "1");
                assertDocumentExists(leaderClient, indexName, "1");

                assertBusy(() -> {
                    assertDocumentExists(client(), indexName, "1");
                    // Sanity check that following_index setting has been set, so that we can verify later that this setting has been unset:
                    assertThat(getIndexSetting(client(), indexName, "index.xpack.ccr.following_index"), equalTo("true"));

                    assertILMPolicy(leaderClient, indexName, policyName, "hot");
                    assertILMPolicy(client(), indexName, policyName, "hot");
                });

                updateIndexSettings(leaderClient, indexName, Settings.builder()
                    .put("index.lifecycle.indexing_complete", true)
                    .build()
                );

                assertBusy(() -> {
                    // Ensure that 'index.lifecycle.indexing_complete' is replicated:
                    assertThat(getIndexSetting(leaderClient, indexName, "index.lifecycle.indexing_complete"), equalTo("true"));
                    assertThat(getIndexSetting(client(), indexName, "index.lifecycle.indexing_complete"), equalTo("true"));

                    assertILMPolicy(leaderClient, indexName, policyName, "warm");
                    assertILMPolicy(client(), indexName, policyName, "warm");

                    // ILM should have placed both indices in the warm phase and there these indices are read-only:
                    assertThat(getIndexSetting(leaderClient, indexName, "index.blocks.write"), equalTo("true"));
                    assertThat(getIndexSetting(client(), indexName, "index.blocks.write"), equalTo("true"));
                    // ILM should have unfollowed the follower index, so the following_index setting should have been removed:
                    // (this controls whether the follow engine is used)
                    assertThat(getIndexSetting(client(), indexName, "index.xpack.ccr.following_index"), nullValue());
                });
            }
        } else {
            fail("unexpected target cluster [" + targetCluster + "]");
        }
    }

    public void testCCRUnfollowDuringSnapshot() throws Exception {
        String indexName = "unfollow-test-index";
        if ("leader".equals(targetCluster)) {
            Settings indexSettings = Settings.builder()
                .put("index.soft_deletes.enabled", true)
                .put("index.number_of_shards", 2)
                .put("index.number_of_replicas", 0)
                .build();
            createIndex(indexName, indexSettings);
            ensureGreen(indexName);
        } else if ("follow".equals(targetCluster)) {
            createNewSingletonPolicy("unfollow-only", "hot", new UnfollowAction(), TimeValue.ZERO);
            followIndex(indexName, indexName);

            // Create the repository before taking the snapshot.
            Request request = new Request("PUT", "/_snapshot/repo");
            request.setJsonEntity(Strings
                .toString(JsonXContent.contentBuilder()
                    .startObject()
                    .field("type", "fs")
                    .startObject("settings")
                    .field("compress", randomBoolean())
                    .field("location", System.getProperty("tests.path.repo"))
                    .field("max_snapshot_bytes_per_sec", "256b")
                    .endObject()
                    .endObject()));
            assertOK(client().performRequest(request));

            try (RestClient leaderClient = buildLeaderClient()) {
                index(leaderClient, indexName, "1");
                assertDocumentExists(leaderClient, indexName, "1");

                updateIndexSettings(leaderClient, indexName, Settings.builder()
                    .put("index.lifecycle.indexing_complete", true)
                    .build());

                // start snapshot
                request = new Request("PUT", "/_snapshot/repo/snapshot");
                request.addParameter("wait_for_completion", "false");
                request.setJsonEntity("{\"indices\": \"" + indexName + "\"}");
                assertOK(client().performRequest(request));

                // add policy and expect it to trigger unfollow immediately (while snapshot in progress)
                logger.info("--> starting unfollow");
                updatePolicy(indexName, "unfollow-only");

                assertBusy(() -> {
                    // Ensure that 'index.lifecycle.indexing_complete' is replicated:
                    assertThat(getIndexSetting(leaderClient, indexName, "index.lifecycle.indexing_complete"), equalTo("true"));
                    assertThat(getIndexSetting(client(), indexName, "index.lifecycle.indexing_complete"), equalTo("true"));
                    // ILM should have unfollowed the follower index, so the following_index setting should have been removed:
                    // (this controls whether the follow engine is used)
                    assertThat(getIndexSetting(client(), indexName, "index.xpack.ccr.following_index"), nullValue());
                    // Following index should have the document
                    assertDocumentExists(client(), indexName, "1");
                    // ILM should have completed the unfollow
                    assertILMPolicy(client(), indexName, "unfollow-only", "completed");
                }, 2, TimeUnit.MINUTES);

                // assert that snapshot succeeded
                assertThat(getSnapshotState("snapshot"), equalTo("SUCCESS"));
                assertOK(client().performRequest(new Request("DELETE", "/_snapshot/repo/snapshot")));
                ResponseException e = expectThrows(ResponseException.class,
                    () -> client().performRequest(new Request("GET", "/_snapshot/repo/snapshot")));
                assertThat(e.getResponse().getStatusLine().getStatusCode(), equalTo(404));
            }
        } else {
            fail("unexpected target cluster [" + targetCluster + "]");
        }
    }

    public void testCcrAndIlmWithRollover() throws Exception {
        String alias = "metrics";
        String indexName = "metrics-000001";
        String nextIndexName = "metrics-000002";
        String policyName = "rollover-test";

        if ("leader".equals(targetCluster)) {
            // Create a policy on the leader
            putILMPolicy(policyName, null, 1, null);
            Request templateRequest = new Request("PUT", "_template/my_template");
            Settings indexSettings = Settings.builder()
                .put("index.soft_deletes.enabled", true)
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", 0)
                .put("index.lifecycle.name", policyName)
                .put("index.lifecycle.rollover_alias", alias)
                .build();
            templateRequest.setJsonEntity("{\"index_patterns\":  [\"metrics-*\"], \"settings\":  " + Strings.toString(indexSettings) + "}");
            assertOK(client().performRequest(templateRequest));
        } else if ("follow".equals(targetCluster)) {
            // Policy with the same name must exist in follower cluster too:
            putILMPolicy(policyName, null, 1, null);

            // Set up an auto-follow pattern
            Request createAutoFollowRequest = new Request("PUT", "/_ccr/auto_follow/my_auto_follow_pattern");
            createAutoFollowRequest.setJsonEntity("{\"leader_index_patterns\": [\"metrics-*\"], " +
                "\"remote_cluster\": \"leader_cluster\", \"read_poll_timeout\": \"1000ms\"}");
            assertOK(client().performRequest(createAutoFollowRequest));

            try (RestClient leaderClient = buildLeaderClient()) {
                // Create an index on the leader using the template set up above
                Request createIndexRequest = new Request("PUT", "/" + indexName);
                createIndexRequest.setJsonEntity("{" +
                    "\"mappings\": {\"_doc\": {\"properties\": {\"field\": {\"type\": \"keyword\"}}}}, " +
                    "\"aliases\": {\"" + alias + "\":  {\"is_write_index\":  true}} }");
                assertOK(leaderClient.performRequest(createIndexRequest));
                // Check that the new index is creeg
                Request checkIndexRequest = new Request("GET", "/_cluster/health/" + indexName);
                checkIndexRequest.addParameter("wait_for_status", "green");
                checkIndexRequest.addParameter("timeout", "70s");
                checkIndexRequest.addParameter("level", "shards");
                assertOK(leaderClient.performRequest(checkIndexRequest));

                // Check that it got replicated to the follower
                assertBusy(() -> assertTrue(indexExists(indexName)));

                // Aliases are not copied from leader index, so we need to add that for the rollover action in follower cluster:
                client().performRequest(new Request("PUT", "/" + indexName + "/_alias/" + alias));

                index(leaderClient, indexName, "1");
                assertDocumentExists(leaderClient, indexName, "1");

                assertBusy(() -> {
                    assertDocumentExists(client(), indexName, "1");
                    // Sanity check that following_index setting has been set, so that we can verify later that this setting has been unset:
                    assertThat(getIndexSetting(client(), indexName, "index.xpack.ccr.following_index"), equalTo("true"));
                });

                // Wait for the index to roll over on the leader
                assertBusy(() -> {
                    assertOK(leaderClient.performRequest(new Request("HEAD", "/" + nextIndexName)));
                    assertThat(getIndexSetting(leaderClient, indexName, "index.lifecycle.indexing_complete"), equalTo("true"));

                });

                assertBusy(() -> {
                    // Wait for the next index should have been created on the leader
                    assertOK(leaderClient.performRequest(new Request("HEAD", "/" + nextIndexName)));
                    // And the old index should have a write block and indexing complete set
                    assertThat(getIndexSetting(leaderClient, indexName, "index.blocks.write"), equalTo("true"));
                    assertThat(getIndexSetting(leaderClient, indexName, "index.lifecycle.indexing_complete"), equalTo("true"));

                });

                assertBusy(() -> {
                    // Wait for the setting to get replicated to the follower
                    assertThat(getIndexSetting(client(), indexName, "index.lifecycle.indexing_complete"), equalTo("true"));
                });

                assertBusy(() -> {
                    // ILM should have unfollowed the follower index, so the following_index setting should have been removed:
                    // (this controls whether the follow engine is used)
                    assertThat(getIndexSetting(client(), indexName, "index.xpack.ccr.following_index"), nullValue());
                    // The next index should have been created on the follower as well
                    indexExists(nextIndexName);
                });

                assertBusy(() -> {
                    // And the previously-follower index should be in the warm phase
                    assertILMPolicy(client(), indexName, policyName, "warm");
                });

                // Clean up
                leaderClient.performRequest(new Request("DELETE", "/_template/my_template"));
            }
        } else {
            fail("unexpected target cluster [" + targetCluster + "]");
        }
    }

    private static void putILMPolicy(String name, String maxSize, Integer maxDocs, TimeValue maxAge) throws IOException {
        final Request request = new Request("PUT", "_ilm/policy/" + name);
        XContentBuilder builder = jsonBuilder();
        builder.startObject();
        {
            builder.startObject("policy");
            {
                builder.startObject("phases");
                {
                    builder.startObject("hot");
                    {
                        builder.startObject("actions");
                        {
                            builder.startObject("rollover");
                            if (maxSize != null) {
                                builder.field("max_size", maxSize);
                            }
                            if (maxAge != null) {
                                builder.field("max_age", maxAge);
                            }
                            if (maxDocs != null) {
                                builder.field("max_docs", maxDocs);
                            }
                            builder.endObject();
                        }
                        {
                            builder.startObject("unfollow");
                            builder.endObject();
                        }
                        builder.endObject();
                    }
                    builder.endObject();
                    builder.startObject("warm");
                    {
                        builder.startObject("actions");
                        {
                            builder.startObject("readonly");
                            builder.endObject();
                        }
                        builder.endObject();
                    }
                    builder.endObject();
                    builder.startObject("delete");
                    {
                        builder.field("min_age", "7d");
                        builder.startObject("actions");
                        {
                            builder.startObject("delete");
                            builder.endObject();
                        }
                        builder.endObject();
                    }
                    builder.endObject();
                }
                builder.endObject();
            }
            builder.endObject();
        }
        builder.endObject();
        request.setJsonEntity(Strings.toString(builder));
        assertOK(client().performRequest(request));
    }

    private static void assertILMPolicy(RestClient client, String index, String policy, String expectedPhase) throws IOException {
        final Request request = new Request("GET", "/" + index + "/_ilm/explain");
        Map<String, Object> response = toMap(client.performRequest(request));
        LOGGER.info("response={}", response);
        Map<?, ?> explanation = (Map<?, ?>) ((Map<?, ?>) response.get("indices")).get(index);
        assertThat(explanation.get("managed"), is(true));
        assertThat(explanation.get("policy"), equalTo(policy));
        assertThat(explanation.get("phase"), equalTo(expectedPhase));
    }

    private static void updateIndexSettings(RestClient client, String index, Settings settings) throws IOException {
        final Request request = new Request("PUT", "/" + index + "/_settings");
        request.setJsonEntity(Strings.toString(settings));
        assertOK(client.performRequest(request));
    }

    private static Object getIndexSetting(RestClient client, String index, String setting) throws IOException {
        Request request = new Request("GET", "/" + index + "/_settings");
        request.addParameter("flat_settings", "true");
        Map<String, Object> response = toMap(client.performRequest(request));
        Map<?, ?> settings = (Map<?, ?>) ((Map<?, ?>) response.get(index)).get("settings");
        return settings.get(setting);
    }

    private static void assertDocumentExists(RestClient client, String index, String id) throws IOException {
        Request request = new Request("HEAD", "/" + index + "/_doc/" + id);
        Response response = client.performRequest(request);
        assertThat(response.getStatusLine().getStatusCode(), equalTo(200));
    }

    private void createNewSingletonPolicy(String policyName, String phaseName, LifecycleAction action, TimeValue after) throws IOException {
        Phase phase = new Phase(phaseName, after, singletonMap(action.getWriteableName(), action));
        LifecyclePolicy lifecyclePolicy = new LifecyclePolicy(policyName, singletonMap(phase.getName(), phase));
        XContentBuilder builder = jsonBuilder();
        lifecyclePolicy.toXContent(builder, null);
        final StringEntity entity = new StringEntity(
            "{ \"policy\":" + Strings.toString(builder) + "}", ContentType.APPLICATION_JSON);
        Request request = new Request("PUT", "_ilm/policy/" + policyName);
        request.setEntity(entity);
        client().performRequest(request);
    }

    public static void updatePolicy(String indexName, String policy) throws IOException {

        Request changePolicyRequest = new Request("PUT", "/" + indexName + "/_settings");
        final StringEntity changePolicyEntity = new StringEntity("{ \"index.lifecycle.name\": \"" + policy + "\" }",
            ContentType.APPLICATION_JSON);
        changePolicyRequest.setEntity(changePolicyEntity);
        assertOK(client().performRequest(changePolicyRequest));
    }

    private String getSnapshotState(String snapshot) throws IOException {
        Response response = client().performRequest(new Request("GET", "/_snapshot/repo/" + snapshot));
        Map<String, Object> responseMap;
        try (InputStream is = response.getEntity().getContent()) {
            responseMap = XContentHelper.convertToMap(XContentType.JSON.xContent(), is, true);
        }
        @SuppressWarnings("unchecked") Map<String, Object> snapResponse = ((List<Map<String, Object>>) responseMap.get("snapshots")).get(0);
        assertThat(snapResponse.get("snapshot"), equalTo(snapshot));
        return (String) snapResponse.get("state");
    }
}
