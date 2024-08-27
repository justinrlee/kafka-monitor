package com.github.justinrlee.kafkamonitor.monitor;

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

public class TopicMonitor implements Runnable {

    AdminClient client;
    Gauge replicaGauge;

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

                replicaGauge.clear();

                for (var topicDescription: tds.entrySet()) {
                    String topicName = topicDescription.getValue().name();
                    partitions = topicDescription.getValue().partitions();

                    for (var topicPartitionInfo: partitions) {
                        String partition = Integer.toString(topicPartitionInfo.partition());

                        Set<Node> replicas = new HashSet<Node>(topicPartitionInfo.replicas());
                        Set<Node> inSyncReplicas = new HashSet<Node>(topicPartitionInfo.isr());
                        Set<Node> observers = new HashSet<Node>(topicPartitionInfo.observers());

                        Set<Node> fullReplicas = replicas.stream()
                                                    .filter(c -> !observers.contains(c))
                                                    .collect(Collectors.toSet());

                        // not currently exposed:
                        // all replicas: replicas
                        // all online replicas
                        // all offline replicas

                        // exposed for all partitions:
                        // total regular replicas: replicas - observers
                        // in-sync regular replicas: in-sync replicas
                        // online regular replicas: (replicas - observers) - missing
                        // offline regular replicas: (replicas - observers): missing

                        // exposed for partitions with observers:
                        // total observers: observers
                        // available observers: observers minus missing
                        // offline observers: observers: missing

                        replicaGauge.labelValues(topicName, partition, "regular", "total").set(
                            fullReplicas.size()
                        );
                        replicaGauge.labelValues(topicName, partition, "regular", "insync").set(
                            inSyncReplicas.size()
                        );
                        replicaGauge.labelValues(topicName, partition, "regular", "online").set(
                            fullReplicas.stream()
                                .filter(Node::hasRack)
                                .count()
                        );
                        replicaGauge.labelValues(topicName, partition, "regular", "offline").set(
                            fullReplicas.stream()
                                .filter(Predicate.not(Node::hasRack))
                                .count()
                        );

                        if (observers.size() > 0) {
                            replicaGauge.labelValues(topicName, partition, "observer", "total").set(
                                observers.size()
                            );
                            replicaGauge.labelValues(topicName, partition, "observer", "online").set(
                                observers.stream()
                                    .filter(Node::hasRack)
                                    .count()
                            );
                            replicaGauge.labelValues(topicName, partition, "observer", "offline").set(
                                observers.stream()
                                    .filter(Predicate.not(Node::hasRack))
                                    .count()
                            );
                        }
                    }
                }
                Thread.sleep(15000);
            }
        } catch (InterruptedException e) {
        // todo: fix exception handling
            System.out.println("Something bad happened - topicmonitor ie");
            System.out.println(e);
        } catch (ExecutionException e) {
            System.out.println("Something bad happened - topicmonitor ee");
            System.out.println(e);
        }
    }
}