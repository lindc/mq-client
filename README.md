# MQ Client

MQ Client 是一个**本地运行的桌面**消息队列客户端工具，用于可视化访问和管理多种消息队列服务，支持远程连接。

## 功能特性

- 🔍 消息队列/主题浏览
- 📬 消息发送与订阅
- 🔐 连接管理（支持远程 broker、TLS）
- 🧩 统一抽象层（ServiceLoader SPI），新增 MQ 只需实现 service + ui 两个提供者

## 支持的消息队列

| MQ               | 客户端库                        | 说明                          |
| ---------------- | ------------------------------- | ----------------------------- |
| RabbitMQ         | amqp-client 5.25.0              | AMQP 0-9-1                    |
| Kafka            | kafka-clients 4.3.1             | destination 即 topic          |
| RocketMQ         | rocketmq-client 5.5.1           | 5.x 协议                      |
| ActiveMQ Artemis | artemis-core-client 2.55.0      | core 协议（非 JMS）           |
| IBM MQ           | com.ibm.mq.allclient 10.0.0.0   | 需 queue manager + channel    |

## 技术栈

- 语言/运行时：Java 25
- UI：JavaFX 26.0.2
- 容器/配置：Spring Boot 4.1.1（仅管理 core 内的单例与配置）
- 插件机制：ServiceLoader SPI（service 与 ui 两层，各 MQ 模块通过 META-INF/services 注册）
- 构建：Maven 多模块
- 日志：SLF4J + Logback（随 spring-boot-starter 引入）

## 快速开始

### 前置条件

- JDK 25+
- Maven 3.9+

### 运行

```bash
# 克隆项目
git clone https://gitee.com/benbear_335/mq-client.git
cd mq-client

# 首次构建：把 12 个模块安装到本地仓库（app 依赖各 UI/service 模块）
mvn install -DskipTests

# 运行（javafx-maven-plugin，入口在 mq-client-app）
mvn -f mq-client-app/pom.xml org.openjfx:javafx-maven-plugin:0.0.8:run
```

> 注：`mq-client-app` 依赖同仓库的 5 个 UI 模块，必须先在根目录执行一次
> `mvn install`，才能用 `-f` 单独构建/运行它。

### 打包

```bash
mvn clean package
```

在根目录执行，产出全部模块的 jar。

## 界面说明

- 顶部 **菜单栏**：文件 / 编辑 / 连接 / 视图 / 帮助；选中连接后，该 MQ 的专属菜单会
  注入到「连接」与「视图」之间（切换/断开连接时移除）。帮助 → 关于 弹关于对话框，
  帮助内还可切换 **中文 / English**，即时生效。
- 左侧 **连接导航**：TreeView 按 MQ 类型分组展示已保存连接（emoji 图标），
  右键菜单可删除连接；单击连接即建立连接并切换右侧面板。
- 右侧 **内容区**：完全由所选 MQ 类型的 UI 插件接管（通常是 TabPane）——例如
  RabbitMQ 有 Queue / Exchange / Binding / 发布 / 消费 等 Tab，部分 Tab 仍是占位。
- **新建连接**：对话框左侧选择 MQ 类型，右侧是该类型的配置表单（由各 MQ 模块提供）。
- **消息详情**：Headers + Body 两个 Tab，Body 右上角支持编码切换（TEXT / RAW / HEX / JSON）。

## 项目结构

MQ Client 是 Maven 多模块工程（根 pom 聚合），模块名与程序名统一为 `mq-client`：

```
mq-client-core/                 # 核心：SPI、模型、服务接口、UI 骨架（org.bear.mqclient.core）
mq-client-rabbitmq-service/     # RabbitMQ 服务实现（adapter）
mq-client-rabbitmq-ui/          # RabbitMQ UI 插件
mq-client-ibm-service/          # IBM MQ 服务实现
mq-client-ibm-ui/               # IBM MQ UI 插件
mq-client-kafka-service/        # Kafka 服务实现
mq-client-kafka-ui/             # Kafka UI 插件
mq-client-artemis-service/      # Artemis 服务实现
mq-client-artemis-ui/           # Artemis UI 插件
mq-client-rocketmq-service/     # RocketMQ 服务实现
mq-client-rocketmq-ui/          # RocketMQ UI 插件
mq-client-app/                  # 聚合运行模块：依赖全部 UI 模块，JavaFX 入口 org.bear.mqclient.app.App
```

## 主题 / 配色

当前**没有样式表**，界面为 JavaFX 默认观感；`application.yml` 中的 `app.theme: light`
目前只是占位配置（尚未接入）。

- 窗口尺寸：`mq-client-core/src/main/resources/application.yml`（默认 1200 × 800）。
- 少量内联样式散在 Java 代码中，例如：
  - 左侧分组节点粗体、连接列表标题：`NavigationPanel.java`
  - 消息 Body 编码工具栏底色：`MessageDetailContainer.java`
- 左侧导航的分组/连接图标是 emoji 映射，定义在 `NavigationPanel.java` 的 `TYPE_ICONS`
  （IBM_MQ 🔵、KAFKA ⚡、RABBITMQ 🐇、ARTEMIS 🔶、ROCKETMQ ☄️）。

## 关于对话框 / ASCII 图案

菜单 **帮助 → 关于** 会弹出关于对话框，左侧是走动的模拟时钟，右侧是一列冒烟的 ASCII 小火车。

火车图案不写在代码里，而是放在资源文件中，**改图案只需编辑对应 `.txt`，无需改 Java 代码**：

- `mq-client-core/src/main/resources/ascii/train.txt` — 车头图案（直接写，反斜杠 `\` 无需转义）
- `mq-client-core/src/main/resources/ascii/smoke.txt` — 烟雾动画帧，**每帧之间用一个空行分隔**，程序按顺序循环播放形成飘动效果

> 提示：ASCII art 依赖等宽字体对齐，编辑时请保持每行的空格数；帧数可自由增减（多帧过渡更顺滑）。

## 许可证

MIT License
