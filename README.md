# AppleMusicStrict

为 Apple Music Android 提供音质控制的 LSPosed 模块。当前版本 **0.2.2**，采用原生 Material Design 3 界面，支持动态配色与深色模式。

## 功能

- **自动最高音质**：开启接管后替代 Apple Music 的流媒体音质偏好，从实际可用版本中优先选择 ALAC；仅有 AAC 时选择最高 AAC 档位。
- **优先杜比全景声**：有 Atmos 版本时优先选择，否则按常规音质排序。Atmos 不等同于无损。
- **实时音质显示**：显示播放器报告的编码、采样率、位深及可取得的音源码率。
- **手动选轨**：列出本曲收到的清单版本，选择后重新加载，尽量保留播放位置及播放状态。
- **设置入口**：Apple Music → 设定 → 音频 → **强制选择音质**，或 LSPosed → 模块设置。

默认隐藏桌面入口，不干预 LSPosed 强制显示模块图标的行为。关闭接管后由 Apple Music 管理音质，设置入口仍保留。

## 兼容性

| 项目 | 要求与验证范围 |
| --- | --- |
| Android | 最低 Android 8.0 / API 26；并非所有系统都经过实测 |
| 框架 | 支持现代 libxposed API 102 的 LSPosed；测试使用 LSPosed IT 2.2.1（7919） |
| Apple Music 1606 | 指定构建的 7.0.0-beta，已测试播放与音源切换 |
| Apple Music 1607 | 指定构建的 7.0.0-beta，仅静态核验，未实机测试 |

模块按 **base APK SHA-256** 精确匹配，未知构建不会安装播放 Hook：

```text
1606  3d09687ed752e48e73f2c72524e18cffff69c66b523096c2e97c8f9135980603
1607  75bcdefe635ec00b2865e789761562a03acd415b5ba18a8e920b995c63811126
```

版本号相同但哈希不同也不会自动启用。模块依赖应用内部接口，升级 Apple Music 后可能需要重新适配。

## 安装与使用

1. 安装模块 APK，在 LSPosed 中启用，作用域选择 `com.apple.android.music`。
2. 从 LSPosed 的“模块设置”打开一次控制界面，完成模块与宿主的标准 URI 访问授权。
3. 完全退出并重新启动 Apple Music，使模块生效。
4. 从 **设定 → 音频 → 强制选择音质** 进入，开启“接管音质选择”，按需开启“优先杜比全景声”。
5. 播放歌曲，查看当前音质与候选列表；选中音轨后点“应用所选音质”。切换可能短暂停顿，选择“自动”可恢复最高音质策略。

手动选择绑定歌曲，不是全局固定码率。其他歌曲使用自动策略；当前实现会保留所选歌曲的选择，直到改选或清除。

## 如何理解音质显示

- **当前播放**来自播放器回报，**可选音质**来自实际收到的播放清单。两者可能不同，例如清单声明 16 bit，播放器输入报告 24 bit。
- AAC 的 64 / 128 / 256 kbps 是标称档位；详细码率来自输入格式元数据，不是实时下载测速，也不是解码后的 PCM 码率。
- HE-AAC 的输入采样率可能是核心采样率。显示 22.05 kHz 不代表解码输出也是 22.05 kHz；目前尚未单独展示 SBR 扩展后的输出采样率。
- 音源格式不代表蓝牙耳机、系统混音器或 DAC 的最终输出。
- 未提供的参数显示为未知，不虚构音质档位。关闭接管时，宿主缓存筛选可能使观察到的清单不完整。

下载歌曲、视频和电台不强制切换。接管在线歌曲时使用网络完整清单，不删除已有缓存或下载。模块不提供账号、订阅或内容授权，不改变 DRM 授权流程。

## 构建

需要 JDK 17+、Android SDK platform 35、Build Tools 34.0.0。项目使用 Gradle Wrapper 8.9、AGP 8.7.3、Material Components 1.12.0。

配置 `ANDROID_HOME`，或在本机 `local.properties` 中设置 `sdk.dir`，然后执行：

```bash
# Linux / WSL / macOS
bash ./gradlew :app:assembleDebug :app:lintDebug
```

```powershell
# Windows
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

也可在仓库 **Actions** 页面手动运行构建工作流，完成后下载构建产物。

签名私钥不包含在仓库中。本机有 `.signing/debug.keystore` 时使用该调试签名，否则使用默认 debug key。自行构建或 CI 构建的 APK 可能与已安装版本签名不同，无法直接覆盖安装。不要提交私人签名密钥。

旧的 `tools/build_sdk.py` 已停用，带 Material 资源的版本需要使用 Gradle。

## 验证与限制

```bash
python3 tests/run.py
python3 tools/verify_profile.py /path/to/apple-music.apkm
```

测试需要 Java/Javac。profile 核验需要自行提供包含 `base.apk` 的 APKM/APKS；仓库不分发 Apple Music 安装包。

- 330 项策略与清单断言通过，但不代替 Android / LSPosed 集成测试。
- 1606、1607 各完成 50 个方法、48 个字段的静态核验。
- 0.2.1 实机验证：ALAC 与 AAC 列表、手动切换 AAC 128、切回自动 ALAC，播放会话正常。
- 0.2.2 已构建并安装，Lint 为 0 errors / 5 warnings；新增设置入口的交互测试待完成。
- 尚未覆盖所有杜比曲目、网络切换、长时间播放和不同输出设备。

详见 [0.2.1 验证记录](VALIDATION-0.2.1.md) 和 [0.2.2 验证记录](VALIDATION-0.2.2.md)。其他早期分析与测试文件属于历史记录，不代表当前版本已验证的能力。

## 问题反馈

请在 [Issues](https://github.com/CZB666-wdnmd/applemusicstrict/issues) 附上 Apple Music 版本与 base APK 哈希、Android / LSPosed 版本、接管开关状态、复现步骤和脱敏后的 `AppleMusicStrict` 日志。

## 许可与参考

[MIT License](LICENSE)。本项目与 Apple Inc. 无隶属关系。

开发过程参考了 [AM-plus-plus](https://github.com/Zennmn/AM-plus-plus) 的宿主生命周期与原生设置模型适配思路。
