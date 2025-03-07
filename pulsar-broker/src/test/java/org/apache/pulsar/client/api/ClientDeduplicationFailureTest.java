/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.api;

import com.google.common.base.Function;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.apache.bookkeeper.test.PortManager;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.loadbalance.impl.SimpleLoadManagerImpl;
import org.apache.pulsar.client.admin.BrokerStats;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.admin.PulsarAdminException;
import org.apache.pulsar.client.impl.BatchMessageIdImpl;
import org.apache.pulsar.client.impl.MessageIdImpl;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.apache.pulsar.common.policies.data.TopicStats;
import org.apache.pulsar.io.PulsarFunctionE2ETest;
import org.apache.pulsar.zookeeper.LocalBookkeeperEnsemble;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import static org.apache.pulsar.broker.auth.MockedPulsarServiceBaseTest.retryStrategically;
import static org.mockito.Mockito.spy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class ClientDeduplicationFailureTest {
    LocalBookkeeperEnsemble bkEnsemble;

    ServiceConfiguration config;
    URL url;
    PulsarService pulsar;
    PulsarAdmin admin;
    PulsarClient pulsarClient;
    BrokerStats brokerStatsClient;
    final String tenant = "external-repl-prop";
    String primaryHost;

    private final int ZOOKEEPER_PORT = PortManager.nextFreePort();
    private final int brokerWebServicePort = PortManager.nextFreePort();
    private final int brokerServicePort = PortManager.nextFreePort();

    private static final Logger log = LoggerFactory.getLogger(PulsarFunctionE2ETest.class);

    @BeforeMethod(timeOut = 300000)
    void setup(Method method) throws Exception {
        log.info("--- Setting up method {} ---", method.getName());

        // Start local bookkeeper ensemble
        bkEnsemble = new LocalBookkeeperEnsemble(3, ZOOKEEPER_PORT, PortManager::nextFreePort);
        bkEnsemble.start();

        String brokerServiceUrl = "http://127.0.0.1:" + brokerWebServicePort;

        config = spy(new ServiceConfiguration());
        config.setClusterName("use");
        config.setWebServicePort(Optional.ofNullable(brokerWebServicePort));
        config.setZookeeperServers("127.0.0.1" + ":" + ZOOKEEPER_PORT);
        config.setBrokerServicePort(Optional.ofNullable(brokerServicePort));
        config.setLoadManagerClassName(SimpleLoadManagerImpl.class.getName());
        config.setTlsAllowInsecureConnection(true);
        config.setAdvertisedAddress("localhost");
        config.setLoadBalancerSheddingEnabled(false);

        config.setAllowAutoTopicCreationType("non-partitioned");

        url = new URL(brokerServiceUrl);
        pulsar = new PulsarService(config, Optional.empty());
        pulsar.start();

        admin = PulsarAdmin.builder().serviceHttpUrl(brokerServiceUrl).build();

        brokerStatsClient = admin.brokerStats();
        primaryHost = String.format("http://%s:%d", "localhost", brokerWebServicePort);

        // update cluster metadata
        ClusterData clusterData = new ClusterData(url.toString());
        admin.clusters().createCluster(config.getClusterName(), clusterData);

        ClientBuilder clientBuilder = PulsarClient.builder().serviceUrl("pulsar://127.0.0.1:" + config.getBrokerServicePort().get()).maxBackoffInterval(1, TimeUnit.SECONDS);
        pulsarClient = clientBuilder.build();

        TenantInfo tenantInfo = new TenantInfo();
        tenantInfo.setAllowedClusters(Sets.newHashSet(Lists.newArrayList("use")));
        admin.tenants().createTenant(tenant, tenantInfo);
    }

    @AfterMethod
    void shutdown() throws Exception {
        log.info("--- Shutting down ---");
        pulsarClient.close();
        admin.close();
        pulsar.close();
        bkEnsemble.stop();
    }

    @Test
    public void testClientDeduplicationWithBkFailure() throws  Exception {
        final String namespacePortion = "dedup";
        final String replNamespace = tenant + "/" + namespacePortion;
        final String sourceTopic = "persistent://" + replNamespace + "/my-topic1";
        final String subscriptionName1 = "sub1";
        final String subscriptionName2 = "sub2";
        final String consumerName1 = "test-consumer-1";
        final String consumerName2 = "test-consumer-2";
        final List<Message<String>> msgRecvd = new LinkedList<>();
        admin.namespaces().createNamespace(replNamespace);
        Set<String> clusters = Sets.newHashSet(Lists.newArrayList("use"));
        admin.namespaces().setNamespaceReplicationClusters(replNamespace, clusters);
        admin.namespaces().setDeduplicationStatus(replNamespace, true);
        Producer<String> producer = pulsarClient.newProducer(Schema.STRING).topic(sourceTopic)
                .producerName("test-producer-1").create();
        Consumer<String> consumer1 = pulsarClient.newConsumer(Schema.STRING).topic(sourceTopic)
                .consumerName(consumerName1).subscriptionName(subscriptionName1).subscribe();
        Consumer<String> consumer2 = pulsarClient.newConsumer(Schema.STRING).topic(sourceTopic)
                .consumerName(consumerName2).subscriptionName(subscriptionName2).subscribe();

        new Thread(() -> {
            while(true) {
                try {
                    Message<String> msg = consumer2.receive();
                    msgRecvd.add(msg);
                    consumer2.acknowledge(msg);
                } catch (PulsarClientException e) {
                    log.error("Failed to consume message: {}", e, e);
                }
            }
        }).start();

        retryStrategically((test) -> {
            try {
                TopicStats topicStats = admin.topics().getStats(sourceTopic);
                boolean c1 =  topicStats!= null
                        && topicStats.subscriptions.get(subscriptionName1) != null
                        && topicStats.subscriptions.get(subscriptionName1).consumers.size() == 1
                        && topicStats.subscriptions.get(subscriptionName1).consumers.get(0).consumerName.equals(consumerName1);

                boolean c2 =  topicStats!= null
                        && topicStats.subscriptions.get(subscriptionName2) != null
                        && topicStats.subscriptions.get(subscriptionName2).consumers.size() == 1
                        && topicStats.subscriptions.get(subscriptionName2).consumers.get(0).consumerName.equals(consumerName2);
                return c1 && c2;
            } catch (PulsarAdminException e) {
                return false;
            }
        }, 5, 200);

        TopicStats topicStats1 = admin.topics().getStats(sourceTopic);
        assertTrue(topicStats1!= null);
        assertTrue(topicStats1.subscriptions.get(subscriptionName1) != null);
        assertEquals(topicStats1.subscriptions.get(subscriptionName1).consumers.size(), 1);
        assertEquals(topicStats1.subscriptions.get(subscriptionName1).consumers.get(0).consumerName, consumerName1);
        TopicStats topicStats2 = admin.topics().getStats(sourceTopic);
        assertTrue(topicStats2!= null);
        assertTrue(topicStats2.subscriptions.get(subscriptionName2) != null);
        assertEquals(topicStats2.subscriptions.get(subscriptionName2).consumers.size(), 1);
        assertEquals(topicStats2.subscriptions.get(subscriptionName2).consumers.get(0).consumerName, consumerName2);

        for (int i=0; i<10; i++) {
            producer.newMessage().sequenceId(i).value("foo-" + i).send();
        }

        for (int i=0; i<10; i++) {
            Message<String> msg = consumer1.receive();
            consumer1.acknowledge(msg);
            assertEquals(msg.getValue(), "foo-" + i);
            assertEquals(msg.getSequenceId(), i);
        }

        log.info("Stopping BK...");
        bkEnsemble.stopBK();

        List<CompletableFuture<MessageId>> futures = new LinkedList<>();
        for (int i=10; i<20; i++) {
            CompletableFuture<MessageId> future = producer.newMessage().sequenceId(i).value("foo-" + i).sendAsync();
            int finalI = i;
            future.thenRun(() -> log.error("message: {} successful", finalI)).exceptionally((Function<Throwable, Void>) throwable -> {
                log.info("message: {} failed: {}", finalI, throwable, throwable);
                return null;
            });
            futures.add(future);
        }

        for (int i = 0; i < futures.size(); i++) {
            try {
                // message should not be produced successfully
                futures.get(i).join();
                fail();
            } catch (CompletionException ex) {

            } catch (Exception e) {
                fail();
            }
        }

        try {
            producer.newMessage().sequenceId(10).value("foo-10").send();
            fail();
        } catch (PulsarClientException ex) {

        }

        try {
            producer.newMessage().sequenceId(10).value("foo-10").send();
            fail();
        } catch (PulsarClientException ex) {

        }

        log.info("Starting BK...");
        bkEnsemble.startBK();

        for (int i=20; i<30; i++) {
            producer.newMessage().sequenceId(i).value("foo-" + i).send();
        }

        MessageId lastMessageId = null;
        for (int i=20; i<30; i++) {
            Message<String> msg = consumer1.receive();
            lastMessageId = msg.getMessageId();
            consumer1.acknowledge(msg);
            assertEquals(msg.getValue(), "foo-" + i);
            assertEquals(msg.getSequenceId(), i);
        }

        // check all messages
        retryStrategically((test) -> msgRecvd.size() >= 20, 5, 200);

        assertEquals(msgRecvd.size(), 20);
        for (int i=0; i<10; i++) {
            assertEquals(msgRecvd.get(i).getValue(), "foo-" + i);
            assertEquals(msgRecvd.get(i).getSequenceId(), i);
        }
        for (int i=10; i<20; i++) {
            assertEquals(msgRecvd.get(i).getValue(), "foo-" + (i + 10));
            assertEquals(msgRecvd.get(i).getSequenceId(), i + 10);
        }

        BatchMessageIdImpl batchMessageId = (BatchMessageIdImpl) lastMessageId;
        MessageIdImpl messageId = (MessageIdImpl) consumer1.getLastMessageId();

        assertEquals(messageId.getLedgerId(), batchMessageId.getLedgerId());
        assertEquals(messageId.getEntryId(), batchMessageId.getEntryId());
    }
}
