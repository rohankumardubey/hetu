/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.metadata;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.discovery.client.ServiceDescriptor;
import io.airlift.discovery.client.ServiceSelector;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.testing.TestingHttpClient;
import io.airlift.http.client.testing.TestingResponse;
import io.airlift.node.NodeConfig;
import io.airlift.node.NodeInfo;
import io.prestosql.client.NodeVersion;
import io.prestosql.failuredetector.NoOpFailureDetector;
import io.prestosql.server.InternalCommunicationConfig;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.annotation.concurrent.GuardedBy;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static io.airlift.discovery.client.ServiceDescriptor.serviceDescriptor;
import static io.airlift.discovery.client.ServiceSelectorConfig.DEFAULT_POOL;
import static io.airlift.http.client.HttpStatus.OK;
import static io.airlift.testing.Assertions.assertEqualsIgnoreOrder;
import static io.prestosql.metadata.NodeState.ACTIVE;
import static io.prestosql.metadata.NodeState.INACTIVE;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotSame;

@Test(singleThreaded = true)
public class TestDiscoveryNodeManager
{
    private final NodeInfo nodeInfo = new NodeInfo("test");
    private final InternalCommunicationConfig internalCommunicationConfig = new InternalCommunicationConfig();
    private NodeVersion expectedVersion;
    private Set<InternalNode> activeNodes;
    private Set<InternalNode> inactiveNodes;
    private InternalNode coordinator;
    private InternalNode currentNode;
    private final PrestoNodeServiceSelector selector = new PrestoNodeServiceSelector();
    private HttpClient testHttpClient;

    @BeforeMethod
    public void setup()
    {
        testHttpClient = new TestingHttpClient(input -> new TestingResponse(OK, ArrayListMultimap.create(), ACTIVE.name().getBytes()));

        expectedVersion = new NodeVersion("1");
        coordinator = new InternalNode(UUID.randomUUID().toString(), URI.create("https://192.0.2.8"), expectedVersion, true);
        currentNode = new InternalNode(nodeInfo.getNodeId(), URI.create("http://192.0.1.1"), expectedVersion, false);

        activeNodes = ImmutableSet.of(
                currentNode,
                new InternalNode(UUID.randomUUID().toString(), URI.create("http://192.0.2.1:8080"), expectedVersion, false),
                new InternalNode(UUID.randomUUID().toString(), URI.create("http://192.0.2.3"), expectedVersion, false),
                coordinator);
        inactiveNodes = ImmutableSet.of(
                new InternalNode(UUID.randomUUID().toString(), URI.create("https://192.0.3.9"), NodeVersion.UNKNOWN, false),
                new InternalNode(UUID.randomUUID().toString(), URI.create("https://192.0.4.9"), new NodeVersion("2"), false));

        selector.announceNodes(activeNodes, inactiveNodes);
    }

    private class MockMetadata
            extends AbstractMockMetadata
    {
    }

    @Test
    public void testGetAllNodes()
    {
        DiscoveryNodeManager manager = new DiscoveryNodeManager(selector, nodeInfo, new NoOpFailureDetector(), expectedVersion, testHttpClient, internalCommunicationConfig, new MockMetadata());
        try {
            AllNodes allNodes = manager.getAllNodes();

            Set<InternalNode> activeNodeSet = allNodes.getActiveNodes();
            assertEqualsIgnoreOrder(activeNodeSet, this.activeNodes);

            for (InternalNode actual : activeNodeSet) {
                for (InternalNode expected : this.activeNodes) {
                    assertNotSame(actual, expected);
                }
            }

            assertEqualsIgnoreOrder(activeNodeSet, manager.getNodes(ACTIVE));

            Set<InternalNode> localInactiveNodes = allNodes.getInactiveNodes();
            assertEqualsIgnoreOrder(localInactiveNodes, this.inactiveNodes);

            for (InternalNode actual : localInactiveNodes) {
                for (InternalNode expected : this.inactiveNodes) {
                    assertNotSame(actual, expected);
                }
            }

            assertEqualsIgnoreOrder(localInactiveNodes, manager.getNodes(INACTIVE));
        }
        finally {
            manager.stop();
        }
    }

