# 2026-10-08 框架更新后的音质检查

用户报告更新框架后不再闪退。已读取设备确认 LSPosed IT v2.2.1-it (7919)。
Apple Music PID 16852 在本轮观察期间持续播放，模块日志于 15:04:13 报告
WAITING 和 ACTIVE，匹配 7.0.0-beta/1606。

## 设置与实际输出分开看

- 无损开关已开启。
- Wi-Fi 和蜂窝音质均为值 3：依据 1606 AudioQuality 的枚举构造参数，对应 HIGH_RES_LOSSLESS。
- Dolby Atmos 设置为值 2：依据 1606 DolbyAtmosState 的构造参数，对应 ALWAYS_ON。
- 当前 Apple Music 的音频轨为 PID 16852 / UID 10447 / session 137 / track 59。
- AudioFlinger 记录该轨为活动状态，48000 Hz、PCM 16-bit、声道掩码 0x2D63F，经过 SPATIALIZER 线程。
- 对应输出线程/HAL 为 44100 Hz、PCM 16-bit、双声道。

这些是解码后/系统处理阶段的格式，不代表原始流编码，也不能由此判定源流是 AAC 或 ALAC，
不能把 HAL 的 44.1 kHz 当作 Apple Music 在网络选轨时降级的证据。
多声道空间处理与始终开启 Atmos 的设置一致，但还没有直接取得源编码信息。

## 模块是否执行严格选择

本次已检查当前 logcat 和框架 modules 日志。仅有 Module loaded、WAITING、ACTIVE，
没有 MASTER、SELECT 或 BLOCKED。ACTIVE 仅证明注册完成，不证明回调已命中。
因此，当前不能确认“首次加载只保留 ALAC”和“最终单轨选择”已生效。
还需区分实际播放路径、歌曲类型/下载状态的提前放行，以及调用被内联等原因。
本轮未修改音质设置、切换曲目、重启应用或安装新模块。
