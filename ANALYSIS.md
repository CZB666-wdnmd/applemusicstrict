# 附件静态分析记录

分析日期：2026-10-08。结论基于附件中的 DEX，尚无真机播放轨迹。

## 身份

| 项目 | 值 |
| --- | --- |
| 包名 | com.apple.android.music |
| versionName | 7.0.0-beta |
| versionCode | 1607 |
| APKS SHA-256 | 9985efb6d5dee3f9f247436f1769b144212f4b23cad660c27464b10356b8c64a |
| base.apk SHA-256 | 75bcdefe635ec00b2865e789761562a03acd415b5ba18a8e920b995c63811126 |
| classes2.dex SHA-256 | 036e4b32a8c9083b4f48b18cf296ec02a38602e476e8d80d8550c388c61a4720 |

APKS 内还有 arm64-v8a、xxxhdpi 两个 split。定位到的相关行为在 Java 播放路径，可以不修改原生解码器。

## 允许 AAC 混入无损候选

以下均位于 `classes2.dex`。`code_item` 为 DEX 文件偏移，不是进程地址；指令偏移单位为 16 bit code unit。

| 类／方法 | code_item | 证据与含义 |
| --- | --- | --- |
| PlayerTrackSelector.getLosslessAdaptiveVariants | 0x49a238 | 首选 variant 为 2 或 3 时构建无损候选；最终使用带 AAC 补充条件的 predicate |
| PlayerTrackSelector.lambda$getLosslessAdaptiveVariants$3 | 0x499c40 | 要求 ALAC，并按 48,000 Hz 区分普通／Hi-Res 候选 |
| PlayerTrackSelector.lambda$getLosslessAdaptiveVariants$4 | 0x499c74 | 先判断无损 predicate；不匹配时仍调用 isAac。等价于 `losslessPredicate(format) || isAac(format)` |
| PlayerTrackSelector.getAacAdaptiveVariants | 0x49a140 | 在独立 AAC 路径中建立候选集合 |
| PlayerTrackSelector.selectAudioTrackWithSettings | 0x499528 | 顺序尝试下载、Atmos、无损、AAC；无损集合可以本来就含 AAC |
| PlayerTrackSelector.selectAudioTrack | 0x4994f0 | 自定义选择返回 null 时，再调用 DefaultTrackSelector；仅返回空值无法禁止回退 |

`selectAudioTrackWithSettings()` 在取得最高分目标后，仅当 `isBitStreamSwitchingEnabled()` 为 false 才把候选集合收敛为一个索引；为 true 时保留多候选。目标音质和实际开始播放的音轨因而可以不同。

## 关闭一个自适应开关不足以修复

| 类／方法 | code_item | 行为 |
| --- | --- | --- |
| PlayerTrackSelector.buildAdaptiveTrackSelectionFactory | 0x499e98 | 开关 true 返回 AdaptiveTrackSelection.Factory；false 返回 BandwidthFixedTrackSelectionFactory |
| MediaPlaybackPreferences.isAdaptiveVariantSelectionEnabled | 0x4bb714 | 读取 key_debug_adaptive_variant_selection，默认 false |
| MediaPlaybackPreferences.isExperimentalAdaptiveTrackSelectorEnabled | 0x4bb8a0 | 读取 experimental_adaptive_track_selection，默认 false；决定实验性算法，不是整个降级路径的总开关 |
| BandwidthFixedTrackSelectionFactory.getTrackSelectionForDefinition | 0x4895a0 | 多候选按码率排序，再选不超过带宽预算的候选；不满足时选择较低的末项；只有一候选时直接固定 |

因此，仅 hook `isAdaptiveVariantSelectionEnabled()` 为 false 无法保证不播 AAC。模拟无限带宽也没有消除缺少无损候选时的回退。

## 首次 HLS 加载早于正式选轨

`classes4.dex` 中：

| 类／方法 | code_item | 行为 |
| --- | --- | --- |
| HlsChunkSource.InitializationTrackSelection.<init> | 0x304308 | 取 TrackGroup 的索引 0 对应格式，再转换为内部排序索引作为 selectedIndex |
| HlsChunkSource.InitializationTrackSelection.updateSelectedTrack | 0x304338 | 当前音轨被列入 blacklist 后可改选其他候选 |

若主列表第一条是 AAC，这条初始化路径允许先请求 AAC。其是否就是你设备上的全部启播原因，需要实际请求与日志确认。模块选择在主列表交给 tracker 之前删除不合格 variant，而不是等第一段音频已加载后才改选轨。

歌曲的 `PlaybackAssetMediaPeriod.createPeriodUpstream()`（classes2.dex，0x4b2a74）为 HLS 创建 `AppleHlsPlaylistParserFactory`。Factory 的两个 `createPlaylistParser` 重载都会创建 `AppleHlsPlaylistParser`。把上下文绑定到 Factory，再绑定到 parser，可以跨 loader 线程维持歌曲范围，不需要全局修改所有 HLS、直播或下载任务。

Apple 专用主列表解析器读取采样率、位深并写入 Format。它和普通 ExoPlayer parser 不是同一个类。

## 设置来源与失败方式

`BaseMediaPlayerContext.getAudioQualitySetting()`（0x489f7c）根据 Wi-Fi／蜂窝网络读取宿主音质设置。两种 getter 均考虑无损总开关，关闭时限制到 AAC 档位。模块复用该接口。

主列表缺少符合要求的 variant 时抛 IOException，由 loader 处理；正式选择缺少可解码音轨时抛 IllegalStateException。附件的 `ExoPlayerImplInternal.handleMessage()` 包含 RuntimeException 转换为播放错误的分支。最终 Apple Music 的错误展示、重试行为仍需真机确认。

模块使用 `PASSTHROUGH`，使严格拒绝不会被框架吞掉后继续执行原有 AAC 路径。安装阶段先解析所有反射目标；安装失败时撤销已经登记的 hook，日志明确显示 DISABLED。已启用后遇到不满足音质的歌曲，采用报错而非静默降级。

## 核验边界

静态分析证明 AAC 被允许进入无损候选、固定带宽工厂可能选择低码率、初始化路径默认选择第一条。它不证明每次播放都会先播 AAC，也不能证明设置页完全没有相关提示。此次没有真机、订阅会话或网络播放验证。

源码模拟测试验证选择策略和反射绑定的行为；真实 Android 构建、API AAR 编译、LSPosed 注入、缓存恢复、预取和交叉淡化仍属于待验收内容。
