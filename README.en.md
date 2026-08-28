# MQ Client

MQ Client is a **local desktop** message queue client for visually accessing and
managing multiple message queue systems, with support for remote connections.

## Features

- 🔍 Browse queues / topics
- 📬 Publish and subscribe to messages
- 🔐 Connection management (remote brokers, TLS)
- 🧩 Unified abstraction layer — one interface, many MQs; adding an MQ means writing one adapter
- 🌐 i18n: Chinese / English switchable at runtime; plugins may ship their own translations
- 📝 File logging: rolling files under `~/.mqclient/logs/`, plugins record via `LogAdapter`

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
- Assembly/Config: zero-framework (manual wiring in `MainApp.bootstrap()`, `application.yml` parsed via snakeyaml)
- Plugin mechanism: ServiceLoader SPI (service + ui layers, registered via META-INF/services)
- Build: Maven multi-module
- Logging: SLF4J + Logback (`logback.xml` rolling files, written under `~/.mqclient/logs/`)
- i18n: `I18n` static facade (JDK ResourceBundle, core bundle + per-plugin bundles)
- Tooling: Lombok (`@Slf4j` etc.)

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

## Internationalization

All UI text is resolved through the `I18n` static facade
(`I18n.get("key")` / `I18n.get("key", arg...)`, `{0}` placeholders supported),
backed by JDK `ResourceBundle` (no framework dependency); the **Language** menu
switches between Chinese / English at runtime. Text lives in two layers:

- **Core bundle**: `mq-client-core/src/main/resources/messages.properties` (Chinese, default)
  plus `messages_en.properties` (English) — shared keys like `menu.*`, `common.*`, `message.*`.
- **Plugin bundles**: each UI plugin ships `messages_<type>.properties` in its own resources
  (plus an optional `_en` variant), e.g. `messages_kafka.properties`. `I18nBinder` discovers all
  plugins via ServiceLoader at startup and registers them automatically, with priority over core.

**Third-party plugin contract**: after implementing `MQUIProvider` and registering it in
`META-INF/services`, just provide `messages_<type>.properties` and the plugin is fully
i18n'd — no core changes needed.

## Logging

- SLF4J + Logback, configured in `mq-client-core/src/main/resources/logback.xml`.
- Output: console + `~/.mqclient/logs/mq-client.log`, rolled daily and by size (10MB),
  kept for 30 days (500MB total cap).
- Plugins record through `LogAdapter` (`org.bear.mqclient.core.util.LogAdapter`):
  `LogAdapter.error("KAFKA", "send failed", e)` — each entry carries a `[source]` tag.
- Core uses Lombok `@Slf4j`.

## Configuration Storage

- Connections are persisted as JSON at `~/.mqclient/connections.json` (Jackson, full rewrite on
  every add/update/remove).
- Passwords are encrypted by each MQ's config editor (e.g. AES); the store itself is
  encryption-agnostic.

## License

MIT License
