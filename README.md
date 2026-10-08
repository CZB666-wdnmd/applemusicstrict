# Apple Music 严格音质（实验模块）

> 0.1.1-lifecycle：参考 AM++ 的生命周期安装方式，限定主进程，等宿主
> Application.onCreate 完整返回后安装播放 hook，全部成功后统一激活。
> 已加入用户提供的 1606 精确 APK profile，同时保留 1607。
> 已构建并通过本地模拟检查；随后获准进行的 1606 实机复测仍发生原生 SIGSEGV，闪退未修复。
> 详见 [排查记录与源码对照](CRASH-INVESTIGATION.md)。

最新实机状态：用户将 LSPosed IT 更新为 2.2.1-it (7919) 后，播放已恢复；当前仍未取得 MASTER/SELECT 命中证据，严格选轨效果待确认。见 [音质检查](QUALITY-CHECK.md)。

针对本次提供的 **Apple Music 7.0.0-beta，versionCode 1606 / 1607**，使用现代 **libxposed API 102.0.0**。作用域固定为 `com.apple.android.music`，仅在主进程激活。

新版 APK 使用本轮更换模块后的同一证书签名，可覆盖本轮诊断版；与最初对话生成的 0.1.0 证书不同。
1606 base.apk SHA-256：`3d09687ed752e48e73f2c72524e18cffff69c66b523096c2e97c8f9135980603`。
其他 APK 哈希仍拒绝安装播放 hook，不会仅凭显示的版本号强行适配。

0.1.1 本地验证：两个 profile 各 4065 项 JVM 检查通过，1606 的 24 个方法和 32 个字段签名核验通过，真实 API 102 编译、APK v2/v3 签名和对齐检查通过。未运行 Android Lint，未作本版真机测试。以下旧版构建说明和历史模拟结果不等于本版实机通过。

**已提供签名 APK 和源码工程，尚未在真机验证。** 已完成 DEX 静态核验、真实 libxposed API 102.0.0 / Android 35 编译、官方 SDK 工具构建、APK 签名与对齐检查和模拟行为测试。因此可以安装测试，但不能把这些结果当作实机生效保证。此次 Gradle 插件拉取仍失败，未完成 Android Lint；APK 使用官方 AAPT2、D8、zipalign、apksigner 直接构建。

## 行为

模块读取 Apple Music 自身的 `getAudioQualitySetting()`，由它决定当前使用 Wi-Fi 还是蜂窝音质，且遵守应用的无损总开关。不会把所有网络一律改成高解析度无损。

| 当前设置 | 歌曲串流允许的音源 | 选择方式 |
| --- | --- | --- |
| 高效率 | 标准 AAC，正码率且低于 256 kbps | 最低可用码率 |
| 高品质 | 标准 AAC，至少 256 kbps | 固定较优候选，禁止先播低码率 AAC |
| 无损 | ALAC，采样率不高于 48 kHz、位深不高于 24 bit | 固定最高可用规格 |
| 高解析度无损 | ALAC，采样率不高于 192 kHz、位深不高于 24 bit | 固定最高可用规格；仅有普通无损母带时允许普通 ALAC |

这里把“高解析度无损”解释为最高可用 ALAC，最高 24 bit/192 kHz。它不是要求每首歌都必须具有超过 48 kHz 的母带。ALAC 的采样率或位深未知时拒绝播放，避免猜测音质。

无符合设置的音源时，模块使该播放路径报错，日志有 `BLOCKED`，不执行 AAC 回退。具体错误文案由 Apple Music 决定，不能保证它在界面展示模块的完整原因。网速不足时可能缓冲更久或播放失败。

## 实现范围

1. 在 `PlaybackAssetMediaPeriod.createPeriodUpstream()` 的歌曲来源创建期间绑定当前播放器上下文。
2. 将上下文从 `AppleHlsPlaylistParserFactory` 传递到其创建的解析器，支持异步加载、预取和不同播放器实例。
3. 在 `AppleHlsPlaylistParser.parse()` 返回 HLS 主列表时，只保留一条满足设置的 variant，并保留关联音频组。这样播放列表跟踪器第一次请求子列表时也没有低码率 AAC 候选。
4. 在 `PlayerTrackSelector.selectAudioTrack()` 中重新检查实际音轨和渲染器支持，返回只有一个音轨索引的 Definition，阻止带宽工厂／自适应选择再降级。
5. 更新宿主原有的 `targetedTrackFormat` 为实际选择，便于原有统计使用同一目标。

音质约束优先于串流歌曲的 Atmos 设置；ALAC 设置会排除 Atmos、binaural、downmix 有损版本。若想继续使用 Atmos，停用模块。

直播电台、视频和已下载歌曲沿用原有路径。下载任务的格式选择、旧下载文件重新下载、Android 音频输出重采样、蓝牙编码不在本模块范围内。旧 AAC 下载必须在 Apple Music 中删除下载后按新音质重新下载。HLS 内的歌词、变量和 session key 数据继续由宿主处理。

对要求无损却收到非 HLS 歌曲串流来源的情况，模块拒绝准备该来源。高品质／高效率的非 HLS 准备阶段没有同样的主列表过滤保证；最终选轨仍有检查。这一限制需要用实机日志评估。

## 构建

需要联网获取 Google Maven、Maven Central 依赖，JDK 17、Gradle 8.9、Android SDK platform 35，以及 SDK Build Tools 34.0.0。工程固定 AGP 8.7.3。仓库没有 Gradle Wrapper 二进制，使用已安装的 Gradle，或在 Android Studio 导入工程并选择 Gradle 8.9。

