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
        int listenerPort = 9400;

        HTTPServer server = HTTPServer.builder()
            .port(listenerPort)
            .buildAndStart();

        // Todo: use real logs
        if (properties.getProperty("monitor.brokers.enabled", "false").equals("true")) {
            System.out.println("Monitoring brokers");
            BrokerMonitor cm = new BrokerMonitor(properties);
            Thread cm_t = new Thread(cm);
            cm_t.start();
        }

        if (properties.getProperty("monitor.replicas.enabled", "false").equals("true")) {
            System.out.println("Monitoring replicas");
            TopicMonitor tm = new TopicMonitor(properties);
            Thread tm_t = new Thread(tm);
            tm_t.start();
        }

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
        
        System.out.println("HTTPServer listening on port http://localhost:" + server.getPort() + "/metrics");

        Thread.currentThread().join(); // sleep forever
    }
}
