package com.github.justinrlee.kafkamonitor;

import java.util.Properties;
import java.util.UUID;
import java.io.IOException;
import java.io.FileInputStream;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.exporter.httpserver.HTTPServer;
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics;

import com.github.justinrlee.kafkamonitor.monitor.BrokerMonitor;
import com.github.justinrlee.kafkamonitor.monitor.TopicMonitor;

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

        HTTPServer server = HTTPServer.builder()
            .port(9400)
            .buildAndStart();

        BrokerMonitor cm = new BrokerMonitor(properties);
        Thread cm_t = new Thread(cm);
        cm_t.start();
        TopicMonitor tm = new TopicMonitor(properties);
        Thread tm_t = new Thread(tm);
        tm_t.start();
        
        System.out.println("HTTPServer listening on port http://localhost:" + server.getPort() + "/metrics");

        Thread.currentThread().join(); // sleep forever
    }
}
