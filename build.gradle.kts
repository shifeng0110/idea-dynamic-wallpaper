// IntelliJ IDEA 动态壁纸插件 — 构建脚本
//
// 版本锁定：
//  * Gradle wrapper = 9.9.1；
//  * org.jetbrains.intellij.platform = 2.20.1。
//
// 环境说明（面向公开仓库）：
//  * IDE 依赖路径来自 local.properties 的 `localIdePath`（该文件不进仓库），
//    用于直接使用本机已安装的 IDE、避免重复下载；未配置时构建会给出明确提示；
//  * JDK 工具链路径通过环境变量 JBR_HOME 提供（gradle.properties 里
//    org.gradle.java.installations.fromEnv=JBR_HOME），避免把本机路径写进仓库。

import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.util.Properties

// 读取本机配置（local.properties 不入库）
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.isFile) {
        f.inputStream().use { input -> props.load(input) }
    }
}
val localIdePath: String? = localProps.getProperty("localIdePath")
    ?: providers.gradleProperty("localIdePath").orNull
    ?: providers.environmentVariable("IDEA_HOME").orNull

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "com.github.shifeng0110"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // IDE 依赖：优先用本机安装（local.properties 的 localIdePath / -PlocalIdePath / IDEA_HOME），
        // 未配置时明确报错而不是静默下载几百 MB 副本。
        local(localIdePath ?: error(
            "缺少本机 IDE 路径：请在 local.properties 里写 localIdePath=<IDEA 安装目录>，" +
                "或用 -PlocalIdePath=... / 环境变量 IDEA_HOME 指定。"
        ))
    }
    // media 包单测（纯 JVM，无 platform classpath）
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // 【视频解码】JCEF 对 H.264（avc1）报 v.error（实证），视频改由纯 Java
    // 解码：JCodec 提供 MP4 demux + H.264 解码，无原生依赖，随插件打包。
    implementation("org.jcodec:jcodec:0.2.5")
    implementation("org.jcodec:jcodec-javase:0.2.5")
}

java {
    toolchain {
        // 平台 2026.2 字节码为 v69(Java 25)，JDK 17 的 javac 读不了 → 编译工具链用 JBR 25
        //（路径经环境变量 JBR_HOME 提供，见 gradle.properties 的 installations.fromEnv）；
        // 输出仍为 --release 17（v61 字节码，JBR 25 运行时兼容），源码保持 Java 17 语法。
        languageVersion = JavaLanguageVersion.of(25)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // D1：since-build=262（2026.2 系列）；until 默认留空，备选收口 untilBuild = provider { "263.*" }
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    // 【2026-11-13 captain 指令】runIde 沙箱容器迁至短 ASCII 友好路径（工作区内、沙箱可写，约 72 字节
    // 远低于 AF_UNIX 108 上限）：262 DirectoryLock 用 AF_UNIX 监听，沙箱拒绝 socket bind 且原长路径
    // 逼近上限；runIde/test 两变体（config/config_runIde）一并生效。实际启动留 t7 提权执行。
    // （2.20.1 实测：扩展属性名 = sandboxContainer，经反射核对 IntelliJPlatformExtension.getSandboxContainer。）
    sandboxContainer = layout.projectDirectory.dir("../.cache-runtime/sand")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    // 输出 v61 字节码（Java 17 语义），由 JBR 25 javac 读取 v69 平台类后编译（见上方 toolchain 注释）
    options.release.set(17)
}

tasks {
    test {
        useJUnitPlatform()
    }
    runIde {
        // 运行时缓存放构建目录内（可移植，不写死本机路径）；自测开关用于本地验证
        val runCacheDir = layout.buildDirectory.dir("run-cache").get().asFile.absolutePath
        jvmArgs("-Didea-wallpaper.cache.dir=$runCacheDir", "-Ddwallpaper.selftest=true")
    }
}

// t5：JUnit Platform 单测（JcefGate 降级 / BGRA 字节序 / FrameSlot 上限 / Registry 分派 / Range 服务等）
tasks.test {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.PASSED, TestLogEvent.FAILED, TestLogEvent.SKIPPED)
    }
}
