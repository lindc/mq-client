# MQBrowser

MQBrowser is a message queue browser tool designed for visually managing message queue services such as RabbitMQ and Kafka.

## Features

- 🔍 Browse and manage message queues
- 📬 Send and receive messages
- 🔐 Connection management
- 📊 Queue monitoring

## Technology Stack

- Backend: Go
- Frontend: Vue.js / React
- Message Queues: RabbitMQ, Kafka

## Quick Start

### Installation

```bash
# Clone the project
git clone https://gitee.com/benbear_335/mqbrowser.git

# Navigate to the project directory
cd mqbrowser
```

### Configuration

Modify the configuration files according to your environment:

- `config.yaml` - Basic configuration
- `.env` - Environment variables

### Run

```bash
# Start the service
go run main.go

# Or use the Makefile
make run
```

## Usage Instructions

1. After starting the application, visit `http://localhost:8080`
2. Configure RabbitMQ/Kafka connection details on the settings page
3. Begin browsing and managing your message queues

## Contribution Guidelines

Pull requests are welcome!

## License

MIT License