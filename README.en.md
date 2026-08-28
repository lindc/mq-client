# MQ Client

MQ Client is a **local desktop** message queue client for visually accessing and
managing multiple message queue systems, with support for remote connections.

## Features

- 🔍 Browse queues / topics
- 📬 Publish and subscribe to messages
- 🔐 Connection management (remote brokers, TLS)
- 🧩 Unified abstraction layer — one interface, many MQs; adding an MQ means writing one adapter

## Supported Message Queues

| MQ | Client library | Notes |
|----|----------------|-------|
| RabbitMQ | amqp-client 5.25.0 | AMQP 0-9-1 |
| Kafka | kafka-clients 4.3.1 | destination = topic |
| RocketMQ | rocketmq-client 5.5.1 | 5.x protocol |
| ActiveMQ Artemis | artemis-core-client 2.55.0 | core protocol (not JMS) |
| IBM MQ | com.ibm.mq.allclient 10.0.0.0 | needs queue manager + channel |

## Technology Stack

- Language/runtime: Java 25
- UI: JavaFX 26.0.2
- Container/Config: Spring Boot 4.1.1 (manages core singletons and config only)
- Plugin mechanism: ServiceLoader SPI (service + ui layers, registered via META-INF/services)
- Build: Maven multi-module
- Logging: SLF4J + Logback (brought in by spring-boot-starter)

## Quick Start

### Prerequisites

- JDK 25+
- Maven 3.9+

### Run

```bash
git clone https://gitee.com/benbear_335/mq-client.git
cd mq-client

# First build: install all 12 modules into the local repo (app depends on the UI/service modules)
mvn install -DskipTests

# Run (javafx-maven-plugin, entry in mq-client-app)
mvn -f mq-client-app/pom.xml org.openjfx:javafx-maven-plugin:0.0.8:run
```

> Note: `mq-client-app` depends on the 5 UI modules in this repo, so run
> `mvn install` once at the root before building it with `-f`.

### Package

```bash
mvn clean package
```

## Usage

1. Click **New Connection…** in the left panel.
2. Pick the MQ type, fill in host / port / credentials and the type-specific extra
   field (RabbitMQ vhost, IBM MQ queue manager, Kafka consumer group).
3. Click a connection in the left tree — it connects and switches the right panel.
4. Use the **Browse** tab to read messages and the **Publish** tab to send them.

## Extending with a New MQ

MQ plugins are discovered via `ServiceLoader` at runtime; adding one needs two modules:

1. **Service module** (`mq-client-<name>-service`): implement `MQServiceProvider`
   (`getType()` / `getDisplayName()` / `createService()`), register it in
   `META-INF/services/org.bear.mqclient.core.spi.MQServiceProvider`.
2. **UI module** (`mq-client-<name>-ui`): implement `MQUIProvider`
   (`createMainPanel()` / `createConfigEditor()` / `getMenus()`), register it in
   `META-INF/services/org.bear.mqclient.core.spi.MQUIProvider`.
3. Add both modules to the root `pom.xml` `<modules>` list; `mq-client-app` must
   depend on the new UI module so the provider is on the runtime classpath.

The core shell (menus, navigation, connection store) stays untouched.

## License

MIT License
