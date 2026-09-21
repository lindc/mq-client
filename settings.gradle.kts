rootProject.name = "mq-client"
// Gradle 9.7.1 仍需这个预览开关：删掉之后各 build.gradle.kts 里的 `projects.appCore`
// 之类类型安全项目访问器会直接「Unresolved reference」（实测）。
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(
    ":app-core",
    ":app-ui",
    ":ai-ui",
    ":ai-service",
    ":ui-common",
    ":openai-service",
    ":openai-ui",
    ":ibm-mq-service", ":ibm-mq-ui",
    ":kafka-service", ":kafka-ui",
    ":artemis-service", ":artemis-ui",
    ":rabbitmq-service", ":rabbitmq-ui",
    ":rocketmq-service", ":rocketmq-ui",
)
