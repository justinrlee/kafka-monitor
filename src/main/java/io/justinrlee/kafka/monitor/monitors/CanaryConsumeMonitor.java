package io.justinrlee.kafka.monitor.monitors;

import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Counter;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.TopicPartition;

import java.util.Properties;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.time.Duration;
import java.nio.ByteBuffer;

public class CanaryConsumeMonitor implements Runnable {

    private final KafkaConsumer<byte[], byte[]> consumer;
    private final String topicName;
    private final Gauge endToEndLatencyGauge;
    private final Counter messagesConsumedCounter;
    private final Counter messagesLostCounter;
    private final Gauge timeSinceLastMessageGauge;
    private final String monitorInstanceId;
    private final Map<Integer, io.justinrlee.kafka.monitor.KafkaMonitor.PartitionInfo> partitionInfoMap;
    
    // Sequence tracking for message loss detection
    private final Map<String, SequenceTracker> sequenceTrackers = new HashMap<>();
    
    // Statistics tracking
    private final Stats stats;

    public CanaryConsumeMonitor(Properties properties, String topicName, 
                               Gauge endToEndLatencyGauge, Counter messagesConsumedCounter, 
                               Counter messagesLostCounter, Gauge timeSinceLastMessageGauge, String monitorInstanceId,
                               Map<Integer, io.justinrlee.kafka.monitor.KafkaMonitor.PartitionInfo> partitionInfoMap) {
        this.topicName = topicName;
        this.endToEndLatencyGauge = endToEndLatencyGauge;
        this.messagesConsumedCounter = messagesConsumedCounter;
        this.messagesLostCounter = messagesLostCounter;
        this.timeSinceLastMessageGauge = timeSinceLastMessageGauge;
        this.monitorInstanceId = monitorInstanceId;
        this.partitionInfoMap = partitionInfoMap;
        
        // Configure consumer properties
        Properties consumerProps = new Properties();
        consumerProps.putAll(properties);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, 
                         "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, 
                         "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); // Use earliest to avoid missing messages during startup, monitor ID filtering handles old messages
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        
        this.consumer = new KafkaConsumer<>(consumerProps);
        this.stats = new Stats(topicName, endToEndLatencyGauge, timeSinceLastMessageGauge);
        
        // Assign specific partitions and seek to current offsets
        List<TopicPartition> partitionsToAssign = new ArrayList<>();
        for (Integer partitionId : partitionInfoMap.keySet()) {
            partitionsToAssign.add(new TopicPartition(topicName, partitionId));
        }
        
        consumer.assign(partitionsToAssign);
        
        // Seek to current offsets for each partition
        for (TopicPartition tp : partitionsToAssign) {
            io.justinrlee.kafka.monitor.KafkaMonitor.PartitionInfo partitionInfo = partitionInfoMap.get(tp.partition());
            consumer.seek(tp, partitionInfo.currentOffset);
            System.out.printf("Consumer seeking to offset %d for partition %d%n", partitionInfo.currentOffset, tp.partition());
        }
        
        System.out.printf("Consumer will read from %d partitions starting at current offsets%n", partitionsToAssign.size());
    }

