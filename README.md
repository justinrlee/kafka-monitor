JDK 17

Needs a client properties file called client.properties
(todo: add option/flag for this)

Generates metrics from CC/CP cluster, including:
* Broker availability (zero if not available)
(todo: maybe rename metric)

```
# HELP broker_available broker availabile
# TYPE broker_available gauge
broker_available{broker_id="1100"} 1.0
broker_available{broker_id="1101"} 1.0
broker_available{broker_id="1102"} 1.0
broker_available{broker_id="1103"} 1.0
broker_available{broker_id="2100"} 1.0
broker_available{broker_id="2101"} 1.0
broker_available{broker_id="2102"} 0.0
broker_available{broker_id="2103"} 1.0
broker_available{broker_id="3100"} 1.0
```

* Total available brokers per rack

```
# HELP broker_count number of brokers
# TYPE broker_count gauge
broker_count{rack="all"} 8.0
broker_count{rack="rack-1"} 4.0
broker_count{rack="rack-2"} 3.0
broker_count{rack="rack-3"} 1.0
```

* Partition statuses:
    * regular replicas
        * total
        * number in-sync
        * number online
        * number offline
    * observer replicas
        * total
        * number online
        * number offline

```
# HELP replicas number of replicas
# TYPE replicas gauge
replicas{partition="0",status="insync",topic="obs",type="regular"} 3.0
replicas{partition="0",status="offline",topic="obs",type="observer"} 0.0
replicas{partition="0",status="offline",topic="obs",type="regular"} 1.0
replicas{partition="0",status="online",topic="obs",type="observer"} 2.0
replicas{partition="0",status="online",topic="obs",type="regular"} 3.0
replicas{partition="0",status="total",topic="obs",type="observer"} 2.0
replicas{partition="0",status="total",topic="obs",type="regular"} 4.0
replicas{partition="1",status="insync",topic="obs",type="regular"} 3.0
replicas{partition="1",status="offline",topic="obs",type="observer"} 0.0
replicas{partition="1",status="offline",topic="obs",type="regular"} 1.0
replicas{partition="1",status="online",topic="obs",type="observer"} 2.0
replicas{partition="1",status="online",topic="obs",type="regular"} 3.0
replicas{partition="1",status="total",topic="obs",type="observer"} 2.0
replicas{partition="1",status="total",topic="obs",type="regular"} 4.0
replicas{partition="2",status="insync",topic="obs",type="regular"} 4.0
replicas{partition="2",status="offline",topic="obs",type="observer"} 0.0
replicas{partition="2",status="offline",topic="obs",type="regular"} 0.0
replicas{partition="2",status="online",topic="obs",type="observer"} 2.0
replicas{partition="2",status="online",topic="obs",type="regular"} 4.0
replicas{partition="2",status="total",topic="obs",type="observer"} 2.0
replicas{partition="2",status="total",topic="obs",type="regular"} 4.0
replicas{partition="3",status="insync",topic="obs",type="regular"} 4.0
replicas{partition="3",status="offline",topic="obs",type="observer"} 1.0
replicas{partition="3",status="offline",topic="obs",type="regular"} 0.0
replicas{partition="3",status="online",topic="obs",type="observer"} 1.0
replicas{partition="3",status="online",topic="obs",type="regular"} 4.0
replicas{partition="3",status="total",topic="obs",type="observer"} 2.0
replicas{partition="3",status="total",topic="obs",type="regular"} 4.0
```

# Build/run

```shell
sudo apt-get update && \
sudo apt-get install -y \
    openjdk-17-jdk-headless \
    maven

git clone https://github.com:justinrlee/kafka-monitor
cd kafka-monitor
mvn package

tee client.properties <<-'EOF'
bootstrap.servers=kafka.internal:9092
security.protocol=SASL_SSL
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="password";
ssl.endpoint.identification.algorithm=https
ssl.truststore.location=truststore.p12
ssl.truststore.password=confluent
EOF

java -cp /home/ubuntu/kafka-monitor/target/kafka-monitor-1.0-SNAPSHOT.jar com.github.justinrlee.kafkamonitor.KafkaMonitor
```