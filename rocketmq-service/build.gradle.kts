plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()
    jvmToolchain(25)

    sourceSets {
        commonMain.dependencies {
            implementation(projects.appCore)
            // MQ 公共基类（MQServiceAdapter）：api 暴露，消费方能看到完整父类链。
            api(projects.mqBase)
        }
        jvmMain.dependencies {
            // RocketMQ Remoting 管理客户端（DefaultMQAdminExt 在 tools 构件里，传递
            // rocketmq-client / remoting / netty）。5.3.1：兼容 4.x NameServer/Broker
            // 的管理面；面向 5.x gRPC Proxy 的接入（rocketmq-client-java）后续按需另开。
            // 照 ibm-mq / kafka 模式直接写坐标，不进 libs.versions.toml。
            implementation("org.apache.rocketmq:rocketmq-tools:5.3.1")
            // ACL 凭证：AclClientRPCHook + SessionCredentials（RPCHook 构造参数注入）。
            implementation("org.apache.rocketmq:rocketmq-acl:5.3.1")
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