    @Override
    public void run() {
        try {
            System.out.println("Starting canary consumer for topic: " + topicName + " with monitor ID: " + monitorInstanceId);
            
            while (true) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(1000));
                
                for (ConsumerRecord<byte[], byte[]> record : records) {
                    processCanaryMessage(record);
                }
                
                // Report statistics periodically
                stats.maybeReport();
            }
        } catch (Exception e) {
            System.err.println("Error in canary consumer for topic " + topicName + ": " + e.getMessage());
            e.printStackTrace();
        } finally {
            consumer.close();
        }
    }
    
    private void processCanaryMessage(ConsumerRecord<byte[], byte[]> record) {
        try {
            long consumeTime = System.currentTimeMillis();
            
            // Extract headers
            Header timestampHeader = record.headers().lastHeader("canary-timestamp-ms");
            Header sequenceHeader = record.headers().lastHeader("canary-sequence");
            Header monitorIdHeader = record.headers().lastHeader("canary-monitor-id");
            Header partitionHeader = record.headers().lastHeader("canary-partition");
            
            if (timestampHeader == null || sequenceHeader == null || partitionHeader == null) {
                System.err.println("Received message without required canary headers on topic " + topicName);
                return;
            }
            
            // Filter messages by monitor instance ID
            if (monitorIdHeader == null) {
                // Old message without monitor ID - ignore
                return;
            }
            
            String messageMonitorId = new String(monitorIdHeader.value());
            if (!monitorInstanceId.equals(messageMonitorId)) {
                // Message from different monitor instance - ignore
                return;
            }
            
            // Parse timestamp and calculate end-to-end latency
            long messageTimestamp = ByteBuffer.wrap(timestampHeader.value()).getLong();
            long endToEndLatencyMs = consumeTime - messageTimestamp;
            
            // Parse sequence number for gap detection
            long sequenceNumber = ByteBuffer.wrap(sequenceHeader.value()).getLong();
            
            // Parse partition from header (should match record.partition())
            int headerPartitionId = ByteBuffer.wrap(partitionHeader.value()).getInt();
            if (headerPartitionId != record.partition()) {
                System.err.printf("Warning: Header partition %d doesn't match record partition %d%n", 
                    headerPartitionId, record.partition());
            }
            
            // Track sequence for this partition
            String partitionKey = topicName + "-" + record.partition();
            SequenceTracker tracker = sequenceTrackers.computeIfAbsent(
                partitionKey, k -> new SequenceTracker());
            
            // Check for gaps in sequence
            long lostMessages = tracker.updateSequence(sequenceNumber);
            if (lostMessages > 0) {
                System.err.printf("Detected %d lost messages on %s (expected: %d, got: %d)%n",
                    lostMessages, partitionKey, tracker.getExpectedSequence() - lostMessages - 1, sequenceNumber);
                messagesLostCounter.labelValues(topicName, String.valueOf(record.partition()))
                    .inc(lostMessages);
            }
            
            // Update metrics and statistics
            stats.recordMessage(endToEndLatencyMs, record.partition());
            messagesConsumedCounter.labelValues(topicName, String.valueOf(record.partition())).inc();
            
        } catch (Exception e) {
            System.err.println("Error processing canary message: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // Class to track sequence numbers and detect gaps
    private static class SequenceTracker {
        private long expectedSequence = -1; // -1 means we haven't seen any messages yet
        // Ring buffer using LinkedHashMap with automatic old entry removal
        private static final int MAX_OUT_OF_ORDER_WINDOW = 1000; // Keep track of last 1000 sequences
        private final Map<Long, Boolean> seenSequences = new LinkedHashMap<Long, Boolean>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
                return size() > MAX_OUT_OF_ORDER_WINDOW;
            }
        };
        
        /**
         * Updates the expected sequence and returns the number of lost messages
         */
        public long updateSequence(long sequenceNumber) {
            if (expectedSequence == -1) {
                // First message we've seen
                expectedSequence = sequenceNumber + 1;
                seenSequences.put(sequenceNumber, Boolean.TRUE);
                return 0;
            }
            
            if (sequenceNumber < expectedSequence) {
                // This is a duplicate or out-of-order message
                if (seenSequences.containsKey(sequenceNumber)) {
                    // Duplicate message
                    return 0;
                } else {
                    // Out of order message - mark as seen
                    seenSequences.put(sequenceNumber, Boolean.TRUE);
                    return 0;
                }
            }
            
            // Calculate how many messages we missed
            long lostMessages = sequenceNumber - expectedSequence;
            
            // Update expected sequence
            expectedSequence = sequenceNumber + 1;
            seenSequences.put(sequenceNumber, Boolean.TRUE);
            
            return Math.max(0, lostMessages);
        }
        
        public long getExpectedSequence() {
            return expectedSequence;
        }
    }
    
    // Statistics tracking class
    private static class Stats {
        private final String topicName;
        private final Gauge endToEndLatencyGauge;
        private final Gauge timeSinceLastMessageGauge;
        private long messageCount = 0;
        private long totalLatency = 0;
        private long maxLatency = 0;
        private long windowStart = System.currentTimeMillis();
        private long windowMessageCount = 0;
        private long windowTotalLatency = 0;
        private long windowMaxLatency = 0;
        private long lastMessageTimestamp = System.currentTimeMillis(); // Track when we last received a message
        
        // Separate intervals for metrics vs logging
        private static final long METRICS_UPDATE_INTERVAL_MS = 1000; // 1 second for Prometheus metrics
        private static final long CONSOLE_LOG_INTERVAL_MS = 10000; // 10 seconds for console logging
        private long lastConsoleLogTime = System.currentTimeMillis();
        
        public Stats(String topicName, Gauge endToEndLatencyGauge, Gauge timeSinceLastMessageGauge) {
            this.topicName = topicName;
            this.endToEndLatencyGauge = endToEndLatencyGauge;
            this.timeSinceLastMessageGauge = timeSinceLastMessageGauge;
        }
        
        public void recordMessage(long latencyMs, int partition) {
            messageCount++;
            totalLatency += latencyMs;
            maxLatency = Math.max(maxLatency, latencyMs);
            
            windowMessageCount++;
            windowTotalLatency += latencyMs;
            windowMaxLatency = Math.max(windowMaxLatency, latencyMs);
            
            // Update timestamp of last received message
            lastMessageTimestamp = System.currentTimeMillis();
            
            // Reset time since last message to 0 (just received a message)
            timeSinceLastMessageGauge.labelValues(topicName).set(0.0);
        }
        
        public void maybeReport() {
            long now = System.currentTimeMillis();
            
            // Always update time since last message (even if no new messages in this window)
            long timeSinceLastMessageMs = now - lastMessageTimestamp;
            double timeSinceLastMessageSeconds = timeSinceLastMessageMs / 1000.0;
            timeSinceLastMessageGauge.labelValues(topicName).set(timeSinceLastMessageSeconds);
            
            // Update Prometheus metrics every 1 second
            if (now - windowStart >= METRICS_UPDATE_INTERVAL_MS) {
                if (windowMessageCount > 0) {
                    // Calculate averages
                    double avgLatency = windowTotalLatency / (double) windowMessageCount;
                    
                    // Update Prometheus metrics (aggregate across all partitions)
                    endToEndLatencyGauge.labelValues(topicName, "all", "average").set(avgLatency);
                    endToEndLatencyGauge.labelValues(topicName, "all", "max").set((double) windowMaxLatency);
                }
                
                // Reset metrics window (but keep lastMessageTimestamp as-is)
                windowStart = now;
                windowMessageCount = 0;
                windowTotalLatency = 0;
                windowMaxLatency = 0;
            }
            
            // Log to console every 10 seconds
            if (now - lastConsoleLogTime >= CONSOLE_LOG_INTERVAL_MS) {
                // Use current window data for logging
                long logWindowMs = now - lastConsoleLogTime;
                
                if (messageCount > 0) {
                    // Calculate averages for the logging window
                    double avgLatency = totalLatency / (double) messageCount;
                    
                    // Log statistics
                    System.out.printf("Canary consumer %s: %d messages in last %.1fs, %.1f ms avg latency, %d ms max latency, %.1f s since last message%n",
                        topicName, messageCount, logWindowMs / 1000.0, avgLatency, maxLatency, timeSinceLastMessageSeconds);
                } else {
                    // No messages in this window - just log staleness
                    System.out.printf("Canary consumer %s: No messages received in last %.1f seconds, %.1f s since last message%n",
                        topicName, logWindowMs / 1000.0, timeSinceLastMessageSeconds);
                }
                
                // Reset console logging counters
                lastConsoleLogTime = now;
                messageCount = 0;
                totalLatency = 0;
                maxLatency = 0;
            }
        }
    }
}
