package io.justinrlee.kafka.monitor.monitors;

// import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;

// Using Confluent Admin API, which exposes information about observers:
// https://docs.confluent.io/platform/7.7/clients/javadocs/javadoc/org/apache/kafka/clients/admin/Admin.html
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.TopicListing;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.DescribeConfigsResult;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;

import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.Node;

import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

import java.util.Properties;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;

import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class TopicMonitor implements Runnable {

    AdminClient client;
    Gauge replicaGauge;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private volatile Map<String, List<Map<String, Object>>> cachedTopicReplicaInfo = new HashMap<>();
    private volatile Map<String, Map<String, String>> cachedTopicConfigInfo = new HashMap<>();
    private final Object cacheLock = new Object();
    private Map<String, Set<Integer>> lastKnownTopicReplicas = new HashMap<>();
    private Map<String, Set<Integer>> lastKnownTopicObservers = new HashMap<>();
    private int iterationCounter = 0;

    List<TopicPartitionInfo> partitions;

    public TopicMonitor(Properties properties) {
        client = KafkaAdminClient.create(properties);

        replicaGauge = Gauge.builder()
            .name("replicas")
            .help("number of replicas")
            .labelNames("topic", "partition", "type", "status")
            .register();
    }

    // todo: Add a separate thread that indicates when these metrics were last updated (to account for monitoring dying)
    // todo: parameterize interval

    public void run() {
        try {
            while (true) {
                ListTopicsResult ltr = client.listTopics(new ListTopicsOptions().listInternal(true));
                Set<String> topics = ltr.names().get();

                DescribeTopicsResult dtr = client.describeTopics(topics);
                Map<String, TopicDescription> tds = dtr.allTopicNames().get();

                // Every 12 iterations (approximately 60 seconds), get topic configs
                if (iterationCounter++ % 12 == 0) {
                    Set<ConfigResource> resources = topics.stream()
                        .map(topic -> new ConfigResource(ConfigResource.Type.TOPIC, topic))
                        .collect(Collectors.toSet());
                    
                    DescribeConfigsResult dcr = client.describeConfigs(resources);
                    Map<ConfigResource, Config> configs = dcr.all().get();

                    // Convert configs to a more manageable format and update cache
                    Map<String, Map<String, String>> newConfigInfo = new HashMap<>();
                    for (Map.Entry<ConfigResource, Config> entry : configs.entrySet()) {
                        String topicName = entry.getKey().name();
                        Map<String, String> topicConfig = entry.getValue().entries().stream()
                            .collect(Collectors.toMap(
                                ConfigEntry::name,
                                ConfigEntry::value
                            ));
                        newConfigInfo.put(topicName, topicConfig);
                    }

                    // Update the cache atomically
                    synchronized (cacheLock) {
                        cachedTopicConfigInfo = newConfigInfo;
                    }
                }

                replicaGauge.clear();
                
                // Update the cached topic information
                Map<String, List<Map<String, Object>>> topicReplicaInfo = new HashMap<>();

                for (var topicDescription: tds.entrySet()) {
                    String topicName = topicDescription.getValue().name();
                    partitions = topicDescription.getValue().partitions();
                    List<Map<String, Object>> partitionList = new ArrayList<>();

                    // Get the last known replicas and observers for this topic
                    Set<Integer> lastKnownReplicas = lastKnownTopicReplicas.getOrDefault(topicName, new HashSet<>());
                    Set<Integer> lastKnownObservers = lastKnownTopicObservers.getOrDefault(topicName, new HashSet<>());

                    // Update last known state for this topic
                    Set<Integer> currentReplicas = new HashSet<>();
                    Set<Integer> currentObservers = new HashSet<>();

                    for (var topicPartitionInfo: partitions) {
                        String partition = Integer.toString(topicPartitionInfo.partition());

                        Map<String, Object> partitionInfo = new HashMap<>();
                        partitionInfo.put("partition", topicPartitionInfo.partition());
                        
                        // Initialize all our state lists
                        List<Integer> leaderList = new ArrayList<>();
                        List<Integer> inSyncList = new ArrayList<>();
                        List<Integer> outOfSyncList = new ArrayList<>();
                        List<Integer> observerList = new ArrayList<>();
                        List<Integer> promotedObserverList = new ArrayList<>();
                        List<Integer> offlineList = new ArrayList<>();

                        // Track the leader first (highest priority)
                        Node leader = topicPartitionInfo.leader();
                        if (leader != null) {
                            leaderList.add(leader.id());
                            // Leader is always in-sync
                            inSyncList.add(leader.id());
                        }

                        // Get all our sets
                        Set<Node> replicas = new HashSet<>(topicPartitionInfo.replicas());
                        Set<Node> isr = new HashSet<>(topicPartitionInfo.isr());
                        Set<Node> observers = new HashSet<>(topicPartitionInfo.observers());
                        
                        // Process each replica exactly once, in priority order
                        for (Node replica : replicas) {
                            // Skip if already processed as leader
                            if (leader != null && replica.id() == leader.id()) {
                                continue;
                            }

                            // If it's an observer, skip it for now
                            if (observers.contains(replica)) {
                                continue;
                            }

                            // If it's offline (no rack), add to offline list
                            if (!replica.hasRack()) {
                                offlineList.add(replica.id());
                                continue;
                            }

                            // If it's in ISR, it's in-sync
                            if (isr.contains(replica)) {
                                inSyncList.add(replica.id());
                            } else {
                                // If it's not in ISR, it's out-of-sync
                                outOfSyncList.add(replica.id());
                            }
                        }

                        // Process observers
                        for (Node observer : observers) {
                            // Skip if this observer is somehow also the leader
                            if (leader != null && observer.id() == leader.id()) {
                                continue;
                            }

                            // If offline (no rack), add to offline list
                            if (!observer.hasRack()) {
                                offlineList.add(observer.id());
                            } else {
                                observerList.add(observer.id());
                                // If the observer is in ISR, add it to both lists
                                if (isr.contains(observer)) {
                                    inSyncList.add(observer.id());
                                    promotedObserverList.add(observer.id());
                                }
                            }
                        }

                        // Update the partition info with our mutually exclusive lists
                        partitionInfo.put("leader", leader != null ? leader.id() : -1);
                        partitionInfo.put("in_sync_replicas", inSyncList);
                        partitionInfo.put("out_of_sync_replicas", outOfSyncList);
                        partitionInfo.put("observers", observerList);
                        partitionInfo.put("promoted_observers", promotedObserverList);
                        partitionInfo.put("offline_replicas", offlineList);

                        // Include all replicas (in-sync, out-of-sync, observers, and offline)
                        Set<Integer> allReplicasSet = new HashSet<>();
                        allReplicasSet.addAll(inSyncList);
                        allReplicasSet.addAll(outOfSyncList);
                        allReplicasSet.addAll(observerList);
                        allReplicasSet.addAll(offlineList);
                        List<Integer> allReplicas = new ArrayList<>(allReplicasSet);
                        partitionInfo.put("replicas", allReplicas);

                        partitionList.add(partitionInfo);
                    }

                    // Update last known state for this topic
                    lastKnownTopicReplicas.put(topicName, currentReplicas);
                    lastKnownTopicObservers.put(topicName, currentObservers);

                    topicReplicaInfo.put(topicName, partitionList);
                }

                // Update the cache atomically
                synchronized (cacheLock) {
                    cachedTopicReplicaInfo = topicReplicaInfo;
                }

                Thread.sleep(5000);
            }
        } catch (InterruptedException e) {
            System.out.println("Something bad happened - topicmonitor ie");
            System.out.println(e);
        } catch (ExecutionException e) {
            System.out.println("Something bad happened - topicmonitor ee");
            System.out.println(e);
        }
    }

    public String getTopicsJson() throws Exception {
        synchronized (cacheLock) {
            return gson.toJson(cachedTopicReplicaInfo);
        }
    }

    public String getTopicJson(String topicName) throws Exception {
        synchronized (cacheLock) {
            List<Map<String, Object>> topicInfo = cachedTopicReplicaInfo.get(topicName);
            if (topicInfo == null) {
                throw new Exception("Topic not found: " + topicName);
            }
            return gson.toJson(topicInfo);
        }
    }

    public String getTopicConfigJson(String topicName) throws Exception {
        synchronized (cacheLock) {
            Map<String, String> topicConfig = cachedTopicConfigInfo.get(topicName);
            if (topicConfig == null) {
                throw new Exception("Topic configuration not found: " + topicName);
            }
            return gson.toJson(topicConfig);
        }
    }
}
