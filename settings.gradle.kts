rootProject.name = "mq-client"
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
    ":ai-panel",
    ":ui-common",
    ":ibm-mq-service", ":ibm-mq-ui",
    ":kafka-service", ":kafka-ui",
    ":artemis-service", ":artemis-ui",
    ":rabbitmq-service", ":rabbitmq-ui",
    ":rocketmq-service", ":rocketmq-ui",
)
