# Apple Music 音质控制 0.2.2

Material 3 原生界面，支持动态配色、深色主题、实时音质和本曲音轨选择。
仅支持附件中 SHA-256 匹配的 Apple Music 7.0.0-beta 1606 / 1607；libxposed API 102。

## 使用

1. 安装 APK，在 LSPosed 中启用模块，作用域勾选 Apple Music。
2. 从 LSPosed 的模块设置打开一次控制界面，再重新打开 Apple Music。也可从 Apple Music → 设定 → 音频 → 强制选择音质进入。
3. 开启“接管音质选择”：忽略宿主流媒体音质设置，自动选择最高可用 ALAC；仅提供 AAC 的歌曲选择最高 AAC。
4. 可启用“优先杜比全景声”。有杜比版本时优先，其他歌曲使用最高无损。
5. 播放后显示本曲完整清单；选择音轨并点“应用所选音质”，保留位置及播放/暂停状态重新加载。下一首恢复自动。

界面区分清单声明规格和实际播放格式。AAC 标称档位来自音轨组名；码率来自解码器输入格式，不使用解码后 PCM 码率。
仅列出实际收到的候选，不虚构每首歌都有所有档位。清单未给出采样率时标注未知。
下载歌曲、视频、电台不强制切换。接管在线播放时使用网络完整清单，不删除已有缓存或下载文件。

## 此次修正

0.2.2 默认不声明桌面 LAUNCHER 入口，保留 MODULE_SETTINGS；不干预 LSPosed 强制显示图标。新增宿主音频分组原生入口，点击打开现有 MD3 控制界面。已构建并安装，入口交互待用户验证，见 VALIDATION-0.2.2.md。

- 歌曲类型应为 1，旧代码的类型 2 判断跳过了在线歌曲。
- 选轨回调按渲染器调用；空/不适用渲染器返回 null，不再使整首歌报错，也不执行宿主 AAC 回退。
- 缓存关联的 dedicateDownloadedPlaylist 参数会使宿主解析器只保留一个版本。在线接管清除此限定，使用完整清单。
- 使用 codec + audio group 标识音轨，避免清单/解码器采样率、声道数差异导致手动选择失败。
- 预加载下一首的选轨结果按歌曲保存，不覆盖当前歌曲的确认状态。
- 通过 Android 标准 URI 授权建立模块对宿主的可见性。Provider 校验调用 UID；仅界面可改配置，宿主只读配置并报告状态。

## 构建

已获用户授权进行本轮本地构建和上机测试。后续仍遵守用户“不经明确授权不本地构建”的要求。

JDK 17+，Android SDK platform 35；Gradle wrapper 8.9、AGP 8.7.3，Material Components 1.12.0。

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
python tests/run.py
python tools/verify_profile.py path/to/1606.apkm
```

签名私钥不打包。当前机器的 `.signing/debug.keystore` 在 git 忽略目录中，清理 build 不会删除它。
无此私钥时 Gradle 使用本机默认 debug key，不能直接覆盖不同签名的已安装版本。
`tools/build_sdk.py` 是旧版无界面的构建入口，已停用。

## 验证

见 `VALIDATION-0.2.1.md`。`ANALYSIS.md`、`QUALITY-CHECK.md`、`CRASH-INVESTIGATION.md` 与旧 Smoke/BootstrapSmoke 是历史材料，不代表新版结果。
当前 `tests/run.py` 运行 ControlSmoke 策略与清单测试；它不模拟完整 Android/LSPosed。
