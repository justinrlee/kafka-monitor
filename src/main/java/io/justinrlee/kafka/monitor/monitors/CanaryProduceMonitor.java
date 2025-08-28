package io.justinrlee.kafka.monitor.monitors;

// import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;

// Using Confluent Admin API, which exposes information about observers
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
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
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import java.nio.ByteBuffer;

public class CanaryProduceMonitor implements Runnable {

    public static final String DEFAULT_TRANSACTION_ID_PREFIX = "canary-producer-";
    Callback cb;
    Stats stats;

    String topicName;
    KafkaProducer<byte[], byte[]> producer;
    String monitorInstanceId;
    Map<Integer, io.justinrlee.kafka.monitor.KafkaMonitor.PartitionInfo> partitionInfoMap;
    double messagesPerPartitionPerSecond;

    Gauge latencyGauge;
    // Map<String, Long> brokerRacks, brokerRacksCache, brokersUp;


    public CanaryProduceMonitor(Properties properties, String topicName, Gauge latencyGauge, String monitorInstanceId, 
                                Map<Integer, io.justinrlee.kafka.monitor.KafkaMonitor.PartitionInfo> partitionInfoMap,
                                double messagesPerPartitionPerSecond) {
        // client = KafkaAdminClient.create(properties);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArraySerializer");
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArraySerializer");
        producer = createKafkaProducer(properties);

        this.topicName = topicName;
        this.latencyGauge = latencyGauge;
        this.monitorInstanceId = monitorInstanceId;
        this.partitionInfoMap = partitionInfoMap;
        this.messagesPerPartitionPerSecond = messagesPerPartitionPerSecond;

        System.out.printf("Producer will send %.1f messages/sec to %d partitions for topic %s (%.1f total messages/sec)%n", 
            messagesPerPartitionPerSecond, partitionInfoMap.size(), topicName, 
            messagesPerPartitionPerSecond * partitionInfoMap.size());
    }


    KafkaProducer<byte[], byte[]> createKafkaProducer(Properties props) {
        return new KafkaProducer<>(props);
    }

    public void run() {
        try {
            if (partitionInfoMap.isEmpty()) {
                System.err.println("No partitions to produce to for topic: " + topicName);
                return;
            }
            
            stats = new Stats(0, 1000, topicName, latencyGauge); // numRecords not used for indefinite runs
            long startMs = System.currentTimeMillis();
            
            // Throttle to configured rate (sequences per second = messages per partition per second)
            ThroughputThrottler throttler = new ThroughputThrottler(messagesPerPartitionPerSecond, startMs);

            long sequenceId = 0;
            while (true) {
                long messageGeneratedMs = System.currentTimeMillis();
                
                // Send the same sequence ID to ALL partitions
                for (Integer partitionId : partitionInfoMap.keySet()) {
                    // Create headers with timestamp information
                    Headers headers = new RecordHeaders();
                    
                    // Add timestamp header as bytes (8-byte long)
                    ByteBuffer timestampBuffer = ByteBuffer.allocate(8);
                    timestampBuffer.putLong(messageGeneratedMs);
                    headers.add("canary-timestamp-ms", timestampBuffer.array());
                    
                    // Add sequence number for message correlation (same for all partitions)
                    ByteBuffer sequenceBuffer = ByteBuffer.allocate(8);
                    sequenceBuffer.putLong(sequenceId);
                    headers.add("canary-sequence", sequenceBuffer.array());
                    
                    // Add partition number for identification
                    ByteBuffer partitionBuffer = ByteBuffer.allocate(4);
                    partitionBuffer.putInt(partitionId);
                    headers.add("canary-partition", partitionBuffer.array());
                    
                    // Add monitor instance ID for message filtering
                    headers.add("canary-monitor-id", monitorInstanceId.getBytes());

                    // Send to specific partition
                    ProducerRecord<byte[], byte[]> record = new ProducerRecord<>(topicName, partitionId, null, null, null, headers);

                    long sendStartMs = System.currentTimeMillis();
                    cb = new PerfCallback(sendStartMs, 0, stats);
                    producer.send(record, cb);
                }

                // Throttle per sequence (not per message, since we send multiple messages per sequence)
                if(throttler.shouldThrottle(sequenceId, messageGeneratedMs)) {
                    throttler.throttle();
                }
                
                sequenceId++;
            }
                
        } catch (Exception e) {
            System.out.println("Something bad happened - CanaryProduceMonitor e");
            System.out.println(e);
        }
    }

    // Visible for testing
    static class Stats {
        private final long start;
        private final int[] latencies;
        private final long sampling;
        private final long reportingInterval;
        private long iteration;
        private int index;
        private long count;
        private long bytes;
        private int maxLatency;
        private long totalLatency;
        private long windowCount;
        private int windowMaxLatency;
        private long windowTotalLatency;
        private long windowBytes;
        private long windowStart;
        private Gauge latencyGauge;
        private String topicName;
        
