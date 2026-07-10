# MQBrowser

MQBrowser is a **local desktop** message queue browser for visually accessing and
managing multiple message queue systems, with support for remote connections.

## Features

- 🔍 Browse queues / topics
- 📬 Publish and subscribe to messages
- 🔐 Connection management (remote brokers, TLS)
- 🧩 Unified abstraction layer — one interface, many MQs; adding an MQ means writing one adapter

## Supported Message Queues

| MQ | Client library | Notes |
|----|---------------|-------|
| RabbitMQ | amqp-client | AMQP 0-9-1 |
| Kafka | kafka-clients | destination = topic |
| MQTT | Eclipse Paho | pub/sub, no server-side browse |
| ActiveMQ Artemis | artemis-jakarta-client | JMS 2.0 |
| IBM MQ | com.ibm.mq.jakarta.client | needs queue manager + channel |

## Technology Stack

- Language/runtime: Java 21
- UI: JavaFX 21
- Build: Maven
- Logging: SLF4J + Logback

## Quick Start

### Prerequisites

- JDK 21+
- Maven 3.9+

### Run

```bash
git clone https://gitee.com/benbear_335/mqbrowser.git
cd mqbrowser

# Run directly via javafx-maven-plugin
mvn javafx:run
```

### Package

```bash
mvn clean package
```

## Usage

1. Click **New Connection…** in the left panel.
2. Pick the MQ type, fill in host / port / credentials and the type-specific extra
   field (RabbitMQ vhost, IBM MQ queue manager, Kafka consumer group).
3. Select the connection and click **Connect**.
4. Use the **Browse** tab to read messages and the **Publish** tab to send them.

## Extending with a New MQ

1. Add a value to `MqType`.
2. Create a class under `mq/adapter/` implementing `MqClient` (extend `AbstractMqClient`).
3. Register it in `MqClientFactory`.

No UI changes required.

## License

MIT License
