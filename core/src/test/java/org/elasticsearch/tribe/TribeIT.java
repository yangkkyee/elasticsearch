/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.tribe;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.support.DestructiveOperations;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.discovery.DiscoveryModule;
import org.elasticsearch.discovery.DiscoverySettings;
import org.elasticsearch.discovery.MasterNotDiscoveredException;
import org.elasticsearch.discovery.zen.ping.unicast.UnicastZenPing;
import org.elasticsearch.node.Node;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.InternalTestCluster;
import org.elasticsearch.test.NodeConfigurationSource;
import org.elasticsearch.transport.Transport;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.stream.Collectors.toSet;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;

/**
 * Note, when talking to tribe client, no need to set the local flag on master read operations, it
 * does it by default.
 */
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.SUITE, numDataNodes = 0, numClientNodes = 0, transportClientRatio = 0.0)
public class TribeIT extends ESIntegTestCase {

    private static final String TRIBE_NODE = "tribe_node";

    private static InternalTestCluster cluster1;
    private static InternalTestCluster cluster2;

    /**
     * A predicate that is used to select none of the remote clusters
     **/
    private static final Predicate<InternalTestCluster> NONE = c -> false;

    /**
     * A predicate that is used to select the remote cluster 1 only
     **/
    private static final Predicate<InternalTestCluster> CLUSTER1_ONLY = c -> c.getClusterName().equals(cluster1.getClusterName());

    /**
     * A predicate that is used to select the remote cluster 2 only
     **/
    private static final Predicate<InternalTestCluster> CLUSTER2_ONLY = c -> c.getClusterName().equals(cluster2.getClusterName());

