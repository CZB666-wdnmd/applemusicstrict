# 启动闪退排查与 AM++ 对照

2026-10-08。已有 0.1.1-lifecycle 候选改动；手机测试继续暂停，未确认闪退修复。

## 0.1.1-lifecycle 更新

用户要求采用 AM++ 的安装逻辑，并提供了 1606 APKM。已完成：

- onModuleLoaded 保存进程名；onPackageReady 仅在主进程安装 Application.onCreate 入口。
- 只 hook 最靠近宿主 Application 的 onCreate 声明，避免同时 hook 基类时在 super.onCreate 返回瞬间提前安装。
- 宿主 onCreate 完整成功返回后才校验 APK、解析播放 Binding、安装功能。
- 所有播放回调先检查 volatile 激活标记，全部安装完成才发布；失败时先保持关闭，再尝试撤销，撤销失败也仍透传。
- 保留宿主 onCreate 的原异常；模块初始化失败只记录日志，不让安装异常破坏宿主初始化。
- 严格音质拒绝继续使用 PASSTHROUGH，避免照搬可选 UI 功能的失败放行策略而静默降级。
- module.prop 显式 autoHotReload=false。保留原来的调用者反优化，未将它认定为已证实根因。
- 精确适配新增 1606 base SHA-256：3d09687ed752e48e73f2c72524e18cffff69c66b523096c2e97c8f9135980603。

1606 的全部 24 个方法、32 个字段签名通过核验。六个重点类比较中，179 个方法的指令序列
在符号化引用后相同，11 个不同；所检查的主列表解析差异是调用的混淆辅助类名称，
无损候选差异涉及合成 predicate 名称和分派值。因此没有宣称两个 APK 全部逻辑一致。
直接使用的 createPeriodUpstream、解析工厂重载、selectAudioTrack、getCurrentItem、
getAudioQualitySetting 等入口仍匹配。

两个 profile 各通过 4065 项 JVM 检查，覆盖延迟安装、主进程限制、宿主异常保留、
准备阶段透传、注册失败且 unhook 失败时的逻辑回滚、未知 APK 拒绝和原有音质策略。
真实 API 102/Android 35 编译成功，APK v2/v3 签名及对齐通过；未运行 Android Lint。
本版签名沿用本轮诊断 APK 的证书。没有再连接手机或安装此版本，实机闪退及播放效果待验。

## 已取得的实机证据

- Apple Music 7.0.0-beta/1607，base.apk SHA-256 与原始模块白名单一致。
- Android SDK 36，LSPosed IT v2.2.0-it (7887)，API 102。
- 启动时 ExoPlayer 播放线程多次发生 SIGSEGV/SEGV_MAPERR，故障地址相同。
- crash_dump64 报 Failed to parse maps；exit-info 的 trace=null，尚无原生调用栈。
- pm disable-user 不能阻止 LSPosed 注入；已恢复包的 enabled=0，不能算有效对照。
- 在 LSPosed 中正式关闭模块后的一次启动观察期内没有崩溃。
- 删除显式 deoptimize 未解决。多个 hook 对照的日志来自并行线程，最后一条日志不能直接当作崩溃位置。
- 14:14:13，只建立 Binding、不注册任何 hook，仍崩溃。
- 14:15:03，APK 哈希校验后直接返回，不建立 Binding、不注册 hook，仍崩溃。
  此版本仍执行模块自身字段初始化、加载日志和 APK 哈希校验，并非完全没有代码执行。
- 重置宿主编译状态后，14:15:50 同样崩溃。

这排除了具体音质 hook 是崩溃的必要条件，但不足以断定 LSPosed 自身存在 bug；
注入兼容性、宿主原生路径和其他环境因素尚未区分。

用户曾授权本轮本地构建，并明确批准因签名不同而更换旧模块。Apple Music 未卸载、未清数据。
暂停时手机保留的是无 hook 诊断版，仍启用。暂停后没有再操作手机。

## AM++ 源码比较

来源：https://github.com/Zennmn/AM-plus-plus

审阅提交：2ce0adf4aeca5143ac83e865df006f3f56866248。

| 项目 | AM++ | 本模块／判断 |
| --- | --- | --- |
| 生命周期 | HookEntry.onPackageReady 只注册 Application.onCreate 入口；onCreate 前准备资源，后安装主要功能 | 原版直接在 onPackageReady 解析播放类并安装全部 hook。延迟安装值得借鉴，但不能解释无 Binding 诊断版也崩溃 |
| 进程 | EmbeddedBootstrap 核对包名、主进程、isFirstPackage，Application 阶段再核对 | 原版只有包名和 isFirstPackage。可补强，但本次崩溃就在主进程 |
| 版本 | profile 按包名、versionName、versionCode 精确选择；7.0 beta 是 1606 | 手机是 1607；不能把它的适配经验当成本机已验证结论 |
| Hook API | libxposed API 102，hook(...).intercept，包装 before/after；必要时 proceed(param.args) | 同样 API 102；有无显式参数的 proceed 不能直接判断对错 |
| 异常 | 安装和可选功能大量 runCatching，失败保留宿主行为；通用 wrapper 不显式设 ExceptionMode | PASSTHROUGH 用于避免严格拒绝被吞掉后放行 AAC；不能盲目改成全部透传。Java catch 不能捕获 SIGSEGV |
| 安装门控 | 部分功能用 HookRegistrationScope：PREPARING、ACTIVE、CLOSED；未激活回调透传 | 原版失败时 unhook。逻辑门控可以避免部分安装阶段执行策略，但尚非已证实修复 |
| 反优化 | AppleHookRegistrar.installHooker 确实调用 module.deoptimize(executable) | “别人完全不反优化所以不崩”不成立；移除本模块反优化的对照也未解决 |
| module.prop | API 102、staticScope=true，额外 autoHotReload=false | 前三项相同；没有证据表明热重载选项导致本次启动崩溃 |

关键文件（相对 AM++ 仓库）：

- app/src/main/java/dev/amenhancer/module/hook/HookEntry.kt
- app/src/main/java/dev/amenhancer/module/hook/EmbeddedBootstrap.kt
- hook-runtime/src/main/java/dev/amenhancer/module/hook/ModernXposedRuntime.kt
- hook-runtime/src/main/java/dev/amenhancer/module/hook/HookRegistrationScope.kt
- host-applemusic/src/main/java/io/github/proify/lyricon/amprovider/xposed/hooks/AppleHookRegistrar.kt
- host-applemusic/src/main/resources/host-profiles/index.json

## 当前状态与下一步

可以借鉴主进程门控、生命周期分阶段安装和安装状态门控，同时保留严格拒绝音质降级的语义。
没有原生崩溃依据前，不将这些工程改进包装成已证实修复。
只有用户恢复允许手机测试之后，才继续注入层最小对照和实际播放验收。

在前一轮源码比较结束时，临时诊断 Java 源码保存在 work，项目 Java 文件曾恢复原始完整实现；本轮已实施上文生命周期改动。
保留构建脚本的 --build-tools 参数，可选择 36.0.0；这解决了本机 JDK 21 配合旧 D8 的构建失败。
此前一个诊断候选的 4029 项 JVM 模拟检查通过；实机 DEX 的 24 个方法、32 个字段签名核验通过。
诊断 APK 使用真实 API 102 AAR/Android 35 成功构建、签名及对齐检查通过。
这些均不是启动或严格播放通过的证明。0.1.1 为待实机验证的候选 APK。