```bash
cd apple-music-strict
gradle --no-daemon :app:assembleDebug :app:lintDebug
```

也可以执行 `bash build.sh`。如 SDK 无法自动发现，在本机新建 `local.properties`，填写 `sdk.dir=你的SDK绝对路径`。

输出为 `app/build/outputs/apk/debug/app-debug.apk`，由本机构建工具使用调试密钥签名。工程没有附带私钥；不同电脑的调试签名可能不同，换电脑构建后覆盖安装可能需要先卸载旧模块。

附带手动触发的 `.github/workflows/build.yml`，可在你自己的仓库构建并下载 APK；本次没有创建远程仓库或运行该工作流。

也可以跳过 Gradle，使用本次实际验证过的 SDK 直接构建脚本。需要官方 SDK platform 35、Build Tools 34.0.0、JDK 17，以及从 Maven Central 获取的真实 `io.github.libxposed:api:102.0.0` AAR：

```bash
python3 tools/build_sdk.py --sdk /path/to/android-sdk \
  --java-home /path/to/jdk-17 --api-aar /path/to/api-102.0.0.aar
```

输出为 `app/build/outputs/apk/sdk-direct/AppleMusicStrict-0.1.0.apk`。脚本编译时引用 API AAR，不把 API 实现或测试模拟对象打包进去。调试密钥仅保存在本地 `app/build/sdk-direct/debug.keystore`，源码包没有附带该私钥。

## 安装与确认

1. 使用框架管理器确认实际支持 **libxposed API 102**。不能只根据“LSPosed 最新版”的名字判断；API 100／101 不符合本工程要求。
2. 安装构建出的模块 APK，在管理器启用它，作用域仅选择 Apple Music。
3. 强制停止 Apple Music 再重新打开。模块没有桌面启动入口，所有设置仍在 Apple Music 内完成。
4. 查找框架日志中 `AppleMusicStrict` 的 `ACTIVE`，随后播放一首未下载歌曲。
5. 应看到 `MASTER: quality=...; one permitted variant retained` 和 `SELECT: ... codec=alac ... tracks=1`。仅看到 `ACTIVE` 不代表歌曲经过了主列表过滤。

ADB 示例：

```bash
adb shell am force-stop com.apple.android.music
adb logcat -v time -s AppleMusicStrict
```

框架可能只把模块日志写入自己的日志文件；ADB 输出为空时查看框架管理器日志。

只有与附件 **base.apk SHA-256 完全一致**的安装版本会启用：

```text
75bcdefe635ec00b2865e789761562a03acd415b5ba18a8e920b995c63811126
```

APK 更新、重打包或不同构建均会显示 `DISABLED` 并停用约束。不要仅改哈希绕过保护，应重新核验所有目标。关闭模块并强制停止应用后，恢复原有行为。

## 建议实机验收

分别检查未下载的 Hi-Res 歌曲、只有 44.1 kHz ALAC 的歌曲、只有 AAC 的内容、Wi-Fi／蜂窝切换、下一首预取、交叉淡化、缓存命中、网络差、设置变化和缺失元数据。选择无损时，AAC-only 内容应报错；Hi-Res 有普通 ALAC 母带时应播放普通 ALAC。直播、视频和下载应保持原有行为。

日志核验的是选择逻辑；首次请求是否真的没有 AAC 音频片段，需要在个人设备上结合实际播放信息／请求记录确认。无需为了本模块关闭证书验证。音质角标不能单独作为验证依据。

## 已完成验证

- 附件包身份：7.0.0-beta / 1607，四个 DEX；base.apk 哈希匹配。
- `tools/verify_profile.py`：24 个方法签名、32 个字段签名均存在。
- `tests/run.py`：Java 17 编译应用源码及明确标注的 API 形状模拟对象，4029 个不变量检查通过。
- 实际模块源码单独使用真实 libxposed API 102.0.0 AAR 和 Android 35 SDK 编译通过。
- 官方 SDK 工具完成 DEX 转换、资源打包和 APK 签名；v2/v3 签名验证、ZIP 对齐检查通过，包名和 SDK 版本符合工程配置。
- 覆盖四档音质、主列表顺序扰动、无候选报错、原列表不修改、变量／key 数据保留、异步解析上下文、正式选轨固定索引、禁止回落到原选轨、下载／视频／直播范围。
- Python 脚本语法、构建脚本语法和 XML 格式通过。

测试模拟对象只用于 JVM 行为验证，不会打包进 Android 模块。真实 API 编译是另一次独立检查；两者均不能替代设备上的框架注入与订阅播放测试。

```bash
python3 tools/verify_profile.py '/path/to/Apple Music_7.0.0-beta.apks'
# 将 APKS 中的 base.apk 解压到临时目录后：
python3 tests/run.py /path/to/base.apk
```

分析记录见 `ANALYSIS.md`。工程只包含新写的模块、核验工具和测试，不包含 Apple APK、原生库或完整反编译源码。

## 参考

- [LSPosed 现代 API 开发说明](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
- [libxposed 102.0.0 发布信息](https://central.sonatype.com/artifact/io.github.libxposed/api/102.0.0)
- [AGP 8.7 的官方兼容性说明](https://developer.android.com/build/releases/agp-8-7-0-release-notes)
