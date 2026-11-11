pluginManagement {
    repositories {
        // 国内镜像优先（原仓库保底）
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
                // aliyun google 对 KSP 组回源 502（CI 实测），排除后由 aliyun public/mavenCentral/portal 兜底
                excludeGroup("com.google.devtools.ksp")
            }
        }
        maven("https://maven.aliyun.com/repository/public")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://repo.itextsupport.com/android")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "io.objectbox") {
                useModule("io.objectbox:objectbox-gradle-plugin:${requested.version}")
            }
        }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            // 同上：KSP 组在该镜像回源 502，排除后走后续仓库
            content {
                excludeGroup("com.google.devtools.ksp")
            }
        }
        maven("https://maven.aliyun.com/repository/public")
        maven("https://mirrors.cloud.tencent.com/repository/maven")
        // jitpack 包 (com.github.*) 必须走 jitpack，置于 mavenCentral 之前
        maven("https://jitpack.io")
        google()
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "rikkahub"
include(":app")
include(":highlight")
include(":ai")
include(":search")
include(":speech")
include(":common")
include(":document")
include(":web")
include(":workspace")
include(":material3")
