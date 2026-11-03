// 构建逻辑（convention plugins）独立成 included build，与主干共享同一份版本目录，
// 保证插件版本单一来源（不重复写在两个 build 里）。
// 注意：pluginManagement 必须放在最前（Gradle 要求），否则预编译脚本插件在编译期
// 按 id 生成访问器时拿不到默认版本。
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
    versionCatalogs {
        create("libs") {
            from(files("../libs.versions.toml"))
        }
    }
}

rootProject.name = "mq-build-logic"
