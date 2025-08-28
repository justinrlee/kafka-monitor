package io.justinrlee.kafka.monitor;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.io.IOException;
import java.io.FileInputStream;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.Node;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Collections;
import java.util.List;
import java.net.InetSocketAddress;

import io.justinrlee.kafka.monitor.monitors.BrokerMonitor;
import io.justinrlee.kafka.monitor.monitors.TopicMonitor;
import io.justinrlee.kafka.monitor.monitors.CanaryProduceMonitor;
import io.justinrlee.kafka.monitor.monitors.CanaryConsumeMonitor;

/**
 * Kafka Monitor with Canary functionality
 *
 */
public class KafkaMonitor
{
    // Helper class to hold partition information
    public static class PartitionInfo {
        public final int partition;
        public final long currentOffset;
        
        public PartitionInfo(int partition, long currentOffset) {
            this.partition = partition;
            this.currentOffset = currentOffset;
        }
        
        @Override
        public String toString() {
            return String.format("Partition{%d, offset=%d}", partition, currentOffset);
        }
    }
    
    // Discover partitions and current offsets for a topic
    private static Map<Integer, PartitionInfo> discoverPartitionInfo(Properties properties, String topicName) {
        Map<Integer, PartitionInfo> partitionInfo = new HashMap<>();
        
        try (AdminClient adminClient = AdminClient.create(properties)) {
            // Get topic description to find partitions
            DescribeTopicsResult topicsResult = adminClient.describeTopics(Collections.singletonList(topicName));
            TopicDescription topicDescription = topicsResult.values().get(topicName).get();
            
            // Create TopicPartition objects for offset lookup
            List<TopicPartition> topicPartitions = new ArrayList<>();
            for (TopicPartitionInfo partitionInfo2 : topicDescription.partitions()) {
                topicPartitions.add(new TopicPartition(topicName, partitionInfo2.partition()));
            }
            
            // Get current (latest) offsets for each partition
            Map<TopicPartition, OffsetSpec> offsetSpecs = new HashMap<>();
            for (TopicPartition tp : topicPartitions) {
                offsetSpecs.put(tp, OffsetSpec.latest());
            }
            
            ListOffsetsResult offsetsResult = adminClient.listOffsets(offsetSpecs);
            Map<TopicPartition, org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo> offsets = offsetsResult.all().get();
            
            // Build partition info map
            for (TopicPartition tp : topicPartitions) {
                long currentOffset = offsets.get(tp).offset();
                partitionInfo.put(tp.partition(), new PartitionInfo(tp.partition(), currentOffset));
            }
            
            System.out.printf("Discovered %d partitions for topic %s:%n", partitionInfo.size(), topicName);
            for (PartitionInfo info : partitionInfo.values()) {
                System.out.printf("  %s%n", info);
            }
            
        } catch (Exception e) {
            System.err.printf("Failed to discover partition info for topic %s: %s%n", topicName, e.getMessage());
            e.printStackTrace();
        }
        
        return partitionInfo;
    }
    public static void main( String[] args ) throws InterruptedException, IOException {

        Properties properties = new Properties();

        try {
                FileInputStream propertyFile = new FileInputStream("client.properties");
                properties.load(propertyFile);
        } catch (Exception e) {
                System.out.println("Unable to load properties file");
                System.out.println(e);
                System.exit(1);
        }
        properties.put("group.id", UUID.randomUUID().toString());
        properties.put("auto.offset.reset", "latest"); // Default for general consumers, canary consumer overrides to "earliest"

        // Generate unique monitor instance ID
        String monitorInstanceId = UUID.randomUUID().toString();
        System.out.println("Monitor instance ID: " + monitorInstanceId);

        // Todo: support loading from config
        int prometheusPort = 9400;
        int apiPort = 9401;

        TopicMonitor tm = null;
        BrokerMonitor bm = null;

        if (properties.getProperty("monitor.topics.enabled", "true").equals("true")) {
            System.out.println("Monitoring topics");
            tm = new TopicMonitor(properties);
            Thread topicMonitor_t = new Thread(tm);
            topicMonitor_t.start();
        }

        final TopicMonitor topicMonitor = tm;

        if (properties.getProperty("monitor.brokers.enabled", "true").equals("true")) {
            System.out.println("Monitoring brokers");
            bm = new BrokerMonitor(properties);
            Thread brokerMonitor_t = new Thread(bm);
            brokerMonitor_t.start();
        }

        final BrokerMonitor brokerMonitor = bm;

        // Create a health check handler
        HttpHandler healthCheckHandler = new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String response = "{\"status\": \"ok\"}";
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                }
            }
        };

        // Create a topics handler
        HttpHandler topicsHandler = new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                // Add CORS headers
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");

                // Handle OPTIONS request for CORS preflight
                if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }

                try {
                    if (topicMonitor == null) {
                        String errorResponse = "{\"error\": \"Topic monitoring is not enabled\"}";
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(503, errorResponse.length());
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(errorResponse.getBytes(StandardCharsets.UTF_8));
                        }
                        return;
                    }

                    String path = exchange.getRequestURI().getPath();
                    String jsonResponse;
                    
                    if (path.equals("/topics")) {
                        // Return all topics
                        jsonResponse = topicMonitor.getTopicsJson();
                    } else if (path.matches("/topics/[a-zA-Z0-9._-]+/config")) {
                        // Extract topic name from path (e.g., /topics/my-topic/config)
                        String topicName = path.substring("/topics/".length(), path.length() - "/config".length());
                        try {
                            jsonResponse = topicMonitor.getTopicConfigJson(topicName);
                        } catch (Exception e) {
                            String errorResponse = "{\"error\": \"" + e.getMessage() + "\"}";
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(404, errorResponse.length());
                            try (OutputStream os = exchange.getResponseBody()) {
                                os.write(errorResponse.getBytes(StandardCharsets.UTF_8));
                            }
                            return;
                        }
                    } else if (path.matches("/topics/[a-zA-Z0-9._-]+/partitions")) {
                        // Extract topic name from path (e.g., /topics/my-topic/config)
                        String topicName = path.substring("/topics/".length(), path.length() - "/partitions".length());
                        try {
                            jsonResponse = topicMonitor.getTopicPartitionsJson(topicName);
                        } catch (Exception e) {
                            String errorResponse = "{\"error\": \"" + e.getMessage() + "\"}";
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(404, errorResponse.length());
                            try (OutputStream os = exchange.getResponseBody()) {
                                os.write(errorResponse.getBytes(StandardCharsets.UTF_8));
                            }
                            return;
                        }
                    } else {
                        // Extract topic name from path (e.g., /topics/my-topic)
                        String topicName = path.substring("/topics/".length());
                        try {
                            jsonResponse = topicMonitor.getTopicJson(topicName);
                        } catch (Exception e) {
                            String errorResponse = "{\"error\": \"" + e.getMessage() + "\"}";
                            exchange.getResponseHeaders().set("Content-Type", "application/json");
                            exchange.sendResponseHeaders(404, errorResponse.length());
                            try (OutputStream os = exchange.getResponseBody()) {
                                os.write(errorResponse.getBytes(StandardCharsets.UTF_8));
                            }
                            return;
                        }
                    }

                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, jsonResponse.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(jsonResponse.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    String errorResponse = "{\"error\": \"" + e.getMessage() + "\"}";
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, errorResponse.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(errorResponse.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        };
        
        // Create a broker information handler
        HttpHandler brokersHandler = new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                // Add CORS headers
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");

                // Handle OPTIONS request for CORS preflight
                if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }

                try {
                    if (brokerMonitor == null) {
                        String errorResponse = "{\"error\": \"Broker monitoring is not enabled\"}";
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(503, errorResponse.length());
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(errorResponse.getBytes(StandardCharsets.UTF_8));
                        }
                        return;
                    }

                    String jsonResponse = brokerMonitor.getBrokersJson();
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, jsonResponse.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(jsonResponse.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    String errorResponse = "{\"error\": \"" + e.getMessage() + "\"}";
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(500, errorResponse.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(errorResponse.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        };
        
        // Create Prometheus metrics server
        HTTPServer prometheusServer = HTTPServer.builder()
            .port(prometheusPort)
            .buildAndStart();

        // Create API server for custom endpoints
        HttpServer apiServer = HttpServer.create(new InetSocketAddress(apiPort), 0);
        apiServer.createContext("/health", healthCheckHandler);
        apiServer.createContext("/topics", topicsHandler);
        apiServer.createContext("/brokers", brokersHandler);
        apiServer.setExecutor(null); // Use the default executor
        apiServer.start();

        // Start canary consumer monitoring FIRST to avoid missing initial messages
        boolean consumersStarted = false;
        if (properties.getProperty("monitor.canary.consume.enabled", "false").equals("true") && !properties.getProperty("monitor.canary.consume.topics", "").equals("")) {
            System.out.println("Starting canary consumers first...");
            
            // Create metrics for end-to-end latency and message tracking
            Gauge endToEndLatencyGauge = Gauge.builder()
                .name("consume.endtoend.latency")
                .help("End-to-end latency from message creation to consumption")
                .labelNames("topic", "partition", "aggregation")
                .register();
                
            Counter messagesConsumedCounter = Counter.builder()
                .name("canary.messages.consumed")
                .help("Number of canary messages consumed")
                .labelNames("topic", "partition")
                .register();
                
            Counter messagesLostCounter = Counter.builder()
                .name("canary.messages.lost")
                .help("Number of canary messages lost (sequence gaps)")
                .labelNames("topic", "partition")
                .register();
                
            Gauge timeSinceLastMessageGauge = Gauge.builder()
                .name("canary.time.since.last.message")
                .help("Time in seconds since the last canary message was consumed")
                .labelNames("topic")
                .register();

            List<String> canaryTopics = Arrays.asList(properties.getProperty("monitor.canary.consume.topics").split("\\s*,\\s*"));
            for (String topicName: canaryTopics) {
                System.out.println("Discovering partitions for consumer topic: " + topicName);
                Map<Integer, PartitionInfo> partitionInfoMap = discoverPartitionInfo(properties, topicName);
                
                if (!partitionInfoMap.isEmpty()) {
                    System.out.println("Starting canary consumer for topic: " + topicName);
                    CanaryConsumeMonitor consumer = new CanaryConsumeMonitor(properties, topicName, 
                        endToEndLatencyGauge, messagesConsumedCounter, messagesLostCounter, timeSinceLastMessageGauge, 
                        monitorInstanceId, partitionInfoMap);
                    Thread consumerThread = new Thread(consumer);
                    consumerThread.start();
                } else {
                    System.err.println("No partitions found for topic: " + topicName + ", skipping consumer");
                }
            }
            
            // Give consumers time to start up and get partition assignments
            System.out.println("Waiting for consumers to initialize...");
            try {
                Thread.sleep(3000); // 3 second delay
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            consumersStarted = true;
        }

        // Start canary producer monitoring AFTER consumers are ready
        if (properties.getProperty("monitor.canary.produce.enabled", "false").equals("true") && !properties.getProperty("monitor.canary.produce.topics", "").equals("")) {
            if (consumersStarted) {
                System.out.println("Now starting canary producers...");
            } else {
                System.out.println("Starting canary producers (no consumers configured)...");
            }
            
            Gauge latencyGauge = Gauge.builder()
                .name("produce.latency")
                .help("latency")
                .labelNames("topic", "partition", "aggregation")
                .register();

            // Get configurable message rate (default 1.0 messages per partition per second)
            double messagesPerPartitionPerSecond = Double.parseDouble(
                properties.getProperty("monitor.canary.messages.per.partition.per.second", "1.0"));
            System.out.printf("Canary producer configured for %.1f messages per partition per second%n", messagesPerPartitionPerSecond);

            List<String> canaryTopics = Arrays.asList(properties.getProperty("monitor.canary.produce.topics").split("\\s*,\\s*"));
            for (String topicName: canaryTopics) {
                System.out.println("Discovering partitions for producer topic: " + topicName);
                Map<Integer, PartitionInfo> partitionInfoMap = discoverPartitionInfo(properties, topicName);
                
                if (!partitionInfoMap.isEmpty()) {
                    System.out.println("Starting canary producer for topic: " + topicName);
                    CanaryProduceMonitor cm1 = new CanaryProduceMonitor(properties, topicName, latencyGauge, 
                        monitorInstanceId, partitionInfoMap, messagesPerPartitionPerSecond);
                    Thread cm1_t = new Thread (cm1);
                    cm1_t.start();
                } else {
                    System.err.println("No partitions found for topic: " + topicName + ", skipping producer");
                }
            }
        }
        
        System.out.println("Prometheus metrics available at http://localhost:" + prometheusPort + "/metrics");
        System.out.println("Health check available at http://localhost:" + apiPort + "/health");
        System.out.println("Topics information available at http://localhost:" + apiPort + "/topics");

        Thread.currentThread().join(); // sleep forever
    }
}
