
# 毫秒定时点击器

GitHub Actions 自动打包的 Android 项目。

## 功能

- 系统时间 / 网络世界时间二选一
- 当前时间实时显示到毫秒
- 目标时间支持 `HH:mm:ss.SSS`
- 提前 / 延迟支持毫秒
- 使用 `SystemClock.elapsedRealtimeNanos()` 进行单调时钟计时
- 最后 0.5ms 使用忙等待降低调度误差
- AccessibilityService 执行 100ms 点击
- 显示发送点击时相对目标的触发误差
- GitHub Actions 自动生成 Debug APK

## GitHub 打包

上传项目到 GitHub 后，进入 Actions -> Build Android APK，运行完成后从
Artifacts 下载 `TimedClicker-MS-debug-apk`。

## 时间精度说明

本项目把“计时与目标时间计算”设计为高精度模式，但 Android 普通应用和
AccessibilityService 的最终调度仍受操作系统、CPU、温控、后台限制及设备厂商 ROM
影响，因此不能保证真实屏幕触点在所有设备上恒定达到 ±1ms。

“网络世界时间”使用多次 HTTP Date 采样并以中位数估算偏差，能够减少部分网络抖动，
但它不是 GPS/国家授时实验室级的直接授时。若要求更高的绝对时间准确度，建议下一步
接入多个 NTP/PTP 时间源并做持续时钟漂移校正。

## 当前限制

“屏幕取点”按钮当前保留为入口提示，X/Y 仍需直接输入。