    /**
     * A predicate that is used to select the the two remote clusters
     **/
    private static final Predicate<InternalTestCluster> ALL = c -> true;

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                // Required to delete _all indices on remote clusters
                .put(DestructiveOperations.REQUIRES_NAME_SETTING.getKey(), false)
                .build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return getMockPlugins();
    }

    @Before
    public void startRemoteClusters() {
        final int minNumDataNodes = 2;
        final int maxNumDataNodes = 4;
        final NodeConfigurationSource nodeConfigurationSource = getNodeConfigSource();
        final Collection<Class<? extends Plugin>> plugins = nodePlugins();

        if (cluster1 == null) {
            cluster1 = new InternalTestCluster(randomLong(), createTempDir(), true, minNumDataNodes, maxNumDataNodes,
                    UUIDs.randomBase64UUID(random()), nodeConfigurationSource, 0, false, "cluster_1",
                    plugins, Function.identity());
        }

        if (cluster2 == null) {
            cluster2 = new InternalTestCluster(randomLong(), createTempDir(), true, minNumDataNodes, maxNumDataNodes,
                    UUIDs.randomBase64UUID(random()), nodeConfigurationSource, 0, false, "cluster_2",
                    plugins, Function.identity());
        }

        doWithAllClusters(c -> {
            try {
                c.beforeTest(random(), 0.1);
                c.ensureAtLeastNumDataNodes(minNumDataNodes);
            } catch (Exception e) {
                throw new RuntimeException("Failed to set up remote cluster [" + c.getClusterName() + "]", e);
            }
        });
    }

    @After
    public void wipeRemoteClusters() {
        doWithAllClusters(c -> {
            final String clusterName = c.getClusterName();
            try {
                c.client().admin().indices().prepareDelete(MetaData.ALL).get();
                c.afterTest();
            } catch (IOException e) {
                throw new RuntimeException("Failed to clean up remote cluster [" + clusterName + "]", e);
            }
        });
    }

    @AfterClass
    public static void stopRemoteClusters() {
        try {
            doWithAllClusters(InternalTestCluster::close);
        } finally {
            cluster1 = null;
            cluster2 = null;
        }
    }

    private Releasable startTribeNode() throws Exception {
        return startTribeNode(ALL, Settings.EMPTY);
    }

    private Releasable startTribeNode(Predicate<InternalTestCluster> filter, Settings settings) throws Exception {
        final String node = internalCluster().startNode(createTribeSettings(filter).put(settings).build());
        return () -> {
            try {
                while(internalCluster().getNodeNames().length > 0) {
                    internalCluster().stopRandomNode(s -> true);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to close tribe node [" + node + "]", e);
            }
        };
    }

    private Settings.Builder createTribeSettings(Predicate<InternalTestCluster> filter) {
        assertNotNull(filter);

        final Settings.Builder settings = Settings.builder();
        settings.put(Node.NODE_NAME_SETTING.getKey(), TRIBE_NODE);
        settings.put(Node.NODE_DATA_SETTING.getKey(), false);
        settings.put(Node.NODE_MASTER_SETTING.getKey(), true);
        settings.put(NetworkModule.HTTP_ENABLED.getKey(), false);
        settings.put(NetworkModule.TRANSPORT_TYPE_SETTING.getKey(), NetworkModule.LOCAL_TRANSPORT);
        settings.put(DiscoveryModule.DISCOVERY_TYPE_SETTING.getKey(), NetworkModule.LOCAL_TRANSPORT);

        doWithAllClusters(filter, c -> {
            String tribeSetting = "tribe." + c.getClusterName() + ".";
            settings.put(tribeSetting + ClusterName.CLUSTER_NAME_SETTING.getKey(), c.getClusterName());
            settings.put(tribeSetting + DiscoverySettings.INITIAL_STATE_TIMEOUT_SETTING.getKey(), "100ms");
            settings.put(tribeSetting + NetworkModule.TRANSPORT_TYPE_SETTING.getKey(), NetworkModule.LOCAL_TRANSPORT);
            settings.put(tribeSetting + DiscoveryModule.DISCOVERY_TYPE_SETTING.getKey(), NetworkModule.LOCAL_TRANSPORT);

            Set<String> hosts = new HashSet<>();
            for (Transport transport : c.getInstances(Transport.class)) {
                TransportAddress address = transport.boundAddress().publishAddress();
                hosts.add(address.getHost() + ":" + address.getPort());
            }
            settings.putArray(tribeSetting + UnicastZenPing.DISCOVERY_ZEN_PING_UNICAST_HOSTS_SETTING.getKey(),
                    hosts.toArray(new String[hosts.size()]));
        });

        return settings;
    }

    public void testGlobalReadWriteBlocks() throws Exception {
        Settings additionalSettings = Settings.builder()
                .put("tribe.blocks.write", true)
                .put("tribe.blocks.metadata", true)
                .build();

        try (Releasable tribeNode = startTribeNode(ALL, additionalSettings)) {
            // Creates 2 indices, test1 on cluster1 and test2 on cluster2
            assertAcked(cluster1.client().admin().indices().prepareCreate("test1"));
            ensureGreen(cluster1.client());

            assertAcked(cluster2.client().admin().indices().prepareCreate("test2"));
            ensureGreen(cluster2.client());

            // Wait for the tribe node to connect to the two remote clusters
            assertNodes(ALL);

            // Wait for the tribe node to retrieve the indices into its cluster state
            assertIndicesExist(client(), "test1", "test2");

            // Writes not allowed through the tribe node
            ClusterBlockException e = expectThrows(ClusterBlockException.class, () -> {
                client().prepareIndex("test1", "type1").setSource("field", "value").get();
            });
            assertThat(e.getMessage(), containsString("blocked by: [BAD_REQUEST/11/tribe node, write not allowed]"));

            e = expectThrows(ClusterBlockException.class, () -> client().prepareIndex("test2", "type2").setSource("field", "value").get());
            assertThat(e.getMessage(), containsString("blocked by: [BAD_REQUEST/11/tribe node, write not allowed]"));

            e = expectThrows(ClusterBlockException.class, () -> client().admin().indices().prepareForceMerge("test1").get());
            assertThat(e.getMessage(), containsString("blocked by: [BAD_REQUEST/10/tribe node, metadata not allowed]"));

            e = expectThrows(ClusterBlockException.class, () -> client().admin().indices().prepareForceMerge("test2").get());
            assertThat(e.getMessage(), containsString("blocked by: [BAD_REQUEST/10/tribe node, metadata not allowed]"));
        }
    }

    public void testIndexWriteBlocks() throws Exception {
        Settings additionalSettings = Settings.builder()
                .put("tribe.blocks.write.indices", "block_*")
                .build();

        try (Releasable tribeNode = startTribeNode(ALL, additionalSettings)) {
            // Creates 2 indices on each remote cluster, test1 and block_test1 on cluster1 and test2 and block_test2 on cluster2
            assertAcked(cluster1.client().admin().indices().prepareCreate("test1"));
            assertAcked(cluster1.client().admin().indices().prepareCreate("block_test1"));
            ensureGreen(cluster1.client());

            assertAcked(cluster2.client().admin().indices().prepareCreate("test2"));
            assertAcked(cluster2.client().admin().indices().prepareCreate("block_test2"));
            ensureGreen(cluster2.client());

            // Wait for the tribe node to connect to the two remote clusters
            assertNodes(ALL);

            // Wait for the tribe node to retrieve the indices into its cluster state
            assertIndicesExist(client(), "test1", "test2", "block_test1", "block_test2");

            // Writes allowed through the tribe node for test1/test2 indices
            client().prepareIndex("test1", "type1").setSource("field", "value").get();
            client().prepareIndex("test2", "type2").setSource("field", "value").get();

            ClusterBlockException e;
            e = expectThrows(ClusterBlockException.class, () -> client().prepareIndex("block_test1", "type1").setSource("foo", 0).get());
            assertThat(e.getMessage(), containsString("blocked by: [FORBIDDEN/8/index write (api)]"));

            e = expectThrows(ClusterBlockException.class, () -> client().prepareIndex("block_test2", "type2").setSource("foo", 0).get());
            assertThat(e.getMessage(), containsString("blocked by: [FORBIDDEN/8/index write (api)]"));
        }
    }

    public void testOnConflictDrop() throws Exception {
        Settings additionalSettings = Settings.builder()
                .put("tribe.on_conflict", "drop")
                .build();

        try (Releasable tribeNode = startTribeNode(ALL, additionalSettings)) {
            // Creates 2 indices on each remote cluster, test1 and conflict on cluster1 and test2 and also conflict on cluster2
            assertAcked(cluster1.client().admin().indices().prepareCreate("test1"));
            assertAcked(cluster1.client().admin().indices().prepareCreate("conflict"));
            ensureGreen(cluster1.client());

            assertAcked(cluster2.client().admin().indices().prepareCreate("test2"));
            assertAcked(cluster2.client().admin().indices().prepareCreate("conflict"));
            ensureGreen(cluster2.client());

            // Wait for the tribe node to connect to the two remote clusters
            assertNodes(ALL);

            // Wait for the tribe node to retrieve the indices into its cluster state
            assertIndicesExist(client(), "test1", "test2");

            ClusterState clusterState = client().admin().cluster().prepareState().get().getState();
            assertThat(clusterState.getMetaData().hasIndex("test1"), is(true));
            assertThat(clusterState.getMetaData().index("test1").getSettings().get("tribe.name"), equalTo(cluster1.getClusterName()));
            assertThat(clusterState.getMetaData().hasIndex("test2"), is(true));
            assertThat(clusterState.getMetaData().index("test2").getSettings().get("tribe.name"), equalTo(cluster2.getClusterName()));
            assertThat(clusterState.getMetaData().hasIndex("conflict"), is(false));
        }
    }

    public void testOnConflictPrefer() throws Exception {
        final String preference = randomFrom(cluster1, cluster2).getClusterName();
        Settings additionalSettings = Settings.builder()
                .put("tribe.on_conflict", "prefer_" + preference)
                .build();

        try (Releasable tribeNode = startTribeNode(ALL, additionalSettings)) {
            assertAcked(cluster1.client().admin().indices().prepareCreate("test1"));
            assertAcked(cluster1.client().admin().indices().prepareCreate("shared"));
            ensureGreen(cluster1.client());

            assertAcked(cluster2.client().admin().indices().prepareCreate("test2"));
            assertAcked(cluster2.client().admin().indices().prepareCreate("shared"));
            ensureGreen(cluster2.client());

            // Wait for the tribe node to connect to the two remote clusters
            assertNodes(ALL);

            // Wait for the tribe node to retrieve the indices into its cluster state
            assertIndicesExist(client(), "test1", "test2", "shared");

            ClusterState clusterState = client().admin().cluster().prepareState().get().getState();
            assertThat(clusterState.getMetaData().hasIndex("test1"), is(true));
            assertThat(clusterState.getMetaData().index("test1").getSettings().get("tribe.name"), equalTo(cluster1.getClusterName()));
            assertThat(clusterState.getMetaData().hasIndex("test2"), is(true));
            assertThat(clusterState.getMetaData().index("test2").getSettings().get("tribe.name"), equalTo(cluster2.getClusterName()));
            assertThat(clusterState.getMetaData().hasIndex("shared"), is(true));
            assertThat(clusterState.getMetaData().index("shared").getSettings().get("tribe.name"), equalTo(preference));
        }
    }

    public void testTribeOnOneCluster() throws Exception {
        try (Releasable tribeNode = startTribeNode()) {
            // Creates 2 indices, test1 on cluster1 and test2 on cluster2
            assertAcked(cluster1.client().admin().indices().prepareCreate("test1"));
            ensureGreen(cluster1.client());

            assertAcked(cluster2.client().admin().indices().prepareCreate("test2"));
            ensureGreen(cluster2.client());

            // Wait for the tribe node to connect to the two remote clusters
            assertNodes(ALL);

            // Wait for the tribe node to retrieve the indices into its cluster state
            assertIndicesExist(client(), "test1", "test2");

            // Creates two docs using the tribe node
            indexRandom(true,
                    client().prepareIndex("test1", "type1", "1").setSource("field1", "value1"),
                    client().prepareIndex("test2", "type1", "1").setSource("field1", "value1")
            );

            // Verify that documents are searchable using the tribe node
            assertHitCount(client().prepareSearch().get(), 2L);

            // Using assertBusy to check that the mappings are in the tribe node cluster state
            assertBusy(() -> {
                ClusterState clusterState = client().admin().cluster().prepareState().get().getState();
                assertThat(clusterState.getMetaData().index("test1").mapping("type1"), notNullValue());
                assertThat(clusterState.getMetaData().index("test2").mapping("type1"), notNullValue());
            });

            // More documents with another type
            indexRandom(true,
                    client().prepareIndex("test1", "type2", "1").setSource("field1", "value1"),
                    client().prepareIndex("test2", "type2", "1").setSource("field1", "value1")
            );
            assertHitCount(client().prepareSearch().get(), 4L);
            assertBusy(() -> {
                ClusterState clusterState = client().admin().cluster().prepareState().get().getState();
                assertThat(clusterState.getMetaData().index("test1").mapping("type1"), notNullValue());
                assertThat(clusterState.getMetaData().index("test1").mapping("type2"), notNullValue());

                assertThat(clusterState.getMetaData().index("test2").mapping("type1"), notNullValue());
                assertThat(clusterState.getMetaData().index("test2").mapping("type2"), notNullValue());
            });

            // Make sure master level write operations fail... (we don't really have a master)
            expectThrows(MasterNotDiscoveredException.class, () -> {
                client().admin().indices().prepareCreate("tribe_index").setMasterNodeTimeout("10ms").get();
            });

            // Now delete an index and makes sure it's reflected in cluster state
            cluster2.client().admin().indices().prepareDelete("test2").get();
            assertBusy(() -> {
                ClusterState clusterState = client().admin().cluster().prepareState().get().getState();
                assertFalse(clusterState.getMetaData().hasIndex("test2"));
                assertFalse(clusterState.getRoutingTable().hasIndex("test2"));
            });
        }
    }

    public void testCloseAndOpenIndex() throws Exception {
        // Creates an index on remote cluster 1
        assertTrue(cluster1.client().admin().indices().prepareCreate("first").get().isAcknowledged());
        ensureGreen(cluster1.client());

        // Closes the index
        assertTrue(cluster1.client().admin().indices().prepareClose("first").get().isAcknowledged());

        try (Releasable tribeNode = startTribeNode()) {
            // Wait for the tribe node to connect to the two remote clusters
            assertNodes(ALL);

            // The closed index is not part of the tribe node cluster state
            ClusterState clusterState = client().admin().cluster().prepareState().get().getState();
            assertFalse(clusterState.getMetaData().hasIndex("first"));

            // Open the index, it becomes part of the tribe node cluster state
            assertTrue(cluster1.client().admin().indices().prepareOpen("first").get().isAcknowledged());
            assertIndicesExist(client(), "first");

            // Create a second index, wait till it is seen from within the tribe node
            assertTrue(cluster2.client().admin().indices().prepareCreate("second").get().isAcknowledged());
            assertIndicesExist(client(), "first", "second");
            ensureGreen(cluster2.client());

            // Close the second index, wait till it gets removed from the tribe node cluster state
            assertTrue(cluster2.client().admin().indices().prepareClose("second").get().isAcknowledged());
            assertIndicesExist(client(), "first");

            // Open the second index, wait till it gets added back to the tribe node cluster state
            assertTrue(cluster2.client().admin().indices().prepareOpen("second").get().isAcknowledged());
            assertIndicesExist(client(), "first", "second");
            ensureGreen(cluster2.client());
        }
    }

    /**
     * Test that the tribe node's cluster state correctly reflect the number of nodes
     * of the remote clusters the tribe node is connected to.
     */
    public void testClusterStateNodes() throws Exception {
        List<Predicate<InternalTestCluster>> predicates = Arrays.asList(NONE, CLUSTER1_ONLY, CLUSTER2_ONLY, ALL);
        Collections.shuffle(predicates, random());

        for (Predicate<InternalTestCluster> predicate : predicates) {
            try (Releasable tribeNode = startTribeNode(predicate, Settings.EMPTY)) {
                assertNodes(predicate);
            }
        }
    }

    private void assertIndicesExist(Client client, String... indices) throws Exception {
        assertBusy(() -> {
            ClusterState state = client.admin().cluster().prepareState().setRoutingTable(true).setMetaData(true).get().getState();
            assertThat(state.getMetaData().getIndices().size(), equalTo(indices.length));
            for (String index : indices) {
                assertTrue(state.getMetaData().hasIndex(index));
                assertTrue(state.getRoutingTable().hasIndex(index));
            }
        });
    }

    private void ensureGreen(Client client) throws Exception {
        assertBusy(() -> {
            ClusterHealthResponse clusterHealthResponse = client.admin().cluster() .prepareHealth()
                    .setWaitForActiveShards(0)
                    .setWaitForEvents(Priority.LANGUID)
                    .setWaitForNoRelocatingShards(true)
                    .get();
            assertThat(clusterHealthResponse.getStatus(), equalTo(ClusterHealthStatus.GREEN));
            assertFalse(clusterHealthResponse.isTimedOut());
        });
    }

    private static void assertNodes(Predicate<InternalTestCluster> filter) throws Exception {
        final Set<String> expectedNodes = Sets.newHashSet(internalCluster().getNodeNames());
        doWithAllClusters(filter, c -> {
            // Adds the tribe client node dedicated to this remote cluster
            for (String tribeNode : internalCluster().getNodeNames()) {
                expectedNodes.add(tribeNode + "/" + c.getClusterName());
            }
            // Adds the remote clusters nodes names
            Collections.addAll(expectedNodes, c.getNodeNames());
        });

        assertBusy(() -> {
            ClusterState state = client().admin().cluster().prepareState().setNodes(true).get().getState();
            Set<String> nodes = StreamSupport.stream(state.getNodes().spliterator(), false).map(DiscoveryNode::getName).collect(toSet());
            assertThat(nodes.containsAll(expectedNodes), is(true));
        });
    }

    private static void doWithAllClusters(Consumer<InternalTestCluster> consumer) {
        doWithAllClusters(cluster -> cluster != null, consumer);
    }

    private static void doWithAllClusters(Predicate<InternalTestCluster> predicate, Consumer<InternalTestCluster> consumer) {
        Stream.of(cluster1, cluster2).filter(predicate).forEach(consumer);
    }
}
