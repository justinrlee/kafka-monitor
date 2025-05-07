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
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.Node;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashMap;
import java.net.InetSocketAddress;

import io.justinrlee.kafka.monitor.monitors.BrokerMonitor;
import io.justinrlee.kafka.monitor.monitors.TopicMonitor;
import io.justinrlee.kafka.monitor.monitors.CanaryProduceMonitor;

/**
 * Hello world!
 *
 */
public class KafkaMonitor
{
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
        properties.put("auto.offset.reset", "latest");

        // Todo: support loading from config
        int prometheusPort = 9400;
        int apiPort = 9401;

        TopicMonitor tm = null;
        BrokerMonitor bm = null;

        if (properties.getProperty("monitor.topics.enabled", "false").equals("true")) {
            System.out.println("Monitoring topics");
            tm = new TopicMonitor(properties);
            Thread topicMonitor_t = new Thread(tm);
            topicMonitor_t.start();
        }

        final TopicMonitor topicMonitor = tm;

        if (properties.getProperty("monitor.brokers.enabled", "false").equals("true")) {
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

        // Todo: use real logs
        if (properties.getProperty("monitor.canary.produce.enabled", "false").equals("true") && !properties.getProperty("monitor.canary.produce.topics", "").equals("")) {
            System.out.println("monitoring produce topics");
            // if (!properties.getProperty("monitor.canary.produce.topics", "").equals("")) {
            //     System.out.println("second test passed");
            // }
            Gauge latencyGauge = Gauge.builder()
                .name("produce.latency")
                .help("latency")
                .labelNames("topic", "aggregation")
                .register();

            List<String> canaryTopics = Arrays.asList(properties.getProperty("monitor.canary.produce.topics").split("\\s*,\\s*"));
            for (String topicName: canaryTopics) {
                System.out.println(topicName);
                CanaryProduceMonitor cm1 = new CanaryProduceMonitor(properties, topicName, latencyGauge);
                Thread cm1_t = new Thread (cm1);
                cm1_t.start();
            }
        }
        
        System.out.println("Prometheus metrics available at http://localhost:" + prometheusPort + "/metrics");
        System.out.println("Health check available at http://localhost:" + apiPort + "/health");
        System.out.println("Topics information available at http://localhost:" + apiPort + "/topics");

        Thread.currentThread().join(); // sleep forever
    }
}
