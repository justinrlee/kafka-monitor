package io.justinrlee.kafka.monitor.monitors;

import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.Counter;

import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;

import java.util.Properties;
import java.util.Collections;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
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
    
    // Sequence tracking for message loss detection
    private final Map<String, SequenceTracker> sequenceTrackers = new HashMap<>();
    
    // Statistics tracking
    private final Stats stats;

    public CanaryConsumeMonitor(Properties properties, String topicName, 
                               Gauge endToEndLatencyGauge, Counter messagesConsumedCounter, 
                               Counter messagesLostCounter, Gauge timeSinceLastMessageGauge, String monitorInstanceId) {
        this.topicName = topicName;
        this.endToEndLatencyGauge = endToEndLatencyGauge;
        this.messagesConsumedCounter = messagesConsumedCounter;
        this.messagesLostCounter = messagesLostCounter;
        this.timeSinceLastMessageGauge = timeSinceLastMessageGauge;
        this.monitorInstanceId = monitorInstanceId;
        
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
        
        // Subscribe to the canary topic
        consumer.subscribe(Collections.singletonList(topicName));
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
            
            if (timestampHeader == null || sequenceHeader == null) {
                System.err.println("Received message without canary headers on topic " + topicName);
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
            stats.recordMessage(endToEndLatencyMs);
            messagesConsumedCounter.labelValues(topicName, String.valueOf(record.partition())).inc();
            
        } catch (Exception e) {
            System.err.println("Error processing canary message: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // Class to track sequence numbers and detect gaps
    private static class SequenceTracker {
        private long expectedSequence = -1; // -1 means we haven't seen any messages yet
        private final Set<Long> seenSequences = new HashSet<>();
        
        /**
         * Updates the expected sequence and returns the number of lost messages
         */
        public long updateSequence(long sequenceNumber) {
            if (expectedSequence == -1) {
                // First message we've seen
                expectedSequence = sequenceNumber + 1;
                seenSequences.add(sequenceNumber);
                return 0;
            }
            
            if (sequenceNumber < expectedSequence) {
                // This is a duplicate or out-of-order message
                if (seenSequences.contains(sequenceNumber)) {
                    // Duplicate message
                    return 0;
                } else {
                    // Out of order message - mark as seen
                    seenSequences.add(sequenceNumber);
                    return 0;
                }
            }
            
            // Calculate how many messages we missed
            long lostMessages = sequenceNumber - expectedSequence;
            
            // Update expected sequence
            expectedSequence = sequenceNumber + 1;
            seenSequences.add(sequenceNumber);
            
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
        private static final long REPORTING_INTERVAL_MS = 5000; // 5 seconds
        
        public Stats(String topicName, Gauge endToEndLatencyGauge, Gauge timeSinceLastMessageGauge) {
            this.topicName = topicName;
            this.endToEndLatencyGauge = endToEndLatencyGauge;
            this.timeSinceLastMessageGauge = timeSinceLastMessageGauge;
        }
        
        public void recordMessage(long latencyMs) {
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
            
            if (now - windowStart >= REPORTING_INTERVAL_MS) {
                if (windowMessageCount > 0) {
                    // Calculate averages
                    double avgLatency = windowTotalLatency / (double) windowMessageCount;
                    
                    // Update Prometheus metrics
                    endToEndLatencyGauge.labelValues(topicName, "average").set(avgLatency);
                    endToEndLatencyGauge.labelValues(topicName, "max").set((double) windowMaxLatency);
                    
                    // Log statistics
                    System.out.printf("Canary consumer %s: %d messages, %.1f ms avg latency, %d ms max latency, %.1f s since last message%n",
                        topicName, windowMessageCount, avgLatency, windowMaxLatency, timeSinceLastMessageSeconds);
                } else {
                    // No messages in this window - just log staleness
                    System.out.printf("Canary consumer %s: No messages received in last %.1f seconds, %.1f s since last message%n",
                        topicName, REPORTING_INTERVAL_MS / 1000.0, timeSinceLastMessageSeconds);
                }
                
                // Reset window (but keep lastMessageTimestamp as-is)
                windowStart = now;
                windowMessageCount = 0;
                windowTotalLatency = 0;
                windowMaxLatency = 0;
            }
        }
    }
}