    @Test
    public void testGetCurrentNode()
    {
        NodeInfo info = new NodeInfo(new NodeConfig()
                .setEnvironment("test")
                .setNodeId(currentNode.getNodeIdentifier()));

        DiscoveryNodeManager manager = new DiscoveryNodeManager(selector, info, new NoOpFailureDetector(), expectedVersion, testHttpClient, internalCommunicationConfig, new MockMetadata());
        try {
            assertEquals(manager.getCurrentNode(), currentNode);
        }
        finally {
            manager.stop();
        }
    }

    @Test
    public void testGetCoordinators()
    {
        DiscoveryNodeManager manager = new DiscoveryNodeManager(selector, nodeInfo, new NoOpFailureDetector(), expectedVersion, testHttpClient, internalCommunicationConfig, new MockMetadata());
        try {
            assertEquals(manager.getCoordinators(), ImmutableSet.of(coordinator));
        }
        finally {
            manager.stop();
        }
    }

    @SuppressWarnings("ResultOfObjectAllocationIgnored")
    @Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = ".* current node not returned .*")
    public void testGetCurrentNodeRequired()
    {
        new DiscoveryNodeManager(selector, new NodeInfo("test"), new NoOpFailureDetector(), expectedVersion, testHttpClient, internalCommunicationConfig, new MockMetadata());
    }

    @Test(timeOut = 60000)
    public void testNodeChangeListener()
            throws Exception
    {
        DiscoveryNodeManager manager = new DiscoveryNodeManager(selector, nodeInfo, new NoOpFailureDetector(), expectedVersion, testHttpClient, internalCommunicationConfig, new MockMetadata());
        try {
            manager.startPollingNodeStates();

            BlockingQueue<AllNodes> notifications = new ArrayBlockingQueue<>(100);
            manager.addNodeChangeListener(notifications::add);
            AllNodes allNodes = notifications.take();
            assertEquals(allNodes.getActiveNodes(), activeNodes);
            assertEquals(allNodes.getInactiveNodes(), inactiveNodes);

            selector.announceNodes(ImmutableSet.of(currentNode), ImmutableSet.of(coordinator));
            allNodes = notifications.take();
            assertEquals(allNodes.getActiveNodes(), ImmutableSet.of(currentNode, coordinator));
            assertEquals(allNodes.getActiveCoordinators(), ImmutableSet.of(coordinator));

            selector.announceNodes(activeNodes, inactiveNodes);
            allNodes = notifications.take();
            assertEquals(allNodes.getActiveNodes(), activeNodes);
            assertEquals(allNodes.getInactiveNodes(), inactiveNodes);
        }
        finally {
            manager.stop();
        }
    }

    public static class PrestoNodeServiceSelector
            implements ServiceSelector
    {
        @GuardedBy("this")
        private List<ServiceDescriptor> descriptors = ImmutableList.of();

        private synchronized void announceNodes(Set<InternalNode> activeNodes, Set<InternalNode> inactiveNodes)
        {
            ImmutableList.Builder<ServiceDescriptor> serviceDescriptorBuilder = ImmutableList.builder();
            for (InternalNode node : Iterables.concat(activeNodes, inactiveNodes)) {
                serviceDescriptorBuilder.add(serviceDescriptor("presto")
                        .setNodeId(node.getNodeIdentifier())
                        .addProperty("http", node.getInternalUri().toString())
                        .addProperty("node_version", node.getNodeVersion().toString())
                        .addProperty("coordinator", String.valueOf(node.isCoordinator()))
                        .build());
            }

            this.descriptors = serviceDescriptorBuilder.build();
        }

        @Override
        public String getType()
        {
            return "presto";
        }

        @Override
        public String getPool()
        {
            return DEFAULT_POOL;
        }

        @Override
        public synchronized List<ServiceDescriptor> selectAllServices()
        {
            return descriptors;
        }

        @Override
        public ListenableFuture<List<ServiceDescriptor>> refresh()
        {
            throw new UnsupportedOperationException();
        }
    }
}
