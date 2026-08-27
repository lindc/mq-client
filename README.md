# MQBrowser

MQBrowser 是一个**本地运行的桌面**消息队列浏览器工具，用于可视化访问和管理多种消息队列服务，支持远程连接。

## 功能特性

- 🔍 消息队列/主题浏览
- 📬 消息发送与订阅
- 🔐 连接管理（支持远程 broker、TLS）
- 🧩 统一抽象层，一个接口适配多种 MQ，新增 MQ 只需实现一个 adapter

## 支持的消息队列

| MQ               | 客户端库                  | 说明                       |
| ---------------- | ------------------------- | -------------------------- |
| RabbitMQ         | amqp-client               | AMQP 0-9-1                 |
| Kafka            | kafka-clients             | destination 即 topic       |
| RocketMQ         | rocketmq-client           | rocketmq client 5.5.0      |
| ActiveMQ Artemis | artemis-jakarta-client    | JMS 2.0                    |
| IBM MQ           | com.ibm.mq.jakarta.client | 需 queue manager + channel |

## 技术栈

- 语言/运行时：Java 21
- UI：JavaFX 21
- 构建：Maven
- 日志：SLF4J + Logback

## 快速开始

### 前置条件

- JDK 21+
- Maven 3.9+

### 运行

```bash
# 克隆项目
git clone https://gitee.com/benbear_335/mqbrowser.git
cd mqbrowser

# 直接运行（javafx-maven-plugin）
mvn javafx:run
```

### 打包

```bash
mvn clean package
```

## 界面说明（当前骨架）

- 顶部 **菜单栏**：File / Edit / Help（Help → About 弹出关于对话框）。
- 左侧 **导航列表**：item 右键有菜单，鼠标悬停整行浮起（阴影 + 轻微放大）。
- 右侧 **内容区**：内层 panel 中的 **TabPane**（Messages / Headers / Properties），
  每个 tab 是带表头的 **表格**，斑马纹 + 边框，行右键有菜单。
- 底部 **状态栏**（ControlsFX StatusBar）。

## 项目结构

```
src/main/
├── java/com/mqbrowser/
│   ├── MqBrowserApp.java         # JavaFX 入口，挂载 styles/app.css
│   ├── model/
│   │   └── TableRowItem.java     # 表格行模型（Name / Type / Value）
│   └── ui/
│       ├── MainView.java         # 主布局：菜单栏 + 左右 panel + 状态栏 + 关于对话框
│       ├── LeftPanel.java        # 左侧导航列表（item 右键菜单 + 悬停浮起）
│       ├── RightPanel.java       # 右侧内层 panel → TabPane → 表格
│       └── AnalogClock.java      # 自绘模拟三针时钟（用于关于对话框）
└── resources/
    ├── styles/app.css            # 全部界面样式与配色变量
    └── ascii/                    # 关于对话框里的 ASCII 图案
        ├── train.txt             # 小火车车头
        └── smoke.txt             # 烟雾动画帧（空行分隔各帧）
```

## 主题 / 配色

界面颜色集中在 **`src/main/resources/styles/app.css`** 顶部的 `.root` 块，
定义为一组 JavaFX looked-up color 变量，改这里即可统一换色：

| 变量               | 当前值    | 作用                     |
| ------------------ | --------- | ------------------------ |
| `-c-left-panel-bg` | `#dfe4ea` | 左侧导航面板背景         |
| `-c-hover-bg`      | `white`   | 列表项悬停时背景（浮起） |
| `-c-selected-bg`   | `#4a90d9` | 选中项 / 主强调蓝        |
| `-c-selected-text` | `white`   | 选中项文字色             |
| `-c-table-border`  | `#c8c8c8` | 右侧表格边框             |
| `-c-row-odd`       | `#ffffff` | 表格斑马纹（偶数索引行） |
| `-c-row-even`      | `#f2f5f8` | 表格斑马纹（奇数索引行） |
| `-c-row-sel-bg`    | `#dbe7f3` | 选中表格行背景           |
| `-c-row-sel-text`  | `#4a90d9` | 选中表格行文字           |
| `-c-row-text`      | `black`   | 未选中表格行文字         |

> ⚠️ 少数几处颜色由 Java 代码绘制，CSS 变量管不到，需要时改这两处：
>
> - **左侧列表项图标**（蓝色小圆点）：`ui/LeftPanel.java` 中 `new Circle(5, Color.web("#4a90d9"))`
> - **左侧面板悬浮阴影**：`ui/MainView.java` 中 `new DropShadow(...)`

## 关于对话框 / ASCII 图案

菜单 **Help → About** 会弹出关于对话框，左侧是走动的模拟时钟，右侧是一列冒烟的 ASCII 小火车。

火车图案不写在代码里，而是放在资源文件中，**改图案只需编辑对应 `.txt`，无需改 Java 代码**：

- `src/main/resources/ascii/train.txt` — 车头图案（直接写，反斜杠 `\` 无需转义）
- `src/main/resources/ascii/smoke.txt` — 烟雾动画帧，**每帧之间用一个空行分隔**，程序按顺序循环播放形成飘动效果

> 提示：ASCII art 依赖等宽字体对齐，编辑时请保持每行的空格数；帧数可自由增减（多帧过渡更顺滑）。

## 许可证

MIT License
