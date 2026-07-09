# MQBrower

MQBrowser 是一个消息队列浏览器工具，用于可视化管理RabbitMQ、Kafka等消息队列服务。

## 功能特性

- 🔍 消息队列浏览与管理
- 📬 消息发送与接收
- 🔐 连接管理
- 📊 队列监控

## 技术栈

- 后端：Go
- 前端：Vue.js / React
- 消息队列：RabbitMQ, Kafka

## 快速开始

### 安装

```bash
# 克隆项目
git clone https://gitee.com/benbear_335/mqbrowser.git

# 进入项目目录
cd mqbrowser
```

### 配置

请根据您的环境修改配置文件：

- `config.yaml` - 基础配置
- `.env` - 环境变量配置

### 运行

```bash
# 启动服务
go run main.go

# 或使用 Makefile
make run
```

## 使用说明

1. 启动应用后访问 `http://localhost:8080`
2. 在设置页面配置 RabbitMQ/Kafka 连接信息
3. 开始浏览和管理您的消息队列

## 贡献指南

欢迎提交 Pull Request！

## 许可证

MIT License