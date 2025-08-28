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

    Gauge latencyGauge;
    // Map<String, Long> brokerRacks, brokerRacksCache, brokersUp;


    public CanaryProduceMonitor(Properties properties, String topicName, Gauge latencyGauge) {
        // client = KafkaAdminClient.create(properties);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArraySerializer");
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArraySerializer");
        producer = createKafkaProducer(properties);

        this.topicName = topicName;
        this.latencyGauge = latencyGauge;

    }


    KafkaProducer<byte[], byte[]> createKafkaProducer(Properties props) {
        return new KafkaProducer<>(props);
    }

    public void run() {
        try {
            byte[] payload = null;
            ProducerRecord<byte[], byte[]> record;
            // 
            stats = new Stats(1000, 5000, topicName, latencyGauge);

            long startMs = System.currentTimeMillis();


            ThroughputThrottler throttler = new ThroughputThrottler(1, startMs);

            long i = 0;
            while (true) {

                // Create headers with timestamp information
                Headers headers = new RecordHeaders();
                long messageGeneratedMs = System.currentTimeMillis();
                
                // Add timestamp header as bytes (8-byte long)
                ByteBuffer timestampBuffer = ByteBuffer.allocate(8);
                timestampBuffer.putLong(messageGeneratedMs);
                headers.add("canary-timestamp-ms", timestampBuffer.array());
                
                // Add sequence number for message correlation
                ByteBuffer sequenceBuffer = ByteBuffer.allocate(8);
                sequenceBuffer.putLong(i);
                headers.add("canary-sequence", sequenceBuffer.array());

                // topicname, partition, timestamp, timestamp, key, value, headers
                record = new ProducerRecord<>(topicName, null, null, null, null, headers);

                long sendStartMs = System.currentTimeMillis();
                cb = new PerfCallback(sendStartMs, 0, stats);
                producer.send(record, cb);

                if(throttler.shouldThrottle(i, sendStartMs)) {
                    throttler.throttle();
                }

                i++;
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

        public Stats(long numRecords, int reportingInterval, String topicName, Gauge latencyGauge) {
            this.start = System.currentTimeMillis();
            this.windowStart = System.currentTimeMillis();
            this.iteration = 0;
            this.sampling = numRecords / Math.min(numRecords, 500000);
            this.latencies = new int[(int) (numRecords / this.sampling) + 1];
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
            if (this.iteration % this.sampling == 0) {
                this.latencies[index] = latency;
                this.index++;
            }
            /* maybe report the recent perf */
            if (time - windowStart >= reportingInterval) {
                printWindow();
                newWindow();
            }
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
            return this.index;
        }

        public void printWindow() {
            long elapsed = System.currentTimeMillis() - windowStart;
            double recsPerSec = 1000.0 * windowCount / (double) elapsed;
            double mbPerSec = 1000.0 * this.windowBytes / (double) elapsed / (1024.0 * 1024.0);
            latencyGauge.labelValues(topicName, "average").set(windowTotalLatency / (double) windowCount);
            latencyGauge.labelValues(topicName, "max").set((double) windowMaxLatency);

            System.out.printf("%d records sent, %.1f records/sec (%.2f MB/sec), %.1f ms avg latency, %.1f ms max latency.%n",
                              windowCount,
                              recsPerSec,
                              mbPerSec,
                              windowTotalLatency / (double) windowCount,
                              (double) windowMaxLatency);
        }

        public void printError() {
            System.out.println("unable to produce to topic");

            // High enough to indicate that there's a problem; not so high that prometheus outputs it in scientific notation
            latencyGauge.labelValues(topicName, "average").set(9999);
            latencyGauge.labelValues(topicName, "max").set(9999);

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