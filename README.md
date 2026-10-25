# TimedClicker MS

Android 定时坐标点击器：通过 AccessibilityService 在指定 X/Y 坐标执行 100ms 点击手势。

## GitHub Actions 自动打包

项目已针对 2026 年 GitHub Actions 环境更新：

- Ubuntu 固定为 `ubuntu-24.04`，避免 `ubuntu-latest` 迁移到 Ubuntu 26 带来的环境变化。
- `actions/checkout@v7`。
- `actions/setup-java@v6` + JDK 17。
- `gradle/actions/setup-gradle@v6`。
- Gradle 使用 `8.14.6`，与本项目 Android Gradle Plugin 8.13.2 兼容。
- 不执行 `chmod +x gradlew`，也不依赖仓库里的 `gradlew` 文件；直接由 setup-gradle 安装指定 Gradle。
- 构建完成后自动上传 Debug APK Artifact。

> Android Gradle Plugin 8.13 官方要求 Gradle 至少 8.13，本项目使用 8.14.6。JDK 17 也符合 AGP 8.13 的要求。

## 非常重要：删除旧 Workflow

如果 GitHub 仓库中已经存在旧的 `.github/workflows/android.yml`、`.github/workflows/gradle.yml`、`.github/workflows/ci.yml` 等 Android 构建 Workflow，请删除或停用它们，只保留：

`.github/workflows/build-apk.yml`

否则旧 Workflow 仍然会同时运行，并可能继续出现：

- `Grant execute permission for gradlew` 失败
- Gradle 8.9 过时警告
- Node.js 20 deprecated 警告

本项目新的 Workflow 不需要 `gradlew`。

## 下载 APK

GitHub Actions 完成后：

`Actions` → `Build Android APK` → 对应运行 → `Artifacts` → `TimedClicker-MS-debug-apk`

## 本地 Android Studio

使用 Android Studio 打开项目根目录即可。应用需要用户手动在系统设置中启用本应用的无障碍服务。

## 精度说明

程序使用 `SystemClock.elapsedRealtimeNanos()`、高精度等待以及最后阶段忙等待来尽量减少调度误差。

但 Android 普通应用和 AccessibilityService 仍受操作系统调度、设备负载、厂商电源管理以及 `dispatchGesture` 实现影响，因此不能承诺真实触摸事件严格恒定在 ±1ms 内。
