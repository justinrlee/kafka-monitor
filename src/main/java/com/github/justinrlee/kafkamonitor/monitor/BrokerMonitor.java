package com.github.justinrlee.kafkamonitor.monitor;

// import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;

// Using Confluent Admin API, which exposes information about observers
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.TopicListing;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;

import java.util.concurrent.ExecutionException;
import org.apache.kafka.common.Node;

import java.util.Properties;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;

import java.util.stream.Collectors;

public class BrokerMonitor implements Runnable {

    AdminClient client;
    Gauge brokerCount, brokerAvailable;
    Map<String, Long> brokerRacks, brokerRacksCache, brokersUp;

    public BrokerMonitor(Properties properties) {
        client = KafkaAdminClient.create(properties);

        brokerCount = Gauge.builder()
            .name("broker_count")
            .help("number of brokers")
            .labelNames("rack")
            .register();

        brokerAvailable = Gauge.builder()
            .name("broker_available")
            .help("broker availabile")
            .labelNames("broker_id")
            .register();

        brokerRacks = Collections.emptyMap();
        brokersUp = new HashMap<String, Long>();
    }

    // todo: Add a separate thread that indicates when these metrics were last updated (to account for monitoring dying)
    // todo: parameterize interval

    public void run() {
        try {
            while (true) {
                DescribeClusterResult result = client.describeCluster();
                List<Node> brokers = new ArrayList<>(result.nodes().get());

                brokerRacksCache = brokerRacks;
                brokerRacks = brokers.stream().collect(Collectors.groupingBy(e -> e.rack(), Collectors.counting()));

                List<String> brokerIds = brokers.stream().map(Node::id).map(Object::toString).collect(Collectors.toList());

                // todo: see if we wanna initialize an admin API instance against every broker.

                // todo: check this logic (if we remove all brokers from a rack, does it show up as zero?)
                for (var rack: brokerRacksCache.entrySet()) {
                    if (brokerRacks.get(rack.getKey()) == null) {
                        brokerRacks.put(rack.getKey(), 0L);
                    }
                }

                // todo: use the same log for this and the above?
                for (var broker: brokersUp.entrySet()) {
                    brokersUp.put(broker.getKey(), 0L);
                }

                for (var broker: brokerIds) {
                        brokersUp.put(broker, 1L);
                    }

                // Update labels
                brokerCount.labelValues("all").set(brokers.size());
                for (var rack: brokerRacks.entrySet()) {
                    brokerCount.labelValues(rack.getKey()).set(rack.getValue());
                }
                for (var broker: brokersUp.entrySet()) {
                    brokerAvailable.labelValues(broker.getKey()).set(broker.getValue());
                }

                Thread.sleep(15000);
            }
        } catch (InterruptedException e) {
            System.out.println("Something bad happened - brokermonitor ie");
            System.out.println(e);
        } catch (ExecutionException e) {
            System.out.println("Something bad happened - brokermonitor ee");
            System.out.println(e);
        }
    }
}