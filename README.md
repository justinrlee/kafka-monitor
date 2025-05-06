# Kafka Topic Health Monitor

A monitoring application for Apache Kafka clusters that provides real-time visibility into topic health, partition distribution, and replica status.

## Features

- Real-time monitoring of Kafka topic health
- Visual representation of replica status and distribution
- Partition-level health monitoring
- Broker rack awareness
- Observer replica support
- Offline replica detection
- Interactive topic details view

## Prerequisites

- Java 11 or later (17 recommended)
- Node.js 16 or later
- npm 8 or later
- Access to a Kafka cluster
- Kafka cluster admin privileges


## Project Structure

```
kafka-monitor/
├── backend/          # Java backend service
│   └── src/         # Java source files
└── frontend/        # React frontend
    └── kafka-monitor-ui/  # Frontend application
```

## Setup Instructions

### Install prereqs

```bash
## Backend: JDK and Maven
sudo apt-get update && \
sudo apt-get install -y \
    openjdk-17-jdk-headless \
    maven

## Frontend: nvm, npm, and node
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.3/install.sh | bash

source .bashrc
nvm ls-remote

nvm install v22.15.0
```

### Create client.properties
(todo: add option/flag to configure filename)

```conf
## client.properties

# Generic Java Kafka client configuration
bootstrap.servers=kafka.internal:9092
security.protocol=SASL_SSL
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="password";
ssl.endpoint.identification.algorithm=https
ssl.truststore.location=truststore.p12
ssl.truststore.password=confluent

# Specific to monitor
monitor.topics.enabled=true
monitor.brokers.enabled=true
```

Build and run backend

```bash
mvn package

java -cp ./target/kafka-monitor-0.1-SNAPSHOT.jar io.justinrlee.kafka.monitor.KafkaMonitor
```

Will listen on ports 9400 (Prometheus endpoint) and 9401 (REST endpoint for frontend)

Build and run frontend (separate terminal)

```bash
cd kafka-monitor/frontend/kafka-monitor-ui
npm install

# Replace with hostname for client to access backend
export VITE_API_URL=http://ec2-3-0-94-206.ap-southeast-1.compute.amazonaws.com:9401

npm run dev
```

Frontend will be accessible on port 3000


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