        // Separate intervals for metrics vs logging
        private static final long METRICS_UPDATE_INTERVAL_MS = 1000; // 1 second for Prometheus metrics
        private static final long CONSOLE_LOG_INTERVAL_MS = 10000; // 10 seconds for console logging
        private long lastConsoleLogTime = System.currentTimeMillis();

        public Stats(long numRecords, int reportingInterval, String topicName, Gauge latencyGauge) {
            this.start = System.currentTimeMillis();
            this.windowStart = System.currentTimeMillis();
            this.lastConsoleLogTime = System.currentTimeMillis();
            this.iteration = 0;
            this.sampling = 1; // Sample every message since we're running indefinitely
            this.latencies = null; // Not needed for indefinite runs
            this.index = 0;
            this.maxLatency = 0;
            this.windowCount = 0;
            this.windowMaxLatency = 0;
            this.windowTotalLatency = 0;
            this.windowBytes = 0;
            this.totalLatency = 0;
            this.reportingInterval = reportingInterval;
            this.latencyGauge = latencyGauge;
            this.topicName = topicName;
        }

        public void record(int latency, int bytes, long time) {
            this.count++;
            this.bytes += bytes;
            this.totalLatency += latency;
            this.maxLatency = Math.max(this.maxLatency, latency);
            this.windowCount++;
            this.windowBytes += bytes;
            this.windowTotalLatency += latency;
            this.windowMaxLatency = Math.max(windowMaxLatency, latency);
            
            // Skip latency array storage since we're running indefinitely
            // if (this.iteration % this.sampling == 0) {
            //     this.latencies[index] = latency;
            //     this.index++;
            // }
            
            /* maybe report the recent perf */
            maybeReport(time);
        }

        public long totalCount() {
            return this.count;
        }

        public long currentWindowCount() {
            return this.windowCount;
        }

        public long iteration() {
            return this.iteration;
        }

        public long bytes() {
            return this.bytes;
        }

        public int index() {
            return 0; // Not used for indefinite runs
        }

        public void maybeReport(long time) {
            // Update Prometheus metrics every 1 second
            if (time - windowStart >= METRICS_UPDATE_INTERVAL_MS) {
                updateMetrics();
                newWindow();
            }
            
            // Log to console every 10 seconds
            if (time - lastConsoleLogTime >= CONSOLE_LOG_INTERVAL_MS) {
                printWindow();
                lastConsoleLogTime = time;
            }
        }

        private void updateMetrics() {
            if (windowCount > 0) {
                // Update Prometheus metrics
                latencyGauge.labelValues(topicName, "all", "average").set(windowTotalLatency / (double) windowCount);
                latencyGauge.labelValues(topicName, "all", "max").set((double) windowMaxLatency);
            }
        }

        public void printWindow() {
            long elapsed = System.currentTimeMillis() - lastConsoleLogTime;
            double recsPerSec = 1000.0 * count / (double) elapsed;
            double mbPerSec = 1000.0 * this.bytes / (double) elapsed / (1024.0 * 1024.0);
            
            if (count > 0) {
                System.out.printf("%d records sent in last %.1fs, %.1f records/sec (%.2f MB/sec), %.1f ms avg latency, %.1f ms max latency.%n",
                                  count,
                                  elapsed / 1000.0,
                                  recsPerSec,
                                  mbPerSec,
                                  totalLatency / (double) count,
                                  (double) maxLatency);
            } else {
                System.out.printf("No records sent in last %.1fs%n", elapsed / 1000.0);
            }
            
            // Reset console logging counters
            count = 0;
            bytes = 0;
            totalLatency = 0;
            maxLatency = 0;
        }

        public void printError() {
            System.out.println("unable to produce to topic");

            // High enough to indicate that there's a problem; not so high that prometheus outputs it in scientific notation
            latencyGauge.labelValues(topicName, "all", "average").set(9999);
            latencyGauge.labelValues(topicName, "all", "max").set(9999);

        }

        public void newWindow() {
            this.windowStart = System.currentTimeMillis();
            this.windowCount = 0;
            this.windowMaxLatency = 0;
            this.windowTotalLatency = 0;
            this.windowBytes = 0;
        }
    }

    static final class PerfCallback implements Callback {
        private final long start;
        private final int bytes;
        private final Stats stats;

        public PerfCallback(long start, int bytes, Stats stats) {
            this.start = start;
            this.stats = stats;
            this.bytes = bytes;
        }

        public void onCompletion(RecordMetadata metadata, Exception exception) {
            long now = System.currentTimeMillis();
            int latency = (int) (now - start);
            // It will only be counted when the sending is successful, otherwise the number of sent records may be
            // magically printed when the sending fails.
            if (exception == null) {
                this.stats.record(latency, bytes, now);
                this.stats.iteration++;
            }
            if (exception != null) {
                this.stats.printError();
                exception.printStackTrace();
            }
        }
    }
}